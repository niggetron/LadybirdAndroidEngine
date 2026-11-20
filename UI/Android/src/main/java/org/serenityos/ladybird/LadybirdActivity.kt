/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 * SPDX-License-Identifier: BSD-2-Clause
 *
 * The engine bridge (native init, services, WebView JNI) is Ladybird's.
 * The visible UI is our own: Jetpack Compose, Material 3 expressive-style,
 * with a vertical tab rail and an arc-shaped address bar.
 */

package org.serenityos.ladybird

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream

data class EngineTab(
    val id: Int,
    val title: String,
    val url: String,
    val view: WebView,
)

class LadybirdActivity : AppCompatActivity() {

    private lateinit var resourceDir: String
    private val engineViews = mutableListOf<WebView>()
    private var timerService = TimerExecutorService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resourceDir = TransferAssets.transferAssets(this)
        val testFile = File("$resourceDir/res/icons/48x48/app-browser.png")
        if (!testFile.exists()) {
            ZipFile("$resourceDir/ladybird-assets.zip").use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val file = File("$resourceDir/${entry.name}")
                    if (!entry.isDirectory) {
                        File(file.parent!!).apply { if (!exists()) mkdirs() }
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            // curl needs Android's CA bundle concatenated into one PEM.
            File("$resourceDir/cacert.pem").outputStream().use { output ->
                Files.walk(Path.of("/system/etc/security/cacerts")).forEach { certPath ->
                    if (!certPath.isDirectory()) {
                        certPath.inputStream().use { input -> input.copyTo(output) }
                    }
                }
            }
        }

        val userDir = applicationContext.getExternalFilesDir(null)!!.absolutePath
        initNativeCode(resourceDir, "Ladybird", timerService, userDir)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BrowserShell(
                    resourceDir = resourceDir,
                    initialUrl = intent.dataString ?: "https://ladybird.org",
                    onViewCreated = { engineViews.add(it) },
                )
            }
        }
    }

    override fun onDestroy() {
        engineViews.forEach { it.dispose() }
        disposeNativeCode()
        super.onDestroy()
    }

    private fun scheduleEventLoop() {
        mainExecutor.execute { execMainEventLoop() }
    }

    private external fun initNativeCode(
        resourceDir: String, tag: String, timerService: TimerExecutorService, userDir: String
    )

    private external fun disposeNativeCode()
    private external fun execMainEventLoop()

    companion object {
        init {
            System.loadLibrary("Ladybird")
        }
    }
}

@Composable
fun BrowserShell(resourceDir: String, initialUrl: String, onViewCreated: (WebView) -> Unit) {
    val context = LocalContext.current
    val tabs = remember { mutableStateListOf<EngineTab>() }
    var nextId by remember { mutableIntStateOf(2) }
    var selectedId by remember { mutableIntStateOf(1) }
    var address by remember { mutableStateOf(initialUrl) }

    fun makeTab(id: Int, url: String): EngineTab {
        val view = WebView(context, null).apply {
            initialize(resourceDir)
            onLoadStart = { loadedUrl, _ ->
                val index = tabs.indexOfFirst { it.id == id }
                if (index >= 0) tabs[index] = tabs[index].copy(title = hostOf(loadedUrl), url = loadedUrl)
                if (id == selectedId) address = loadedUrl
            }
            loadURL(url)
        }
        onViewCreated(view)
        return EngineTab(id, hostOf(url), url, view)
    }

    if (tabs.isEmpty())
        tabs.add(makeTab(1, initialUrl))

    Row(modifier = Modifier.fillMaxSize()) {
        VerticalTabRail(
            tabs = tabs,
            selectedId = selectedId,
            onSelect = { id ->
                selectedId = id
                tabs.firstOrNull { it.id == id }?.let { address = it.url }
            },
            onAdd = {
                val tab = makeTab(nextId, "https://ladybird.org")
                tabs.add(tab)
                selectedId = tab.id
                nextId += 1
                address = tab.url
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            ArcAddressBar(
                address = address,
                onAddressChange = { address = it },
                onGo = {
                    val url = normalizeUrl(address)
                    val index = tabs.indexOfFirst { it.id == selectedId }
                    if (index >= 0) {
                        tabs[index].view.loadURL(url)
                        tabs[index] = tabs[index].copy(url = url, title = hostOf(url))
                    }
                    address = url
                },
            )

            val selected = tabs.firstOrNull { it.id == selectedId }
            if (selected != null) {
                key(selected.id) {
                    AndroidView(
                        factory = { selected.view },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalTabRail(
    tabs: List<EngineTab>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxHeight(),
    ) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = tab.id == selectedId,
                onClick = { onSelect(tab.id) },
                icon = { Icon(Icons.Filled.Tab, contentDescription = null) },
                label = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        FloatingActionButton(
            onClick = onAdd,
            modifier = Modifier.padding(bottom = 16.dp),
            shape = RoundedCornerShape(50),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New tab")
        }
    }
}

@Composable
fun ArcAddressBar(
    address: String,
    onAddressChange: (String) -> Unit,
    onGo: () -> Unit,
) {
    Surface(
        shape = ArcShape(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().height(120.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                singleLine = true,
                label = { Text("Address") },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(),
                modifier = Modifier.weight(1f).heightIn(min = 54.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            FloatingActionButton(
                onClick = onGo,
                shape = RoundedCornerShape(50),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Go")
            }
        }
    }
}

private class ArcShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height * 0.60f)
            cubicTo(
                size.width * 0.78f, size.height * 1.20f,
                size.width * 0.22f, size.height * 1.20f,
                0f, size.height * 0.60f,
            )
            close()
        }
        return Outline.Generic(path)
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

private fun hostOf(url: String): String =
    runCatching { URI(url).host ?: url }.getOrDefault(url)

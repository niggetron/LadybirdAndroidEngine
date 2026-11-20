# Ladybird Android build — full audit and fixes

Base: LadybirdBrowser/ladybird master @ `143885c5`.
Ladybird's own `Nightly Android` workflow is `disabled_manually` upstream, so these
Android-only breakages have no upstream CI coverage. Every fix below is applied
directly to the source in this repository (not via workflow sed hacks).

## Root cause

`AK/Platform.h` defines `AK_OS_LINUX` on Android (`__linux__` is defined by the NDK),
while CMake's `LINUX` variable is **false** on Android. Upstream code therefore calls
Linux-only code on Android, while CMake excludes the files implementing it.

## Fixes, line by line

| # | File | Original line/code | Problem | Fix |
|---|---|---|---|---|
| 1 | `UI/Android/build.gradle.kts` | `abiFilters += listOf("x86_64", "arm64-v8a")` | Builds x86_64 too | workflow builds `arm64-v8a` and `armeabi-v7a` separately |
| 2 | `vcpkg.json` | `"platform": "linux \| bsd \| windows"` (harfbuzz) | `LibGfx` includes `harfbuzz/hb-ot.h` on all platforms, but Android's vcpkg manifest omitted harfbuzz | added `\| android` |
| 3 | `Meta/CMake/rust_crate.cmake` | `RUST_TARGET_TRIPLE` defaults to host | Rust static libs built for x86_64, linker rejects them for aarch64 | Gradle passes `-DRUST_TARGET_TRIPLE=aarch64-linux-android` / `armv7-linux-androideabi` |
| 4 | `Libraries/LibCore/EventLoopImplementationUnix.cpp:341` | `post_event(notifier, ...)` | `post_event` takes `EventReceiver*`; Android branch passed a reference | `post_event(&notifier, ...)` |
| 5 | `Libraries/LibIPC/Rust/src/shared_memory.rs` | `#[cfg(target_os = "linux")]` / `#[cfg(not(target_os = "linux"))]` | `libc` crate has no `shm_open`/`shm_unlink` for Android | route `target_os = "android"` through the `memfd_create` path |
| 6 | `Libraries/LibSandbox/ConnectBroker.h` | `void set_connect_broker_fd(int);` | Defined only in `Seccomp.cpp`, which CMake excludes on Android | inline no-op under `AK_OS_ANDROID` |
| 7 | `Services/AudioServer/main.cpp`, `Services/MediaServer/main.cpp` | `#if defined(AK_OS_LINUX)` | Refer to broker/system-FFmpeg code excluded on Android | `&& !defined(AK_OS_ANDROID)` |
| 8 | `Libraries/LibMedia/DecoderRegistrations.cpp` | `#ifdef AK_OS_LINUX` (3 blocks) | Registers system-FFmpeg decoders from `SystemFFmpegDecoders.cpp` (excluded on Android) | `&& !defined(AK_OS_ANDROID)` |
| 9 | `Libraries/LibSandbox/Sandbox.cpp:790` | `#if defined(AK_OS_LINUX)` around `restrict_filesystem()` | Calls `start_file_broker()` from `FileBroker.cpp` (excluded on Android) | `&& !defined(AK_OS_ANDROID)` |
| 10 | `Services/Compositor/main.cpp`, `Services/VideoDecoderServer/main.cpp` | `#if defined(AK_OS_LINUX)` | Call `restrict_filesystem()` from `SandboxLinux.cpp` (excluded on Android) | `&& !defined(AK_OS_ANDROID)` |
| 11 | `Libraries/LibWebView/CrashReport.cpp` | `#if !defined(AK_OS_MACOS) && !defined(AK_OS_LINUX)` | Generic stubs skipped on Android, but `CrashReportPOSIX.cpp` is excluded there | include Android: `(!defined(AK_OS_LINUX) \|\| defined(AK_OS_ANDROID))` |
| 12 | `Libraries/LibWebView/HelperProcess.cpp` | `#if defined(AK_OS_LINUX)` (2 broker blocks) | Calls `audio_daemon_socket_path()` from `AudioDaemonSocket.cpp` (excluded on Android) | `&& !defined(AK_OS_ANDROID)` |
| 13 | vcpkg meson helper | `none.txt` sets build-machine compiler to `false` | Ladybird's `libpsl` overlay port needs a native C compiler | workflow replaces it with a real x86_64-linux native file |
| 14 | CI host packages | Ubuntu runner | vcpkg ports need `autoconf/autoconf-archive/automake/libtool` | workflow installs them |

## UI

Ladybird's stock `UI/Android` Activity/layout is **not used for the visible UI**.
`LadybirdActivity.kt` is rewritten with Jetpack Compose:
- vertical tab rail (one entry per open tab, `+` FAB),
- arc-shaped address bar (`ArcShape`),
- Material 3 expressive-style tonal surfaces and shapes.

It still uses Ladybird's own `WebView`, `WebViewImplementation`, JNI bridge and
service classes, so the rendered pages come from the **Ladybird engine**, not WebView.

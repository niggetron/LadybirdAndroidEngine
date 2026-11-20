/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "WebViewImplementationNative.h"
#include "JNIHelpers.h"
#include <AK/Random.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/DecodedImageFrame.h>
#include <string.h>
#include <LibWebView/ViewImplementation.h>
#include <LibWebView/WebContentClient.h>
#include <android/bitmap.h>
#include <jni.h>

namespace Ladybird {

static Gfx::BitmapFormat to_gfx_bitmap_format(i32 f)
{
    switch (f) {
    case ANDROID_BITMAP_FORMAT_RGBA_8888:
        return Gfx::BitmapFormat::RGBA8888;
    default:
        VERIFY_NOT_REACHED();
    }
}

WebViewImplementationNative::WebViewImplementationNative(jobject thiz)
    : m_java_instance(thiz)
{
    // NOTE: m_java_instance's global ref is controlled by the JNI bindings
    initialize_client(CreateNewClient::Yes);

    on_ready_to_paint = [this]() {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, invalidate_layout_method);
    };

    on_load_start = [this](URL::URL const& url, bool is_redirect) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, on_load_start_method, url_string, is_redirect);
        env.get()->DeleteLocalRef(url_string);
    };
}

void WebViewImplementationNative::initialize_client(WebView::ViewImplementation::CreateNewClient)
{
    m_client_state = {};

    auto new_client = bind_web_content_client();

    new_client->on_web_content_process_crash = [] {
        warnln("WebContent crashed!");
        // FIXME: launch a new client
    };
    new_client->register_view(0, *this);

    m_client_state.client_handle = generate_random_uuid();
    client().async_set_window_handle(0, m_client_state.client_handle);

    client().async_set_viewport(0, viewport_size(), m_device_pixel_ratio, Web::ViewportIsFullscreen::No);
    client().async_set_zoom_level(0, m_zoom_level);

    set_system_visibility_state(Web::HTML::VisibilityState::Visible);

    // FIXME: update_palette, update system fonts
}

void WebViewImplementationNative::paint_into_bitmap(void* android_bitmap_raw, AndroidBitmapInfo const& info)
{
    // Software bitmaps only for now!
    VERIFY((info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) == 0);
    if (!android_bitmap_raw)
        return;

    auto* destination = static_cast<u8*>(android_bitmap_raw);
    auto const width = static_cast<size_t>(info.width);
    auto const height = static_cast<size_t>(info.height);
    auto const stride = static_cast<size_t>(info.stride);

    Gfx::Bitmap const* source = m_client_state.has_usable_bitmap
        ? m_client_state.front_bitmap.bitmap.ptr()
        : m_backup_bitmap.ptr();

    if (!source) {
        for (size_t y = 0; y < height; ++y) {
            auto* row = destination + y * stride;
            for (size_t x = 0; x < min(width, stride / 4); ++x) {
                row[x * 4 + 0] = 0xFF;
                row[x * 4 + 1] = 0x00;
                row[x * 4 + 2] = 0xFF;
                row[x * 4 + 3] = 0xFF;
            }
        }
        return;
    }

    auto const columns = min(width, static_cast<size_t>(source->width()));
    auto const rows = min(height, static_cast<size_t>(source->height()));
    for (size_t y = 0; y < rows; ++y)
        memcpy(destination + y * stride, source->scanline_u8(static_cast<int>(y)), columns * 4);
}

void WebViewImplementationNative::set_viewport_geometry(int w, int h)
{
    m_viewport_size = { w, h };
    handle_resize();
}

void WebViewImplementationNative::set_device_pixel_ratio(double f)
{
    m_device_pixel_ratio = f;
    handle_resize();
}

void WebViewImplementationNative::set_zoom_level(double f)
{
    m_zoom_level = f;
    client().async_set_zoom_level(0, m_zoom_level);
}

void WebViewImplementationNative::mouse_event(Web::MouseEvent::Type event_type, float x, float y, float raw_x, float raw_y)
{
    Gfx::IntPoint position = { x, y };
    Gfx::IntPoint screen_position = { raw_x, raw_y };
    auto event = Web::MouseEvent {
        event_type,
        position.to_type<Web::DevicePixels>(),
        screen_position.to_type<Web::DevicePixels>(),
        Web::UIEvents::MouseButton::Primary,
        Web::UIEvents::MouseButton::Primary,
        Web::UIEvents::KeyModifier::Mod_None,
        0,
        0,
        0,
        0,
        Web::WheelDeltaPrecision::Discrete,
        Web::ScrollGesturePhase::None,
        0,
        nullptr
    };

    enqueue_input_event(move(event));
}

NonnullRefPtr<WebView::WebContentClient> WebViewImplementationNative::bind_web_content_client()
{
    JavaEnvironment env(global_vm);

    int socket_fds[2] {};
    MUST(Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, socket_fds));

    int ui_fd = socket_fds[0];
    int wc_fd = socket_fds[1];

    // NOTE: The java object takes ownership of the socket fds
    env.get()->CallVoidMethod(m_java_instance, bind_webcontent_method, wc_fd);

    auto socket = MUST(Core::LocalSocket::adopt_fd(ui_fd));
    MUST(socket->set_blocking(true));

    auto new_client = make_ref_counted<WebView::WebContentClient>(make<IPC::Transport>(move(socket)), *this);

    return new_client;
}

}

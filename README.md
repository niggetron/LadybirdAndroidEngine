# Ladybird Android Engine (custom expressive UI)

Full Ladybird source tree (base `143885c5`) with every known Android build breakage
fixed directly in the source — see [ANDROID_BUILD_FIXES.md](ANDROID_BUILD_FIXES.md).

The visible UI is **our own**, written in Jetpack Compose with Material 3
expressive-style components:

- **Vertical tab rail** with an open-tab list and a `+` FAB
- **Arc-shaped address bar** (`ArcShape`) with a go FAB
- Engine rendering via Ladybird's own `WebView` / `WebViewImplementation` / JNI bridge
  and its WebContent, RequestServer and ImageDecoder services

The stock Ladybird `activity_main.xml` layout and toolbar are not used.

## Releases

`.github/workflows/build.yml` builds **arm64-v8a** and **armeabi-v7a** APKs from this
repository on every push and every 7 days, then publishes both in one GitHub Release.
This is the Ladybird engine — no System WebView.

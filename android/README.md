# Android

Native Kotlin / Jetpack Compose application. Target device: Android 15.

## Build
Open this directory in Android Studio with a current stable Android Gradle Plugin and JDK 17. The initial scaffold is being expanded; do not treat this milestone as a finished transfer client.

## Planned integration
- Android NSD service discovery for `_airbridge._tcp.`
- Foreground connection service for reliable active sessions.
- Android Sharesheet send target and SAF document picker.
- Received files stored in app-specific external files, with user-visible export/share actions.
- Clipboard sync is opt-in and respects Android background clipboard restrictions.

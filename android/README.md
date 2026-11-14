# Android app

Native Kotlin + Jetpack Compose application, minimum Android 8.0 (API 26).

## Current prototype

- Advertises AirBridge over Android NSD / Bonjour-compatible DNS-SD.
- Discovers AirBridge Mac service instances on the local network.
- Receives files into `Downloads/AirBridge` using MediaStore on Android 10+.
- Exposes Android's `ACTION_SEND` and `ACTION_SEND_MULTIPLE` share-sheet targets.
- Sends shared files to a discovered Mac using the ABR1 TCP protocol and SHA-256 verification.

## Test flow

1. Connect the phone and Mac to the same LAN.
2. Start AirBridge on both devices.
3. From another Android app, use Share → AirBridge.
4. Choose the discovered Mac and send.

The current protocol has no authentication or encryption. Use only on a trusted test LAN and only with non-sensitive test files.

## Build

```sh
gradle assembleDebug
```

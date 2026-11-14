# AirBridge

Private, direct file sharing and clipboard sync between one Android phone and one Mac.

## Design goals
- No cloud storage, accounts, relay, or internet dependency for local transfers.
- Direct LAN discovery and transfer; mobile data can remain disabled.
- Trusted-device pairing with explicit approval.
- macOS menu-bar drop target and Android share-sheet integration.
- Resumable, chunked transfers with SHA-256 integrity checks.
- Optional USB-C transport, implemented only after validating a supported macOS/Android USB protocol.

## Repository layout
- `android/`: native Kotlin + Jetpack Compose app.
- `macos/`: native SwiftUI menu-bar app.
- `protocol/`: transport and message contract shared by both apps.
- `assets/`: app identity artwork.

## Connectivity
For Wi-Fi, both devices join the same local network. AirBridge discovers peers with Bonjour/DNS-SD (Android NSD) and transfers directly over the LAN. A phone hotspot is not required when both devices already share a router/network. Internet access is not used for local transfer or clipboard sync.

For USB-C, a cable alone does not expose a generic bidirectional file socket on macOS. The USB transport must use a supported protocol and be tested on the target phone; USB debugging/ADB will not be silently required.

## Clipboard privacy
Clipboard sync is opt-in and limited to the paired device. macOS publishes clipboard changes only while AirBridge is active. Android applies incoming clipboard text using Android's supported clipboard APIs and visible foreground operation where required. Clipboard contents are never uploaded to a server.

## Build status
Initial project foundation. This repository is not yet a release build; device discovery, pairing, transfer, clipboard and USB support are being implemented and tested in stages.

## Build
See `android/README.md` and `macos/README.md`.

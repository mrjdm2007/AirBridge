# AirBridge

AirBridge is a native Android ↔ macOS direct-transfer project. Its goal is private local sharing without accounts, cloud storage, or a relay.

## Current implementation snapshot

This repository is an **experimental development build**, not a finished release.

Implemented in the current source:
- Android LAN service advertisement and Bonjour/NSD discovery.
- Mac Bonjour discovery and a TCP receive listener.
- Android share-sheet entry for one or multiple files.
- Mac file picker to send to a discovered Android device.
- Direct local-network file transfer using the shared ABR1 framing.
- SHA-256 file-integrity verification.
- Received files saved to `Downloads/AirBridge` on both platforms.
- Mac-side libmtp command integration for USB file listing, upload and download.

## Important limitations — not a release yet

- **Pairing, authentication and transport encryption are not implemented.** Do not use this prototype on an untrusted network or for sensitive files.
- Wi-Fi transfers have not yet been verified between the actual Moto Edge 50 Neo and MacBook.
- Mac build and MTP hardware operation have not been verified; the macOS Actions jobs have been cancelled before producing an artifact.
- MTP currently requires Homebrew libmtp tools and manual USB File Transfer mode selection; automatic USB detection is not implemented.
- Clipboard synchronization is not implemented.
- Transfer resume, user-facing progress/cancellation, robust reconnect and extensive error recovery are not implemented.
- Mac drag-and-drop sending and trusted-device persistence are not yet implemented.

## Feature roadmap

See [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md) for the prioritized plan covering secure pairing, encrypted transfers, resumable queues, folder sharing, drag-and-drop, opt-in clipboard sync, transfer history and advanced options. These are planned features, not claims about the current prototype.

## Wi-Fi setup for development testing

Both devices must be on the same local network. Internet access is not required for local transfer. Android advertises `_airbridge._tcp.` using NSD; the Mac browses Bonjour and runs a direct TCP receiver.

On Android, open AirBridge and share a file to it from another app. When the Mac appears, tap **Send**.

On Mac, open AirBridge, wait for the Android device under **Wi-Fi devices**, and use **Send file…**.

## USB MTP setup

Install libmtp on the Mac:

```sh
brew install libmtp
```

Unlock the phone, connect a data-capable USB cable, select **File transfer / Android Auto** on the phone, and press Refresh in the Mac app.

## Build

- Android: see [android/README.md](android/README.md).
- macOS: see [macos/README.md](macos/README.md).

CI status is not a substitute for hardware testing. Do not treat an unverified build as a completed release.

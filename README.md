# AirBridge

AirBridge is a native Android ↔ macOS direct-transfer project. It is designed for local file sharing without accounts, cloud storage, or a relay.

## Current implementation snapshot

This repository is still an **experimental development build**, not a finished secure release.

Implemented in the source:
- Android LAN service advertisement and Bonjour/NSD discovery.
- Mac Bonjour discovery and a TCP receive listener.
- Android share-sheet entry for one or multiple files.
- Mac file picker to send to a discovered Android device.
- Direct local-network file transfer using the shared ABR1 framing.
- SHA-256 file-integrity verification.
- Received files saved to `Downloads/AirBridge` on both platforms.
- Mac-side libmtp command integration for USB file listing, upload and download.

The `hardening/reliable-transfers` branch contains additional transfer hardening and progress/history work. It remains a draft because the security protocol and real-device validation are incomplete.

## Important limitations — not a release yet

- **Pairing, authentication and transport encryption are not implemented.** Do not use this prototype on an untrusted network or for sensitive files.
- Wi-Fi transfers and USB MTP have not yet been validated on the actual Moto Edge 50 Neo and MacBook hardware.
- MTP requires Homebrew libmtp tools and manual USB File Transfer mode selection; automatic USB detection is not implemented.
- Transfer resume, robust reconnect, folder transfer, Mac drag-and-drop and clipboard synchronization are not implemented in the main branch.
- CI builds prove compilation and packaging only; they do not prove secure or reliable device-to-device operation.

See [docs/FEATURE_ROADMAP.md](docs/FEATURE_ROADMAP.md) for the remaining release blockers and planned features.

## Download both apps from one Actions run

The combined workflow builds both platforms in the same run:
- **AirBridge-Android-APK** — Android debug APK.
- **AirBridge-macOS-App** — macOS app ZIP.

Open [AirBridge — Android + macOS builds](https://github.com/mrjdm2007/AirBridge/actions/workflows/build-all.yml), open the latest successful run on the `main` branch, then download both files from its **Artifacts** section. Artifacts are temporary CI downloads, not a signed public release.

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

## Build locally

- Android: see [android/README.md](android/README.md).
- macOS: see [macos/README.md](macos/README.md).

Do not treat an unverified CI build as a completed release.
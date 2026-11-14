# macOS

Native SwiftUI menu-bar application for macOS 13+.

## USB MTP transfers

The Mac app uses the open-source **libmtp** command-line tools as its MTP host backend. It does not use ADB or require USB debugging.

Install the dependency once:

```sh
brew install libmtp
```

Then:

1. Unlock the Android phone.
2. Connect it using a USB data cable.
3. On the phone's USB notification, select **File transfer / Android Auto**.
4. Open AirBridge and press **Refresh**.
5. Use **Send files to phone** to upload from the Mac, or the download icon beside a listed phone file to save it to the Mac.

The app expects the Homebrew executable paths on Apple Silicon (`/opt/homebrew`) and Intel (`/usr/local`). The phone must remain unlocked and in MTP mode during operations. Other applications that open the phone's MTP session can interfere; close them and reconnect if AirBridge cannot open the device.

## Build

```sh
brew install libmtp
swift build -c release
```

The macOS CI installs libmtp before compiling and packages the app. The current package artifact does not embed libmtp; the Homebrew dependency must be installed on the Mac that runs AirBridge.

## Current scope

Implemented here: MTP device detection through libmtp, phone file listing, Mac-to-phone upload, and phone-to-Mac download. The app also has a separate Wi-Fi discovery and transfer prototype; that transport is currently plaintext and unauthenticated.

MTP requirements: macOS host, Homebrew `libmtp`, unlocked Android device, and USB File Transfer mode. The MTP implementation is not a replacement for the full OpenMTP feature set: folder navigation, robust folder upload/download, and device-specific hardware validation remain incomplete.

Wi-Fi pairing/authentication/encryption, clipboard synchronization, resumable transfers, and hardware validation remain incomplete. Do not use Wi-Fi transfer for sensitive files until authenticated encryption is implemented.

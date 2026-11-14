# AirBridge transport design

This document records the implementation target for replacing the current prototype transports. It is a design decision, not a claim that the implementation is complete.

## Wi-Fi: LocalSend Protocol v2-compatible HTTPS

Replace the custom `ABR1` raw TCP stream with the LocalSend v2 REST transfer flow:

1. Advertise the HTTPS endpoint through local-network discovery. Discovery is only a locator; it never grants trust.
2. Each installation creates and persists a device TLS identity. The SHA-256 fingerprint of the DER certificate is the device identity presented during pairing.
3. On first contact, show the peer identity and require explicit user approval. Persist the approved certificate fingerprint and reject a changed fingerprint unless the user removes and re-pairs the device.
4. The sender posts metadata to `/api/localsend/v2/prepare-upload`. The receiver presents a per-transfer approval UI and returns a session ID and per-file random upload tokens only after approval.
5. Upload binary file bodies to `/api/localsend/v2/upload` over HTTPS. Require a valid session, token, and the paired certificate identity. Verify the declared SHA-256 before publishing the temporary file.
6. Implement cancel and expiry for pending sessions. Remove partial files on disconnect, cancellation, failed authentication, or hash mismatch. Never expose an unauthenticated HTTP fallback for file contents.

Use the published LocalSend protocol DTOs and endpoint semantics for interoperability. AirBridge-specific persistent trust and UI policy must not weaken TLS certificate verification or turn discovery into authentication.

## USB: OpenMTP-style MTP file manager

Keep USB MTP independent of Wi-Fi pairing and discovery. The Mac is the MTP initiator/USB host and Android is the MTP responder. Build an OpenMTP-like two-pane workflow around a native MTP backend:

- Detect the connected MTP device and enumerate each storage volume.
- Browse directories lazily, retaining storage ID, parent folder ID, object ID, object name, size, and object type.
- Support navigation, refresh, multi-selection, drag-and-drop, create-folder, rename, delete, and file upload/download.
- Use MTP object handles and storage/folder identifiers rather than treating a flat CLI listing as a filesystem.
- Stream data through libmtp callbacks where supported, report progress, and cancel safely. MTP requires the file size before a send transaction begins.
- Keep operations serialized per physical device unless the backend explicitly supports concurrent sessions. Handle disconnects and device re-enumeration without corrupting the file list.

OpenMTP is a workflow and reliability reference. Do not copy its proprietary/third-party binaries into AirBridge. If any OpenMTP source is reused, preserve its MIT license and notices. Prefer implementing the same user-facing workflow using AirBridge's own SwiftUI UI and a properly packaged libmtp backend.

## Required validation before release

- Shared protocol fixtures for Android and macOS: metadata encoding, certificate fingerprinting, session/token validation, hash verification, filename/path sanitization, and protocol error cases.
- Wi-Fi integration tests in both directions: first-pair approval, repeat trusted transfer, rejected approval, wrong certificate, expired/invalid token, interruption, cancellation, and hash mismatch.
- USB tests: storage enumeration, nested folder browsing, large-file send/receive, duplicate names, cancellation, disconnect/reconnect, and device-specific behavior.
- Physical Android-to-Mac and Mac-to-Android tests. CI compilation alone is not hardware validation.

## Current implementation status

The current branch still contains the `ABR1` raw TCP prototype and a basic macOS libmtp command-line wrapper. This document describes the intended replacement; it does not mean the replacement is already implemented.

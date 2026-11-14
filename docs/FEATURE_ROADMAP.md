# AirBridge Feature Roadmap

This roadmap separates release-critical work from optional convenience features. Status below reflects code present on `hardening/reliable-transfers`; successful CI builds do not replace real-device validation.

## Current implementation notes

- Implemented: Android and macOS LAN file transfers, Bonjour/NSD discovery, SHA-256 integrity verification, Android share-sheet sending, multi-selection sending, macOS MTP listing/upload/download through libmtp, Android and macOS send progress, macOS persistent Wi-Fi transfer history, macOS outgoing-transfer cancellation, and periodic MTP connection polling.
- Partially implemented: transfer management (progress and a basic Mac history only), USB/MTP (manual refresh plus periodic polling; actual device/phone compatibility still needs testing), cancellation (macOS outgoing LAN sends only).
- Not implemented: authenticated pairing, encrypted LAN transport, resumable transfers, speed/ETA, searchable cross-platform history, clipboard sync, robust folder transfers, and trusted-device management.
- Security warning: the current ABR1 LAN protocol is plaintext and unauthenticated. Use only on a trusted local network; do not transfer sensitive files until authenticated encryption is implemented.

## P0 — Required before everyday use

- [ ] **Authenticated device pairing** — show a short-lived pairing code/QR on the receiving device; require explicit approval on first connection; remember trusted devices with a revoke option.
- [ ] **Encrypted transport** — use TLS with per-device identity keys established during pairing. Reject unknown or changed identities and never silently fall back to plaintext.
- [ ] **Transfer queue** — queue multiple files, show per-file and aggregate progress, speed, ETA, and clear success/failure states.
- [ ] **Cancel and safe cleanup** — macOS outgoing LAN send cancellation is implemented; Android sends, incoming transfers and MTP cancellation still need cancellation controls and cleanup tests.
- [ ] **Resumable transfers** — persist transfer ID, file size, modification metadata and verified chunk offsets; revalidate the source and destination before resuming.
- [ ] **Reliable background transfers** — Android foreground-service notification while active; macOS menu-bar/background operation with explicit status.
- [ ] **End-to-end verification** — retain SHA-256 verification and add tests for truncated, reordered, duplicate and corrupted chunks.
- [ ] **Release and hardware validation** — successful macOS and Android builds plus real Moto/ Mac Wi-Fi and USB-MTP testing.

## P1 — High-value everyday features

- [ ] **Folder and multi-file transfer** — preserve directory structure, handle name collisions, and let users choose whether to replace, keep both, or skip.
- [ ] **Mac drag-and-drop** — drop files or folders onto the app/device row to send.
- [ ] **Clipboard sync** — opt-in text-only clipboard sharing between paired devices, with a visible pause switch and loop prevention. Never sync silently before pairing.
- [ ] **Transfer history** — macOS now stores the last 100 successful Wi-Fi transfers and supports clearing; searchable history, failures, Android and MTP entries remain.
- [ ] **Trusted-device manager** — rename devices, set favorites, inspect last-seen time and revoke trust.
- [ ] **Automatic device reconnect** — MTP connection state is polled on macOS; trusted Wi-Fi device reconnect is not implemented.
- [ ] **Notifications and quick actions** — show completion/failure notifications and provide open/reveal/retry actions.
- [ ] **Storage destination controls** — choose Mac destination and Android destination; show available space before large transfers.
- [ ] **Collision-safe naming** — deterministic “keep both” names and atomic final-file rename after verification.

## P2 — Quality-of-life features

- [ ] **Send links and text snippets** — share plain text and URLs through the native share sheet.
- [ ] **QR pairing and connection diagnostics** — QR-assisted pairing plus clear Wi-Fi, firewall, permission and USB troubleshooting.
- [ ] **Transfer rules** — optional per-device default destination and file-type routing.
- [ ] **Bandwidth controls** — optional transfer speed cap and pause/resume scheduling.
- [ ] **Large-transfer warnings** — warn about low battery, low storage and metered networks; never block local transfers unnecessarily.
- [ ] **File preview handoff** — open received files or reveal them in Finder/Files after completion.
- [ ] **Accessibility and localization** — keyboard navigation on macOS, screen-reader labels and localized strings.
- [ ] **Diagnostics export** — user-triggered redacted logs with filenames, local IPs, pairing secrets and file contents excluded.

## P3 — Optional advanced features

- [ ] **Local-network folder sync** — user-selected folders with one-way or two-way sync, conflict detection and a dry-run preview.
- [ ] **Photo/video optimized mode** — preserve original quality by default; optional compression must be opt-in and show estimated output size.
- [ ] **Nearby-network fallback** — manual IP/port connection when Bonjour/NSD discovery is blocked, still requiring authenticated pairing.
- [ ] **Multiple-device send** — send the same selected files to multiple already-trusted devices with independent progress.
- [ ] **Checksum manifest export** — optional manifest for archival or batch workflows.

## Suggested implementation order

1. Pairing, device identity and encrypted transport.
2. Transfer queue, progress, cancellation and safe temporary-file handling.
3. Chunked resumable transfers and protocol tests.
4. Folder transfer, collision handling and Mac drag-and-drop.
5. Transfer history, trusted-device manager and notifications.
6. Opt-in text clipboard sync.
7. Diagnostics, accessibility and optional advanced workflows.

## Security and privacy rules

- No cloud relay or account requirement.
- No plaintext fallback after encryption is introduced.
- Clipboard synchronization is off by default and text-only in the first version.
- Do not automatically accept transfers from unknown devices.
- Do not overwrite files without an explicit user-selected collision policy.
- Logs must not contain file contents, pairing codes, private keys or authentication tokens.
- Keep received files in a temporary location until integrity verification succeeds, then atomically move them into the chosen destination.

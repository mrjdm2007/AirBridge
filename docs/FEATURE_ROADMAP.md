# AirBridge Feature Roadmap

This roadmap separates release-critical work from optional convenience features. Items in this document are planned, not implemented, unless explicitly marked otherwise.

## P0 — Required before everyday use

- [ ] **Authenticated device pairing** — show a short-lived pairing code/QR on the receiving device; require explicit approval on first connection; remember trusted devices with a revoke option.
- [ ] **Encrypted transport** — use TLS with per-device identity keys established during pairing. Reject unknown or changed identities and never silently fall back to plaintext.
- [ ] **Transfer queue** — queue multiple files, show per-file and aggregate progress, speed, ETA, and clear success/failure states.
- [ ] **Cancel and safe cleanup** — cancellation must close sockets and remove incomplete temporary files without touching completed files.
- [ ] **Resumable transfers** — persist transfer ID, file size, modification metadata and verified chunk offsets; revalidate the source and destination before resuming.
- [ ] **Reliable background transfers** — Android foreground-service notification while active; macOS menu-bar/background operation with explicit status.
- [ ] **End-to-end verification** — retain SHA-256 verification and add tests for truncated, reordered, duplicate and corrupted chunks.
- [ ] **Release and hardware validation** — successful macOS and Android builds plus real Moto/ Mac Wi-Fi and USB-MTP testing.

## P1 — High-value everyday features

- [ ] **Folder and multi-file transfer** — preserve directory structure, handle name collisions, and let users choose whether to replace, keep both, or skip.
- [ ] **Mac drag-and-drop** — drop files or folders onto the app/device row to send.
- [ ] **Clipboard sync** — opt-in text-only clipboard sharing between paired devices, with a visible pause switch and loop prevention. Never sync silently before pairing.
- [ ] **Transfer history** — searchable history with filename, direction, device, timestamp, size and result; allow clearing history.
- [ ] **Trusted-device manager** — rename devices, set favorites, inspect last-seen time and revoke trust.
- [ ] **Automatic device reconnect** — reconnect only to previously paired devices and require user approval for new devices.
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

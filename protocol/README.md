# AirBridge local protocol (v1)

## Discovery
- Service type: `_airbridge._tcp.`
- Instance name: user-configurable device display name.
- TXT metadata: protocol version and a random installation identifier only. Never publish clipboard contents or filenames in discovery records.

## Pairing
1. A device advertises a local service.
2. The other device connects and requests pairing.
3. Both devices display a short authentication code derived from an ephemeral key exchange.
4. The user confirms the same code on both devices.
5. Persist a per-peer identity and rotate session keys; reject unknown peers by default.

## Transfer
- Direct TCP connection on the advertised port.
- Versioned, length-prefixed message envelope.
- File data is streamed in bounded chunks; never buffer an entire file in memory.
- Include transfer ID, sanitized display filename, total byte count, chunk offset, and SHA-256 digest.
- Write to a temporary file, verify digest, then atomically move into the AirBridge Received folder.
- Resume only after validating transfer ID, expected size and existing partial-file state.

## Clipboard
- Text-only v1; size limit and deduplication ID.
- Bidirectional setting, with Mac-to-Android as the default.
- Ignore updates originating from the remote clipboard to prevent feedback loops.
- Clipboard payloads are sent only over the authenticated local session.

## Transport priority
1. Direct Wi-Fi LAN.
2. Validated USB-C transport when a supported USB data protocol is available.
3. No cloud or relay fallback.

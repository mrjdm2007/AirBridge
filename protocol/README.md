# AirBridge transport protocol

## Current prototype framing: ABR1

The first prototype uses a single TCP connection per file. The same binary framing is implemented in Kotlin and Swift.

| Field | Size | Encoding |
|---|---:|---|
| Magic | 4 bytes | ASCII `ABR1` |
| Filename length | 4 bytes | Unsigned big-endian integer |
| File size | 8 bytes | Unsigned big-endian integer |
| SHA-256 | 32 bytes | Raw digest bytes |
| Filename | Variable | UTF-8, 1–1024 bytes |
| File data | File size | Raw bytes |

The receiver streams file data into a temporary file, calculates SHA-256, and only publishes the file into the AirBridge Downloads folder after the digest matches. A one-byte `0x06` acknowledgement is sent after successful receipt.

Bonjour service type: `_airbridge._tcp.`.

## Security status

ABR1 is a development framing, **not a secure authenticated protocol**. Pairing, authentication, replay protection and TLS are not implemented. SHA-256 detects accidental or in-transit corruption; it does not authenticate the sender or encrypt content. Do not expose this prototype to untrusted networks or use it for sensitive data.

## Planned protocol work

- Authenticated pairing with user approval and persistent peer identity.
- TLS or an equivalent authenticated encrypted transport.
- Bounded transfer sizes and resource quotas.
- Chunk identifiers, resume offsets and transfer IDs.
- Progress, cancellation and explicit completion/error messages.
- Clipboard messages with deduplication and loop prevention.

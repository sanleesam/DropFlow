# DropFlow Protocol Specification v1 (DFP/1)

This document specifies the **DropFlow Protocol v1 (DFP/1)**, a lightweight, zero-configuration local network protocol designed for fast, direct peer-to-peer file transfers between trusted devices (specifically macOS and Android) without relying on any external cloud services.

---

## 1. Goals

- **Zero-Configuration Local Discovery**: Automatically locate peer devices on the same local area network (LAN) or Wi-Fi subnetwork without manual IP entry.
- **Direct High-Performance Transfers**: Establish direct TCP connections for actual data streaming to ensure maximum speed and minimal protocol overhead.
- **Strict User Privacy & Local-Only Architecture**: All data must remain within the local subnet. File payloads never exit the local network.
- **Trust and Security**: Prevent unauthorized access by implementing a local pairing code verification handshake and trust-token validation.
- **Low Memory Overhead & Large File Support**: Support infinite file sizes and batch transfers by dividing payloads into structured streamable binary chunks.
- **Simplicity & Extensibility**: Control states are communicated using structured JSON control frames, while heavy file payloads utilize clean binary streaming framing.

---

## 2. Discovery Flow (UDP Broadcast)

Peer discovery is executed periodically using **UDP Broadcast** over a dedicated local port.

- **Discovery Port**: `42382`
- **Broadcast Interval**: Peer discovery broadcasts are emitted every 3 seconds.
- **Broadcast Target**: `255.255.255.255` (or the local subnet's broadcast address, e.g., `192.168.1.255`).

### 2.1 Discovery Broadcast (DISCOVER_PEER)
A device active on the subnet periodically announces its presence by broadcasting the following JSON payload over UDP:

```json
{
  "protocol": "DFP/1",
  "type": "DISCOVER_PEER",
  "deviceId": "macbook-pro-7af4",
  "deviceName": "Sanlee's MacBook Pro",
  "deviceType": "laptop",
  "port": 42382,
  "status": "online"
}
```

### 2.2 Discovery Response (DISCOVER_PEER_ACK)
When a listening device detects a `DISCOVER_PEER` broadcast, it responds directly back to the sender's unicast IP address and port with a discovery acknowledgement:

```json
{
  "protocol": "DFP/1",
  "type": "DISCOVER_PEER_ACK",
  "deviceId": "pixel-9-pro-91b4",
  "deviceName": "Pixel 9 Pro",
  "deviceType": "phone",
  "port": 42382,
  "status": "online"
}
```

---

## 3. Pairing Flow

Before file transfers can be authorized, unlinked devices must establish a local trust relation. Pairing is done over a direct TCP connection established on the recipient's port (`42382`).

### 3.1 Pairing Request (PAIR_REQUEST)
The initiator establishes a TCP connection to the receiver and transmits a JSON control block containing an out-of-band generated pairing code (e.g. 6-digit numeric):

```json
{
  "type": "PAIR_REQUEST",
  "deviceId": "macbook-pro-7af4",
  "deviceName": "Sanlee's MacBook Pro",
  "pairingCode": "129482"
}
```

### 3.2 User Confirmation & Response (PAIR_RESPONSE)
The receiver displays the pairing code and device information to the user.
- If the user **approves** the code, the receiver generates a long-lived local cryptographic token and returns:

```json
{
  "type": "PAIR_RESPONSE",
  "status": "accepted",
  "trustToken": "sec-a94f83b28b7e41cf9804e3923485ab89d"
}
```

- If the user **denies** or pairing times out:

```json
{
  "type": "PAIR_RESPONSE",
  "status": "rejected",
  "message": "Pairing code verification failed or timed out."
}
```

---

## 4. Transfer Request & Handshake

Every file transfer session begins with a control handshake over TCP to negotiate the transfer contents.

### 4.1 Sender Transfer Request (TRANSFER_REQUEST)
The sender establishes a TCP connection and sends a `TRANSFER_REQUEST` control frame detailing the list of files to transfer:

```json
{
  "type": "TRANSFER_REQUEST",
  "transferId": "tx-129849-ab3d",
  "trustToken": "sec-a94f83b28b7e41cf9804e3923485ab89d",
  "files": [
    {
      "fileId": "file-0",
      "name": "movie.mp4",
      "size": 134217728,
      "mimeType": "video/mp4",
      "hash": "sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
  ]
}
```

### 4.2 Receiver Response (TRANSFER_RESPONSE)
The receiver presents an incoming transfer notification to the user (or automatically accepts if configured for auto-accept from this trusted device) and responds:

- If **accepted**:

```json
{
  "type": "TRANSFER_RESPONSE",
  "transferId": "tx-129849-ab3d",
  "status": "accepted",
  "dataPort": 42383
}
```

*Note: The receiver can optionally specify a dedicated data port (`dataPort`) to receive raw binary frames, keeping control and data channels separate.*

- If **denied**:

```json
{
  "type": "TRANSFER_RESPONSE",
  "transferId": "tx-129849-ab3d",
  "status": "rejected",
  "message": "User declined the incoming file transfer request."
}
```

---

## 5. Chunked Transfer Design (Binary Stream)

To support massive files (e.g. multi-gigabyte video files) without running out of RAM, DFP/1 transfers files in sequential **binary chunks** over the negotiated TCP data connection.

### 5.1 Binary Frame Structure
Each frame transmitted consists of a 10-byte fixed header followed by the payload.

| Field Name | Size (Bytes) | Data Type | Description |
| :--- | :--- | :--- | :--- |
| **Magic Bytes** | 4 | `[u8; 4]` | Always `DFP1` (DropFlow Protocol v1 magic identifier) |
| **Version** | 1 | `uint8` | `0x01` |
| **Frame Tag** | 1 | `uint8` | `0x01` Request, `0x02` Accept, `0x03` Reject, `0x04` FileHeader, `0x05` DataChunk, `0x06` TransferComplete, `0x07` Cancel, `0x08` TransferAck, `0x09` FileComplete |
| **Payload Length** | 4 | `uint32 (BE)` | Length $N$ of succeeding payload bytes |
| **Payload** | $N$ | `bytes` | Frame payload (JSON for metadata, raw bytes for data chunks) |

### 5.2 Multi-File Stream Sequence
For each file in the transfer session:
1. Sender transmits `FileHeader` (`0x04`) frame with file index, relative path, size, and optional SHA-256 digest.
2. Sender streams `DataChunk` (`0x05`) frames (64 KB chunks).
3. Receiver streams to `<file>.dropflow-part` and calculates streaming SHA-256 digest on the fly.
4. Sender transmits `FileComplete` (`0x09`) frame with final checksum.
5. Receiver verifies checksum and byte count, then renames `.dropflow-part` to destination file (resolving filename collisions safely).

---

## 6. Progress Updates

During the active transfer, the receiver periodically writes progress updates back to the sender over the control TCP connection:

- **Update Frequency**: Every 250ms, or every 5% progress increment (whichever is less frequent, to save bandwidth).

```json
{
  "type": "TRANSFER_PROGRESS",
  "transferId": "tx-129849-ab3d",
  "fileId": "file-0",
  "status": "Sending...",
  "bytesReceived": 56360960,
  "progressPercent": 42,
  "speedBytesPerSecond": 131072000,
  "estimatedSecondsRemaining": 12
}
```

---

## 7. Error Messages (TRANSFER_ERROR)

If at any point during discovery, pairing, handshake, or active data streaming an issue is encountered, a `TRANSFER_ERROR` JSON control frame is sent and the TCP connections are closed cleanly.

```json
{
  "type": "TRANSFER_ERROR",
  "transferId": "tx-129849-ab3d",
  "code": "INSUFFICIENT_STORAGE",
  "message": "Out of storage space on the destination device."
}
```

### 7.1 Standardized Error Codes

- `INVALID_TRUST_TOKEN`: The provided security token is unknown or expired.
- `USER_CANCELLED`: The sender or receiver cancelled the transfer.
- `INSUFFICIENT_STORAGE`: The receiver does not have enough disk space.
- `HASH_MISMATCH`: The received file's hash does not match the handshake value.
- `CONNECTION_TIMEOUT`: Data stream stalled for too long.
- `FILE_WRITE_FAILURE`: Local filesystem error on the receiver.

---

## 8. Transfer Completion

Once all chunk indices for all files have been streamed:

1. The sender sends a `TRANSFER_COMPLETE` control message:

```json
{
  "type": "TRANSFER_COMPLETE",
  "transferId": "tx-129849-ab3d"
}
```

2. The receiver validates the integrity of all written files against the SHA-256 hashes provided in the initial `TRANSFER_REQUEST`.
3. If hashes match, the receiver responds with `TRANSFER_ACK`:

```json
{
  "type": "TRANSFER_ACK",
  "transferId": "tx-129849-ab3d",
  "status": "success"
}
```

4. The connection is cleanly closed, and the user UI is updated.

---

## 9. Future Compatibility & Versioning

- **Protocol Negotiation**: Every connection begins with control metadata containing the protocol identifier (e.g. `"protocol": "DFP/1"`).
- **Major Upgrades**: If a device running DFP/1 receives a connection initiating with `DFP/2`, it must check its compatibility and gracefully respond with a `TRANSFER_ERROR` code `UNSUPPORTED_PROTOCOL_VERSION` if it cannot parse the frames.
- **Backward Compatibility**: Newer protocol versions should support parsing DFP/1 JSON frames and fallback to v1 binary layouts if the peer is identified as DFP/1.

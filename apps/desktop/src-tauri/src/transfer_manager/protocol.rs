use serde::{Deserialize, Serialize};
use std::io::{Read, Write};

pub const MAGIC_BYTES: &[u8; 4] = b"DFP1";
pub const PROTOCOL_VERSION: u8 = 1;
pub const HELLO_HANDSHAKE: &str = "HELLO DFP/1";
pub const HELLO_ACK: &str = "HELLO_ACK DFP/1";

pub const MAX_METADATA_SIZE: usize = 64 * 1024; // 64 KB
/// Large manifests and resume lists (DFP/1.1 peers only).
pub const MAX_LARGE_METADATA_SIZE: usize = 16 * 1024 * 1024;
pub const MAX_CHUNK_SIZE: usize = 1024 * 1024; // 1 MB
pub const DEFAULT_CHUNK_SIZE: usize = 64 * 1024; // 64 KB

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameTag {
    TransferRequest = 0x01,
    TransferAccept = 0x02,
    TransferReject = 0x03,
    FileHeader = 0x04,
    DataChunk = 0x05,
    TransferComplete = 0x06,
    Cancel = 0x07,
    TransferAck = 0x08,
    FileComplete = 0x09,
    Heartbeat = 0x0A,
    Error = 0x0B,
    /// DFP/1.1: a `TransferRequest` whose manifest does not fit in 64 KB.
    TransferRequestLarge = 0x0C,
    /// DFP/1.1: what the receiver already holds from an interrupted attempt.
    ResumeState = 0x0D,
}

impl TryFrom<u8> for FrameTag {
    type Error = String;

    fn try_from(value: u8) -> Result<Self, String> {
        match value {
            0x01 => Ok(FrameTag::TransferRequest),
            0x02 => Ok(FrameTag::TransferAccept),
            0x03 => Ok(FrameTag::TransferReject),
            0x04 => Ok(FrameTag::FileHeader),
            0x05 => Ok(FrameTag::DataChunk),
            0x06 => Ok(FrameTag::TransferComplete),
            0x07 => Ok(FrameTag::Cancel),
            0x08 => Ok(FrameTag::TransferAck),
            0x09 => Ok(FrameTag::FileComplete),
            0x0A => Ok(FrameTag::Heartbeat),
            0x0B => Ok(FrameTag::Error),
            0x0C => Ok(FrameTag::TransferRequestLarge),
            0x0D => Ok(FrameTag::ResumeState),
            _ => Err(format!("Unknown frame tag: 0x{value:02X}")),
        }
    }
}

// ─── Data Payload Types ──────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileMetadata {
    pub file_index: u32,
    pub relative_path: String,
    pub size_bytes: u64,
    pub sha256_checksum: String,
    /// Local only. Never sent: it would reveal the sender's folder layout.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub final_path: String,
}

fn default_platform() -> String {
    "Desktop".to_string()
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferMetadata {
    pub session_id: String,
    pub sender_id: String,
    pub sender_name: String,
    #[serde(default = "default_platform")]
    pub sender_platform: String,
    pub total_files: u32,
    pub total_size_bytes: u64,
    pub files: Vec<FileMetadata>,
    /// DFP/1.1 capabilities the sender offers. Older peers ignore the field.
    #[serde(default, skip_serializing_if = "Vec::is_empty", deserialize_with = "bounded_features")]
    pub features: Vec<String>,
}

pub const MAX_FEATURES: usize = 16;
pub const MAX_FEATURE_NAME: usize = 32;

/// A request is parsed before anyone approves it, so the capability list is
/// bounded while it is read instead of after it has been allocated.
fn bounded_features<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Vec<String>, D::Error> {
    struct Features;
    impl<'de> serde::de::Visitor<'de> for Features {
        type Value = Vec<String>;
        fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
            formatter.write_str("a short list of feature names")
        }
        fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut seq: A) -> Result<Vec<String>, A::Error> {
            let mut features = Vec::new();
            while let Some(name) = seq.next_element::<std::borrow::Cow<'de, str>>()? {
                if features.len() == MAX_FEATURES || name.len() > MAX_FEATURE_NAME {
                    return Err(serde::de::Error::custom("too many or too long feature names"));
                }
                features.push(name.into_owned());
            }
            Ok(features)
        }
    }
    deserializer.deserialize_seq(Features)
}

/// JSON `TransferAccept` payload. Older receivers send the text `ACCEPTED`
/// or a JSON object without `features`; both mean "no extra capabilities".
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct AcceptPayload {
    #[serde(default)]
    pub status: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub features: Vec<String>,
}

/// One staged file the receiver can continue: index, bytes held, and the
/// SHA-256 of exactly those bytes.
#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
pub struct ResumeEntry {
    #[serde(rename = "i")]
    pub file_index: u32,
    #[serde(rename = "o")]
    pub offset: u64,
    #[serde(rename = "h")]
    pub sha256: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileHeaderPayload {
    pub file_index: u32,
    pub relative_path: String,
    pub size_bytes: u64,
    pub sha256_checksum: String,
    #[serde(default)]
    pub resume_offset: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileCompletePayload {
    pub file_index: u32,
    pub bytes_written: u64,
    pub sha256_checksum: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferAckPayload {
    pub status: String,
    #[serde(default)]
    pub resume_offset: u64,
}

// ─── Binary Frame Encoding & Decoding Helpers ────────────────────────────────

pub const FRAME_HEADER_SIZE: usize = 10;

pub(super) fn encode_frame_header(tag: FrameTag, payload_len: u32) -> [u8; FRAME_HEADER_SIZE] {
    let mut header = [0u8; FRAME_HEADER_SIZE];
    header[0..4].copy_from_slice(MAGIC_BYTES);
    header[4] = PROTOCOL_VERSION;
    header[5] = tag as u8;
    header[6..10].copy_from_slice(&payload_len.to_be_bytes());
    header
}

/// Writes a 10-byte global packet header: `[DFP1][VERSION:1][TAG:1][LEN:4 BE]`
pub fn write_frame_header<W: Write>(
    writer: &mut W,
    tag: FrameTag,
    payload_len: u32,
) -> Result<(), String> {
    writer
        .write_all(&encode_frame_header(tag, payload_len))
        .map_err(|e| format!("Failed to write frame header: {e}"))?;

    Ok(())
}

/// Writes a whole frame (header + payload) with one write, so a control frame
/// never leaves as two segments. Same bytes on the wire as header-then-payload.
pub fn write_frame<W: Write>(writer: &mut W, tag: FrameTag, payload: &[u8]) -> Result<(), String> {
    let mut frame = Vec::with_capacity(FRAME_HEADER_SIZE + payload.len());
    frame.extend_from_slice(&encode_frame_header(tag, payload.len() as u32));
    frame.extend_from_slice(payload);
    writer
        .write_all(&frame)
        .map_err(|e| format!("Failed to write {tag:?} frame: {e}"))?;
    writer
        .flush()
        .map_err(|e| format!("Failed to flush {tag:?} frame: {e}"))
}

/// Validates a 10-byte global packet header that has already been read.
pub fn parse_frame_header(header: &[u8; FRAME_HEADER_SIZE]) -> Result<(FrameTag, u32), String> {
    if &header[0..4] != MAGIC_BYTES {
        return Err("Invalid protocol magic bytes".to_string());
    }

    let version = header[4];
    if version != PROTOCOL_VERSION {
        return Err(format!("Unsupported protocol version: {version}"));
    }

    let tag = FrameTag::try_from(header[5])?;
    let payload_len = u32::from_be_bytes([header[6], header[7], header[8], header[9]]);

    let max_payload_len = match tag {
        FrameTag::DataChunk => MAX_CHUNK_SIZE,
        FrameTag::TransferRequestLarge | FrameTag::ResumeState => MAX_LARGE_METADATA_SIZE,
        _ => MAX_METADATA_SIZE,
    };
    if payload_len as usize > max_payload_len {
        return Err(format!(
            "{:?} payload size {payload_len} exceeds max {max_payload_len}",
            tag
        ));
    }

    Ok((tag, payload_len))
}

/// Reads and validates the 10-byte global packet header.
pub fn read_frame_header<R: Read>(reader: &mut R) -> Result<(FrameTag, u32), String> {
    let mut header = [0u8; FRAME_HEADER_SIZE];
    reader
        .read_exact(&mut header)
        .map_err(|e| format!("Failed to read frame header: {e}"))?;
    parse_frame_header(&header)
}

pub fn perform_outgoing_handshake<S: Read + Write>(stream: &mut S) -> Result<(), String> {
    write_heartbeat(stream, HELLO_HANDSHAKE)?;
    let (tag, payload_len) = read_frame_header(stream)?;
    if tag != FrameTag::Heartbeat {
        return Err(format!("Expected handshake heartbeat, received {tag:?}"));
    }
    let mut payload = vec![0u8; payload_len as usize];
    stream
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read handshake acknowledgement: {e}"))?;
    if payload.as_slice() != HELLO_ACK.as_bytes() {
        return Err("Invalid handshake acknowledgement".to_string());
    }
    Ok(())
}

pub fn perform_incoming_handshake<S: Read + Write>(stream: &mut S) -> Result<(), String> {
    let (tag, payload_len) = read_frame_header(stream)?;
    if tag != FrameTag::Heartbeat {
        return Err(format!("Expected handshake heartbeat, received {tag:?}"));
    }
    let mut payload = vec![0u8; payload_len as usize];
    stream
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read handshake: {e}"))?;
    if payload.as_slice() != HELLO_HANDSHAKE.as_bytes() {
        return Err("Invalid handshake message".to_string());
    }
    write_heartbeat(stream, HELLO_ACK)
}

fn write_heartbeat<W: Write>(writer: &mut W, message: &str) -> Result<(), String> {
    let payload = message.as_bytes();
    write_frame(writer, FrameTag::Heartbeat, payload)
}

/// Reads a frame payload. Memory grows with the bytes that actually arrive,
/// so a peer cannot reserve a large buffer by only claiming a length.
pub fn read_payload<R: Read>(reader: &mut R, payload_len: u32) -> Result<Vec<u8>, String> {
    let len = payload_len as usize;
    let mut payload = Vec::with_capacity(len.min(MAX_METADATA_SIZE));
    reader
        .take(len as u64)
        .read_to_end(&mut payload)
        .map_err(|e| format!("Failed to read frame payload: {e}"))?;
    if payload.len() != len {
        return Err("Connection closed in the middle of a frame".to_string());
    }
    Ok(payload)
}

/// Sends the `TransferRequest`. A manifest over 64 KB uses the large frame,
/// which only DFP/1.1 peers accept. Returns `true` when the large frame was used.
pub fn write_transfer_request<W: Write>(
    writer: &mut W,
    metadata: &TransferMetadata,
) -> Result<bool, String> {
    let json_bytes = serde_json::to_vec(metadata)
        .map_err(|e| format!("Failed to serialize TransferMetadata: {e}"))?;

    if json_bytes.len() > MAX_LARGE_METADATA_SIZE {
        return Err("Too many files for one transfer. Send them in smaller groups.".to_string());
    }
    let large = json_bytes.len() > MAX_METADATA_SIZE;
    let tag = if large { FrameTag::TransferRequestLarge } else { FrameTag::TransferRequest };
    write_frame(writer, tag, &json_bytes)?;
    Ok(large)
}

/// Reads a JSON `TransferRequest` frame (either size class).
pub fn read_transfer_request<R: Read>(reader: &mut R) -> Result<TransferMetadata, String> {
    let (tag, payload_len) = read_frame_header(reader)?;
    if tag != FrameTag::TransferRequest && tag != FrameTag::TransferRequestLarge {
        return Err(format!("Expected TransferRequest tag, received {:?}", tag));
    }
    let payload = read_payload(reader, payload_len)?;
    serde_json::from_slice(&payload)
        .map_err(|e| format!("Failed to parse TransferMetadata JSON: {e}"))
}

/// Sends a JSON `FileHeader` frame before streaming each file.
pub fn write_file_header<W: Write>(
    writer: &mut W,
    payload: &FileHeaderPayload,
) -> Result<(), String> {
    let json_bytes = serde_json::to_vec(payload)
        .map_err(|e| format!("Failed to serialize FileHeaderPayload: {e}"))?;

    if json_bytes.len() > MAX_METADATA_SIZE {
        return Err(format!(
            "FileHeaderPayload exceeds 64KB limit: {} bytes",
            json_bytes.len()
        ));
    }

    write_frame(writer, FrameTag::FileHeader, &json_bytes)
}

/// Reads a JSON `FileHeader` frame.
pub fn read_file_header<R: Read>(reader: &mut R) -> Result<FileHeaderPayload, String> {
    let (tag, payload_len) = read_frame_header(reader)?;
    if tag != FrameTag::FileHeader {
        return Err(format!("Expected FileHeader tag, received {:?}", tag));
    }

    if payload_len as usize > MAX_METADATA_SIZE {
        return Err(format!(
            "FileHeader payload size {payload_len} exceeds max 64KB"
        ));
    }

    let mut payload = vec![0u8; payload_len as usize];
    reader
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read FileHeader payload: {e}"))?;

    let header: FileHeaderPayload = serde_json::from_slice(&payload)
        .map_err(|e| format!("Failed to parse FileHeaderPayload JSON: {e}"))?;

    Ok(header)
}

/// Sends a JSON `FileComplete` frame after streaming each file.
pub fn write_file_complete<W: Write>(
    writer: &mut W,
    payload: &FileCompletePayload,
) -> Result<(), String> {
    let json_bytes = serde_json::to_vec(payload)
        .map_err(|e| format!("Failed to serialize FileCompletePayload: {e}"))?;

    if json_bytes.len() > MAX_METADATA_SIZE {
        return Err(format!(
            "FileCompletePayload exceeds 64KB limit: {} bytes",
            json_bytes.len()
        ));
    }

    write_frame(writer, FrameTag::FileComplete, &json_bytes)
}

/// Reads a JSON `FileComplete` frame.
pub fn read_file_complete<R: Read>(reader: &mut R) -> Result<FileCompletePayload, String> {
    let (tag, payload_len) = read_frame_header(reader)?;
    if tag != FrameTag::FileComplete {
        return Err(format!("Expected FileComplete tag, received {:?}", tag));
    }

    if payload_len as usize > MAX_METADATA_SIZE {
        return Err(format!(
            "FileComplete payload size {payload_len} exceeds max 64KB"
        ));
    }

    let mut payload = vec![0u8; payload_len as usize];
    reader
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read FileComplete payload: {e}"))?;

    let complete: FileCompletePayload = serde_json::from_slice(&payload)
        .map_err(|e| format!("Failed to parse FileCompletePayload JSON: {e}"))?;

    Ok(complete)
}

/// Sends a simple status response frame (`TransferAccept`, `TransferReject`, or `TransferAck`).
pub fn write_response_frame<W: Write>(
    writer: &mut W,
    tag: FrameTag,
    message: &str,
) -> Result<(), String> {
    let msg_bytes = message.as_bytes();
    write_frame(writer, tag, msg_bytes)
}

/// Sends a structured `TransferAckPayload` frame.
pub fn write_transfer_ack<W: Write>(
    writer: &mut W,
    payload: &TransferAckPayload,
) -> Result<(), String> {
    let json_bytes = serde_json::to_vec(payload)
        .map_err(|e| format!("Failed to serialize TransferAckPayload: {e}"))?;

    write_frame(writer, FrameTag::TransferAck, &json_bytes)
}

/// Reads a `TransferAckPayload` or legacy response frame.
pub fn read_transfer_ack<R: Read>(reader: &mut R) -> Result<TransferAckPayload, String> {
    let (tag, payload_len) = read_frame_header(reader)?;
    if tag != FrameTag::TransferAck && tag != FrameTag::TransferAccept {
        return Err(format!(
            "Expected TransferAck or TransferAccept tag, received {:?}",
            tag
        ));
    }

    let mut payload = vec![0u8; payload_len as usize];
    reader
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read TransferAck payload: {e}"))?;

    if let Ok(ack) = serde_json::from_slice::<TransferAckPayload>(&payload) {
        return Ok(ack);
    }

    let msg = String::from_utf8_lossy(&payload);
    let mut resume_offset = 0;
    if let Some(stripped) = msg.strip_prefix("RESUME:") {
        if let Ok(off) = stripped.parse::<u64>() {
            resume_offset = off;
        }
    }

    Ok(TransferAckPayload {
        status: msg.to_string(),
        resume_offset,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_protocol_request_roundtrip() {
        let meta = TransferMetadata {
            session_id: "test-session-123".to_string(),
            sender_id: "sender-uuid-456".to_string(),
            sender_name: "MacBook Pro".to_string(),
            sender_platform: "Desktop".to_string(),
            total_files: 2,
            total_size_bytes: 104857600,
            files: vec![
                FileMetadata {
                    file_index: 0,
                    relative_path: "docs/report.pdf".to_string(),
                    size_bytes: 52428800,
                    sha256_checksum:
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                            .to_string(),
                    final_path: String::new(),
                },
                FileMetadata {
                    file_index: 1,
                    relative_path: "videos/demo.mp4".to_string(),
                    size_bytes: 52428800,
                    sha256_checksum:
                        "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"
                            .to_string(),
                    final_path: String::new(),
                },
            ],
            features: Vec::new(),
        };

        let mut buffer = Vec::new();
        write_transfer_request(&mut buffer, &meta).unwrap();

        let mut cursor = std::io::Cursor::new(buffer);
        let decoded = read_transfer_request(&mut cursor).unwrap();

        assert_eq!(meta, decoded);
    }

    #[test]
    fn test_file_header_roundtrip() {
        let header = FileHeaderPayload {
            file_index: 0,
            relative_path: "images/vacation.png".to_string(),
            size_bytes: 2048576,
            sha256_checksum: "d41d8cd98f00b204e9800998ecf8427e".to_string(),
            resume_offset: 0,
        };

        let mut buffer = Vec::new();
        write_file_header(&mut buffer, &header).unwrap();

        let mut cursor = std::io::Cursor::new(buffer);
        let decoded = read_file_header(&mut cursor).unwrap();

        assert_eq!(header, decoded);
    }

    #[test]
    fn test_file_complete_roundtrip() {
        let complete = FileCompletePayload {
            file_index: 0,
            bytes_written: 2048576,
            sha256_checksum: "d41d8cd98f00b204e9800998ecf8427e".to_string(),
        };

        let mut buffer = Vec::new();
        write_file_complete(&mut buffer, &complete).unwrap();

        let mut cursor = std::io::Cursor::new(buffer);
        let decoded = read_file_complete(&mut cursor).unwrap();

        assert_eq!(complete, decoded);
    }

    #[test]
    fn test_transfer_ack_roundtrip_and_fallback() {
        let ack = TransferAckPayload {
            status: "ACCEPTED".to_string(),
            resume_offset: 1048576,
        };

        let mut buffer = Vec::new();
        write_transfer_ack(&mut buffer, &ack).unwrap();

        let mut cursor = std::io::Cursor::new(buffer);
        let decoded = read_transfer_ack(&mut cursor).unwrap();
        assert_eq!(ack, decoded);

        // Test fallback string parsing
        let mut string_buf = Vec::new();
        write_response_frame(&mut string_buf, FrameTag::TransferAccept, "RESUME:5242880").unwrap();
        let mut cursor2 = std::io::Cursor::new(string_buf);
        let decoded_str = read_transfer_ack(&mut cursor2).unwrap();
        assert_eq!(decoded_str.resume_offset, 5242880);
    }

    #[test]
    fn test_handshake_uses_heartbeat_frame() {
        let mut buffer = Vec::new();
        write_heartbeat(&mut buffer, HELLO_HANDSHAKE).unwrap();

        let mut cursor = std::io::Cursor::new(buffer);
        let (tag, payload_len) = read_frame_header(&mut cursor).unwrap();
        assert_eq!(tag, FrameTag::Heartbeat);
        let mut payload = vec![0u8; payload_len as usize];
        cursor.read_exact(&mut payload).unwrap();
        assert_eq!(payload, HELLO_HANDSHAKE.as_bytes());
    }

    #[test]
    fn large_manifests_use_the_large_frame_and_local_paths_stay_local() {
        let file = |index: u32| FileMetadata {
            file_index: index,
            relative_path: format!("Photos/2026/IMG_{index:05}.HEIC"),
            size_bytes: 1,
            sha256_checksum: String::new(),
            final_path: format!("/Users/someone/Pictures/IMG_{index:05}.HEIC"),
        };
        let mut meta = TransferMetadata {
            session_id: "s".into(),
            sender_id: "d".into(),
            sender_name: "Test".into(),
            sender_platform: "Desktop".into(),
            total_files: 1,
            total_size_bytes: 1,
            files: vec![FileMetadata { final_path: String::new(), ..file(0) }],
            features: vec!["batch".into()],
        };
        let mut small = Vec::new();
        assert!(!write_transfer_request(&mut small, &meta).unwrap());
        assert_eq!(small[5], FrameTag::TransferRequest as u8);
        assert!(!String::from_utf8_lossy(&small).contains("finalPath"));
        // A peer that predates `features` sends none; that must still parse.
        let legacy = br#"{"sessionId":"s","senderId":"d","senderName":"T","totalFiles":0,"totalSizeBytes":0,"files":[]}"#;
        assert!(serde_json::from_slice::<TransferMetadata>(legacy).unwrap().features.is_empty());

        meta.files = (0..5000).map(|i| FileMetadata { final_path: String::new(), ..file(i) }).collect();
        meta.total_files = 5000;
        meta.total_size_bytes = 5000;
        let mut large = Vec::new();
        assert!(write_transfer_request(&mut large, &meta).unwrap());
        assert_eq!(large[5], FrameTag::TransferRequestLarge as u8);
        assert_eq!(read_transfer_request(&mut std::io::Cursor::new(large)).unwrap(), meta);
    }

    #[test]
    fn a_request_cannot_inflate_memory_through_its_feature_list() {
        let request = |features: String| {
            format!(r#"{{"sessionId":"s","senderId":"d","senderName":"T","totalFiles":0,"totalSizeBytes":0,"files":[],"features":[{features}]}}"#)
        };
        let ok = request(r#""batch","resume""#.into());
        assert_eq!(serde_json::from_str::<TransferMetadata>(&ok).unwrap().features, ["batch", "resume"]);
        let many = request(vec![r#""""#; 5_000_000].join(","));
        assert!(serde_json::from_str::<TransferMetadata>(&many).is_err());
        let long = request(format!(r#""{}""#, "x".repeat(MAX_FEATURE_NAME + 1)));
        assert!(serde_json::from_str::<TransferMetadata>(&long).is_err());
    }

    #[test]
    fn payload_reads_fail_on_truncation_without_trusting_the_claimed_length() {
        let mut short = std::io::Cursor::new(vec![1u8; 10]);
        assert!(read_payload(&mut short, MAX_LARGE_METADATA_SIZE as u32).is_err());
        assert_eq!(read_payload(&mut std::io::Cursor::new(b"abc".to_vec()), 3).unwrap(), b"abc");
    }

    #[test]
    fn test_invalid_magic_bytes_rejected() {
        let bad_header = [0x00, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x05];
        let mut cursor = std::io::Cursor::new(bad_header);
        let result = read_frame_header(&mut cursor);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Invalid protocol magic bytes"));
    }
}

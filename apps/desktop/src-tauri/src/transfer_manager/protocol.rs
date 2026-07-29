use serde::{Deserialize, Serialize};
use std::io::{Read, Write};

pub const MAGIC_BYTES: &[u8; 4] = b"DFP1";
pub const PROTOCOL_VERSION: u8 = 1;

pub const MAX_METADATA_SIZE: usize = 64 * 1024; // 64 KB
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
}

impl TryFrom<u8> for FrameTag {
    type Error = String;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
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
    #[serde(default)]
    pub final_path: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferMetadata {
    pub session_id: String,
    pub sender_id: String,
    pub sender_name: String,
    pub total_files: u32,
    pub total_size_bytes: u64,
    pub files: Vec<FileMetadata>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileHeaderPayload {
    pub file_index: u32,
    pub relative_path: String,
    pub size_bytes: u64,
    pub sha256_checksum: String,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileCompletePayload {
    pub file_index: u32,
    pub bytes_written: u64,
    pub sha256_checksum: String,
}

// ─── Binary Frame Encoding & Decoding Helpers ────────────────────────────────

/// Writes a 10-byte global packet header: `[DFP1][VERSION:1][TAG:1][LEN:4 BE]`
pub fn write_frame_header<W: Write>(
    writer: &mut W,
    tag: FrameTag,
    payload_len: u32,
) -> Result<(), String> {
    let mut header = [0u8; 10];
    header[0..4].copy_from_slice(MAGIC_BYTES);
    header[4] = PROTOCOL_VERSION;
    header[5] = tag as u8;
    header[6..10].copy_from_slice(&payload_len.to_be_bytes());

    writer
        .write_all(&header)
        .map_err(|e| format!("Failed to write frame header: {e}"))?;

    Ok(())
}

/// Reads and validates the 10-byte global packet header.
pub fn read_frame_header<R: Read>(reader: &mut R) -> Result<(FrameTag, u32), String> {
    let mut header = [0u8; 10];
    reader
        .read_exact(&mut header)
        .map_err(|e| format!("Failed to read frame header: {e}"))?;

    if &header[0..4] != MAGIC_BYTES {
        return Err("Invalid protocol magic bytes".to_string());
    }

    let version = header[4];
    if version != PROTOCOL_VERSION {
        return Err(format!("Unsupported protocol version: {version}"));
    }

    let tag = FrameTag::try_from(header[5])?;
    let payload_len = u32::from_be_bytes([header[6], header[7], header[8], header[9]]);

    Ok((tag, payload_len))
}

/// Sends a complete JSON `TransferRequest` frame.
pub fn write_transfer_request<W: Write>(
    writer: &mut W,
    metadata: &TransferMetadata,
) -> Result<(), String> {
    let json_bytes = serde_json::to_vec(metadata)
        .map_err(|e| format!("Failed to serialize TransferMetadata: {e}"))?;

    if json_bytes.len() > MAX_METADATA_SIZE {
        return Err(format!(
            "TransferMetadata payload exceeds 64KB limit: {} bytes",
            json_bytes.len()
        ));
    }

    write_frame_header(writer, FrameTag::TransferRequest, json_bytes.len() as u32)?;
    writer
        .write_all(&json_bytes)
        .map_err(|e| format!("Failed to write TransferMetadata payload: {e}"))?;
    writer
        .flush()
        .map_err(|e| format!("Failed to flush stream: {e}"))?;

    Ok(())
}

/// Reads a JSON `TransferRequest` frame.
pub fn read_transfer_request<R: Read>(reader: &mut R) -> Result<TransferMetadata, String> {
    let (tag, payload_len) = read_frame_header(reader)?;
    if tag != FrameTag::TransferRequest {
        return Err(format!("Expected TransferRequest tag, received {:?}", tag));
    }

    if payload_len as usize > MAX_METADATA_SIZE {
        return Err(format!(
            "Metadata size {payload_len} exceeds max allowed 64KB"
        ));
    }

    let mut payload = vec![0u8; payload_len as usize];
    reader
        .read_exact(&mut payload)
        .map_err(|e| format!("Failed to read metadata payload: {e}"))?;

    let metadata: TransferMetadata = serde_json::from_slice(&payload)
        .map_err(|e| format!("Failed to parse TransferMetadata JSON: {e}"))?;

    Ok(metadata)
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

    write_frame_header(writer, FrameTag::FileHeader, json_bytes.len() as u32)?;
    writer
        .write_all(&json_bytes)
        .map_err(|e| format!("Failed to write FileHeader payload: {e}"))?;
    writer
        .flush()
        .map_err(|e| format!("Failed to flush stream: {e}"))?;

    Ok(())
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

    write_frame_header(writer, FrameTag::FileComplete, json_bytes.len() as u32)?;
    writer
        .write_all(&json_bytes)
        .map_err(|e| format!("Failed to write FileComplete payload: {e}"))?;
    writer
        .flush()
        .map_err(|e| format!("Failed to flush stream: {e}"))?;

    Ok(())
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
    write_frame_header(writer, tag, msg_bytes.len() as u32)?;
    writer
        .write_all(msg_bytes)
        .map_err(|e| format!("Failed to write response payload: {e}"))?;
    writer
        .flush()
        .map_err(|e| format!("Failed to flush stream: {e}"))?;
    Ok(())
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
    fn test_invalid_magic_bytes_rejected() {
        let bad_header = [0x00, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x05];
        let mut cursor = std::io::Cursor::new(bad_header);
        let result = read_frame_header(&mut cursor);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Invalid protocol magic bytes"));
    }
}

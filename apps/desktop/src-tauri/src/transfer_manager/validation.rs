use std::collections::HashSet;
use super::protocol::{FileHeaderPayload, TransferMetadata};
use super::security::sanitize_relative_path;

pub fn validate_request(meta: &TransferMetadata) -> Result<(), String> {
    if meta.session_id.is_empty() || meta.total_files == 0 || meta.total_files as usize != meta.files.len() {
        return Err("Invalid transfer session or file count".into());
    }
    let mut total = 0u64;
    let mut paths = HashSet::new();
    for (index, file) in meta.files.iter().enumerate() {
        let path = sanitize_relative_path(&file.relative_path)?;
        // Windows filesystem paths are case insensitive. Reject collisions on
        // every platform so the same request has consistent safe semantics.
        if file.file_index as usize != index || !paths.insert(path.to_string_lossy().to_lowercase()) {
            return Err("Duplicate file path or invalid file index".into());
        }
        total = total.checked_add(file.size_bytes).ok_or("Transfer size overflow")?;
    }
    if total != meta.total_size_bytes { return Err("Transfer byte total does not match file metadata".into()); }
    Ok(())
}

pub fn validate_header(meta: &TransferMetadata, index: usize, header: &FileHeaderPayload) -> Result<(), String> {
    let expected = meta.files.get(index).ok_or("Unexpected file header")?;
    if header.file_index != expected.file_index || header.relative_path != expected.relative_path
        || header.size_bytes != expected.size_bytes {
        return Err("File header does not match the approved transfer".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> TransferMetadata {
        serde_json::from_value(serde_json::json!({"sessionId":"s", "senderId":"d", "senderName":"Test", "totalFiles":1, "totalSizeBytes":3,
            "files":[{"fileIndex":0,"relativePath":"Folder/a.txt","sizeBytes":3,"sha256Checksum":""}]})).unwrap()
    }
    #[test]
    fn validates_counts_paths_and_totals() {
        let mut m=fixture(); assert!(validate_request(&m).is_ok());
        m.total_size_bytes=4; assert!(validate_request(&m).is_err()); m.total_size_bytes=3;
        m.files[0].relative_path="../escape".into(); assert!(validate_request(&m).is_err());
        m=fixture(); m.files.push(m.files[0].clone()); m.total_files=2; m.total_size_bytes=6;
        m.files[1].file_index=1; assert!(validate_request(&m).is_err());
    }
    #[test]
    fn rejects_unapproved_file_headers() {
        let m=fixture();
        let mut h=FileHeaderPayload {file_index:0,relative_path:"Folder/a.txt".into(),size_bytes:3,sha256_checksum:String::new(),resume_offset:0};
        assert!(validate_header(&m,0,&h).is_ok());
        h.size_bytes=4; assert!(validate_header(&m,0,&h).is_err());
    }
}

use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::net::{Shutdown, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager};
use uuid::Uuid;

use super::protocol::{
    read_frame_header, read_transfer_ack, write_file_complete, write_file_header,
    write_frame_header, write_transfer_request, FileCompletePayload, FileHeaderPayload,
    FileMetadata, FrameTag, TransferAckPayload, TransferMetadata, DEFAULT_CHUNK_SIZE,
};
use super::receiver::{TransferCompletedPayload, TransferFailedPayload, TransferProgressPayload};

#[allow(clippy::too_many_arguments)]
pub fn send_files_over_tcp(
    app: &AppHandle,
    peer_address: &str,
    peer_port: u16,
    local_uuid: &str,
    local_device_name: &str,
    file_paths: Vec<String>,
    cancel_flag: Arc<AtomicBool>,
    session_id: Option<String>,
) -> Result<String, String> {
    let _sleep_guard = app
        .try_state::<crate::power_manager::SleepState>()
        .map(|s| s.sleep_manager.acquire());

    if file_paths.is_empty() {
        return Err("No files selected for transfer".to_string());
    }

    let session_id = session_id.unwrap_or_else(|| format!("tx-{}", Uuid::new_v4()));
    let mut files_metadata = Vec::new();
    let mut total_size_bytes: u64 = 0;

    for (idx, path_str) in file_paths.iter().enumerate() {
        let path = Path::new(path_str);
        if !path.exists() || !path.is_file() {
            return Err(format!("Invalid file path: {path_str}"));
        }

        let metadata = std::fs::metadata(path)
            .map_err(|e| format!("Failed to read metadata for {path_str}: {e}"))?;

        let filename = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("file")
            .to_string();

        let file_size = metadata.len();
        total_size_bytes += file_size;

        files_metadata.push(FileMetadata {
            file_index: idx as u32,
            relative_path: filename,
            size_bytes: file_size,
            sha256_checksum: String::new(), // Populated dynamically during streaming
            final_path: path_str.clone(),
        });
    }

    let total_files_count = files_metadata.len() as u32;

    let transfer_meta = TransferMetadata {
        session_id: session_id.clone(),
        sender_id: local_uuid.to_string(),
        sender_name: local_device_name.to_string(),
        sender_platform: crate::device_discovery::get_system_platform(),
        total_files: total_files_count,
        total_size_bytes,
        files: files_metadata.clone(),
    };

    let target_socket_addr = format!("{}:{}", peer_address, peer_port);

    let mut stream = TcpStream::connect_timeout(
        &target_socket_addr
            .parse()
            .map_err(|e| format!("Invalid peer socket address {target_socket_addr}: {e}"))?,
        Duration::from_secs(10),
    )
    .map_err(|e| format!("Failed to connect to peer at {target_socket_addr}: {e}"))?;

    println!("[Sender] CONNECTED to {target_socket_addr}");

    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .map_err(|e| format!("Failed to set stream timeout: {e}"))?;

    // 1. Send TransferRequest metadata frame
    write_transfer_request(&mut stream, &transfer_meta)?;
    println!("[Sender] REQUEST_SENT");

    crate::state_manager::update_incomplete_session(
        app,
        crate::state_manager::IncompleteTransferSchema {
            session_id: session_id.clone(),
            file_name: transfer_meta
                .files
                .first()
                .map(|f| f.relative_path.clone())
                .unwrap_or_default(),
            device_name: peer_address.to_string(),
            total_files: total_files_count,
            total_size_bytes,
            bytes_completed: 0,
            direction: "send".to_string(),
            receive_dir: None,
            file_paths: Some(file_paths.clone()),
            files: None,
            timestamp_ms: Some(
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64,
            ),
        },
    );

    // 2. Read acceptance frame header & payload
    let (resp_tag, resp_len) = read_frame_header(&mut stream)
        .map_err(|e| format!("Failed to read peer acceptance response header: {e}"))?;

    if resp_tag != FrameTag::TransferAccept {
        let err_msg = if resp_tag == FrameTag::TransferReject {
            "Transfer declined by recipient".to_string()
        } else {
            format!("Peer rejected transfer with frame tag {:?}", resp_tag)
        };
        let _ = app.emit(
            "transfer-failed",
            TransferFailedPayload {
                session_id: session_id.clone(),
                error: err_msg.clone(),
            },
        );
        return Err(err_msg);
    }

    let mut resp_payload = vec![0u8; resp_len as usize];
    if resp_len > 0 {
        stream
            .read_exact(&mut resp_payload)
            .map_err(|e| format!("Failed to read peer response payload: {e}"))?;
    }
    println!("[Sender] ACCEPT_RECEIVED");

    let start_time = Instant::now();
    let mut total_sent_bytes: u64 = 0;
    let mut last_emit_time = Instant::now();
    let emit_interval = Duration::from_millis(50); // Max 20 FPS progress emissions

    // 3. Stream data for each file
    println!("[Sender] STREAMING {} files", total_files_count);
    for (idx, path_str) in file_paths.iter().enumerate() {
        if cancel_flag.load(Ordering::Relaxed) {
            let _ = write_frame_header(&mut stream, FrameTag::Cancel, 0);
            let err_msg = "Transfer cancelled by user".to_string();
            let _ = app.emit(
                "transfer-failed",
                TransferFailedPayload {
                    session_id: session_id.clone(),
                    error: err_msg.clone(),
                },
            );
            return Err(err_msg);
        }

        let mut file = File::open(path_str)
            .map_err(|e| format!("Failed to open file for reading {path_str}: {e}"))?;

        let file_meta = &transfer_meta.files[idx];

        // 3a. Send FileHeader frame
        let file_header_payload = FileHeaderPayload {
            file_index: idx as u32,
            relative_path: file_meta.relative_path.clone(),
            size_bytes: file_meta.size_bytes,
            sha256_checksum: String::new(),
            resume_offset: 0,
        };
        write_file_header(&mut stream, &file_header_payload)?;

        // Read TransferAck from receiver to negotiate resume_offset
        let ack = read_transfer_ack(&mut stream).unwrap_or_else(|_| TransferAckPayload {
            status: "ACCEPTED".to_string(),
            resume_offset: 0,
        });

        let mut file_bytes_sent: u64 = 0;
        if ack.resume_offset > 0
            && ack.resume_offset < file_meta.size_bytes
            && file.seek(SeekFrom::Start(ack.resume_offset)).is_ok()
        {
            file_bytes_sent = ack.resume_offset;
            total_sent_bytes += ack.resume_offset;
            println!(
                "[Sender] RESUMING file '{}' from offset {}",
                file_meta.relative_path, ack.resume_offset
            );
        }

        let mut hasher = Sha256::new();
        let mut file_buffer = vec![0u8; DEFAULT_CHUNK_SIZE];

        loop {
            if cancel_flag.load(Ordering::Relaxed) {
                let _ = write_frame_header(&mut stream, FrameTag::Cancel, 0);
                let err_msg = "Transfer cancelled by user".to_string();
                let _ = app.emit(
                    "transfer-failed",
                    TransferFailedPayload {
                        session_id: session_id.clone(),
                        error: err_msg.clone(),
                    },
                );
                return Err(err_msg);
            }

            let n = file
                .read(&mut file_buffer)
                .map_err(|e| format!("Failed reading file chunk: {e}"))?;

            if n == 0 {
                break;
            }

            hasher.update(&file_buffer[..n]);

            // Write DataChunk frame header + payload
            write_frame_header(&mut stream, FrameTag::DataChunk, n as u32)?;
            stream
                .write_all(&file_buffer[..n])
                .map_err(|e| format!("Failed writing chunk payload to socket: {e}"))?;

            file_bytes_sent += n as u64;
            total_sent_bytes += n as u64;

            let is_final = total_sent_bytes == total_size_bytes;
            if is_final || last_emit_time.elapsed() >= emit_interval {
                last_emit_time = Instant::now();
                let elapsed_secs = start_time.elapsed().as_secs_f64();
                let speed = if elapsed_secs > 0.0 {
                    (total_sent_bytes as f64 / elapsed_secs) as u64
                } else {
                    0
                };

                let percentage = if total_size_bytes > 0 {
                    ((total_sent_bytes as f32 / total_size_bytes as f32) * 100.0).min(100.0)
                } else {
                    100.0
                };

                let _ = app.emit(
                    "transfer-progress",
                    TransferProgressPayload {
                        session_id: session_id.clone(),
                        current_file_index: idx as u32,
                        current_file_name: file_meta.relative_path.clone(),
                        current_file_bytes: file_bytes_sent,
                        current_file_total_bytes: file_meta.size_bytes,
                        session_bytes_sent: total_sent_bytes,
                        session_total_bytes: total_size_bytes,
                        total_files: total_files_count,
                        percentage,
                        speed_bytes_per_sec: speed,
                    },
                );
            }
        }

        let file_checksum = format!("{:x}", hasher.finalize());

        // 3b. Send FileComplete frame
        let file_comp_payload = FileCompletePayload {
            file_index: idx as u32,
            bytes_written: file_bytes_sent,
            sha256_checksum: file_checksum,
        };
        write_file_complete(&mut stream, &file_comp_payload)?;
    }

    stream
        .flush()
        .map_err(|e| format!("Failed to flush TCP stream: {e}"))?;

    // 4. Send TransferComplete frame
    let session_bytes = session_id.as_bytes();
    write_frame_header(
        &mut stream,
        FrameTag::TransferComplete,
        session_bytes.len() as u32,
    )?;
    stream
        .write_all(session_bytes)
        .map_err(|e| format!("Failed writing TransferComplete payload: {e}"))?;
    stream
        .flush()
        .map_err(|e| format!("Failed flushing TransferComplete frame: {e}"))?;
    println!("[Sender] TRANSFER_COMPLETE_SENT");

    // 5. Wait for TransferAck frame from receiver
    let (ack_tag, ack_len) = read_frame_header(&mut stream)
        .map_err(|e| format!("Failed waiting for TransferAck from receiver: {e}"))?;

    if ack_tag != FrameTag::TransferAck {
        let err_msg = format!("Expected TransferAck, received {:?}", ack_tag);
        let _ = app.emit(
            "transfer-failed",
            TransferFailedPayload {
                session_id: session_id.clone(),
                error: err_msg.clone(),
            },
        );
        return Err(err_msg);
    }

    if ack_len > 0 {
        let mut ack_buf = vec![0u8; ack_len as usize];
        let _ = stream.read_exact(&mut ack_buf);
    }
    println!("[Sender] ACK_RECEIVED");

    // 6. Graceful shutdown
    stream
        .shutdown(Shutdown::Write)
        .map_err(|e| format!("Failed to shutdown TCP write stream: {e}"))?;
    println!("[Sender] SHUTDOWN");

    crate::state_manager::remove_incomplete_session(app, &session_id);

    let size_mb = (total_size_bytes as f64) / (1024.0 * 1024.0);
    let formatted_size = if size_mb >= 1.0 {
        format!("{:.1} MB", size_mb)
    } else {
        format!("{} KB", total_size_bytes.div_ceil(1024))
    };

    let main_filename = transfer_meta
        .files
        .first()
        .map(|f| f.relative_path.as_str())
        .unwrap_or("File");

    let _ = app.emit(
        "transfer-completed",
        TransferCompletedPayload {
            session_id: session_id.clone(),
            file_name: main_filename.to_string(),
            device_name: "Target Device".to_string(),
            size: formatted_size,
            timestamp: "Just now".to_string(),
            total_files: total_files_count,
            total_size_bytes,
            files: transfer_meta.files,
            receive_dir: String::new(),
        },
    );

    println!("[Sender] Successfully completed streaming {total_sent_bytes} bytes over TCP");
    Ok(session_id)
}

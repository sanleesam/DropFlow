use std::fs::File;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::path::Path;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter};
use uuid::Uuid;

use super::protocol::{
    read_frame_header, write_frame_header, write_transfer_request, FrameTag, DEFAULT_CHUNK_SIZE,
    FileMetadata, TransferMetadata,
};
use super::receiver::{TransferCompletedPayload, TransferFailedPayload, TransferProgressPayload};

pub fn send_files_over_tcp(
    app: &AppHandle,
    peer_address: &str,
    peer_port: u16,
    local_uuid: &str,
    local_device_name: &str,
    file_paths: Vec<String>,
) -> Result<String, String> {
    if file_paths.is_empty() {
        return Err("No files selected for transfer".to_string());
    }

    let session_id = format!("tx-{}", Uuid::new_v4());
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
        });
    }

    let transfer_meta = TransferMetadata {
        session_id: session_id.clone(),
        sender_id: local_uuid.to_string(),
        sender_name: local_device_name.to_string(),
        total_files: files_metadata.len() as u32,
        total_size_bytes,
        files: files_metadata,
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

    // 2. Read acceptance frame header & payload
    let (resp_tag, resp_len) = read_frame_header(&mut stream)
        .map_err(|e| format!("Failed to read peer acceptance response header: {e}"))?;

    if resp_tag != FrameTag::TransferAccept {
        let err_msg = format!("Peer rejected transfer with frame tag {:?}", resp_tag);
        let _ = app.emit("transfer-failed", TransferFailedPayload {
            session_id: session_id.clone(),
            error: err_msg.clone(),
        });
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

    // 3. Stream data chunks for each file
    println!("[Sender] STREAMING");
    for (idx, path_str) in file_paths.iter().enumerate() {
        let mut file = File::open(path_str)
            .map_err(|e| format!("Failed to open file for reading {path_str}: {e}"))?;

        let file_meta = &transfer_meta.files[idx];
        let mut file_buffer = vec![0u8; DEFAULT_CHUNK_SIZE];

        loop {
            let n = file
                .read(&mut file_buffer)
                .map_err(|e| format!("Failed reading file chunk: {e}"))?;

            if n == 0 {
                break;
            }

            // Write DataChunk frame header + payload
            write_frame_header(&mut stream, FrameTag::DataChunk, n as u32)?;
            stream
                .write_all(&file_buffer[..n])
                .map_err(|e| format!("Failed writing chunk payload to socket: {e}"))?;

            total_sent_bytes += n as u64;

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

            let _ = app.emit("transfer-progress", TransferProgressPayload {
                session_id: session_id.clone(),
                file_name: file_meta.relative_path.clone(),
                bytes_sent: total_sent_bytes,
                total_bytes: total_size_bytes,
                percentage,
                speed_bytes_per_sec: speed,
            });
        }
    }

    stream.flush().map_err(|e| format!("Failed to flush TCP stream: {e}"))?;

    // 4. Send TransferComplete frame
    let session_bytes = session_id.as_bytes();
    write_frame_header(&mut stream, FrameTag::TransferComplete, session_bytes.len() as u32)?;
    stream
        .write_all(session_bytes)
        .map_err(|e| format!("Failed writing TransferComplete payload: {e}"))?;
    stream.flush().map_err(|e| format!("Failed flushing TransferComplete frame: {e}"))?;
    println!("[Sender] TRANSFER_COMPLETE_SENT");

    // 5. Wait for TransferAck frame from receiver
    let (ack_tag, ack_len) = read_frame_header(&mut stream)
        .map_err(|e| format!("Failed waiting for TransferAck from receiver: {e}"))?;

    if ack_tag != FrameTag::TransferAck {
        let err_msg = format!("Expected TransferAck, received {:?}", ack_tag);
        let _ = app.emit("transfer-failed", TransferFailedPayload {
            session_id: session_id.clone(),
            error: err_msg.clone(),
        });
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

    let size_mb = (total_size_bytes as f64) / (1024.0 * 1024.0);
    let formatted_size = if size_mb >= 1.0 {
        format!("{:.1} MB", size_mb)
    } else {
        format!("{} KB", (total_size_bytes + 1023) / 1024)
    };

    let main_filename = transfer_meta
        .files
        .first()
        .map(|f| f.relative_path.as_str())
        .unwrap_or("File");

    let _ = app.emit("transfer-completed", TransferCompletedPayload {
        session_id: session_id.clone(),
        file_name: main_filename.to_string(),
        device_name: "Target Device".to_string(),
        size: formatted_size,
        timestamp: "Just now".to_string(),
    });

    println!("[Sender] Successfully completed streaming {total_sent_bytes} bytes over TCP");
    Ok(session_id)
}

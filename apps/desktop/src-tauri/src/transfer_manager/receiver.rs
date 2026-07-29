use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};

use super::protocol::{
    read_file_complete, read_file_header, read_frame_header, read_transfer_request,
    write_response_frame, FileMetadata, FrameTag,
};
use super::security::{
    get_default_receive_dir, get_part_file_path, resolve_collision_path, sanitize_relative_path,
    verify_safe_target_path,
};

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TransferProgressPayload {
    pub session_id: String,
    pub current_file_index: u32,
    pub current_file_name: String,
    pub current_file_bytes: u64,
    pub current_file_total_bytes: u64,
    pub session_bytes_sent: u64,
    pub session_total_bytes: u64,
    pub total_files: u32,
    pub percentage: f32,
    pub speed_bytes_per_sec: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TransferCompletedPayload {
    pub session_id: String,
    pub file_name: String,
    pub device_name: String,
    pub size: String,
    pub timestamp: String,
    pub total_files: u32,
    pub total_size_bytes: u64,
    pub files: Vec<FileMetadata>,
    pub receive_dir: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TransferFailedPayload {
    pub session_id: String,
    pub error: String,
}

pub struct TransferReceiver {
    port: u16,
    is_running: Arc<AtomicBool>,
}

impl TransferReceiver {
    /// Binds TCP listener on dynamic port `0.0.0.0:0` and starts background server task.
    pub fn start(app_handle: AppHandle) -> Result<Self, String> {
        let listener = std::net::TcpListener::bind("0.0.0.0:0")
            .map_err(|e| format!("Failed to bind TCP transfer receiver: {e}"))?;

        let bound_port = listener
            .local_addr()
            .map_err(|e| format!("Failed to query bound TCP port: {e}"))?
            .port();

        listener
            .set_nonblocking(false)
            .map_err(|e| format!("Failed to set blocking mode: {e}"))?;

        let is_running = Arc::new(AtomicBool::new(true));
        let running_flag = Arc::clone(&is_running);

        thread::spawn(move || {
            println!("[Receiver] TCP receiver listening on port {bound_port}");
            for stream_result in listener.incoming() {
                if !running_flag.load(Ordering::Relaxed) {
                    break;
                }

                match stream_result {
                    Ok(mut stream) => {
                        let app = app_handle.clone();
                        thread::spawn(move || {
                            if let Err(err) = handle_incoming_connection(&app, &mut stream) {
                                println!("[Receiver] Connection error: {err}");
                            }
                        });
                    }
                    Err(e) => {
                        println!("[Receiver] Accept error: {e}");
                    }
                }
            }
        });

        Ok(Self {
            port: bound_port,
            is_running,
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn stop(&self) {
        self.is_running.store(false, Ordering::Relaxed);
    }
}

fn cleanup_files(part_files: &[PathBuf]) {
    for path in part_files {
        if path.exists() {
            let _ = fs::remove_file(path);
        }
    }
}

fn handle_incoming_connection(app: &AppHandle, stream: &mut TcpStream) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .map_err(|e| format!("Failed to set socket read timeout: {e}"))?;

    // 1. Read TransferRequest metadata frame
    let metadata = read_transfer_request(stream)?;
    println!(
        "[Receiver] REQUEST_RECEIVED from '{}' ({} files, {} bytes)",
        metadata.sender_name, metadata.total_files, metadata.total_size_bytes
    );

    // 2. Accept transfer
    write_response_frame(stream, FrameTag::TransferAccept, "ACCEPTED")?;
    println!("[Receiver] ACCEPT_SENT");

    let receive_dir = get_default_receive_dir()?;
    let mut total_received_bytes: u64 = 0;
    let start_time = Instant::now();
    let mut created_part_files: Vec<PathBuf> = Vec::new();
    let mut final_dest_paths: Vec<(PathBuf, PathBuf)> = Vec::new();
    let mut last_emit_time = Instant::now();
    let emit_interval = Duration::from_millis(50); // Max 20 FPS progress updates

    let mut received_files_meta: Vec<FileMetadata> = Vec::new();

    println!("[Receiver] STREAMING {} files", metadata.total_files);
    for _file_idx in 0..metadata.total_files {
        // Read FileHeader frame
        let file_header = match read_file_header(stream) {
            Ok(h) => h,
            Err(e) => {
                cleanup_files(&created_part_files);
                return Err(format!("Failed to read FileHeader: {e}"));
            }
        };

        let safe_rel_path = match sanitize_relative_path(&file_header.relative_path) {
            Ok(p) => p,
            Err(e) => {
                cleanup_files(&created_part_files);
                return Err(e);
            }
        };

        let final_target_path = match verify_safe_target_path(&receive_dir, &safe_rel_path) {
            Ok(p) => p,
            Err(e) => {
                cleanup_files(&created_part_files);
                return Err(e);
            }
        };

        let collision_resolved_path = resolve_collision_path(&final_target_path);
        let part_file_path = get_part_file_path(&collision_resolved_path);

        if let Some(parent) = part_file_path.parent() {
            if let Err(e) = fs::create_dir_all(parent) {
                cleanup_files(&created_part_files);
                return Err(format!(
                    "Failed to create directory structure {:?}: {e}",
                    parent
                ));
            }
        }

        let mut out_file = match File::create(&part_file_path) {
            Ok(f) => {
                created_part_files.push(part_file_path.clone());
                final_dest_paths.push((part_file_path.clone(), collision_resolved_path.clone()));
                f
            }
            Err(e) => {
                cleanup_files(&created_part_files);
                return Err(format!(
                    "Failed to create part file {:?}: {e}",
                    part_file_path
                ));
            }
        };

        let mut file_hasher = Sha256::new();
        let mut file_bytes_received: u64 = 0;

        while file_bytes_received < file_header.size_bytes {
            let (tag, payload_len) = match read_frame_header(stream) {
                Ok(res) => res,
                Err(e) => {
                    cleanup_files(&created_part_files);
                    let _ = app.emit(
                        "transfer-failed",
                        TransferFailedPayload {
                            session_id: metadata.session_id.clone(),
                            error: format!("Failed to read frame header: {e}"),
                        },
                    );
                    return Err(format!("Failed to read frame header: {e}"));
                }
            };

            if tag == FrameTag::Cancel {
                cleanup_files(&created_part_files);
                let _ = app.emit(
                    "transfer-failed",
                    TransferFailedPayload {
                        session_id: metadata.session_id.clone(),
                        error: "Transfer cancelled by sender".to_string(),
                    },
                );
                return Err("Transfer cancelled by sender".to_string());
            }

            if tag != FrameTag::DataChunk {
                cleanup_files(&created_part_files);
                return Err(format!("Expected DataChunk frame tag, received {:?}", tag));
            }

            // Read payload length bytes
            let mut chunk_buf = vec![0u8; payload_len as usize];
            if let Err(e) = stream.read_exact(&mut chunk_buf) {
                cleanup_files(&created_part_files);
                let _ = app.emit(
                    "transfer-failed",
                    TransferFailedPayload {
                        session_id: metadata.session_id.clone(),
                        error: format!("Failed to read DataChunk payload: {e}"),
                    },
                );
                return Err(format!("Failed to read DataChunk payload: {e}"));
            }

            if let Err(e) = out_file.write_all(&chunk_buf) {
                cleanup_files(&created_part_files);
                return Err(format!("Failed to write chunk to disk: {e}"));
            }

            file_hasher.update(&chunk_buf);

            file_bytes_received += payload_len as u64;
            total_received_bytes += payload_len as u64;

            let is_final = total_received_bytes == metadata.total_size_bytes;
            if is_final || last_emit_time.elapsed() >= emit_interval {
                last_emit_time = Instant::now();
                let elapsed_secs = start_time.elapsed().as_secs_f64();
                let speed = if elapsed_secs > 0.0 {
                    (total_received_bytes as f64 / elapsed_secs) as u64
                } else {
                    0
                };

                let percentage = if metadata.total_size_bytes > 0 {
                    ((total_received_bytes as f32 / metadata.total_size_bytes as f32) * 100.0)
                        .min(100.0)
                } else {
                    100.0
                };

                let _ = app.emit(
                    "transfer-progress",
                    TransferProgressPayload {
                        session_id: metadata.session_id.clone(),
                        current_file_index: file_header.file_index,
                        current_file_name: file_header.relative_path.clone(),
                        current_file_bytes: file_bytes_received,
                        current_file_total_bytes: file_header.size_bytes,
                        session_bytes_sent: total_received_bytes,
                        session_total_bytes: metadata.total_size_bytes,
                        total_files: metadata.total_files,
                        percentage,
                        speed_bytes_per_sec: speed,
                    },
                );
            }
        }

        if let Err(e) = out_file.flush() {
            cleanup_files(&created_part_files);
            return Err(format!("Failed to flush file to disk: {e}"));
        }

        // Read FileComplete frame
        let file_complete = match read_file_complete(stream) {
            Ok(fc) => fc,
            Err(e) => {
                cleanup_files(&created_part_files);
                return Err(format!("Failed to read FileComplete frame: {e}"));
            }
        };

        // Verify streaming SHA-256 checksum if provided by sender
        let rx_checksum = format!("{:x}", file_hasher.finalize());
        if !file_complete.sha256_checksum.is_empty() && rx_checksum != file_complete.sha256_checksum
        {
            cleanup_files(&created_part_files);
            let err_msg = format!(
                "File checksum mismatch for file {}",
                file_header.relative_path
            );
            let _ = app.emit(
                "transfer-failed",
                TransferFailedPayload {
                    session_id: metadata.session_id.clone(),
                    error: err_msg.clone(),
                },
            );
            return Err(err_msg);
        }

        received_files_meta.push(FileMetadata {
            file_index: file_header.file_index,
            relative_path: file_header.relative_path.clone(),
            size_bytes: file_header.size_bytes,
            sha256_checksum: rx_checksum,
            final_path: collision_resolved_path.to_string_lossy().to_string(),
        });
    }

    // All files received & checksums verified cleanly!
    // Rename all .dropflow-part files to their final collision-resolved destination paths
    for (part_path, final_path) in &final_dest_paths {
        if let Err(e) = fs::rename(part_path, final_path) {
            println!(
                "[Receiver] Error renaming {:?} to {:?}: {e}",
                part_path, final_path
            );
        }
    }

    // 3. Receive TransferComplete frame
    let (comp_tag, comp_len) = match read_frame_header(stream) {
        Ok(res) => res,
        Err(e) => {
            cleanup_files(&created_part_files);
            return Err(format!("Failed to read TransferComplete frame header: {e}"));
        }
    };

    if comp_tag != FrameTag::TransferComplete {
        cleanup_files(&created_part_files);
        return Err(format!(
            "Expected TransferComplete tag, received {:?}",
            comp_tag
        ));
    }

    let mut comp_payload = vec![0u8; comp_len as usize];
    if comp_len > 0 {
        if let Err(e) = stream.read_exact(&mut comp_payload) {
            cleanup_files(&created_part_files);
            return Err(format!("Failed reading TransferComplete payload: {e}"));
        }
    }
    println!("[Receiver] FILE_COMPLETE");

    // 4. Verify total session byte count match
    if total_received_bytes != metadata.total_size_bytes {
        cleanup_files(&created_part_files);
        let err_msg = format!(
            "Byte count mismatch: expected {} bytes, received {} bytes",
            metadata.total_size_bytes, total_received_bytes
        );
        let _ = app.emit(
            "transfer-failed",
            TransferFailedPayload {
                session_id: metadata.session_id.clone(),
                error: err_msg.clone(),
            },
        );
        return Err(err_msg);
    }

    // 5. Send TransferAck response
    if let Err(e) = write_response_frame(stream, FrameTag::TransferAck, "ACK") {
        cleanup_files(&created_part_files);
        return Err(format!("Failed to send TransferAck: {e}"));
    }
    println!("[Receiver] ACK_SENT");
    println!("[Receiver] CONNECTION_CLOSED");

    // Format human-readable file size string
    let size_mb = (metadata.total_size_bytes as f64) / (1024.0 * 1024.0);
    let formatted_size = if size_mb >= 1.0 {
        format!("{:.1} MB", size_mb)
    } else {
        format!("{} KB", metadata.total_size_bytes.div_ceil(1024))
    };

    let main_filename = metadata
        .files
        .first()
        .map(|f| f.relative_path.as_str())
        .unwrap_or("Received File");

    let _ = app.emit(
        "transfer-completed",
        TransferCompletedPayload {
            session_id: metadata.session_id.clone(),
            file_name: main_filename.to_string(),
            device_name: metadata.sender_name.clone(),
            size: formatted_size,
            timestamp: "Just now".to_string(),
            total_files: metadata.total_files,
            total_size_bytes: metadata.total_size_bytes,
            files: received_files_meta,
            receive_dir: receive_dir.to_string_lossy().to_string(),
        },
    );

    println!("[Receiver] Transfer completed successfully ({total_received_bytes} bytes written)");
    Ok(())
}

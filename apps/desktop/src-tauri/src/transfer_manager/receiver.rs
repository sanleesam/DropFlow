use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter};

use super::protocol::{
    read_frame_header, read_transfer_request, write_response_frame, FrameTag,
};
use super::security::{get_default_receive_dir, sanitize_relative_path, verify_safe_target_path};

#[derive(Debug, Serialize, Clone)]
pub struct TransferProgressPayload {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    #[serde(rename = "fileName")]
    pub file_name: String,
    #[serde(rename = "bytesSent")]
    pub bytes_sent: u64,
    #[serde(rename = "totalBytes")]
    pub total_bytes: u64,
    pub percentage: f32,
    #[serde(rename = "speedBytesPerSec")]
    pub speed_bytes_per_sec: u64,
}

#[derive(Debug, Serialize, Clone)]
pub struct TransferCompletedPayload {
    #[serde(rename = "sessionId")]
    pub session_id: String,
    #[serde(rename = "fileName")]
    pub file_name: String,
    #[serde(rename = "deviceName")]
    pub device_name: String,
    pub size: String,
    pub timestamp: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct TransferFailedPayload {
    #[serde(rename = "sessionId")]
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
        let listener = TcpListener::bind("0.0.0.0:0")
            .map_err(|e| format!("Failed to bind TCP transfer receiver: {e}"))?;

        let bound_port = listener
            .local_addr()
            .map_err(|e| format!("Failed to query bound TCP port: {e}"))?
            .port();

        // 30 second read/write socket timeout
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

fn handle_incoming_connection(app: &AppHandle, stream: &mut TcpStream) -> Result<(), String> {
    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .map_err(|e| format!("Failed to set socket read timeout: {e}"))?;

    // 1. Read TransferRequest metadata frame
    let metadata = read_transfer_request(stream)?;
    println!(
        "[Receiver] Incoming transfer request from '{}' ({} bytes, {} files)",
        metadata.sender_name, metadata.total_size_bytes, metadata.total_files
    );

    // 2. Accept transfer
    write_response_frame(stream, FrameTag::TransferAccept, "ACCEPTED")?;

    let receive_dir = get_default_receive_dir()?;
    let mut total_received_bytes: u64 = 0;
    let start_time = Instant::now();

    // 3. Receive file headers and data chunks
    for file_meta in &metadata.files {
        let safe_rel_path = sanitize_relative_path(&file_meta.relative_path)?;
        let target_file_path = verify_safe_target_path(&receive_dir, &safe_rel_path)?;

        if let Some(parent) = target_file_path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create directory structure {:?}: {e}", parent))?;
        }

        let mut out_file = File::create(&target_file_path)
            .map_err(|e| format!("Failed to create destination file {:?}: {e}", target_file_path))?;

        let mut file_bytes_received: u64 = 0;

        while file_bytes_received < file_meta.size_bytes {
            let (tag, payload_len) = read_frame_header(stream)?;

            if tag == FrameTag::Cancel {
                let _ = app.emit("transfer-failed", TransferFailedPayload {
                    session_id: metadata.session_id.clone(),
                    error: "Transfer cancelled by sender".to_string(),
                });
                return Err("Transfer cancelled by sender".to_string());
            }

            if tag != FrameTag::DataChunk {
                return Err(format!("Expected DataChunk frame tag, received {:?}", tag));
            }

            // Read payload length bytes
            let mut chunk_buf = vec![0u8; payload_len as usize];
            stream
                .read_exact(&mut chunk_buf)
                .map_err(|e| format!("Failed to read DataChunk payload: {e}"))?;

            out_file
                .write_all(&chunk_buf)
                .map_err(|e| format!("Failed to write chunk to disk: {e}"))?;

            file_bytes_received += payload_len as u64;
            total_received_bytes += payload_len as u64;

            let elapsed_secs = start_time.elapsed().as_secs_f64();
            let speed = if elapsed_secs > 0.0 {
                (total_received_bytes as f64 / elapsed_secs) as u64
            } else {
                0
            };

            let percentage = if metadata.total_size_bytes > 0 {
                ((total_received_bytes as f32 / metadata.total_size_bytes as f32) * 100.0).min(100.0)
            } else {
                100.0
            };

            let _ = app.emit("transfer-progress", TransferProgressPayload {
                session_id: metadata.session_id.clone(),
                file_name: file_meta.relative_path.clone(),
                bytes_sent: total_received_bytes,
                total_bytes: metadata.total_size_bytes,
                percentage,
                speed_bytes_per_sec: speed,
            });
        }
        out_file.flush().map_err(|e| format!("Failed to flush file to disk: {e}"))?;
    }

    // Format human-readable file size string
    let size_mb = (metadata.total_size_bytes as f64) / (1024.0 * 1024.0);
    let formatted_size = if size_mb >= 1.0 {
        format!("{:.1} MB", size_mb)
    } else {
        format!("{} KB", (metadata.total_size_bytes + 1023) / 1024)
    };

    let main_filename = metadata
        .files
        .first()
        .map(|f| f.relative_path.as_str())
        .unwrap_or("Received File");

    let _ = app.emit("transfer-completed", TransferCompletedPayload {
        session_id: metadata.session_id.clone(),
        file_name: main_filename.to_string(),
        device_name: metadata.sender_name.clone(),
        size: formatted_size,
        timestamp: "Just now".to_string(),
    });

    println!("[Receiver] Transfer completed successfully ({total_received_bytes} bytes written)");
    Ok(())
}

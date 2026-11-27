//! Incoming transfers for the app: listens, asks the person, runs the session
//! core, and reports to the window. The protocol itself lives in `session`.
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use log::{error, info};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager};

use super::protocol::{
    perform_incoming_handshake, read_transfer_request, write_response_frame, FileMetadata,
    FrameTag, TransferMetadata,
};
use super::session::{Progress, ReceiveSetup, SessionEvent};

/// File lists sent to the window and kept in history stop here. A transfer
/// can hold many thousands of files; the counts and totals stay exact.
pub const UI_FILE_LIMIT: usize = 200;

pub fn format_size(bytes: u64) -> String {
    let megabytes = bytes as f64 / (1024.0 * 1024.0);
    if megabytes >= 1.0 {
        format!("{megabytes:.1} MB")
    } else {
        format!("{} KB", bytes.div_ceil(1024))
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct IncomingTransferRequestPayload {
    pub session_id: String,
    pub sender_id: String,
    pub sender_name: String,
    pub sender_platform: String,
    pub total_files: u32,
    pub total_size_bytes: u64,
    pub files: Vec<FileMetadata>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct TransferProgressPayload {
    pub session_id: String,
    pub direction: String,
    pub device_name: String,
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

impl TransferProgressPayload {
    pub fn from_progress(
        session_id: &str,
        direction: &str,
        device_name: &str,
        total_files: u32,
        progress: &Progress<'_>,
    ) -> Self {
        let percentage = if progress.session_total > 0 {
            ((progress.session_bytes as f64 / progress.session_total as f64) * 100.0).min(100.0) as f32
        } else {
            100.0
        };
        Self {
            session_id: session_id.to_string(),
            direction: direction.to_string(),
            device_name: device_name.to_string(),
            current_file_index: progress.file_index,
            current_file_name: progress.file_name.to_string(),
            current_file_bytes: progress.file_bytes,
            current_file_total_bytes: progress.file_total,
            session_bytes_sent: progress.session_bytes,
            session_total_bytes: progress.session_total,
            total_files,
            percentage,
            speed_bytes_per_sec: progress.speed_bytes_per_sec,
        }
    }
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
        let connection_count = Arc::new(AtomicUsize::new(0));

        thread::spawn(move || {
            info!("[Receiver] TCP receiver listening on port {bound_port}");
            // Clear staged data that is too old to resume.
            if let (Ok(dir), Some(state)) = (
                super::engine::configured_receive_dir(&app_handle),
                app_handle.try_state::<crate::transfer_manager::TransferState>(),
            ) {
                super::session::sweep_staging(&dir, &state.staging, super::session::STAGING_MAX_AGE);
            }
            for stream_result in listener.incoming() {
                if !running_flag.load(Ordering::Relaxed) {
                    break;
                }

                match stream_result {
                    Ok(mut stream) => {
                        let Some(permit) = super::lifecycle::ConnectionPermit::acquire(&connection_count) else { continue; };
                        let app = app_handle.clone();
                        thread::spawn(move || {
                            let _permit = permit;
                            if let Err(err) = handle_incoming_connection(&app, &mut stream) {
                                error!("[Receiver] Connection error: {err}");
                            }
                        });
                    }
                    Err(e) => {
                        error!("[Receiver] Accept error: {e}");
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

struct CancellationGuard<'a> {
    app: &'a AppHandle,
    session_id: String,
}

impl<'a> Drop for CancellationGuard<'a> {
    fn drop(&mut self) {
        if let Some(state) = self
            .app
            .try_state::<crate::transfer_manager::TransferState>()
        {
            if let Ok(mut cancellations) = state.active_cancellations.lock() {
                cancellations.remove(&self.session_id);
            }
        }
    }
}

fn handle_incoming_connection(app: &AppHandle, stream: &mut TcpStream) -> Result<(), String> {
    let _sleep_guard = app
        .try_state::<crate::power_manager::SleepState>()
        .map(|s| s.sleep_manager.acquire());
    let mut stream = super::transport::CancellableStream::new(stream,Arc::new(AtomicBool::new(false)))
        .map_err(|e|e.to_string())?;

    stream
        .set_read_timeout(Some(Duration::from_secs(15)))
        .map_err(|e| format!("Failed to set socket read timeout: {e}"))?;
    stream.set_write_timeout(Some(Duration::from_secs(15))).map_err(|e|e.to_string())?;

    perform_incoming_handshake(&mut stream)?;
    info!("[Receiver] HANDSHAKE_COMPLETE");

    // 1. Read TransferRequest metadata frame
    let metadata = read_transfer_request(&mut stream)?;
    super::validation::validate_request(&metadata)?;
    info!("[Receiver] REQUEST_RECEIVED from '{}' ({} files, {} bytes)",
        metadata.sender_name, metadata.total_files, metadata.total_size_bytes
    );

    let container = app.try_state::<crate::state_manager::AppStateContainer>().ok_or("Receiver state is not ready")?;
    let limit = container.state.lock()
        .map_err(|e|e.to_string())?.settings.max_concurrent_transfers as usize;
    let state = app.try_state::<crate::transfer_manager::TransferState>().ok_or("Receiver state is not ready")?;
    let claim = {
        let mut active = state.active_cancellations.lock().map_err(|e|e.to_string())?;
        super::lifecycle::register_session(&mut active, &metadata.session_id, limit)
    };
    let cancel_flag = match claim { Ok(flag)=>flag, Err(error)=> { write_response_frame(&mut stream,FrameTag::TransferReject,&error)?; return Err(error); } };
    let _guard = CancellationGuard {
        app,
        session_id: metadata.session_id.clone(),
    };
    stream.set_cancellation(cancel_flag.clone());

    let result=receive_session(app,&mut stream,&metadata,cancel_flag.clone())
        .map_err(|error| if cancel_flag.load(Ordering::Relaxed) { "Transfer cancelled".into() } else { error });
    super::lifecycle::report_result(result, |error| {
        let _ = app.emit("transfer-failed", TransferFailedPayload { session_id: metadata.session_id.clone(), error: error.into() });
        crate::state_manager::remove_incomplete_session(app,&metadata.session_id);
    })
}

fn receive_session(
    app: &AppHandle,
    stream: &mut super::transport::CancellableStream<'_>,
    metadata: &TransferMetadata,
    cancel_flag: Arc<AtomicBool>,
) -> Result<(), String> {
    // 2. Check authorization settings & trusted devices
    let require_confirmation = app
        .try_state::<crate::state_manager::AppStateContainer>()
        .and_then(|container| container.state.lock().ok().map(|state| state.settings.require_confirmation))
        .unwrap_or(true);

    if super::lifecycle::needs_approval(require_confirmation, false, false) {
        let (tx, rx) = tokio::sync::oneshot::channel::<super::engine::AuthorizationResponse>();

        {
            let state = app.state::<crate::transfer_manager::TransferState>();
            let mut pending = state.pending_authorizations.lock().map_err(|e| e.to_string())?;
            if !pending.is_empty() {
                write_response_frame(stream, FrameTag::TransferReject, "BUSY: Another incoming request is awaiting approval")?;
                return Err("Busy: another incoming request is awaiting approval".into());
            }
            pending.insert(metadata.session_id.clone(), tx);
        }

        let _ = app.emit(
            "incoming-transfer-request",
            IncomingTransferRequestPayload {
                session_id: metadata.session_id.clone(),
                sender_id: metadata.sender_id.clone(),
                sender_name: metadata.sender_name.clone(),
                sender_platform: metadata.sender_platform.clone(),
                total_files: metadata.total_files,
                total_size_bytes: metadata.total_size_bytes,
                files: metadata.files.iter().take(UI_FILE_LIMIT).cloned().collect(),
            },
        );

        let auth_response = tauri::async_runtime::block_on(async {
            match tokio::time::timeout(Duration::from_secs(60), rx).await {
                Ok(Ok(resp)) => resp,
                _ => super::engine::AuthorizationResponse { accept: false, trust_device: false },
            }
        });

        if let Some(state) = app.try_state::<crate::transfer_manager::TransferState>() {
            if let Ok(mut pending) = state.pending_authorizations.lock() {
                pending.remove(&metadata.session_id);
            }
        }

        let _ = app.emit("incoming-transfer-dismiss", &metadata.session_id);

        if !auth_response.accept {
            info!("[Receiver] REJECTED transfer request for session {}", metadata.session_id);
            write_response_frame(stream, FrameTag::TransferReject, "REJECTED")?;
            return Err("Transfer declined by recipient".to_string());
        }

        if auth_response.trust_device {
            if let Some(container) = app.try_state::<crate::state_manager::AppStateContainer>() {
                let platform_name = if metadata.sender_platform.is_empty() {
                    "Desktop".to_string()
                } else {
                    metadata.sender_platform.clone()
                };
                let trusted_entry = crate::state_manager::TrustedDeviceSchema {
                    device_id: metadata.sender_id.clone(),
                    device_name: metadata.sender_name.clone(),
                    first_seen: "Just now".to_string(),
                    last_seen: "Just now".to_string(),
                    public_key: None,
                    platform: platform_name,
                };
                let _ = crate::state_manager::add_trusted_device(app.clone(), container, trusted_entry);
            }
        }
    }

    // 3. Accept and receive. The window shows the transfer straight away.
    let first = &metadata.files[0];
    let _ = app.emit(
        "transfer-progress",
        TransferProgressPayload {
            session_id: metadata.session_id.clone(),
            direction: "receive".into(),
            device_name: metadata.sender_name.clone(),
            current_file_index: 0,
            current_file_name: first.relative_path.clone(),
            current_file_bytes: 0,
            current_file_total_bytes: first.size_bytes,
            session_bytes_sent: 0,
            session_total_bytes: metadata.total_size_bytes,
            total_files: metadata.total_files,
            percentage: 0.0,
            speed_bytes_per_sec: 0,
        },
    );

    let receive_dir = super::engine::configured_receive_dir(app)?;
    crate::state_manager::update_incomplete_session(
        app,
        crate::state_manager::IncompleteTransferSchema {
            session_id: metadata.session_id.clone(),
            file_name: first.relative_path.clone(),
            device_name: metadata.sender_name.clone(),
            total_files: metadata.total_files,
            total_size_bytes: metadata.total_size_bytes,
            bytes_completed: 0,
            direction: "receive".to_string(),
            receive_dir: Some(receive_dir.to_string_lossy().to_string()),
            file_paths: None,
            files: None,
            timestamp_ms: Some(
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_millis() as u64,
            ),
        },
    );

    let state = app.state::<crate::transfer_manager::TransferState>();
    let setup = ReceiveSetup {
        receive_dir: &receive_dir,
        allow_features: super::engine::fast_transfer_enabled(app),
        staging: &state.staging,
    };
    let mut on_event = |event: SessionEvent<'_>| match event {
        SessionEvent::RequestSent => {}
        SessionEvent::Accepted(mode) => info!(
            "[Receiver] ACCEPT_SENT batch={} resume={}; streaming {} files",
            mode.batch, mode.resume, metadata.total_files
        ),
        SessionEvent::Finishing => info!("[Receiver] FILE_COMPLETE"),
        SessionEvent::Progress(progress) => {
            let _ = app.emit(
                "transfer-progress",
                TransferProgressPayload::from_progress(
                    &metadata.session_id,
                    "receive",
                    &metadata.sender_name,
                    metadata.total_files,
                    &progress,
                ),
            );
        }
    };
    let summary = super::session::receive_session(stream, metadata, &setup, &cancel_flag, &mut on_event)
        .map_err(|failure| {
            if failure.resumable {
                info!("[Receiver] Interrupted; kept the received part for a retry");
            }
            failure.message
        })?;
    info!("[Receiver] ACK_SENT");
    info!(
        "{} batch={} resumed_bytes={}",
        super::perf::summary("receive", metadata.files.len(), summary.bytes_received, summary.elapsed),
        summary.negotiated.batch,
        summary.bytes_resumed
    );
    info!("[Receiver] CONNECTION_CLOSED");

    crate::state_manager::remove_incomplete_session(app, &metadata.session_id);

    let _ = app.emit(
        "transfer-completed",
        TransferCompletedPayload {
            session_id: metadata.session_id.clone(),
            file_name: first.relative_path.clone(),
            device_name: metadata.sender_name.clone(),
            size: format_size(metadata.total_size_bytes),
            timestamp: "Just now".to_string(),
            total_files: metadata.total_files,
            total_size_bytes: metadata.total_size_bytes,
            files: summary.files.into_iter().take(UI_FILE_LIMIT).collect(),
            receive_dir: receive_dir.to_string_lossy().to_string(),
        },
    );

    info!("[Receiver] Transfer completed successfully ({} bytes written)", summary.bytes_received);
    Ok(())
}

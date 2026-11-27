//! Outgoing transfers for the app: connects, runs the session core, and
//! reports to the window. The protocol itself lives in `session`.
use std::collections::HashSet;
use std::net::TcpStream;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::Duration;

use log::info;
use tauri::{AppHandle, Emitter, Manager};
use uuid::Uuid;

use super::protocol::FileMetadata;
use super::receiver::{
    emit_phase, format_size, TransferCompletedPayload, TransferProgressPayload, UI_FILE_LIMIT,
};
use super::session::{send_session, SendFile, SendJob, SessionEvent};

/// Two selected files can share a name ("a.txt" from two folders). The
/// receiver rejects duplicate paths, so later ones become "a (2).txt".
fn unique_relative_paths(files: &mut [SendFile]) {
    let mut seen: HashSet<String> = HashSet::new();
    for file in files.iter_mut() {
        if seen.insert(file.relative_path.to_lowercase()) {
            continue;
        }
        let (folder, name) = match file.relative_path.rfind('/') {
            Some(slash) => file.relative_path.split_at(slash + 1),
            None => ("", file.relative_path.as_str()),
        };
        let (stem, extension) = match name.rfind('.') {
            Some(dot) if dot > 0 => name.split_at(dot),
            _ => (name, ""),
        };
        let renamed = (2u32..)
            .map(|number| format!("{folder}{stem} ({number}){extension}"))
            .find(|candidate| seen.insert(candidate.to_lowercase()))
            .unwrap_or_default();
        file.relative_path = renamed;
    }
}

pub fn build_job(
    session_id: String,
    sender_id: &str,
    sender_name: &str,
    sender_platform: String,
    file_paths: &[String],
    offer_features: bool,
) -> Result<SendJob, String> {
    let mut files = Vec::new();
    let mut total: u64 = 0;
    for (path, relative_path) in super::selection::expand_selection(file_paths)? {
        let size_bytes = std::fs::metadata(&path)
            .map_err(|e| format!("Failed to read metadata for {}: {e}", path.display()))?
            .len();
        total = total
            .checked_add(size_bytes)
            .ok_or_else(|| "Selected files are too large".to_string())?;
        files.push(SendFile { path, relative_path, size_bytes });
    }
    unique_relative_paths(&mut files);
    Ok(SendJob {
        session_id,
        sender_id: sender_id.to_string(),
        sender_name: sender_name.to_string(),
        sender_platform,
        files,
        offer_features,
    })
}

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
    let job = build_job(
        session_id.clone(),
        local_uuid,
        local_device_name,
        crate::device_discovery::get_system_platform(),
        &file_paths,
        super::engine::fast_transfer_enabled(app),
    )?;
    let total_files = job.files.len() as u32;
    let total_size_bytes = job.total_bytes();

    let target = std::net::SocketAddr::new(
        peer_address
            .parse()
            .map_err(|e| format!("Invalid peer IP address {peer_address}: {e}"))?,
        peer_port,
    );
    let mut socket = TcpStream::connect_timeout(&target, Duration::from_secs(10))
        .map_err(|e| format!("Failed to connect to peer at {target}: {e}"))?;
    info!("[Sender] CONNECTED to {target}");
    let mut stream = super::transport::CancellableStream::new(&mut socket, cancel_flag.clone())
        .map_err(|e| format!("Failed to configure cancellable socket: {e}"))?;

    crate::state_manager::update_incomplete_session(
        app,
        crate::state_manager::IncompleteTransferSchema {
            session_id: session_id.clone(),
            file_name: job.files.first().map(|f| f.relative_path.clone()).unwrap_or_default(),
            device_name: peer_address.to_string(),
            total_files,
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

    let mut resume_granted = false;
    let mut on_event = |event: SessionEvent<'_>| match event {
        SessionEvent::RequestSent => {
            info!("[Sender] REQUEST_SENT");
            emit_phase(app, &session_id, "waiting");
        }
        SessionEvent::Accepted(mode) => {
            info!(
                "[Sender] ACCEPT_RECEIVED batch={} resume={}; streaming {total_files} files",
                mode.batch, mode.resume
            );
            resume_granted = mode.resume;
            emit_phase(app, &session_id, "transferring");
        }
        SessionEvent::Finishing => {
            info!("[Sender] TRANSFER_COMPLETE_SENT");
            emit_phase(app, &session_id, "finishing");
        }
        SessionEvent::Progress(progress) => {
            let _ = app.emit(
                "transfer-progress",
                TransferProgressPayload::from_progress(&session_id, "send", peer_address, total_files, &progress),
            );
        }
    };
    // The command's result is the single failure report; no failure event here.
    let summary = send_session(&mut stream, &job, &cancel_flag, &mut on_event).map_err(|error| {
        // The receiver kept what arrived; sending the same files again continues.
        if resume_granted && error.starts_with("Connection lost") {
            format!("{error} [resumable]")
        } else {
            error
        }
    })?;
    info!("[Sender] ACK_RECEIVED");
    info!(
        "{} batch={} resumed_bytes={}",
        super::perf::summary("send", total_files as usize, summary.bytes_sent, summary.elapsed),
        summary.negotiated.batch,
        summary.bytes_resumed
    );

    crate::state_manager::remove_incomplete_session(app, &session_id);

    let files: Vec<FileMetadata> = job
        .files
        .iter()
        .take(UI_FILE_LIMIT)
        .enumerate()
        .map(|(index, file)| FileMetadata {
            file_index: index as u32,
            relative_path: file.relative_path.clone(),
            size_bytes: file.size_bytes,
            sha256_checksum: String::new(),
            final_path: file.path.to_string_lossy().into_owned(),
        })
        .collect();
    let _ = app.emit(
        "transfer-completed",
        TransferCompletedPayload {
            session_id: session_id.clone(),
            file_name: job.files.first().map(|f| f.relative_path.clone()).unwrap_or_else(|| "File".into()),
            device_name: "Target Device".to_string(),
            size: format_size(total_size_bytes),
            timestamp: "Just now".to_string(),
            total_files,
            total_size_bytes,
            files,
            receive_dir: String::new(),
        },
    );

    info!("[Sender] Completed: {total_size_bytes} bytes in {total_files} files");
    Ok(session_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_named_files_from_different_folders_get_distinct_names() {
        let file = |name: &str| SendFile { path: name.into(), relative_path: name.into(), size_bytes: 1 };
        let mut files = vec![
            file("a.txt"),
            file("A.TXT"),
            file("a.txt"),
            file("Folder/a.txt"),
            file("Folder/a.txt"),
            file("notes"),
            file("notes"),
            file(".env"),
            file(".env"),
        ];
        unique_relative_paths(&mut files);
        let names: Vec<&str> = files.iter().map(|f| f.relative_path.as_str()).collect();
        assert_eq!(
            names,
            ["a.txt", "A (2).TXT", "a (3).txt", "Folder/a.txt", "Folder/a (2).txt", "notes", "notes (2)", ".env", ".env (2)"]
        );
    }
}

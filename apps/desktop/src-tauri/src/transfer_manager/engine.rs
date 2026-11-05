use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use log::info;
use tauri::{AppHandle, State};

use super::receiver::TransferReceiver;
use super::security::get_default_receive_dir;
use super::sender::send_files_over_tcp;
use tauri::Manager;
use tauri_plugin_opener::OpenerExt;

pub(crate) fn configured_receive_dir(app: &AppHandle) -> Result<std::path::PathBuf, String> {
    let selected = app.try_state::<crate::state_manager::AppStateContainer>()
        .and_then(|container| container.state.lock().ok().map(|state| state.settings.receive_directory.clone()));
    let Some(selected) = selected.filter(|path| !path.trim().is_empty()) else {
        return get_default_receive_dir();
    };
    let path = std::path::PathBuf::from(selected);
    if !path.is_absolute() { return Err("Receive directory must be an absolute path".into()); }
    std::fs::create_dir_all(&path).map_err(|e| format!("Cannot create receive directory: {e}"))?;
    path.canonicalize().map_err(|e| format!("Cannot access receive directory: {e}"))
}

pub struct AuthorizationResponse {
    pub accept: bool,
    pub trust_device: bool,
}

pub struct TransferState {
    pub receiver: Mutex<Option<TransferReceiver>>,
    pub active_cancellations: Mutex<HashMap<String, Arc<AtomicBool>>>,
    pub pending_authorizations:
        Mutex<HashMap<String, tokio::sync::oneshot::Sender<AuthorizationResponse>>>,
}

impl Default for TransferState {
    fn default() -> Self {
        Self {
            receiver: Mutex::new(None),
            active_cancellations: Mutex::new(HashMap::new()),
            pending_authorizations: Mutex::new(HashMap::new()),
        }
    }
}

#[tauri::command]
pub fn respond_transfer_request(
    state: State<'_, TransferState>,
    session_id: String,
    accept: bool,
    trust_device: bool,
) -> Result<(), String> {
    let mut pending = state
        .pending_authorizations
        .lock()
        .map_err(|e| e.to_string())?;
    if let Some(sender) = pending.remove(&session_id) {
        let _ = sender.send(AuthorizationResponse {
            accept,
            trust_device,
        });
        Ok(())
    } else {
        Err(format!("No pending transfer authorization request for session {session_id}"))
    }
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn send_files(
    app: AppHandle,
    state: State<'_, TransferState>,
    session_id: Option<String>,
    peer_address: String,
    peer_port: u16,
    local_uuid: String,
    local_device_name: String,
    file_paths: Vec<String>,
) -> Result<String, String> {
    let target_session_id = session_id.unwrap_or_else(|| format!("tx-{}", uuid::Uuid::new_v4()));
    let limit = app.state::<crate::state_manager::AppStateContainer>().state.lock()
        .map_err(|e|e.to_string())?.settings.max_concurrent_transfers as usize;
    let cancel_flag = {
        let mut cancellations = state
            .active_cancellations
            .lock()
            .map_err(|e| e.to_string())?;
        super::lifecycle::register_session(&mut cancellations, &target_session_id, limit)?
    };

    let cancel_flag_clone = Arc::clone(&cancel_flag);
    let session_id_param = target_session_id.clone();
    let transfer_app = app.clone();

    let result = tauri::async_runtime::spawn_blocking(move || {
        send_files_over_tcp(
            &transfer_app,
            &peer_address,
            peer_port,
            &local_uuid,
            &local_device_name,
            file_paths,
            cancel_flag_clone,
            Some(session_id_param),
        )
    })
    .await;

    {
        let mut cancellations = state
            .active_cancellations
            .lock()
            .map_err(|e| e.to_string())?;
        cancellations.remove(&target_session_id);
    }

    let result=result.map_err(|e| format!("Transfer task failed to join: {e}"))?;
    if result.is_err() {
        crate::state_manager::remove_incomplete_session(&app, &target_session_id);
        if cancel_flag.load(Ordering::Relaxed) { return Err("Transfer cancelled by user".into()); }
    }
    result
}

#[tauri::command]
pub fn cancel_transfer(state: State<'_, TransferState>, session_id: String) -> Result<(), String> {
    let cancellations = state
        .active_cancellations
        .lock()
        .map_err(|e| e.to_string())?;
    super::lifecycle::cancel_session(&cancellations, &session_id);
    info!("[Engine] Cancel requested for session {session_id}");
    Ok(())
}

#[tauri::command]
pub fn get_receive_dir(app: AppHandle) -> Result<String, String> {
    let dir = configured_receive_dir(&app)?;
    Ok(dir.to_string_lossy().to_string())
}

#[tauri::command]
pub fn get_receiver_port(state: State<'_, TransferState>) -> Result<u16, String> {
    let receiver = state.receiver.lock().map_err(|e| e.to_string())?;
    receiver
        .as_ref()
        .map(|r| r.port())
        .ok_or_else(|| "TCP receiver not initialized".to_string())
}

#[tauri::command]
pub fn open_received_file(app: AppHandle, path: String) -> Result<(), String> {
    let target = std::path::Path::new(&path);
    if !target.exists() {
        return Err(format!("File does not exist: {path}"));
    }

    let receive_dir = configured_receive_dir(&app)?.canonicalize().map_err(|e| e.to_string())?;
    let canonical = target.canonicalize().map_err(|e| e.to_string())?;
    if !canonical.starts_with(&receive_dir) {
        return Err(format!(
            "Security alert: path {:?} is outside receive directory {:?}",
            target, receive_dir
        ));
    }

    app.opener().open_path(canonical.to_string_lossy().into_owned(), None::<&str>)
        .map_err(|e| format!("Failed to open received item: {e}"))
}

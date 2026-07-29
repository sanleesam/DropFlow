use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, State};

use super::receiver::TransferReceiver;
use super::security::get_default_receive_dir;
use super::sender::send_files_over_tcp;

pub struct TransferState {
    pub receiver: Mutex<Option<TransferReceiver>>,
    pub active_cancellations: Mutex<HashMap<String, Arc<AtomicBool>>>,
}

impl Default for TransferState {
    fn default() -> Self {
        Self {
            receiver: Mutex::new(None),
            active_cancellations: Mutex::new(HashMap::new()),
        }
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
    let cancel_flag = Arc::new(AtomicBool::new(false));

    let target_session_id = session_id.unwrap_or_else(|| format!("tx-{}", uuid::Uuid::new_v4()));
    {
        let mut cancellations = state
            .active_cancellations
            .lock()
            .map_err(|e| e.to_string())?;
        cancellations.insert(target_session_id.clone(), Arc::clone(&cancel_flag));
    }

    let cancel_flag_clone = Arc::clone(&cancel_flag);
    let session_id_param = target_session_id.clone();

    let result = tauri::async_runtime::spawn_blocking(move || {
        send_files_over_tcp(
            &app,
            &peer_address,
            peer_port,
            &local_uuid,
            &local_device_name,
            file_paths,
            cancel_flag_clone,
            Some(session_id_param),
        )
    })
    .await
    .map_err(|e| format!("Transfer task failed to join: {e}"))?;

    {
        let mut cancellations = state
            .active_cancellations
            .lock()
            .map_err(|e| e.to_string())?;
        cancellations.remove(&target_session_id);
    }

    result
}

#[tauri::command]
pub fn cancel_transfer(state: State<'_, TransferState>, session_id: String) -> Result<(), String> {
    let cancellations = state
        .active_cancellations
        .lock()
        .map_err(|e| e.to_string())?;
    if let Some(flag) = cancellations.get(&session_id) {
        flag.store(true, Ordering::Relaxed);
        println!("[Engine] Cancel requested for session {session_id}");
        Ok(())
    } else {
        // If exact session_id is not mapped, set all active cancellations to true
        for flag in cancellations.values() {
            flag.store(true, Ordering::Relaxed);
        }
        Ok(())
    }
}

#[tauri::command]
pub fn get_receive_dir() -> Result<String, String> {
    let dir = get_default_receive_dir()?;
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

use std::sync::Mutex;
use tauri::AppHandle;

use super::receiver::TransferReceiver;
use super::security::get_default_receive_dir;
use super::sender::send_files_over_tcp;

pub struct TransferState {
    pub receiver: Mutex<Option<TransferReceiver>>,
}

#[tauri::command]
pub fn send_files(
    app: AppHandle,
    peer_address: String,
    peer_port: u16,
    local_uuid: String,
    local_device_name: String,
    file_paths: Vec<String>,
) -> Result<String, String> {
    send_files_over_tcp(
        &app,
        &peer_address,
        peer_port,
        &local_uuid,
        &local_device_name,
        file_paths,
    )
}

#[tauri::command]
pub fn get_receive_dir() -> Result<String, String> {
    let dir = get_default_receive_dir()?;
    Ok(dir.to_string_lossy().to_string())
}

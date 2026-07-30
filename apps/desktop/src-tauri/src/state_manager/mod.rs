use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};
use uuid::Uuid;

pub const CURRENT_SCHEMA_VERSION: u32 = 1;
pub const MAX_PERSISTED_HISTORY_CAPACITY: usize = 500;

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FileMetadataSchema {
    pub file_index: u32,
    pub relative_path: String,
    pub size_bytes: u64,
    #[serde(default)]
    pub sha256_checksum: Option<String>,
    #[serde(default)]
    pub final_path: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RecentTransferSchema {
    pub id: String,
    pub file_name: String,
    pub device_name: String,
    pub size: String,
    pub timestamp: String,
    #[serde(default)]
    pub timestamp_ms: Option<u64>,
    pub status: String,
    pub direction: String,
    pub total_files: u32,
    #[serde(default)]
    pub total_size_bytes: Option<u64>,
    #[serde(default)]
    pub files: Option<Vec<FileMetadataSchema>>,
    #[serde(default)]
    pub receive_dir: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct UserSettings {
    pub device_name: String,
    pub receive_directory: String,
    pub auto_accept: bool,
    pub sound_notifications: bool,
    pub theme: String,
    pub accent_color: String,
    pub max_concurrent_transfers: u32,
}

impl Default for UserSettings {
    fn default() -> Self {
        let default_dir = crate::transfer_manager::security::get_default_receive_dir()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_else(|_| "".to_string());

        Self {
            device_name: "Desktop-Device".to_string(),
            receive_directory: default_dir,
            auto_accept: false,
            sound_notifications: true,
            theme: "dark".to_string(),
            accent_color: "blue".to_string(),
            max_concurrent_transfers: 3,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct IncompleteTransferSchema {
    pub session_id: String,
    pub file_name: String,
    pub device_name: String,
    pub total_files: u32,
    pub total_size_bytes: u64,
    pub bytes_completed: u64,
    pub direction: String,
    #[serde(default)]
    pub receive_dir: Option<String>,
    #[serde(default)]
    pub file_paths: Option<Vec<String>>,
    #[serde(default)]
    pub files: Option<Vec<FileMetadataSchema>>,
    #[serde(default)]
    pub timestamp_ms: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppStateSchema {
    pub version: u32,
    pub device_uuid: String,
    pub settings: UserSettings,
    pub history: Vec<RecentTransferSchema>,
    #[serde(default)]
    pub incomplete_transfers: Vec<IncompleteTransferSchema>,
}

pub struct AppStateContainer {
    pub state: Mutex<AppStateSchema>,
    pub state_file_path: PathBuf,
}

fn get_state_file_path(app: &AppHandle) -> PathBuf {
    let app_data = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    fs::create_dir_all(&app_data).ok();
    app_data.join("state.json")
}

pub fn load_or_create_state_at_path(path: &PathBuf) -> AppStateSchema {
    if path.exists() {
        match fs::read_to_string(path) {
            Ok(contents) => {
                match serde_json::from_str::<AppStateSchema>(&contents) {
                    Ok(mut parsed) => {
                        println!(
                            "[StateManager] Loaded valid state version {}",
                            parsed.version
                        );
                        // Clean up device_uuid if invalid
                        if Uuid::parse_str(&parsed.device_uuid).is_err() {
                            parsed.device_uuid = Uuid::new_v4().to_string();
                        }
                        // Cap history to max 500
                        if parsed.history.len() > MAX_PERSISTED_HISTORY_CAPACITY {
                            parsed.history.truncate(MAX_PERSISTED_HISTORY_CAPACITY);
                        }
                        return parsed;
                    }
                    Err(e) => {
                        eprintln!("[StateManager] Corrupted state file {:?}: {e}. Creating backup state.json.bak", path);
                        let bak_path = path.with_extension("json.bak");
                        let _ = fs::copy(path, &bak_path);
                    }
                }
            }
            Err(e) => {
                eprintln!("[StateManager] Failed to read state file {:?}: {e}", path);
            }
        }
    }

    // Default state creation
    let default_state = AppStateSchema {
        version: CURRENT_SCHEMA_VERSION,
        device_uuid: Uuid::new_v4().to_string(),
        settings: UserSettings::default(),
        history: Vec::new(),
        incomplete_transfers: Vec::new(),
    };

    save_state_atomic_at_path(path, &default_state).ok();
    default_state
}

pub fn save_state_atomic_at_path(path: &PathBuf, state: &AppStateSchema) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Failed creating directory {:?}: {e}", parent))?;
    }

    let tmp_path = path.with_extension("json.tmp");
    let json_bytes =
        serde_json::to_vec_pretty(state).map_err(|e| format!("Serialization error: {e}"))?;

    fs::write(&tmp_path, json_bytes)
        .map_err(|e| format!("Failed to write tmp state {:?}: {e}", tmp_path))?;
    fs::rename(&tmp_path, path).map_err(|e| {
        format!(
            "Atomic rename failed from {:?} to {:?}: {e}",
            tmp_path, path
        )
    })?;

    Ok(())
}

pub fn load_or_create_state(app: &AppHandle) -> AppStateSchema {
    let path = get_state_file_path(app);
    load_or_create_state_at_path(&path)
}

pub fn save_state_atomic(app: &AppHandle, state: &AppStateSchema) -> Result<(), String> {
    let path = get_state_file_path(app);
    save_state_atomic_at_path(&path, state)
}

// ─── Tauri Commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn get_app_state(container: State<'_, AppStateContainer>) -> Result<AppStateSchema, String> {
    let state = container.state.lock().map_err(|e| e.to_string())?;
    Ok(state.clone())
}

#[tauri::command]
pub fn save_settings(
    app: AppHandle,
    container: State<'_, AppStateContainer>,
    settings: UserSettings,
) -> Result<(), String> {
    let mut state = container.state.lock().map_err(|e| e.to_string())?;
    let old_name = state.settings.device_name.clone();
    state.settings = settings.clone();

    save_state_atomic(&app, &state)?;

    // If device name changed, notify discovery engine
    if old_name != settings.device_name {
        if let Some(discovery) = app.try_state::<crate::device_discovery::DiscoveryState>() {
            let port = app
                .try_state::<crate::transfer_manager::TransferState>()
                .and_then(|ts| ts.receiver.lock().ok()?.as_ref().map(|r| r.port()))
                .unwrap_or(0);

            discovery
                .engine
                .update_advertisement(&state.device_uuid, &settings.device_name, "Desktop", port)
                .ok();
            println!(
                "[StateManager] Updated device advertisement name to '{}'",
                settings.device_name
            );
        }
    }

    Ok(())
}

#[tauri::command]
pub fn save_history(
    app: AppHandle,
    container: State<'_, AppStateContainer>,
    history: Vec<RecentTransferSchema>,
) -> Result<(), String> {
    let mut state = container.state.lock().map_err(|e| e.to_string())?;
    let mut bounded_history = history;
    if bounded_history.len() > MAX_PERSISTED_HISTORY_CAPACITY {
        bounded_history.truncate(MAX_PERSISTED_HISTORY_CAPACITY);
    }
    state.history = bounded_history;
    save_state_atomic(&app, &state)?;
    Ok(())
}

#[tauri::command]
pub fn clear_history(
    app: AppHandle,
    container: State<'_, AppStateContainer>,
) -> Result<(), String> {
    let mut state = container.state.lock().map_err(|e| e.to_string())?;
    state.history.clear();
    save_state_atomic(&app, &state)?;
    println!("[StateManager] Cleared transfer history successfully");
    Ok(())
}

pub fn update_incomplete_session(app: &AppHandle, incomplete: IncompleteTransferSchema) {
    if let Some(container) = app.try_state::<AppStateContainer>() {
        if let Ok(mut state) = container.state.lock() {
            state
                .incomplete_transfers
                .retain(|item| item.session_id != incomplete.session_id);
            state.incomplete_transfers.push(incomplete);
            save_state_atomic(app, &state).ok();
        }
    }
}

pub fn remove_incomplete_session(app: &AppHandle, session_id: &str) {
    if let Some(container) = app.try_state::<AppStateContainer>() {
        if let Ok(mut state) = container.state.lock() {
            state
                .incomplete_transfers
                .retain(|item| item.session_id != session_id);
            save_state_atomic(app, &state).ok();
        }
    }
}

#[tauri::command]
pub fn get_incomplete_transfers(
    container: State<'_, AppStateContainer>,
) -> Result<Vec<IncompleteTransferSchema>, String> {
    let state = container.state.lock().map_err(|e| e.to_string())?;
    Ok(state.incomplete_transfers.clone())
}

#[tauri::command]
pub fn remove_incomplete_transfer(
    app: AppHandle,
    _container: State<'_, AppStateContainer>,
    session_id: String,
) -> Result<(), String> {
    remove_incomplete_session(&app, &session_id);
    Ok(())
}

// ─── Tests ───────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dropflow_test_{}", Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn test_load_nonexistent_creates_default_and_persists() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");

        assert!(!state_path.exists());
        let loaded = load_or_create_state_at_path(&state_path);

        assert!(state_path.exists());
        assert_eq!(loaded.version, CURRENT_SCHEMA_VERSION);
        assert!(!loaded.device_uuid.is_empty());
        assert!(Uuid::parse_str(&loaded.device_uuid).is_ok());
    }

    #[test]
    fn test_atomic_save_and_reload() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");

        let mut initial = load_or_create_state_at_path(&state_path);
        initial.settings.device_name = "Custom-PC".to_string();
        initial.history.push(RecentTransferSchema {
            id: "tx-test-1".to_string(),
            file_name: "test.zip".to_string(),
            device_name: "Phone".to_string(),
            size: "1.2 MB".to_string(),
            timestamp: "Just now".to_string(),
            timestamp_ms: Some(1722268800000),
            status: "completed".to_string(),
            direction: "send".to_string(),
            total_files: 1,
            total_size_bytes: Some(1200000),
            files: None,
            receive_dir: None,
            error: None,
        });

        save_state_atomic_at_path(&state_path, &initial).unwrap();

        let reloaded = load_or_create_state_at_path(&state_path);
        assert_eq!(reloaded.settings.device_name, "Custom-PC");
        assert_eq!(reloaded.history.len(), 1);
        assert_eq!(reloaded.history[0].id, "tx-test-1");
        assert_eq!(reloaded.history[0].timestamp_ms, Some(1722268800000));
    }

    #[test]
    fn test_corrupt_file_recovery_creates_backup() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");
        let bak_path = dir.join("state.json.bak");

        fs::write(&state_path, "{ invalid json garbage }").unwrap();

        assert!(!bak_path.exists());
        let recovered = load_or_create_state_at_path(&state_path);

        assert!(bak_path.exists());
        assert_eq!(
            fs::read_to_string(&bak_path).unwrap(),
            "{ invalid json garbage }"
        );
        assert!(state_path.exists());
        assert!(!recovered.device_uuid.is_empty());
        assert!(Uuid::parse_str(&recovered.device_uuid).is_ok());
    }

    #[test]
    fn test_default_receive_directory_non_empty() {
        let defaults = UserSettings::default();
        assert!(!defaults.receive_directory.is_empty());
        assert!(defaults.receive_directory.contains("DropFlow"));
    }

    #[test]
    fn test_history_capacity_cap_500() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");

        let mut state = load_or_create_state_at_path(&state_path);
        for i in 0..600 {
            state.history.push(RecentTransferSchema {
                id: format!("tx-{i}"),
                file_name: format!("file_{i}.txt"),
                device_name: "Remote".to_string(),
                size: "1 KB".to_string(),
                timestamp: "Just now".to_string(),
                timestamp_ms: Some(1722268800000 + i as u64),
                status: "completed".to_string(),
                direction: "receive".to_string(),
                total_files: 1,
                total_size_bytes: Some(1000),
                files: None,
                receive_dir: None,
                error: None,
            });
        }

        save_state_atomic_at_path(&state_path, &state).unwrap();

        let reloaded = load_or_create_state_at_path(&state_path);
        assert_eq!(reloaded.history.len(), 500);
        assert_eq!(reloaded.history[0].id, "tx-0");
        assert_eq!(reloaded.history[499].id, "tx-499");
    }

    #[test]
    fn test_clear_history_preserves_settings_and_uuid() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");

        let mut state = load_or_create_state_at_path(&state_path);
        let orig_uuid = state.device_uuid.clone();
        state.settings.device_name = "Desktop-PC".to_string();
        state.history.push(RecentTransferSchema {
            id: "tx-1".to_string(),
            file_name: "test.pdf".to_string(),
            device_name: "Mac".to_string(),
            size: "1 MB".to_string(),
            timestamp: "Just now".to_string(),
            timestamp_ms: Some(1234567),
            status: "completed".to_string(),
            direction: "send".to_string(),
            total_files: 1,
            total_size_bytes: Some(1000),
            files: None,
            receive_dir: None,
            error: None,
        });

        save_state_atomic_at_path(&state_path, &state).unwrap();
        assert_eq!(state.history.len(), 1);

        state.history.clear();
        save_state_atomic_at_path(&state_path, &state).unwrap();

        let reloaded = load_or_create_state_at_path(&state_path);
        assert_eq!(reloaded.history.len(), 0);
        assert_eq!(reloaded.device_uuid, orig_uuid);
        assert_eq!(reloaded.settings.device_name, "Desktop-PC");
    }

    #[test]
    fn test_incomplete_transfers_persistence() {
        let dir = create_test_temp_dir();
        let state_path = dir.join("state.json");

        let mut state = load_or_create_state_at_path(&state_path);
        assert_eq!(state.incomplete_transfers.len(), 0);

        state.incomplete_transfers.push(IncompleteTransferSchema {
            session_id: "tx-resume-101".to_string(),
            file_name: "movie.mkv".to_string(),
            device_name: "MacBook".to_string(),
            total_files: 1,
            total_size_bytes: 104857600,
            bytes_completed: 52428800,
            direction: "receive".to_string(),
            receive_dir: Some("/Users/test/Downloads/DropFlow".to_string()),
            file_paths: None,
            files: None,
            timestamp_ms: Some(1722268800000),
        });

        save_state_atomic_at_path(&state_path, &state).unwrap();

        let reloaded = load_or_create_state_at_path(&state_path);
        assert_eq!(reloaded.incomplete_transfers.len(), 1);
        assert_eq!(reloaded.incomplete_transfers[0].session_id, "tx-resume-101");
        assert_eq!(reloaded.incomplete_transfers[0].bytes_completed, 52428800);
    }
}

pub mod device_discovery;
pub mod power_manager;
pub mod state_manager;
pub mod transfer_manager;

use std::sync::{Arc, Mutex};
use tauri::Manager;

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[derive(serde::Serialize)]
pub struct ReleaseInfo {
    pub version: String,
    pub display_version: String,
    pub release_tag: String,
    pub channel: String,
}

#[tauri::command]
fn get_release_info() -> ReleaseInfo {
    let raw_tag = option_env!("DROPFLOW_RELEASE_TAG").unwrap_or("");
    let app_version = env!("CARGO_PKG_VERSION");

    let (display_version, channel, release_tag) = if !raw_tag.is_empty() {
        let tag_clean = raw_tag.strip_prefix('v').unwrap_or(raw_tag);
        let ch = if tag_clean.contains("-beta") {
            "beta"
        } else {
            "stable"
        };
        (tag_clean.to_string(), ch.to_string(), raw_tag.to_string())
    } else {
        (app_version.to_string(), "stable".to_string(), String::new())
    };

    ReleaseInfo {
        version: app_version.to_string(),
        display_version,
        release_tag,
        channel,
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .setup(|app| {
            let app_state = state_manager::load_or_create_state(app.handle());
            let local_uuid = app_state.device_uuid.clone();

            let sleep_manager = power_manager::SleepManager::new();
            app.manage(power_manager::SleepState {
                sleep_manager: Arc::clone(&sleep_manager),
            });

            let discovery_engine = device_discovery::MdnsDiscoveryEngine::new(local_uuid.clone())
                .expect("Failed to initialize mDNS engine");

            // Start TCP transfer receiver on dynamic OS port
            let receiver = transfer_manager::TransferReceiver::start(app.handle().clone())
                .expect("Failed to start TCP transfer receiver");
            let bound_port = receiver.port();
            println!("[Lib] TCP Receiver successfully bound on port {bound_port}");

            app.manage(state_manager::AppStateContainer {
                state: Mutex::new(app_state),
                state_file_path: app
                    .path()
                    .app_data_dir()
                    .unwrap_or_else(|_| std::path::PathBuf::from(".")),
            });

            app.manage(device_discovery::DiscoveryState {
                engine: Box::new(discovery_engine),
                local_uuid,
            });

            app.manage(transfer_manager::TransferState {
                receiver: Mutex::new(Some(receiver)),
                active_cancellations: Mutex::new(std::collections::HashMap::new()),
                pending_authorizations: Mutex::new(std::collections::HashMap::new()),
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            device_discovery::start_discovery,
            device_discovery::update_advertisement,
            device_discovery::get_local_uuid,
            device_discovery::get_current_peers,
            device_discovery::get_system_computer_name,
            device_discovery::get_system_platform,
            transfer_manager::engine::send_files,
            transfer_manager::engine::cancel_transfer,
            transfer_manager::engine::get_receive_dir,
            transfer_manager::engine::get_receiver_port,
            transfer_manager::engine::open_received_file,
            transfer_manager::engine::respond_transfer_request,
            state_manager::get_app_state,
            state_manager::save_settings,
            state_manager::save_history,
            state_manager::clear_history,
            state_manager::get_incomplete_transfers,
            state_manager::remove_incomplete_transfer,
            state_manager::add_trusted_device,
            state_manager::remove_trusted_device,
            state_manager::get_trusted_devices,
            get_release_info,
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.try_state::<device_discovery::DiscoveryState>() {
                    state.engine.update_advertisement("", "", "", 0).ok();
                    println!("[Advertiser] App window destroyed, cleanly unregistered service");
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

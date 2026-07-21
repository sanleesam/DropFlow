pub mod device_discovery;
pub mod transfer_manager;

use std::sync::Mutex;
use tauri::Manager;

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let local_uuid = device_discovery::get_or_create_uuid(app.handle());
            let discovery_engine = device_discovery::MdnsDiscoveryEngine::new(local_uuid.clone())
                .expect("Failed to initialize mDNS engine");

            // Start TCP transfer receiver on dynamic OS port
            let receiver = transfer_manager::TransferReceiver::start(app.handle().clone())
                .expect("Failed to start TCP transfer receiver");
            let bound_port = receiver.port();
            println!("[Lib] TCP Receiver successfully bound on port {bound_port}");

            app.manage(device_discovery::DiscoveryState {
                engine: Box::new(discovery_engine),
                local_uuid,
            });

            app.manage(transfer_manager::TransferState {
                receiver: Mutex::new(Some(receiver)),
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
            transfer_manager::engine::send_files,
            transfer_manager::engine::get_receive_dir,
            transfer_manager::engine::get_receiver_port,
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

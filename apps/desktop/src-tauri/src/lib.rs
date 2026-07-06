pub mod device_discovery;

use tauri::Manager;

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let local_uuid = device_discovery::get_or_create_uuid(app.handle());
            let discovery_engine = device_discovery::MdnsDiscoveryEngine::new(local_uuid.clone())
                .expect("Failed to initialize mDNS engine");

            app.manage(device_discovery::DiscoveryState {
                engine: Box::new(discovery_engine),
                local_uuid,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            device_discovery::start_discovery,
            device_discovery::update_advertisement,
            device_discovery::get_local_uuid,
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

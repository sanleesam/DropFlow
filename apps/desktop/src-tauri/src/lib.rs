pub mod device_discovery;
pub mod fs_browser;
pub mod logging;
pub mod power_manager;
pub mod state_manager;
pub mod transfer_manager;
pub mod update_manager;
mod window_material;

use std::sync::{Arc, Mutex};
use log::info;
use tauri::Manager;

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
    release_info(app_version, raw_tag)
}

fn release_info(app_version: &str, raw_tag: &str) -> ReleaseInfo {
    // A local build has no CI tag. Its package version still identifies its
    // release line; this is independent of the user's selected update channel.
    let display_version = if raw_tag.is_empty() {
        app_version
    } else {
        raw_tag.strip_prefix('v').unwrap_or(raw_tag)
    };
    let parsed = semver::Version::parse(display_version).ok();
    let channel = match parsed.as_ref().map(|version| version.pre.as_str()) {
        Some("") => "stable",
        Some(pre) => match pre.split('.').next() {
            Some("beta") => "beta",
            Some("dev") => "development",
            _ => "prerelease",
        },
        None => "unknown",
    };

    ReleaseInfo {
        version: app_version.to_string(),
        display_version: display_version.to_string(),
        release_tag: raw_tag.to_string(),
        channel: channel.to_string(),
    }
}

#[cfg(test)]
mod release_info_tests {
    use super::release_info;

    #[test]
    fn local_beta_is_not_labeled_stable() {
        let info = release_info("0.1.0-beta.17", "");
        assert_eq!(info.version, "0.1.0-beta.17");
        assert_eq!(info.display_version, info.version);
        assert_eq!(info.channel, "beta");
        assert!(info.release_tag.is_empty());
    }

    #[test]
    fn ci_tag_remains_authoritative() {
        let info = release_info("0.1.0-beta.15", "v0.1.0-beta.17");
        assert_eq!(info.display_version, "0.1.0-beta.17");
        assert_eq!(info.channel, "beta");
        assert_eq!(info.release_tag, "v0.1.0-beta.17");
    }

    #[test]
    fn development_builds_are_identified_with_and_without_a_tag() {
        for tag in ["", "v0.1.0-dev.6", "0.1.0-dev.6"] {
            assert_eq!(release_info("0.1.0-dev.6", tag).channel, "development");
        }
    }

    #[test]
    fn only_a_final_version_is_stable() {
        assert_eq!(release_info("0.1.0", "").channel, "stable");
        assert_eq!(release_info("0.1.0-rc.1", "").channel, "prerelease");
        assert_eq!(release_info("invalid", "").channel, "unknown");
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
            // Structured logging first so every later stage is captured.
            let log_dir = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| std::path::PathBuf::from("."));
            if let Some(path) = logging::init(&log_dir) {
                info!("[Lib] Logging to {}", path.display());
            }

            app.manage(window_material::MaterialState::default());
            if let Some(window) = app.get_webview_window("main") {
                window_material::refresh(&window);
            }

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
            info!("[Lib] TCP Receiver successfully bound on port {bound_port}");

            app.manage(state_manager::AppStateContainer {
                state: Mutex::new(app_state),
                state_file_path: app
                    .path()
                    .app_data_dir()
                    .unwrap_or_else(|_| std::path::PathBuf::from(".")),
            });
            app.manage(update_manager::PendingUpdate::new());

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
            update_manager::check_for_updates,
            update_manager::download_and_install_update,
            update_manager::clear_pending_update,
            device_discovery::start_discovery,
            device_discovery::update_advertisement,
            device_discovery::get_local_uuid,
            device_discovery::get_current_peers,
            device_discovery::get_system_computer_name,
            device_discovery::get_system_platform,
            device_discovery::get_system_device_type,
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
            fs_browser::get_disk_usage,
            fs_browser::get_user_folders,
            fs_browser::list_directory,
            fs_browser::read_image_preview,
            fs_browser::rename_path,
            fs_browser::delete_path,
            fs_browser::create_folder,
        ])
        .on_page_load(|webview, payload| {
            if payload.event() == tauri::webview::PageLoadEvent::Finished {
                if let Some(window) = webview.app_handle().get_webview_window("main") {
                    window_material::refresh(&window);
                }
            }
        })
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Focused(true)) {
                if let Some(webview) = window.app_handle().get_webview_window("main") {
                    window_material::refresh(&webview);
                }
            }
            if let tauri::WindowEvent::Destroyed = event {
                if let Some(state) = window.try_state::<device_discovery::DiscoveryState>() {
                    state.engine.update_advertisement("", "", "", 0).ok();
                    info!("[Advertiser] App window destroyed, cleanly unregistered service");
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

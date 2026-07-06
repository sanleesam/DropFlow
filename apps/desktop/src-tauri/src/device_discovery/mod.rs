use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::net::UdpSocket;
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, State, Manager};

// ─── Data Types ──────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiscoveredDevice {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub device_type: String, // "laptop" | "desktop" | "phone"
    pub status: String, // "online"
    #[serde(rename = "lastSeen")]
    pub last_seen: String, // "Now"
    pub ip: String,
    pub port: u16,
    pub version: String,
}

// ─── Discovery Engine Trait (for Modular Backends / Fallbacks) ──────────────────

pub trait DiscoveryEngine: Send + Sync {
    /** Announce local device details on the LAN */
    fn update_advertisement(
        &self,
        device_id: &str,
        device_name: &str,
        device_type: &str,
        port: u16,
    ) -> Result<(), String>;

    /** Start listening for peer announcement events on the LAN */
    fn start_browsing(&self, app: AppHandle) -> Result<(), String>;
}

// ─── UUID Persistence Helper ─────────────────────────────────────────────────

pub fn get_or_create_uuid(app: &AppHandle) -> String {
    let app_data = app.path().app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));
    
    // Ensure parent directory exists
    fs::create_dir_all(&app_data).ok();
    
    let uuid_path = app_data.join("device_uuid");
    if let Ok(content) = fs::read_to_string(&uuid_path) {
        let trimmed = content.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    
    let new_uuid = Uuid::new_v4().to_string();
    fs::write(&uuid_path, &new_uuid).ok();
    new_uuid
}

// ─── mDNS Discovery Implementation ───────────────────────────────────────────

pub struct MdnsDiscoveryEngine {
    pub daemon: ServiceDaemon,
    pub active_registration: Mutex<Option<ServiceInfo>>,
    pub is_browsing: Mutex<bool>,
    pub local_uuid: String,
    pub discovered_peers: Arc<Mutex<HashMap<String, DiscoveredDevice>>>,
}

impl MdnsDiscoveryEngine {
    pub fn new(local_uuid: String) -> Result<Self, String> {
        let daemon = ServiceDaemon::new().map_err(|e| format!("Failed to create mDNS daemon: {}", e))?;
        Ok(Self {
            daemon,
            active_registration: Mutex::new(None),
            is_browsing: Mutex::new(false),
            local_uuid,
            discovered_peers: Arc::new(Mutex::new(HashMap::new())),
        })
    }
}

fn get_local_ip() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}

impl DiscoveryEngine for MdnsDiscoveryEngine {
    fn update_advertisement(
        &self,
        device_id: &str,
        device_name: &str,
        device_type: &str,
        port: u16,
    ) -> Result<(), String> {
        let mut active_reg = self.active_registration.lock().map_err(|e| e.to_string())?;
        let was_active = active_reg.is_some();

        // Unregister existing service if any
        if let Some(old_info) = active_reg.take() {
            self.daemon.unregister(&old_info.get_fullname()).ok();
            println!("[Advertiser] Service unregistered: {}", old_info.get_fullname());
        }

        // 0 indicates stop advertising / disabled visibility
        if port == 0 {
            return Ok(());
        }

        let local_ip = get_local_ip().unwrap_or_else(|| "127.0.0.1".to_string());
        let service_type = "_dropflow._tcp.local.";
        let instance_name = format!("{}.{}", device_id, service_type);
        let host_name = format!("{}.local.", device_id);

        let mut properties = HashMap::new();
        properties.insert("device_name".to_string(), device_name.to_string());
        properties.insert("device_id".to_string(), device_id.to_string());
        properties.insert("device_uuid".to_string(), self.local_uuid.clone());
        properties.insert("device_type".to_string(), device_type.to_string());
        properties.insert("version".to_string(), "DFP/1".to_string());
        properties.insert("port".to_string(), port.to_string());
        properties.insert("ip".to_string(), local_ip.clone());

        let service_info = ServiceInfo::new(
            service_type,
            &instance_name,
            &host_name,
            local_ip.as_str(),
            port,
            properties,
        )
        .map_err(|e| format!("Failed to build mDNS service info: {}", e))?;

        self.daemon.register(service_info.clone())
            .map_err(|e| format!("Failed to register mDNS service: {}", e))?;

        if was_active {
            println!("[Advertiser] Service updated: {} on {}:{}", instance_name, local_ip, port);
        } else {
            println!("[Advertiser] Service registered: {} on {}:{}", instance_name, local_ip, port);
        }

        *active_reg = Some(service_info);
        Ok(())
    }

    fn start_browsing(&self, app: AppHandle) -> Result<(), String> {
        let mut is_browsing = self.is_browsing.lock().map_err(|e| e.to_string())?;
        if *is_browsing {
            return Ok(());
        }
        *is_browsing = true;

        let daemon = self.daemon.clone();
        let service_type = "_dropflow._tcp.local.";
        let local_uuid = self.local_uuid.clone();
        let discovered_peers = self.discovered_peers.clone();

        let receiver = daemon.browse(service_type)
            .map_err(|e| format!("Failed to browse mDNS services: {}", e))?;

        println!("[Browser] Started mDNS browsing for {}", service_type);

        std::thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                match event {
                    ServiceEvent::ServiceFound(service_type, fullname) => {
                        println!("[Browser] Service discovered: {} of type {}", fullname, service_type);
                    }
                    ServiceEvent::ServiceResolved(info) => {
                        println!("[Browser] Service resolved: {}", info.get_fullname());

                        let discovered_uuid = info.get_property_val_str("device_uuid")
                            .unwrap_or("")
                            .to_string();

                        // Strict filter: ignore our own advertised instance
                        if !discovered_uuid.is_empty() && discovered_uuid == local_uuid {
                            println!("[Browser] Self device ignored: UUID={}", discovered_uuid);
                            continue;
                        }

                        let id = if discovered_uuid.is_empty() {
                            info.get_property_val_str("device_id")
                                .unwrap_or_else(|| info.get_fullname())
                                .to_string()
                        } else {
                            discovered_uuid
                        };

                        let name = info.get_property_val_str("device_name")
                            .unwrap_or_else(|| info.get_fullname())
                            .to_string();
                        let device_type = info.get_property_val_str("device_type")
                            .unwrap_or("laptop")
                            .to_string();
                        let version = info.get_property_val_str("version")
                            .unwrap_or("DFP/1")
                            .to_string();
                        let ip = info.get_addresses_v4()
                            .iter()
                            .next()
                            .map(|addr| addr.to_string())
                            .unwrap_or_else(|| "127.0.0.1".to_string());
                        let port = info.get_port();

                        let device = DiscoveredDevice {
                            id: id.clone(),
                            name,
                            device_type,
                            status: "online".to_string(),
                            last_seen: "Now".to_string(),
                            ip,
                            port,
                            version,
                        };

                        let mut peers = discovered_peers.lock().unwrap();
                        if peers.contains_key(&id) {
                            println!("[Browser] Peer updated: {} (UUID={})", device.name, id);
                        } else {
                            println!("[Browser] Peer added: {} (UUID={})", device.name, id);
                        }
                        peers.insert(id, device.clone());

                        app.emit("peer-discovered", &device).ok();
                    }
                    ServiceEvent::ServiceRemoved(_service_type, fullname) => {
                        println!("[Browser] Service removed: {}", fullname);
                        let id = fullname.split('.').next().unwrap_or(&fullname).to_string();

                        let mut peers = discovered_peers.lock().unwrap();
                        if peers.remove(&id).is_some() {
                            println!("[Browser] Peer removed: ID={}", id);
                        }

                        app.emit("peer-lost", &id).ok();
                    }
                    ServiceEvent::SearchStopped(_) => {
                        println!("[Browser] Search stopped");
                        break;
                    }
                    _ => {}
                }
            }
        });

        Ok(())
    }
}

// ─── Tauri State ─────────────────────────────────────────────────────────────

pub struct DiscoveryState {
    pub engine: Box<dyn DiscoveryEngine>,
    pub local_uuid: String,
}

// ─── Tauri Custom Commands ───────────────────────────────────────────────────

#[tauri::command]
pub fn get_local_uuid(state: State<'_, DiscoveryState>) -> String {
    state.local_uuid.clone()
}

#[tauri::command]
pub fn update_advertisement(
    state: State<'_, DiscoveryState>,
    device_id: String,
    device_name: String,
    device_type: String,
    port: u16,
) -> Result<(), String> {
    state.engine.update_advertisement(&device_id, &device_name, &device_type, port)
}

#[tauri::command]
pub fn start_discovery(
    app: AppHandle,
    state: State<'_, DiscoveryState>,
) -> Result<(), String> {
    state.engine.start_browsing(app)
}

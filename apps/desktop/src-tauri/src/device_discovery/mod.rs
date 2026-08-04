use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use mdns_sd::{ResolvedService, ScopedIp, ServiceDaemon, ServiceEvent, ServiceInfo};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};
use uuid::Uuid;

const SERVICE_TYPE: &str = "_dropflow._tcp.local.";
const PROTOCOL_VERSION: &str = "DFP/1";
const MAX_DEVICE_NAME_BYTES: usize = 64;

// ─── Data Types ──────────────────────────────────────────────────────────────

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
pub struct PeerAddress {
    pub address: String,
    pub family: String,
    pub interface: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub device_type: String,
    pub status: String,
    #[serde(rename = "lastSeen")]
    pub last_seen: String,
    pub addresses: Vec<PeerAddress>,
    pub port: u16,
    pub version: String,
}

#[derive(Debug, PartialEq, Eq)]
struct ValidatedPeerMetadata {
    id: String,
    name: String,
    device_type: String,
    version: String,
}

// ─── Discovery Engine Trait ──────────────────────────────────────────────────

pub trait DiscoveryEngine: Send + Sync {
    fn update_advertisement(
        &self,
        device_id: &str,
        device_name: &str,
        device_type: &str,
        port: u16,
    ) -> Result<(), String>;

    fn start_browsing(&self, app: AppHandle) -> Result<(), String>;

    fn current_peers(&self) -> Result<Vec<DiscoveredDevice>, String>;
}

// ─── UUID Persistence Helper ─────────────────────────────────────────────────

pub fn get_or_create_uuid(app: &AppHandle) -> String {
    let app_data = app
        .path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."));

    fs::create_dir_all(&app_data).ok();

    let uuid_path = app_data.join("device_uuid");
    if let Ok(content) = fs::read_to_string(&uuid_path) {
        let trimmed = content.trim();
        if let Ok(uuid) = Uuid::parse_str(trimmed) {
            return uuid.to_string();
        }
        if !trimmed.is_empty() {
            println!("[Discovery] Ignoring invalid persisted device UUID");
        }
    }

    let new_uuid = Uuid::new_v4().to_string();
    fs::write(&uuid_path, &new_uuid).ok();
    new_uuid
}

// ─── Peer Validation ─────────────────────────────────────────────────────────

fn is_self_peer(device_uuid: Option<&str>, local_uuid: &str) -> bool {
    matches!(
        device_uuid.and_then(|value| Uuid::parse_str(value).ok()),
        Some(uuid) if uuid.to_string() == local_uuid
    )
}

fn validate_peer_metadata(
    device_uuid: Option<&str>,
    device_id: Option<&str>,
    device_name: Option<&str>,
    device_type: Option<&str>,
    version: Option<&str>,
    port: u16,
    addresses: &[PeerAddress],
) -> Result<ValidatedPeerMetadata, String> {
    let uuid = device_uuid.ok_or("missing device_uuid")?;
    let canonical_uuid = Uuid::parse_str(uuid)
        .map_err(|_| "invalid device_uuid")?
        .to_string();

    let advertised_id = device_id.ok_or("missing device_id")?;
    let canonical_device_id = Uuid::parse_str(advertised_id)
        .map_err(|_| "invalid device_id")?
        .to_string();
    if canonical_device_id != canonical_uuid {
        return Err("device_id does not match device_uuid".to_string());
    }

    let name = device_name.map(str::trim).unwrap_or_default();
    if name.is_empty() {
        return Err("missing device_name".to_string());
    }
    if name.len() > MAX_DEVICE_NAME_BYTES || name.chars().any(char::is_control) {
        return Err("invalid device_name".to_string());
    }

    let device_type = match device_type {
        Some("laptop" | "desktop" | "phone" | "tablet") => device_type.unwrap().to_string(),
        Some(_) => return Err("invalid device_type".to_string()),
        None => return Err("missing device_type".to_string()),
    };

    if version != Some(PROTOCOL_VERSION) {
        return Err("unsupported or missing protocol version".to_string());
    }
    if port == 0 {
        return Err("invalid service port".to_string());
    }
    if addresses.is_empty() {
        return Err("service has no usable addresses".to_string());
    }

    Ok(ValidatedPeerMetadata {
        id: canonical_uuid,
        name: name.to_string(),
        device_type,
        version: PROTOCOL_VERSION.to_string(),
    })
}

fn resolved_addresses(info: &ResolvedService) -> Vec<PeerAddress> {
    let mut addresses = Vec::new();

    for scoped_ip in info.get_addresses() {
        match scoped_ip {
            ScopedIp::V4(ip) => {
                let address = ip.addr();
                if address.is_unspecified() || address.is_loopback() || address.is_multicast() {
                    continue;
                }
                addresses.push(PeerAddress {
                    address: address.to_string(),
                    family: "ipv4".to_string(),
                    interface: None,
                });
            }
            ScopedIp::V6(ip) => {
                let address = ip.addr();
                if address.is_unspecified() || address.is_loopback() || address.is_multicast() {
                    continue;
                }
                let interface_name = ip.scope_id().name.trim();
                addresses.push(PeerAddress {
                    address: address.to_string(),
                    family: "ipv6".to_string(),
                    interface: (!interface_name.is_empty()).then(|| interface_name.to_string()),
                });
            }
            _ => {}
        }
    }

    addresses.sort_by(|left, right| {
        (&left.family, &left.address, &left.interface).cmp(&(
            &right.family,
            &right.address,
            &right.interface,
        ))
    });
    addresses.dedup();
    addresses
}

fn device_from_resolved_service(info: &ResolvedService) -> Result<DiscoveredDevice, String> {
    let addresses = resolved_addresses(info);
    let metadata = validate_peer_metadata(
        info.get_property_val_str("device_uuid"),
        info.get_property_val_str("device_id"),
        info.get_property_val_str("device_name"),
        info.get_property_val_str("device_type"),
        info.get_property_val_str("version"),
        info.get_port(),
        &addresses,
    )?;

    Ok(DiscoveredDevice {
        id: metadata.id,
        name: metadata.name,
        device_type: metadata.device_type,
        status: "online".to_string(),
        last_seen: "Now".to_string(),
        addresses,
        port: info.get_port(),
        version: metadata.version,
    })
}

// ─── Peer Registry ───────────────────────────────────────────────────────────

#[derive(Default)]
struct PeerRegistry {
    peers: HashMap<String, DiscoveredDevice>,
    service_to_peer: HashMap<String, String>,
}

enum PeerUpsert {
    Added(DiscoveredDevice),
    Updated(DiscoveredDevice),
    Unchanged,
}

struct RegistryUpsert {
    peer: PeerUpsert,
    replaced_peer_id: Option<String>,
}

enum ServiceRemoval {
    Removed(String),
    StillMapped(String),
    MissingPeer(String),
    UnknownService,
}

impl PeerRegistry {
    fn upsert(&mut self, service_fullname: String, device: DiscoveredDevice) -> RegistryUpsert {
        let replaced_peer_id = self
            .service_to_peer
            .insert(service_fullname, device.id.clone())
            .and_then(|previous_id| {
                if previous_id == device.id
                    || self.service_to_peer.values().any(|id| id == &previous_id)
                {
                    None
                } else {
                    self.peers.remove(&previous_id).map(|_| previous_id)
                }
            });

        let peer = match self.peers.get(&device.id) {
            None => {
                self.peers.insert(device.id.clone(), device.clone());
                PeerUpsert::Added(device)
            }
            Some(existing) if existing == &device => PeerUpsert::Unchanged,
            Some(_) => {
                self.peers.insert(device.id.clone(), device.clone());
                PeerUpsert::Updated(device)
            }
        };

        RegistryUpsert {
            peer,
            replaced_peer_id,
        }
    }

    fn remove_service(&mut self, service_fullname: &str) -> ServiceRemoval {
        let Some(peer_id) = self.service_to_peer.remove(service_fullname) else {
            return ServiceRemoval::UnknownService;
        };

        if self.service_to_peer.values().any(|id| id == &peer_id) {
            return ServiceRemoval::StillMapped(peer_id);
        }

        if self.peers.remove(&peer_id).is_some() {
            ServiceRemoval::Removed(peer_id)
        } else {
            ServiceRemoval::MissingPeer(peer_id)
        }
    }

    fn snapshot(&self) -> Vec<DiscoveredDevice> {
        let mut peers: Vec<_> = self.peers.values().cloned().collect();
        peers.sort_by(|left, right| left.id.cmp(&right.id));
        peers
    }
}

// ─── mDNS Discovery Implementation ───────────────────────────────────────────

pub struct MdnsDiscoveryEngine {
    daemon: ServiceDaemon,
    active_registration: Mutex<Option<ServiceInfo>>,
    is_browsing: Mutex<bool>,
    local_uuid: String,
    peer_registry: Arc<Mutex<PeerRegistry>>,
}

impl MdnsDiscoveryEngine {
    pub fn new(local_uuid: String) -> Result<Self, String> {
        let daemon =
            ServiceDaemon::new().map_err(|e| format!("Failed to create mDNS daemon: {e}"))?;
        Ok(Self {
            daemon,
            active_registration: Mutex::new(None),
            is_browsing: Mutex::new(false),
            local_uuid,
            peer_registry: Arc::new(Mutex::new(PeerRegistry::default())),
        })
    }
}

fn emit_peer_discovered(app: &AppHandle, device: &DiscoveredDevice) {
    if let Err(error) = app.emit("peer-discovered", device) {
        println!(
            "[Discovery] Failed to emit peer-discovered for {}: {error}",
            device.id
        );
    }
}

fn emit_peer_lost(app: &AppHandle, peer_id: &str) {
    if let Err(error) = app.emit("peer-lost", peer_id) {
        println!("[Discovery] Failed to emit peer-lost for {peer_id}: {error}");
    }
}

impl DiscoveryEngine for MdnsDiscoveryEngine {
    fn update_advertisement(
        &self,
        device_id: &str,
        device_name: &str,
        device_type: &str,
        port: u16,
    ) -> Result<(), String> {
        let mut active_registration = self
            .active_registration
            .lock()
            .map_err(|error| error.to_string())?;

        if port == 0 {
            if let Some(previous) = active_registration.take() {
                self.daemon
                    .unregister(previous.get_fullname())
                    .map_err(|error| format!("Failed to unregister mDNS service: {error}"))?;
                println!(
                    "[Advertiser] Unregister requested: {}",
                    previous.get_fullname()
                );
            }
            return Ok(());
        }

        let canonical_device_id = Uuid::parse_str(device_id)
            .map_err(|_| "Invalid local device UUID".to_string())?
            .to_string();
        if canonical_device_id != self.local_uuid {
            return Err(
                "Local advertisement UUID does not match persisted device UUID".to_string(),
            );
        }

        let mut properties = HashMap::new();
        properties.insert("device_name".to_string(), device_name.to_string());
        properties.insert("device_id".to_string(), canonical_device_id.clone());
        properties.insert("device_uuid".to_string(), self.local_uuid.clone());
        properties.insert("device_type".to_string(), device_type.to_string());
        properties.insert("version".to_string(), PROTOCOL_VERSION.to_string());

        let host_name = format!("{canonical_device_id}.local.");
        let service_info = ServiceInfo::new(
            SERVICE_TYPE,
            &canonical_device_id,
            &host_name,
            "",
            port,
            properties,
        )
        .map_err(|error| format!("Failed to build mDNS service info: {error}"))?
        .enable_addr_auto();

        if let Some(previous) = active_registration.as_ref() {
            if previous.get_fullname() != service_info.get_fullname() {
                self.daemon
                    .unregister(previous.get_fullname())
                    .map_err(|error| format!("Failed to unregister prior mDNS service: {error}"))?;
                println!(
                    "[Advertiser] Unregister requested: {}",
                    previous.get_fullname()
                );
            }
        }

        self.daemon
            .register(service_info.clone())
            .map_err(|error| format!("Failed to register mDNS service: {error}"))?;

        println!(
            "[Advertiser] Registered {} on port {} with automatic interface address management",
            service_info.get_fullname(),
            port
        );
        *active_registration = Some(service_info);
        Ok(())
    }

    fn start_browsing(&self, app: AppHandle) -> Result<(), String> {
        let mut is_browsing = self.is_browsing.lock().map_err(|error| error.to_string())?;
        if *is_browsing {
            println!("[Discovery] Browser already running");
            return Ok(());
        }
        *is_browsing = true;
        drop(is_browsing);

        let daemon = self.daemon.clone();
        let local_uuid = self.local_uuid.clone();
        let peer_registry = self.peer_registry.clone();

        std::thread::spawn(move || loop {
            match daemon.browse(SERVICE_TYPE) {
                Ok(receiver) => {
                    println!("[Discovery] Browser started for {SERVICE_TYPE}");

                    while let Ok(event) = receiver.recv() {
                        match event {
                            ServiceEvent::SearchStarted(service_type) => {
                                println!("[Discovery] Search started: {service_type}");
                            }
                            ServiceEvent::ServiceFound(service_type, fullname) => {
                                println!("[Discovery] Service found: {fullname} ({service_type})");
                            }
                            ServiceEvent::ServiceResolved(info) => {
                                let fullname = info.get_fullname().to_string();
                                let discovered_uuid = info.get_property_val_str("device_uuid");

                                if is_self_peer(discovered_uuid, &local_uuid) {
                                    println!("[Discovery] Self device ignored: {fullname}");
                                    continue;
                                }

                                let device = match device_from_resolved_service(&info) {
                                    Ok(device) => device,
                                    Err(reason) => {
                                        println!(
                                            "[Discovery] Peer rejected: {fullname} ({reason})"
                                        );
                                        continue;
                                    }
                                };

                                let update = match peer_registry.lock() {
                                    Ok(mut registry) => registry.upsert(fullname.clone(), device),
                                    Err(error) => {
                                        println!("[Discovery] Peer registry unavailable: {error}");
                                        continue;
                                    }
                                };

                                if let Some(replaced_peer_id) = update.replaced_peer_id {
                                    println!(
                                        "[Discovery] Peer identity replaced for service {fullname}: {replaced_peer_id}"
                                    );
                                    emit_peer_lost(&app, &replaced_peer_id);
                                }

                                match update.peer {
                                    PeerUpsert::Added(device) => {
                                        println!(
                                            "[Discovery] Peer accepted: {} (UUID={}, addresses={})",
                                            device.name,
                                            device.id,
                                            device.addresses.len()
                                        );
                                        emit_peer_discovered(&app, &device);
                                    }
                                    PeerUpsert::Updated(device) => {
                                        println!(
                                            "[Discovery] Peer re-resolved with changes: {} (UUID={})",
                                            device.name, device.id
                                        );
                                        emit_peer_discovered(&app, &device);
                                    }
                                    PeerUpsert::Unchanged => {
                                        println!(
                                            "[Discovery] Peer re-resolved unchanged: {fullname}"
                                        );
                                    }
                                }
                            }
                            ServiceEvent::ServiceRemoved(_, fullname) => {
                                let removal = match peer_registry.lock() {
                                    Ok(mut registry) => registry.remove_service(&fullname),
                                    Err(error) => {
                                        println!("[Discovery] Peer registry unavailable: {error}");
                                        continue;
                                    }
                                };

                                match removal {
                                    ServiceRemoval::Removed(peer_id) => {
                                        println!(
                                            "[Discovery] Service removed: {fullname} -> peer {peer_id}"
                                        );
                                        emit_peer_lost(&app, &peer_id);
                                    }
                                    ServiceRemoval::StillMapped(peer_id) => {
                                        println!(
                                            "[Discovery] Service removed but peer remains mapped: {fullname} -> {peer_id}"
                                        );
                                    }
                                    ServiceRemoval::MissingPeer(peer_id) => {
                                        println!(
                                            "[Discovery] Removal mapping had no peer record: {fullname} -> {peer_id}"
                                        );
                                    }
                                    ServiceRemoval::UnknownService => {
                                        println!("[Discovery] Unknown service removal ignored: {fullname}");
                                    }
                                }
                            }
                            ServiceEvent::SearchStopped(service_type) => {
                                println!("[Discovery] Search stopped: {service_type}");
                                break;
                            }
                            _ => {}
                        }
                    }
                }
                Err(error) => {
                    println!("[Discovery] Browse request failed: {error}");
                }
            }

            std::thread::sleep(std::time::Duration::from_millis(500));
        });

        Ok(())
    }

    fn current_peers(&self) -> Result<Vec<DiscoveredDevice>, String> {
        self.peer_registry
            .lock()
            .map(|registry| registry.snapshot())
            .map_err(|error| error.to_string())
    }
}

// ─── Tauri State and Commands ────────────────────────────────────────────────

pub struct DiscoveryState {
    pub engine: Box<dyn DiscoveryEngine>,
    pub local_uuid: String,
}

#[tauri::command]
pub fn get_local_uuid(state: State<'_, DiscoveryState>) -> String {
    state.local_uuid.clone()
}

#[tauri::command]
pub fn get_current_peers(
    state: State<'_, DiscoveryState>,
) -> Result<Vec<DiscoveredDevice>, String> {
    state.engine.current_peers()
}

#[tauri::command]
pub fn get_system_computer_name() -> String {
    #[cfg(target_os = "macos")]
    {
        if let Ok(output) = std::process::Command::new("scutil")
            .args(["--get", "ComputerName"])
            .output()
        {
            let name = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !name.is_empty() {
                return name;
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        if let Ok(name) = std::env::var("COMPUTERNAME") {
            if !name.is_empty() {
                return name;
            }
        }
    }

    std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "DropFlow Device".to_string())
}

#[tauri::command]
pub fn get_system_platform() -> String {
    #[cfg(target_os = "android")]
    {
        "Mobile".to_string()
    }
    #[cfg(not(target_os = "android"))]
    {
        "Desktop".to_string()
    }
}

#[tauri::command]
pub fn get_system_device_type() -> String {
    #[cfg(target_os = "macos")]
    {
        if let Ok(output) = std::process::Command::new("sysctl")
            .args(["-n", "hw.model"])
            .output()
        {
            let model = String::from_utf8_lossy(&output.stdout).to_lowercase();
            if model.contains("book") {
                return "laptop".to_string();
            }
        }
        "desktop".to_string()
    }

    #[cfg(target_os = "windows")]
    {
        if let Ok(output) = std::process::Command::new("WMIC")
            .args(["path", "Win32_Battery", "get", "EstimatedChargeRemaining"])
            .output()
        {
            let stdout = String::from_utf8_lossy(&output.stdout);
            if !stdout.trim().is_empty() && !stdout.contains("No Instance") {
                return "laptop".to_string();
            }
        }
        "desktop".to_string()
    }

    #[cfg(target_os = "linux")]
    {
        if std::path::Path::new("/sys/class/power_supply/BAT0").exists()
            || std::path::Path::new("/sys/class/power_supply/BAT1").exists()
        {
            return "laptop".to_string();
        }
        "desktop".to_string()
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        "desktop".to_string()
    }
}

#[tauri::command]
pub fn update_advertisement(
    state: State<'_, DiscoveryState>,
    transfer_state: State<'_, crate::transfer_manager::TransferState>,
    device_id: String,
    device_name: String,
    device_type: String,
    port: u16,
) -> Result<(), String> {
    let effective_port = if port == 0 {
        0
    } else {
        transfer_state
            .receiver
            .lock()
            .map_err(|e| e.to_string())?
            .as_ref()
            .map(|r| r.port())
            .unwrap_or(port)
    };

    state
        .engine
        .update_advertisement(&device_id, &device_name, &device_type, effective_port)
}

#[tauri::command]
pub fn start_discovery(app: AppHandle, state: State<'_, DiscoveryState>) -> Result<(), String> {
    state.engine.start_browsing(app)
}

#[cfg(test)]
mod tests {
    use super::{
        is_self_peer, validate_peer_metadata, DiscoveredDevice, PeerAddress, PeerRegistry,
        PeerUpsert, ServiceRemoval, PROTOCOL_VERSION,
    };

    fn ipv4_address() -> PeerAddress {
        PeerAddress {
            address: "192.168.1.20".to_string(),
            family: "ipv4".to_string(),
            interface: None,
        }
    }

    fn ipv6_address() -> PeerAddress {
        PeerAddress {
            address: "fe80::20".to_string(),
            family: "ipv6".to_string(),
            interface: Some("en0".to_string()),
        }
    }

    fn valid_device(id: &str) -> DiscoveredDevice {
        DiscoveredDevice {
            id: id.to_string(),
            name: "Peer Mac".to_string(),
            device_type: "laptop".to_string(),
            status: "online".to_string(),
            last_seen: "Now".to_string(),
            addresses: vec![ipv4_address()],
            port: 42382,
            version: PROTOCOL_VERSION.to_string(),
        }
    }

    #[test]
    fn accepts_valid_current_dropflow_metadata() {
        let uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        let result = validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("Peer Mac"),
            Some("laptop"),
            Some(PROTOCOL_VERSION),
            42382,
            &[ipv4_address()],
        )
        .expect("valid metadata should be accepted");

        assert_eq!(result.id, uuid);
        assert_eq!(result.device_type, "laptop");
    }

    #[test]
    fn rejects_invalid_or_unknown_peer_metadata() {
        let uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

        assert!(validate_peer_metadata(
            Some("not-a-uuid"),
            Some(uuid),
            Some("Peer Mac"),
            Some("laptop"),
            Some(PROTOCOL_VERSION),
            42382,
            &[ipv4_address()],
        )
        .is_err());
        assert!(validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("Peer Mac"),
            Some("printer"),
            Some(PROTOCOL_VERSION),
            42382,
            &[ipv4_address()],
        )
        .is_err());
        assert!(validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("\n"),
            Some("laptop"),
            Some(PROTOCOL_VERSION),
            42382,
            &[ipv4_address()],
        )
        .is_err());
    }

    #[test]
    fn rejects_missing_required_endpoint_data() {
        let uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";

        assert!(validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("Peer Mac"),
            Some("laptop"),
            Some(PROTOCOL_VERSION),
            0,
            &[ipv4_address()],
        )
        .is_err());
        assert!(validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("Peer Mac"),
            Some("laptop"),
            Some(PROTOCOL_VERSION),
            42382,
            &[],
        )
        .is_err());
    }

    #[test]
    fn self_filter_requires_matching_uuid() {
        let local_uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        assert!(is_self_peer(Some(local_uuid), local_uuid));
        assert!(is_self_peer(
            Some("F47AC10B-58CC-4372-A567-0E02B2C3D479"),
            local_uuid
        ));
        assert!(!is_self_peer(None, local_uuid));
        assert!(!is_self_peer(
            Some("cb02b2c3-d479-4372-a567-f47ac10b58cc"),
            local_uuid
        ));
    }

    #[test]
    fn preserves_ipv6_without_substituting_localhost() {
        let uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        let metadata = validate_peer_metadata(
            Some(uuid),
            Some(uuid),
            Some("IPv6 Peer"),
            Some("phone"),
            Some(PROTOCOL_VERSION),
            42382,
            &[ipv6_address()],
        )
        .expect("IPv6-only peer should be valid");

        assert_eq!(metadata.id, uuid);
        assert_ne!(ipv6_address().address, "127.0.0.1");
    }

    #[test]
    fn removal_uses_fullname_to_peer_correlation() {
        let peer_id = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        let fullname = "f47ac10b-58cc-4372-a567-0e02b2c3d479 (2)._dropflow._tcp.local.";
        let mut registry = PeerRegistry::default();

        let update = registry.upsert(fullname.to_string(), valid_device(peer_id));
        assert!(matches!(update.peer, PeerUpsert::Added(_)));
        assert!(
            matches!(registry.remove_service(fullname), ServiceRemoval::Removed(id) if id == peer_id)
        );
        assert!(registry.snapshot().is_empty());
    }
}

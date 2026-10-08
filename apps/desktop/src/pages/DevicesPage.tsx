import React, { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { Clock, Folder, HardDrive, Link2, Monitor, Pencil, Plus, RefreshCw, Search, Send, Settings, Unlink, Wifi, X } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { Device } from "../types/device";
import { deviceArt, deviceKindLabel, platformLabel } from "../ui/artwork";
import desktopArt from "../assets/pack/desktop.png";
import laptopArt from "../assets/pack/laptop.png";
import phoneArt from "../assets/pack/phone.png";
import { useDiskUsage } from "../ui/useDisk";
import { formatBytes } from "../utils/formatters";

function peerIp(device: Device): string {
  const match = device.addresses.find((item) => item.address.includes("."));
  return match?.address || device.addresses[0]?.address || "—";
}

function osLine(name: string, type: string, local: boolean): string {
  if (local) return platformLabel();
  const blob = `${name} ${type}`.toLowerCase();
  if (blob.includes("mac") || blob.includes("iphone") || blob.includes("ipad")) return blob.includes("iphone") ? "iPhone" : blob.includes("ipad") ? "iPad" : "macOS";
  if (blob.includes("android") || type === "phone") return "Android";
  if (blob.includes("linux")) return "Linux";
  if (platformLabel() === "Windows" && (type === "desktop" || type === "laptop")) return "Windows";
  return deviceKindLabel(type);
}

export const DevicesPage: React.FC = () => {
  const { settings, updateSetting, addTrustedDevice, removeTrustedDevice } = useSettings();
  const flow = useDropFlow();
  const disk = useDiskUsage();
  const [query, setQuery] = useState("");
  const [pickedId, setPickedId] = useState("this");
  const [hidden, setHidden] = useState<string[]>([]);
  const [banner, setBanner] = useState(true);
  const [editing, setEditing] = useState(false);
  const [localIp, setLocalIp] = useState("");

  useEffect(() => {
    invoke<{ localIp: string }>("get_user_folders").then((folders) => setLocalIp(folders.localIp)).catch(() => undefined);
  }, []);

  const peers = useMemo(() => {
    const q = query.trim().toLowerCase();
    return flow.devices.filter((device) => {
      if (hidden.includes(device.id)) return false;
      if (!q) return true;
      return device.name.toLowerCase().includes(q) || deviceKindLabel(device.type).toLowerCase().includes(q);
    });
  }, [flow.devices, hidden, query]);

  const showThis = !query.trim() || (settings.deviceName || "This device").toLowerCase().includes(query.trim().toLowerCase());
  const count = peers.length + (showThis ? 1 : 0);
  const pickedPeer = flow.devices.find((device) => device.id === pickedId);
  const viewingThis = pickedId === "this" || !pickedPeer;
  const trusted = pickedPeer ? settings.trustedDevices.some((device) => device.deviceId === pickedPeer.id) : false;
  const used = disk ? disk.totalBytes - disk.freeBytes : 0;

  const trustPeer = async (device: Device, next: boolean) => {
    if (next) {
      if (!settings.autoAcceptTrustedDevices) updateSetting("autoAcceptTrustedDevices", true);
      await addTrustedDevice({
        deviceId: device.id,
        deviceName: device.name,
        firstSeen: device.lastSeen || "Just now",
        lastSeen: device.lastSeen || "Just now",
        platform: deviceKindLabel(device.type),
      });
    } else {
      await removeTrustedDevice(device.id);
    }
  };

  const refresh = () => {
    setHidden([]);
    flow.retryDiscovery();
  };

  return (
    <div className="df-page df-devices">
      <header className="df-page-bar">
        <div>
          <h1>Devices</h1>
          <p>Manage and connect your devices</p>
        </div>
        <div className="df-page-tools">
          <label className="df-search">
            <Search size={15} />
            <input value={query} placeholder="Search devices..." onChange={(event) => setQuery(event.target.value)} />
          </label>
          <button type="button" className="df-btn df-btn-primary" onClick={refresh}><Plus size={16} /> Add Device</button>
        </div>
      </header>

      {banner && (
        <section className="df-banner">
          <div className="df-banner-art">
            <img src={laptopArt} alt="" />
            <img src={phoneArt} alt="" />
            <img src={desktopArt} alt="" />
          </div>
          <div>
            <strong>Transfer files across all your devices</strong>
            <p>Keep your devices connected to quickly send and receive files, manage storage, and access your files anywhere.</p>
          </div>
          <button type="button" className="df-quiet" aria-label="Dismiss" onClick={() => setBanner(false)}><X size={16} /></button>
        </section>
      )}

      <div className="df-devices-grid">
        <section className="df-card df-device-board">
          <div className="df-card-head">
            <h2>My Devices ({count})</h2>
            <button type="button" className="df-link" onClick={refresh}><RefreshCw size={14} /> Refresh</button>
          </div>
          {flow.discovery === "error" && flow.devices.length === 0 && (
            <p className="df-kicker">Couldn't look for devices. Check Wi-Fi, then refresh.</p>
          )}
          {flow.discovery === "searching" && flow.devices.length === 0 && <p className="df-kicker">Looking for devices on this network.</p>}
          {flow.discovery === "empty" && <p className="df-kicker">Open DropFlow on the other device. Both need the same Wi-Fi.</p>}
          <div className="df-device-cards">
            {showThis && (
              <button type="button" className={viewingThis ? "df-dev-card selected" : "df-dev-card"} onClick={() => setPickedId("this")}>
                <img src={deviceArt(flow.deviceType)} alt="" />
                <div className="df-dev-name">
                  <b>{settings.deviceName || "This device"}</b>
                  <Pencil size={13} onClick={(event) => { event.stopPropagation(); setPickedId("this"); setEditing(true); }} />
                </div>
                <div className="df-dev-status"><i className="df-dot" /> Online <span className="df-pill">This device</span></div>
                <div className="df-dev-meta"><Monitor size={13} /> {osLine(settings.deviceName, flow.deviceType, true)}</div>
                <div className="df-dev-meta"><HardDrive size={13} /> {deviceKindLabel(flow.deviceType)}</div>
                {disk && <div className="df-dev-meta"><HardDrive size={13} /> {formatBytes(disk.totalBytes)} ({formatBytes(used)} used)</div>}
                <span className="df-btn df-btn-soft df-btn-compact" onClick={(event) => { event.stopPropagation(); flow.openDeviceFiles("this"); }}><Folder size={14} /> View Files</span>
              </button>
            )}
            {peers.map((device) => (
              <button key={device.id} type="button" className={pickedId === device.id ? "df-dev-card selected" : "df-dev-card"} onClick={() => setPickedId(device.id)}>
                <img src={deviceArt(device.type)} alt="" />
                <div className="df-dev-name"><b>{device.name}</b></div>
                <div className="df-dev-status"><i className="df-dot" /> Online</div>
                <div className="df-dev-meta"><Monitor size={13} /> {osLine(device.name, device.type, false)}</div>
                <div className="df-dev-meta"><HardDrive size={13} /> {deviceKindLabel(device.type)}</div>
                <span className="df-btn df-btn-soft df-btn-compact" onClick={(event) => { event.stopPropagation(); flow.openDeviceFiles(device.id); }}><Folder size={14} /> View Files</span>
              </button>
            ))}
          </div>
        </section>

        <aside className="df-card df-device-side">
          <button type="button" className="df-side-x" aria-label="Close details" onClick={() => setPickedId("this")}><X size={16} /></button>
          {viewingThis ? (
            <DeviceSide
              art={deviceArt(flow.deviceType)}
              name={settings.deviceName || "This device"}
              online="Online"
              rows={[
                ["Device name", settings.deviceName || "This device"],
                ["Platform", osLine(settings.deviceName, flow.deviceType, true)],
                ["Model", deviceKindLabel(flow.deviceType)],
                ["Storage", disk ? `${formatBytes(disk.totalBytes)} (${formatBytes(used)} used)` : "—"],
              ]}
              connection={[
                ["Status", "Online"],
                ["Local network IP", localIp || "—"],
                ["Last seen", "Just now"],
              ]}
              storageUsed={disk ? used / disk.totalBytes : 0}
              editing={editing}
              draftName={settings.deviceName}
              onDraft={(value) => { updateSetting("deviceName", value); updateSetting("deviceNameMode", "custom"); }}
              onEdit={() => setEditing(true)}
              autoAccept={!settings.requireConfirmation}
              onAutoAccept={() => updateSetting("requireConfirmation", !settings.requireConfirmation)}
              onSend={() => flow.setPage("home")}
              onFiles={() => flow.openDeviceFiles("this")}
              onManage={() => setEditing(true)}
              onDisconnect={() => updateSetting("deviceVisibility", false)}
            />
          ) : pickedPeer && (
            <DeviceSide
              art={deviceArt(pickedPeer.type)}
              name={pickedPeer.name}
              online="Online"
              rows={[
                ["Device name", pickedPeer.name],
                ["Platform", osLine(pickedPeer.name, pickedPeer.type, false)],
                ["Model", deviceKindLabel(pickedPeer.type)],
                ["Storage", "Not shared by this device"],
              ]}
              connection={[
                ["Status", "Online"],
                ["Local network IP", peerIp(pickedPeer)],
                ["Last seen", pickedPeer.lastSeen || "Just now"],
              ]}
              autoAccept={trusted && settings.autoAcceptTrustedDevices}
              onAutoAccept={() => trustPeer(pickedPeer, !(trusted && settings.autoAcceptTrustedDevices))}
              onSend={() => { flow.selectDevice(pickedPeer.id); flow.setPage("home"); }}
              onFiles={() => flow.openDeviceFiles(pickedPeer.id)}
              onManage={() => flow.openDeviceFiles(pickedPeer.id)}
              onDisconnect={() => { setHidden((prev) => [...prev, pickedPeer.id]); setPickedId("this"); }}
            />
          )}
        </aside>
      </div>
    </div>
  );
};

function DeviceSide({
  art, name, online, rows, connection, storageUsed, editing, draftName, onDraft, onEdit, autoAccept, onAutoAccept, onSend, onFiles, onManage, onDisconnect,
}: {
  art: string;
  name: string;
  online: string;
  rows: [string, string][];
  connection: [string, string][];
  storageUsed?: number;
  editing?: boolean;
  draftName?: string;
  onDraft?: (value: string) => void;
  onEdit?: () => void;
  autoAccept: boolean;
  onAutoAccept: () => void;
  onSend: () => void;
  onFiles: () => void;
  onManage: () => void;
  onDisconnect: () => void;
}) {
  const icons = [Monitor, Monitor, HardDrive, HardDrive];
  const connIcons = [Wifi, Wifi, Clock];
  return (
    <>
      <div className="df-side-hero">
        <img src={art} alt="" />
        <div>
          <strong>{name}</strong>
          <span className="df-status"><i className="df-dot" /> {online}</span>
        </div>
      </div>
      <div className="df-side-actions">
        <button type="button" onClick={onSend}><Send size={16} /><span>Send Files</span></button>
        <button type="button" onClick={onFiles}><Folder size={16} /><span>View Files</span></button>
        <button type="button" onClick={onManage}><Settings size={16} /><span>Manage</span></button>
        <button type="button" onClick={onDisconnect}><Unlink size={16} /><span>Disconnect</span></button>
      </div>
      <div className="df-side-block">
        <div className="df-card-head"><h3>Device Information</h3>{onEdit && <button type="button" className="df-link" onClick={onEdit}>Edit</button>}</div>
        {editing && onDraft ? (
          <input className="df-input" value={draftName || ""} onChange={(event) => onDraft(event.target.value)} />
        ) : null}
        {rows.map(([label, value], index) => {
          const Icon = icons[index] || Monitor;
          return (
            <div key={label} className="df-info-row">
              <span><Icon size={14} /> {label}</span>
              <b>{value}</b>
            </div>
          );
        })}
        {typeof storageUsed === "number" && storageUsed > 0 && <div className="df-storage-track df-side-bar"><span style={{ width: `${Math.min(100, storageUsed * 100)}%` }} /></div>}
      </div>
      <div className="df-side-block">
        <h3>Connection</h3>
        {connection.map(([label, value], index) => {
          const Icon = connIcons[index] || Link2;
          return (
            <div key={label} className="df-info-row">
              <span><Icon size={14} /> {label}</span>
              <b>{label === "Status" ? <><i className="df-dot" /> {value}</> : value}</b>
            </div>
          );
        })}
      </div>
      <div className="df-side-block">
        <h3>Sync Settings</h3>
        <div className="df-info-row">
          <span><Folder size={14} /> Auto-accept files</span>
          <button type="button" className={autoAccept ? "df-switch on" : "df-switch"} role="switch" aria-checked={autoAccept} onClick={onAutoAccept}><i /></button>
        </div>
      </div>
    </>
  );
}

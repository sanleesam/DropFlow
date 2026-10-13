import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import {
  FolderOpen,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Wifi,
} from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { useToast } from "../components/ToastProvider";
import {
  DeviceCard,
  DeviceVisual,
  EmptyState,
  PageHeader,
  SettingRow,
  Switch,
} from "../components/ui/Desktop";
import { DiscoveryState } from "../components/ui/DiscoveryState";
import { deviceKindLabel, platformLabel } from "../ui/artwork";
import { useDiskUsage } from "../ui/useDisk";
import { formatBytes } from "../utils/formatters";

export function DevicesPage() {
  const { settings, updateSetting, addTrustedDevice, removeTrustedDevice } =
    useSettings();
  const flow = useDropFlow();
  const { addToast } = useToast();
  const disk = useDiskUsage();
  const [query, setQuery] = useState("");
  const [pickedId, setPickedId] = useState("this");
  const [hidden, setHidden] = useState<string[]>([]);
  const [editing, setEditing] = useState(false);
  const [localIp, setLocalIp] = useState("");
  useEffect(() => {
    invoke<{ localIp: string }>("get_user_folders")
      .then((folders) => setLocalIp(folders.localIp))
      .catch(() => undefined);
  }, []);
  const peer = flow.devices.find((device) => device.id === pickedId);
  const local = !peer;
  const q = query.trim().toLowerCase();
  const peers = flow.devices.filter(
    (device) =>
      !hidden.includes(device.id) &&
      `${device.name} ${deviceKindLabel(device.type)}`
        .toLowerCase()
        .includes(q),
  );
  const name = peer?.name || settings.deviceName || "This device";
  const showLocal = `${settings.deviceName} This device ${platformLabel()}`
    .toLowerCase()
    .includes(q);
  const trusted =
    !!peer &&
    settings.trustedDevices.some((device) => device.deviceId === peer.id);
  const refresh = () => {
    setHidden([]);
    flow.retryDiscovery();
  };
  const trustPeer = async () => {
    if (!peer) return;
    try {
      if (trusted && settings.autoAcceptTrustedDevices)
        await removeTrustedDevice(peer.id);
      else {
        if (!settings.autoAcceptTrustedDevices)
          updateSetting("autoAcceptTrustedDevices", true);
        await addTrustedDevice({
          deviceId: peer.id,
          deviceName: peer.name,
          firstSeen: peer.lastSeen || "Just now",
          lastSeen: peer.lastSeen || "Just now",
          platform: deviceKindLabel(peer.type),
        });
      }
    } catch {
      addToast("Couldn't update trust for this device.", "error");
    }
  };

  return (
    <div className="df-page">
      <PageHeader
        title="Devices"
        description="Discover and manage devices on your local network."
      />
      <div className="df-v2-toolbar">
        <span className="df-meta">
          {flow.devices.length} nearby{" "}
          {flow.devices.length === 1 ? "device" : "devices"} · 1 local device
        </span>
        <div className="df-page-tools">
          <label className="df-search">
            <Search size={16} />
            <input
              aria-label="Search devices"
              value={query}
              placeholder="Search devices…"
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="df-btn df-btn-soft"
            onClick={refresh}
          >
            <RefreshCw size={16} />
            Refresh
          </button>
        </div>
      </div>
      <div className="df-v2-workspace">
        <div className="df-v2-stack">
          <div className="df-v2-callout">
            <Wifi size={18} />
            <p>
              Open DropFlow on both devices. Discovery works automatically when
              they share a local network.
            </p>
          </div>
          <section aria-label="Discovered devices">
            <div className="df-v2-devices">
              {showLocal && (
                <DeviceCard
                  name={settings.deviceName || "This device"}
                  type={flow.deviceType}
                  status={settings.deviceVisibility ? "Online" : "Hidden"}
                  local
                  selected={local}
                  footer={platformLabel()}
                  onClick={() => {
                    setPickedId("this");
                    setEditing(false);
                  }}
                />
              )}
              {peers.map((device) => (
                <DeviceCard
                  key={device.id}
                  name={device.name}
                  type={device.type}
                  status="Online"
                  selected={peer?.id === device.id}
                  footer={
                    device.addresses.find(
                      (address) => address.family === "IPv4",
                    )?.address || deviceKindLabel(device.type)
                  }
                  onClick={() => {
                    setPickedId(device.id);
                    setEditing(false);
                  }}
                />
              ))}
              {!q && flow.devices.length === 0 && (
                <DiscoveryState state={flow.discovery} onRetry={refresh} />
              )}
            </div>
            {q && !showLocal && !peers.length && (
              <EmptyState
                icon={<Search size={22} />}
                title="No matching devices"
              >
                Try a different name or device type.
              </EmptyState>
            )}
          </section>
          {hidden.length > 0 && (
            <div className="df-v2-callout">
              <p>
                {hidden.length} hidden from this view. Refresh to show them
                again.
              </p>
              <button type="button" className="df-link" onClick={refresh}>
                Show all
              </button>
            </div>
          )}
        </div>
        <aside className="df-card df-card-pad df-v2-context df-v2-device-details">
          <div className="df-v2-context-overview">
            <h2>Device details</h2>
            <DeviceVisual type={peer?.type || flow.deviceType} large />
            <h3>{name}</h3>
            <span className="df-status">
              <i
                className={`df-dot${local && !settings.deviceVisibility ? " idle" : ""}`}
              />
              {local
                ? settings.deviceVisibility
                  ? "This device · visible"
                  : "This device · hidden"
                : "Online"}
            </span>
          </div>
          <div className="df-v2-context-content">
            <dl>
              <div>
                <dt>Type</dt>
                <dd>{deviceKindLabel(peer?.type || flow.deviceType)}</dd>
              </div>
              {local && (
                <div>
                  <dt>Platform</dt>
                  <dd>{platformLabel()}</dd>
                </div>
              )}
              <div>
                <dt>Local address</dt>
                <dd>
                  {peer
                    ? peer.addresses
                        .map((address) => address.address)
                        .join(", ") || "Not shared"
                    : localIp || "Not available"}
                </dd>
              </div>
              {peer && (
                <>
                  <div>
                    <dt>Version</dt>
                    <dd>{peer.version || "Not shared"}</dd>
                  </div>
                  <div>
                    <dt>Last seen</dt>
                    <dd>{peer.lastSeen || "Just now"}</dd>
                  </div>
                </>
              )}
              {local && disk && (
                <div>
                  <dt>Free storage</dt>
                  <dd>
                    {formatBytes(disk.freeBytes)} of{" "}
                    {formatBytes(disk.totalBytes)}
                  </dd>
                </div>
              )}
            </dl>
            <div className="df-v2-context-actions">
              {!local && (
                <button
                  type="button"
                  className="df-btn df-btn-primary"
                  onClick={() => {
                    flow.selectDevice(peer!.id);
                    flow.setPage("home");
                  }}
                >
                  <Send size={16} />
                  Send files
                </button>
              )}
              <button
                type="button"
                className="df-btn df-btn-soft"
                onClick={() => flow.openDeviceFiles(peer?.id || "this")}
              >
                <FolderOpen size={16} />
                {local ? "Browse local files" : "View transfer files"}
              </button>
              {local && (
                <button
                  type="button"
                  className="df-btn df-btn-soft"
                  onClick={() => setEditing(!editing)}
                >
                  Rename this device
                </button>
              )}
              {editing && local && (
                <label className="df-meta">
                  Device name
                  <input
                    autoFocus
                    aria-label="Device name"
                    className="df-input"
                    value={settings.deviceName}
                    onChange={(event) => {
                      updateSetting("deviceName", event.target.value);
                      updateSetting("deviceNameMode", "custom");
                    }}
                  />
                </label>
              )}
              {local ? (
                <>
                  <SettingRow
                    title="Visible on this network"
                    description="Allow other devices to discover you."
                  >
                    <Switch
                      label="Visible on this network"
                      checked={settings.deviceVisibility}
                      onChange={() =>
                        updateSetting(
                          "deviceVisibility",
                          !settings.deviceVisibility,
                        )
                      }
                    />
                  </SettingRow>
                  <SettingRow
                    title="Ask before accepting"
                    description="Review incoming files before receiving."
                  >
                    <Switch
                      label="Ask before accepting"
                      checked={settings.requireConfirmation}
                      onChange={() =>
                        updateSetting(
                          "requireConfirmation",
                          !settings.requireConfirmation,
                        )
                      }
                    />
                  </SettingRow>
                </>
              ) : (
                <>
                  <SettingRow
                    title="Auto-accept from this device"
                    description={
                      <>
                        <ShieldCheck size={12} /> Only trust devices you
                        recognize.
                      </>
                    }
                  >
                    <Switch
                      label={`Auto-accept from ${name}`}
                      checked={trusted && settings.autoAcceptTrustedDevices}
                      onChange={trustPeer}
                    />
                  </SettingRow>
                  <button
                    type="button"
                    className="df-link"
                    onClick={() => {
                      setHidden((prev) => [...prev, peer!.id]);
                      setPickedId("this");
                    }}
                  >
                    Hide from this view
                  </button>
                </>
              )}
            </div>
          </div>
        </aside>
      </div>
    </div>
  );
}

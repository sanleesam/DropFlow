import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { ArrowUp, EyeOff, FolderOpen, RefreshCw, Search } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { useToast } from "../components/ToastProvider";
import {
  DeviceVisual,
  EmptyState,
  Page,
  SearchField,
  SettingRow,
  Switch,
  ToolGroup,
} from "../components/ui/Desktop";
import { DiscoveryState } from "../components/ui/DiscoveryState";
import { deviceKindLabel, platformLabel } from "../ui/artwork";
import { useDiskUsage } from "../ui/useDisk";
import { formatBytes } from "../utils/formatters";

export function DevicesPage() {
  const { settings, updateSetting, addTrustedDevice, removeTrustedDevice } = useSettings();
  const flow = useDropFlow();
  const { addToast } = useToast();
  const disk = useDiskUsage();
  const [query, setQuery] = useState("");
  const [pickedId, setPickedId] = useState("this");
  const [hidden, setHidden] = useState<string[]>([]);
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
      `${device.name} ${deviceKindLabel(device.type)}`.toLowerCase().includes(q),
  );
  const localName = settings.deviceName || "This device";
  const name = peer?.name || localName;
  const showLocal = `${settings.deviceName} This device ${platformLabel()}`.toLowerCase().includes(q);
  const trusted = !!peer && settings.trustedDevices.some((device) => device.deviceId === peer.id);
  const refresh = () => {
    setHidden([]);
    flow.retryDiscovery();
  };
  const trustPeer = async () => {
    if (!peer) return;
    try {
      if (trusted) await removeTrustedDevice(peer.id);
      else
        await addTrustedDevice({
          deviceId: peer.id,
          deviceName: peer.name,
          firstSeen: peer.lastSeen || "Just now",
          lastSeen: peer.lastSeen || "Just now",
          platform: deviceKindLabel(peer.type),
        });
    } catch {
      addToast("Couldn't remember this device.", "error");
    }
  };
  const ipv4 = (addresses: { address: string; family: string }[]) =>
    addresses.find((address) => address.family.toLowerCase() === "ipv4")?.address;

  return (
    <Page
      title="Devices"
      toolbar={
        <>
          <SearchField value={query} onChange={setQuery} label="Search devices" />
          <ToolGroup label="Discovery">
            <button
              type="button"
              className="tool-btn"
              aria-label="Refresh"
              title="Refresh"
              onClick={refresh}
            >
              <RefreshCw size={15} strokeWidth={1.9} />
            </button>
          </ToolGroup>
        </>
      }
    >
      <div className="split">
        <div>
          {showLocal && (
            <section className="section" aria-label="This device">
              <div className="card list">
                <button
                  type="button"
                  className="row hit has-leading"
                  aria-selected={local}
                  onClick={() => setPickedId("this")}
                >
                  <DeviceVisual type={flow.deviceType} medium local />
                  <div className="grow">
                    <div className="row-title ellipsis">{localName}</div>
                    <div className="row-sub">
                      This {deviceKindLabel(flow.deviceType).toLowerCase()} · {platformLabel()}
                    </div>
                  </div>
                  <span className="row-trail">
                    <i className={`dot${settings.deviceVisibility ? "" : " idle"}`} />
                    {settings.deviceVisibility ? "Visible" : "Hidden"}
                  </span>
                </button>
              </div>
            </section>
          )}

          <section className="section" aria-label="Discovered devices">
            <div className="section-head">
              <h2>Nearby</h2>
              <span className="count">{flow.devices.length || ""}</span>
            </div>
            <div className="card list">
              {peers.map((device) => (
                <button
                  key={device.id}
                  type="button"
                  className="row hit has-leading"
                  aria-selected={peer?.id === device.id}
                  onClick={() => setPickedId(device.id)}
                >
                  <DeviceVisual type={device.type} medium />
                  <div className="grow">
                    <div className="row-title ellipsis" title={device.name}>
                      {device.name}
                    </div>
                    <div className="row-sub num">
                      {deviceKindLabel(device.type)}
                      {ipv4(device.addresses) ? ` · ${ipv4(device.addresses)}` : ""}
                    </div>
                  </div>
                  {settings.trustedDevices.some((item) => item.deviceId === device.id) && (
                    <span className="tag">Remembered</span>
                  )}
                </button>
              ))}
              {!q && flow.devices.length === 0 && (
                <div style={{ display: "grid" }}>
                  <DiscoveryState state={flow.discovery} onRetry={refresh} />
                </div>
              )}
              {q && !peers.length && (
                <EmptyState compact icon={<Search size={18} />} title="No matches">
                  Try another name.
                </EmptyState>
              )}
              {!q && flow.devices.length > 0 && !peers.length && (
                <EmptyState compact icon={<EyeOff size={18} />} title="All hidden">
                  Refresh to show them again.
                </EmptyState>
              )}
            </div>
            {hidden.length > 0 && (
              <p className="form-foot">
                {hidden.length} hidden ·{" "}
                <button type="button" className="btn btn-plain" onClick={refresh}>
                  Show all
                </button>
              </p>
            )}
          </section>
        </div>

        <aside className="card inspector" aria-label="Device details">
          <div className="inspector-hero">
            <DeviceVisual type={peer?.type || flow.deviceType} large local={local} />
            <h2>{name}</h2>
            <span className="tag">
              <i className={`dot${local && !settings.deviceVisibility ? " idle" : ""}`} />
              {local ? (settings.deviceVisibility ? "This device · visible" : "This device · hidden") : "Online"}
            </span>
          </div>

          <div className="inspector-actions">
            {!local && (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() => {
                  flow.selectDevice(peer!.id);
                  flow.setPage("home");
                }}
              >
                <ArrowUp size={15} strokeWidth={2.2} />
                Send Files
              </button>
            )}
            <button
              type="button"
              className="btn"
              onClick={() => flow.openDeviceFiles(peer?.id || "this")}
            >
              <FolderOpen size={15} />
              {local ? "Files" : "History"}
            </button>
          </div>

          <dl className="facts">
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
              <dt>Address</dt>
              <dd className="mono">
                {peer
                  ? peer.addresses.map((address) => address.address).join(", ") || "Not shared"
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
                <dt>Available</dt>
                <dd>
                  {formatBytes(disk.freeBytes)} of {formatBytes(disk.totalBytes)}
                </dd>
              </div>
            )}
          </dl>

          {local ? (
            <div>
              <SettingRow title="Name">
                <input
                  aria-label="Device name"
                  className="field inline"
                  value={settings.deviceName}
                  placeholder="This device"
                  onChange={(event) => {
                    updateSetting("deviceName", event.target.value);
                    updateSetting("deviceNameMode", "custom");
                  }}
                />
              </SettingRow>
              <SettingRow title="Visible on this network">
                <Switch
                  label="Visible on this network"
                  checked={settings.deviceVisibility}
                  onChange={() => updateSetting("deviceVisibility", !settings.deviceVisibility)}
                />
              </SettingRow>
              <SettingRow title="Ask before accepting">
                <Switch
                  label="Ask before accepting"
                  checked={settings.requireConfirmation}
                  onChange={() => updateSetting("requireConfirmation", !settings.requireConfirmation)}
                />
              </SettingRow>
            </div>
          ) : (
            <div>
              <SettingRow title="Remember" description="Name only. Approval still applies.">
                <Switch label={`Remember ${name}`} checked={trusted} onChange={trustPeer} />
              </SettingRow>
              <button
                type="button"
                className="btn btn-plain"
                style={{ marginTop: 6 }}
                onClick={() => {
                  setHidden((prev) => [...prev, peer!.id]);
                  setPickedId("this");
                }}
              >
                Hide from list
              </button>
            </div>
          )}
        </aside>
      </div>
    </Page>
  );
}

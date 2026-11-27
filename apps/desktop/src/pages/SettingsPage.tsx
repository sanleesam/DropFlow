import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { ShieldCheck } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { useUpdater } from "../hooks/useUpdater";
import { useDropFlow } from "../session/DropFlowProvider";
import { UpdatesSettings } from "../components/UpdatesSettings";
import { NearbyLinkSettings } from "../components/NearbyLinkSettings";
import {
  EmptyState,
  FormGroup,
  Modal,
  Page,
  Segmented,
  SettingRow,
  Switch,
} from "../components/ui/Desktop";
import { platformLabel } from "../ui/artwork";
import { BrandMark } from "../components/ui/BrandMark";
import {
  getGlassPreference,
  setGlassPreference,
  useGlassMode,
  type GlassPreference,
} from "../ui/glass/LiquidGlass";

const TABS = [
  { id: "general", label: "General" },
  { id: "transfers", label: "Transfers" },
  { id: "devices", label: "Devices" },
  { id: "updates", label: "Updates" },
  { id: "appearance", label: "Appearance" },
] as const;
type Tab = (typeof TABS)[number]["id"];

export function SettingsPage() {
  const { settings, updateSetting, removeTrustedDevice } = useSettings();
  const { addToast } = useToast();
  const flow = useDropFlow();
  const updater = useUpdater();
  const glassMode = useGlassMode();
  const [glassPref, setGlassPref] = useState<GlassPreference>(getGlassPreference);
  const [tab, setTab] = useState<Tab>("general");
  const [release, setRelease] = useState<{ display_version?: string; channel?: string } | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [loadingVersion, setLoadingVersion] = useState(true);
  // What this device can do for a direct link; absent on builds without it.
  const [link, setLink] = useState<{ role: "host" | "join" | "none"; ready: boolean; detail: string } | null>(null);
  useEffect(() => {
    invoke<{ role: "host" | "join" | "none"; ready: boolean; detail: string }>("direct_link_status")
      .then((status) => setLink(status && typeof status.role === "string" ? status : null))
      .catch(() => setLink(null));
  }, []);
  useEffect(() => {
    invoke<{ display_version: string; channel: string }>("get_release_info")
      .then(setRelease)
      .catch(() => undefined)
      .finally(() => setLoadingVersion(false));
  }, []);
  const version =
    release?.display_version ||
    (updater.state.lastCheckedAt ? updater.state.versionInfo.currentVersion : undefined) ||
    (loadingVersion ? "Loading…" : "Not available");
  const browseFolder = async () => {
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        defaultPath: settings.receiveDirectory || undefined,
      });
      if (typeof selected === "string") updateSetting("receiveDirectory", selected);
    } catch {
      addToast("Couldn't choose a folder.", "error");
    }
  };
  const clearHistory = async () => {
    setClearing(true);
    try {
      await invoke("clear_history");
      setConfirmClear(false);
      addToast("Transfer history cleared.", "success");
    } catch {
      addToast("Couldn't clear transfer history.", "error");
    } finally {
      setClearing(false);
    }
  };
  const chooseGlass = (next: GlassPreference) => {
    setGlassPref(next);
    setGlassPreference(next);
  };

  return (
    <Page
      title="Settings"
      toolbar={<Segmented label="Settings sections" options={[...TABS]} value={tab} onChange={setTab} />}
    >
      <div className="settings">
        {tab === "general" && (
          <>
            <FormGroup>
              <div className="about">
                <BrandMark className="about-mark" />
                <div className="grow">
                  <h3>DropFlow</h3>
                  <p className="footnote num">
                    {version}
                    {release?.channel ? ` · ${release.channel}` : ""} · {platformLabel()}
                  </p>
                </div>
              </div>
            </FormGroup>
            <FormGroup title="This Device" footer="Shown to other devices on your network.">
              <SettingRow title="Name">
                <input
                  id="settings-name"
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
            </FormGroup>
            {import.meta.env.VITE_DROPFLOW_NEARBY_LAB === "1" && <NearbyLinkSettings />}
          </>
        )}

        {tab === "transfers" && (
          <>
            <FormGroup title="Receiving">
              <SettingRow
                title="Save to"
                description={<span title={settings.receiveDirectory}>{settings.receiveDirectory || "Downloads/DropFlow"}</span>}
              >
                <div style={{ display: "flex", gap: 6 }}>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() =>
                      invoke<string>("get_receive_dir")
                        .then((path) => invoke("open_received_file", { path }))
                        .catch(() => addToast("Couldn't open the receive folder.", "error"))
                    }
                  >
                    Open
                  </button>
                  <button type="button" className="btn btn-sm" onClick={browseFolder}>
                    Change…
                  </button>
                </div>
              </SettingRow>
              <SettingRow title="Show folder when done" description="Files never open on their own.">
                <Switch
                  label="Open received folder"
                  checked={settings.autoOpenCompleted}
                  onChange={() => updateSetting("autoOpenCompleted", !settings.autoOpenCompleted)}
                />
              </SettingRow>
            </FormGroup>
            <FormGroup title="Speed" footer="Sends many files without pausing between them, and continues after a dropped connection.">
              <SettingRow title="Faster transfers" description="With devices that support it.">
                <Switch
                  label="Faster transfers"
                  checked={settings.fastTransfer}
                  onChange={() => updateSetting("fastTransfer", !settings.fastTransfer)}
                />
              </SettingRow>
            </FormGroup>
            {link && link.role !== "none" && (
              <FormGroup
                title="Direct Link"
                footer={
                  link.role === "host"
                    ? "Experimental. This PC creates a private Wi-Fi link and the other device joins it, so the router no longer limits the speed."
                    : "Experimental. Works with a Windows PC that has Direct Link on. This Mac has no Wi-Fi internet during the transfer, and a link that fails ends the transfer instead of falling back."
                }
              >
                <SettingRow
                  title="Use a direct link"
                  description={
                    !link.ready
                      ? link.detail || "Not available on this device."
                      : link.role === "host"
                        ? "Needs Faster transfers."
                        : "This Mac leaves its Wi-Fi while a transfer runs, then returns."
                  }
                >
                  <Switch
                    label="Use a direct link"
                    checked={settings.directLink && settings.fastTransfer && link.ready}
                    disabled={!settings.fastTransfer || !link.ready}
                    onChange={() => updateSetting("directLink", !settings.directLink)}
                  />
                </SettingRow>
              </FormGroup>
            )}
            <FormGroup
              title="Security"
              footer={
                settings.requireConfirmation
                  ? "Remembered names aren't verified, so every transfer still asks."
                  : "Off: anyone who can reach DropFlow can send you files."
              }
            >
              <SettingRow title="Ask before accepting">
                <Switch
                  label="Ask before accepting"
                  checked={settings.requireConfirmation}
                  onChange={() => updateSetting("requireConfirmation", !settings.requireConfirmation)}
                />
              </SettingRow>
            </FormGroup>
            <FormGroup title="History" footer="Clearing history keeps your files.">
              <SettingRow title="Saved transfers">
                <span className="secondary num">{flow.sessionStore.recentTransfers.length}</span>
                <button
                  type="button"
                  className="btn btn-sm btn-destructive"
                  disabled={!flow.sessionStore.recentTransfers.length}
                  onClick={() => setConfirmClear(true)}
                >
                  Clear…
                </button>
              </SettingRow>
            </FormGroup>
          </>
        )}

        {tab === "devices" && (
          <FormGroup title="Remembered" footer="For recognition only. Approval still applies.">
            {settings.trustedDevices.length === 0 && (
              <EmptyState compact icon={<ShieldCheck size={18} />} title="None yet">
                Remember a device when you accept a transfer.
              </EmptyState>
            )}
            {settings.trustedDevices.map((device) => (
              <SettingRow key={device.deviceId} title={device.deviceName} description={device.platform || "Device"}>
                <button
                  type="button"
                  className="btn btn-sm"
                  aria-label={`Forget ${device.deviceName}`}
                  onClick={() =>
                    removeTrustedDevice(device.deviceId).catch(() => addToast("Couldn't remove this device.", "error"))
                  }
                >
                  Forget
                </button>
              </SettingRow>
            ))}
          </FormGroup>
        )}

        {tab === "updates" && <UpdatesSettings installedVersion={version} />}

        {tab === "appearance" && (
          <>
            <FormGroup title="Appearance">
              <SettingRow title="Theme">
                <Segmented
                  glass={false}
                  label="Theme"
                  value={settings.themeMode}
                  onChange={(value) => updateSetting("themeMode", value)}
                  options={[
                    { id: "system", label: "Auto" },
                    { id: "light", label: "Light" },
                    { id: "dark", label: "Dark" },
                  ]}
                />
              </SettingRow>
              <SettingRow
                title="Glass"
                description={
                  glassMode === "refract"
                    ? "Refracting"
                    : glassMode === "frost"
                      ? glassPref === "frost"
                        ? "Frosted, no refraction"
                        : "Frosted · this web view can't refract yet"
                      : "Opaque surfaces"
                }
              >
                <Segmented
                  glass={false}
                  label="Glass"
                  value={glassPref}
                  onChange={chooseGlass}
                  options={[
                    { id: "auto", label: "Liquid" },
                    { id: "frost", label: "Frosted" },
                    { id: "solid", label: "Solid" },
                  ]}
                />
              </SettingRow>
            </FormGroup>
            <FormGroup title="Motion" footer="System Reduce Motion is always respected.">
              <SettingRow title="Reduce motion">
                <Switch
                  label="Reduce animations"
                  checked={settings.reduceAnimations}
                  onChange={() => updateSetting("reduceAnimations", !settings.reduceAnimations)}
                />
              </SettingRow>
            </FormGroup>
          </>
        )}
      </div>

      {confirmClear && (
        <Modal title="Clear transfer history" alert onEscape={clearing ? undefined : () => setConfirmClear(false)}>
          <h2>Clear history?</h2>
          <p className="lede">Removes the list on this device. Your files stay.</p>
          <div className="sheet-actions">
            <button type="button" className="btn" disabled={clearing} onClick={() => setConfirmClear(false)}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary"
              style={{ background: "var(--red)" }}
              disabled={clearing}
              onClick={clearHistory}
            >
              {clearing ? "Clearing…" : "Clear"}
            </button>
          </div>
        </Modal>
      )}
    </Page>
  );
}

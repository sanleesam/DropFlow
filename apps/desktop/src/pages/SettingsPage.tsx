import { useEffect, useState, type KeyboardEvent } from "react";
import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import {
  ArrowLeftRight,
  FolderOpen,
  Monitor,
  Palette,
  RefreshCw,
  Settings,
  ShieldCheck,
} from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { useUpdater } from "../hooks/useUpdater";
import { useDropFlow } from "../session/DropFlowProvider";
import { UpdatesSettings } from "../components/UpdatesSettings";
import {
  EmptyState,
  Modal,
  PageHeader,
  SettingRow,
  Switch,
} from "../components/ui/Desktop";
import { platformLabel } from "../ui/artwork";

const TABS = [
  { id: "general", label: "General", icon: Settings },
  { id: "transfers", label: "Transfers", icon: ArrowLeftRight },
  { id: "devices", label: "Devices", icon: Monitor },
  { id: "updates", label: "Updates", icon: RefreshCw },
  { id: "appearance", label: "Appearance", icon: Palette },
] as const;
type Tab = (typeof TABS)[number]["id"];

export function SettingsPage() {
  const { settings, updateSetting, removeTrustedDevice } = useSettings();
  const { addToast } = useToast();
  const flow = useDropFlow();
  const updater = useUpdater();
  const [tab, setTab] = useState<Tab>("general");
  const [release, setRelease] = useState<{
    display_version?: string;
    channel?: string;
  } | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [loadingVersion, setLoadingVersion] = useState(true);
  useEffect(() => {
    invoke<{ display_version: string; channel: string }>("get_release_info")
      .then(setRelease)
      .catch(() => undefined)
      .finally(() => setLoadingVersion(false));
  }, []);
  const version =
    release?.display_version ||
    (updater.state.lastCheckedAt
      ? updater.state.versionInfo.currentVersion
      : undefined) ||
    (loadingVersion ? "Loading…" : "Not available");
  const browseFolder = async () => {
    try {
      const selected = await open({
        directory: true,
        multiple: false,
        defaultPath: settings.receiveDirectory || undefined,
      });
      if (typeof selected === "string")
        updateSetting("receiveDirectory", selected);
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
  const tabKey = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next =
      event.key === "ArrowRight"
        ? (index + 1) % TABS.length
        : event.key === "ArrowLeft"
          ? (index + TABS.length - 1) % TABS.length
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? TABS.length - 1
              : null;
    if (next === null) return;
    event.preventDefault();
    setTab(TABS[next].id);
    event.currentTarget.parentElement
      ?.querySelectorAll<HTMLButtonElement>("button")
      [next].focus();
  };
  return (
    <div className="df-page">
      <PageHeader
        title="Settings"
        description="Make DropFlow fit the way you work."
      />
      <div className="df-v2-workspace">
        <div className="df-v2-stack">
          <div
            className="df-v2-tabs"
            role="tablist"
            aria-label="Settings sections"
          >
            {TABS.map(({ id, label, icon: Icon }, index) => (
              <button
                key={id}
                type="button"
                id={`settings-tab-${id}`}
                role="tab"
                aria-selected={tab === id}
                aria-controls={`settings-panel-${id}`}
                tabIndex={tab === id ? 0 : -1}
                className={tab === id ? "active" : ""}
                onClick={() => setTab(id)}
                onKeyDown={(event) => tabKey(event, index)}
              >
                <Icon size={16} />
                {label}
              </button>
            ))}
          </div>
          <div
            id={`settings-panel-${tab}`}
            role="tabpanel"
            aria-labelledby={`settings-tab-${tab}`}
            className="df-v2-stack"
          >
            {tab === "general" && (
              <section className="df-card df-card-pad">
                <h2 className="df-v2-section-title">This device</h2>
                <p className="df-kicker">
                  Choose how this device appears to others.
                </p>
                <label className="df-v2-name-label" htmlFor="settings-name">
                  Device name
                </label>
                <input
                  id="settings-name"
                  className="df-input"
                  value={settings.deviceName}
                  onChange={(event) => {
                    updateSetting("deviceName", event.target.value);
                    updateSetting("deviceNameMode", "custom");
                  }}
                />
                <SettingRow
                  title="Visible on this network"
                  description="Turn this off to stop advertising DropFlow."
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
              </section>
            )}
            {tab === "transfers" && (
              <section className="df-card df-card-pad">
                <h2 className="df-v2-section-title">Receiving files</h2>
                <SettingRow
                  title="Download folder"
                  description={
                    <span title={settings.receiveDirectory}>
                      {settings.receiveDirectory || "Downloads / DropFlow"}
                    </span>
                  }
                >
                  <button
                    type="button"
                    className="df-btn df-btn-soft df-btn-sm"
                    onClick={browseFolder}
                  >
                    <FolderOpen size={14} />
                    Change
                  </button>
                </SettingRow>
                <SettingRow
                  title="Open finished transfers"
                  description="Open a received file when the transfer completes."
                >
                  <Switch
                    label="Open finished transfers"
                    checked={settings.autoOpenCompleted}
                    onChange={() =>
                      updateSetting(
                        "autoOpenCompleted",
                        !settings.autoOpenCompleted,
                      )
                    }
                  />
                </SettingRow>
                <SettingRow
                  title="Ask before accepting"
                  description="Review incoming files before receiving them."
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
                <SettingRow
                  title="Auto-accept trusted devices"
                  description="Skip the incoming prompt for devices you have trusted."
                >
                  <Switch
                    label="Auto-accept trusted devices"
                    checked={settings.autoAcceptTrustedDevices}
                    onChange={() =>
                      updateSetting(
                        "autoAcceptTrustedDevices",
                        !settings.autoAcceptTrustedDevices,
                      )
                    }
                  />
                </SettingRow>
                <SettingRow
                  title="Transfer history"
                  description={`${flow.sessionStore.recentTransfers.length} saved transfers. Clearing history does not delete your files.`}
                >
                  <button
                    type="button"
                    className="df-btn df-btn-danger df-btn-sm"
                    disabled={!flow.sessionStore.recentTransfers.length}
                    onClick={() => setConfirmClear(true)}
                  >
                    Clear history
                  </button>
                </SettingRow>
              </section>
            )}
            {tab === "devices" && (
              <section className="df-card df-card-pad">
                <h2 className="df-v2-section-title">Trusted devices</h2>
                <p className="df-kicker">
                  Manage devices you have allowed to send files without a
                  prompt.
                </p>
                {settings.trustedDevices.length === 0 && (
                  <EmptyState
                    compact
                    icon={<ShieldCheck size={22} />}
                    title="No trusted devices yet"
                  >
                    Trust a device when accepting a transfer, or from its device
                    details.
                  </EmptyState>
                )}
                {settings.trustedDevices.map((device) => (
                  <div key={device.deviceId} className="df-list-row">
                    <ShieldCheck size={18} />
                    <div className="df-grow">
                      <strong>{device.deviceName}</strong>
                      <p className="df-meta">{device.platform || "Device"}</p>
                    </div>
                    <button
                      type="button"
                      className="df-btn df-btn-soft df-btn-sm"
                      aria-label={`Remove trust for ${device.deviceName}`}
                      onClick={() =>
                        removeTrustedDevice(device.deviceId).catch(() =>
                          addToast("Couldn't remove this device.", "error"),
                        )
                      }
                    >
                      Remove
                    </button>
                  </div>
                ))}
              </section>
            )}
            {tab === "updates" && (
              <UpdatesSettings installedVersion={version} />
            )}
            {tab === "appearance" && (
              <section className="df-card df-card-pad">
                <h2 className="df-v2-section-title">Appearance & motion</h2>
                <SettingRow
                  title="Dark theme"
                  description="Use the dark navy color scheme. Your preference is saved."
                >
                  <Switch
                    label="Dark theme"
                    checked={settings.darkTheme}
                    onChange={() =>
                      updateSetting("darkTheme", !settings.darkTheme)
                    }
                  />
                </SettingRow>
                <SettingRow
                  title="Reduce animations"
                  description="Minimize interface motion. System reduced-motion preferences are always respected."
                >
                  <Switch
                    label="Reduce animations"
                    checked={settings.reduceAnimations}
                    onChange={() =>
                      updateSetting(
                        "reduceAnimations",
                        !settings.reduceAnimations,
                      )
                    }
                  />
                </SettingRow>
              </section>
            )}
          </div>
        </div>
        <aside className="df-v2-stack df-v2-rail">
          <section className="df-card df-card-pad df-v2-context">
            <h2>About DropFlow</h2>
            <div className="df-v2-about-mark">
              <ArrowLeftRight size={28} />
            </div>
            <h3>DropFlow</h3>
            <p className="df-v2-installed">
              Send files. Simply.
              <br />
              Version {version}
              {release?.channel ? ` · ${release.channel}` : ""}
            </p>
            <p className="df-v2-installed">
              A local-first file transfer application for your nearby devices.
            </p>
            <dl>
              <div>
                <dt>Platform</dt>
                <dd>{platformLabel()}</dd>
              </div>
              <div>
                <dt>Update channel</dt>
                <dd>
                  {settings.updateChannel === "beta" ? "Beta" : "Development"}
                </dd>
              </div>
              <div>
                <dt>Device name</dt>
                <dd>{settings.deviceName || "This device"}</dd>
              </div>
            </dl>
          </section>
          <section className="df-card df-card-pad df-v2-note">
            <ShieldCheck size={20} />
            <p>
              Your files travel directly between devices. Your download folder,
              trust choices, and appearance settings stay on this device.
            </p>
          </section>
        </aside>
      </div>
      {confirmClear && (
        <Modal
          title="Clear transfer history"
          alert
          onEscape={clearing ? undefined : () => setConfirmClear(false)}
        >
          <h2 className="df-section-title">Clear transfer history?</h2>
          <p className="df-kicker">
            This removes the saved list on this device. Your files and settings
            stay.
          </p>
          <div className="df-v2-dialog-actions">
            <button
              type="button"
              className="df-btn df-btn-soft"
              disabled={clearing}
              onClick={() => setConfirmClear(false)}
            >
              Cancel
            </button>
            <button
              type="button"
              className="df-btn df-btn-danger"
              disabled={clearing}
              onClick={clearHistory}
            >
              {clearing ? "Clearing…" : "Clear history"}
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

import React, { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { useUpdater } from "../hooks/useUpdater";
import { useDropFlow } from "../session/DropFlowProvider";
import { UpdateChannel } from "../types/updater";
import { formatBytes } from "../utils/formatters";

const CHANNELS: { id: UpdateChannel; label: string; description: string }[] = [
  { id: "beta", label: "Beta", description: "Curated milestone builds." },
  { id: "development", label: "Development", description: "Newer experimental builds. These can be unstable." },
];

export const SettingsPage: React.FC = () => {
  const { settings, updateSetting, removeTrustedDevice } = useSettings();
  const { addToast } = useToast();
  const flow = useDropFlow();
  const updater = useUpdater();
  const [release, setRelease] = useState<{ display_version?: string; channel?: string } | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);

  useEffect(() => {
    invoke<{ display_version: string; channel: string }>("get_release_info").then(setRelease).catch(() => undefined);
  }, []);

  const browseFolder = async () => {
    try {
      const selected = await open({ directory: true, multiple: false, defaultPath: settings.receiveDirectory || undefined });
      if (typeof selected === "string") updateSetting("receiveDirectory", selected);
    } catch (err) {
      console.error(err);
      addToast("Couldn't choose a folder.", "error");
    }
  };

  const clearHistory = async () => {
    try {
      await invoke("clear_history");
      setConfirmClear(false);
      addToast("Transfer history cleared.", "success");
    } catch (err) {
      console.error(err);
      addToast("Couldn't clear transfer history.", "error");
    }
  };

  const version = release?.display_version || updater.state.versionInfo.currentVersion;
  const status = updater.state.status;
  const busy = status === "checking" || status === "downloading" || status === "installing";

  return (
    <div className="df-page">
      <header>
        <h1 className="df-title">Settings</h1>
        <p className="df-subtitle">How this device appears, where files are saved, and updates.</p>
      </header>
      <div className="df-split">
        <section className="df-card df-card-pad">
          <h2 className="df-section-title">This device</h2>
          <label className="df-meta" htmlFor="settings-name">Name other devices see</label>
          <input id="settings-name" className="df-input" style={{ marginTop: 6 }} value={settings.deviceName} onChange={(event) => { updateSetting("deviceName", event.target.value); updateSetting("deviceNameMode", "custom"); }} />
          <div className="df-setting">
            <div><h3>Visible on this network</h3><p>Turn this off to stop advertising DropFlow.</p></div>
            <button type="button" role="switch" aria-checked={settings.deviceVisibility} className={settings.deviceVisibility ? "df-switch on" : "df-switch"} onClick={() => updateSetting("deviceVisibility", !settings.deviceVisibility)}><i /></button>
          </div>
          <div className="df-setting">
            <div><h3>Download folder</h3><p className="df-ellipsis">{settings.receiveDirectory || "Downloads / DropFlow"}</p></div>
            <button type="button" className="df-btn df-btn-ghost df-btn-sm" onClick={browseFolder}>Change</button>
          </div>
          <div className="df-setting">
            <div><h3>Open finished transfers</h3><p>Open a received file when the transfer completes.</p></div>
            <button type="button" role="switch" aria-checked={settings.autoOpenCompleted} className={settings.autoOpenCompleted ? "df-switch on" : "df-switch"} onClick={() => updateSetting("autoOpenCompleted", !settings.autoOpenCompleted)}><i /></button>
          </div>
          <div className="df-setting">
            <div><h3>Ask before accepting</h3><p>Show a prompt for incoming files.</p></div>
            <button type="button" role="switch" aria-checked={settings.requireConfirmation} className={settings.requireConfirmation ? "df-switch on" : "df-switch"} onClick={() => updateSetting("requireConfirmation", !settings.requireConfirmation)}><i /></button>
          </div>
          <div className="df-setting">
            <div><h3>Auto-accept trusted devices</h3><p>Skip the prompt for devices you have trusted.</p></div>
            <button type="button" role="switch" aria-checked={settings.autoAcceptTrustedDevices} className={settings.autoAcceptTrustedDevices ? "df-switch on" : "df-switch"} onClick={() => updateSetting("autoAcceptTrustedDevices", !settings.autoAcceptTrustedDevices)}><i /></button>
          </div>
          <div className="df-setting">
            <div><h3>Dark theme</h3><p>Use the dark color scheme. The moon in the corner does the same thing.</p></div>
            <button type="button" role="switch" aria-checked={settings.darkTheme} className={settings.darkTheme ? "df-switch on" : "df-switch"} onClick={() => updateSetting("darkTheme", !settings.darkTheme)}><i /></button>
          </div>
          <div className="df-setting">
            <div><h3>Reduce animations</h3><p>Minimize motion in the interface.</p></div>
            <button type="button" role="switch" aria-checked={settings.reduceAnimations} className={settings.reduceAnimations ? "df-switch on" : "df-switch"} onClick={() => updateSetting("reduceAnimations", !settings.reduceAnimations)}><i /></button>
          </div>
          <div className="df-setting">
            <div>
              <h3>Transfer history</h3>
              <p>{flow.sessionStore.recentTransfers.length} saved {flow.sessionStore.recentTransfers.length === 1 ? "transfer" : "transfers"}. This does not delete the files.</p>
            </div>
            <button type="button" className="df-btn df-btn-danger df-btn-sm" disabled={flow.sessionStore.recentTransfers.length === 0} onClick={() => setConfirmClear(true)}>Clear</button>
          </div>
        </section>

        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          <section className="df-card df-card-pad">
            <h2 className="df-section-title">Trusted devices</h2>
            {settings.trustedDevices.length === 0 && <p className="df-kicker">Trust a device from an incoming transfer, or from its device page.</p>}
            {settings.trustedDevices.map((device) => (
              <div key={device.deviceId} className="df-list-row">
                <div className="df-grow">
                  <strong>{device.deviceName}</strong>
                  <div className="df-meta">{device.platform || "Device"}</div>
                </div>
                <button type="button" className="df-btn df-btn-ghost df-btn-sm" onClick={() => removeTrustedDevice(device.deviceId)}>Remove</button>
              </div>
            ))}
          </section>
          <section className="df-card df-card-pad">
            <h2 className="df-section-title">Updates</h2>
            <p className="df-kicker">DropFlow {version} · {release?.channel || settings.updateChannel}</p>
            <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 12 }}>
              {CHANNELS.map((channel) => (
                <label key={channel.id} className="df-file-row" style={{ border: "1px solid var(--df-border)", cursor: "pointer" }}>
                  <input type="radio" name="channel" checked={settings.updateChannel === channel.id} onChange={() => updateSetting("updateChannel", channel.id)} />
                  <div>
                    <strong>{channel.label}</strong>
                    <div className="df-meta">{channel.description}</div>
                  </div>
                </label>
              ))}
            </div>
            <p className="df-kicker" style={{ marginTop: 12 }}>
              {status === "update-available" && `Version ${updater.state.versionInfo.availableVersion} is ready to download.`}
              {status === "no-update" && "You're on the latest build for this channel."}
              {status === "checking" && "Checking for updates…"}
              {status === "downloading" && "Downloading the update…"}
              {status === "installing" && "Installing…"}
              {status === "restart-required" && "Restart DropFlow to finish installing."}
              {status === "error" && "Couldn't check for updates. Try again."}
              {status === "idle" && "Check when you want the next build."}
            </p>
            {updater.state.progress && (status === "downloading" || status === "installing") && (
              <>
                <div className="df-progress" style={{ marginTop: 8 }}><span style={{ width: `${updater.state.progress.percentage}%` }} /></div>
                <p className="df-meta">{formatBytes(updater.state.progress.downloadedBytes)} / {formatBytes(updater.state.progress.totalBytes)}</p>
              </>
            )}
            <div style={{ marginTop: 12 }}>
              {status === "update-available" && <button type="button" className="df-btn df-btn-primary" onClick={updater.downloadUpdate}>Download update</button>}
              {status === "restart-required" && <button type="button" className="df-btn df-btn-primary" onClick={updater.restartApplication}>Restart and install</button>}
              {status !== "update-available" && status !== "restart-required" && (
                <button type="button" className="df-btn df-btn-ghost" disabled={busy} onClick={updater.checkForUpdates}>{status === "checking" ? "Checking…" : "Check for updates"}</button>
              )}
            </div>
          </section>
        </div>
      </div>
      {confirmClear && (
        <div className="df-modal-back">
          <div className="df-dialog" role="alertdialog" aria-label="Clear transfer history">
            <h2 className="df-section-title">Clear transfer history?</h2>
            <p className="df-kicker">This removes the list on this device. Your files and settings stay.</p>
            <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 16 }}>
              <button type="button" className="df-btn df-btn-ghost" onClick={() => setConfirmClear(false)}>Cancel</button>
              <button type="button" className="df-btn df-btn-danger" onClick={clearHistory}>Clear history</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

import { AlertCircle, Download, RefreshCw } from "lucide-react";
import { useSettings } from "./SettingsProvider";
import { useUpdater } from "../hooks/useUpdater";
import { FormGroup, Progress, SettingRow } from "./ui/Desktop";
import { formatBytes } from "../utils/formatters";

const CHANNELS = [
  { id: "beta", title: "Beta", detail: "Milestone builds. Recommended." },
  { id: "development", title: "Development", detail: "Newest builds. May be unfinished." },
] as const;

export function UpdatesSettings({ installedVersion }: { installedVersion: string }) {
  const { settings, updateSetting } = useSettings();
  const updater = useUpdater();
  const { status, progress, versionInfo, lastCheckedAt, error } = updater.state;
  const busy = ["checking", "downloading", "installing"].includes(status);
  const messages = {
    idle: "Not checked yet",
    checking: "Checking…",
    "no-update": "Up to date",
    "update-available": `${versionInfo.availableVersion || "Update"} available`,
    downloading: "Downloading…",
    installing: "Installing…",
    "restart-required": "Restart to finish",
    error: "Update failed",
  };
  return (
    <>
      <FormGroup
        title="Software Update"
        footer={lastCheckedAt ? `Last checked ${new Date(lastCheckedAt).toLocaleString()}` : "Updates only install when you ask."}
      >
        <SettingRow title={installedVersion} description={<span role="status">{messages[status]}</span>}>
          {status === "update-available" ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={updater.downloadUpdate}>
              <Download size={13} />
              Download
            </button>
          ) : status === "restart-required" ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={updater.restartApplication}>
              Restart
            </button>
          ) : (
            <button type="button" className="btn btn-sm" disabled={busy} onClick={updater.checkForUpdates}>
              <RefreshCw size={12} />
              {status === "checking" ? "Checking…" : "Check Now"}
            </button>
          )}
        </SettingRow>
        {progress && ["downloading", "installing"].includes(status) && (
          <div className="form-row" style={{ display: "block" }}>
            <Progress value={progress.percentage} label="Update download" />
            <p className="footnote num" style={{ marginTop: 6 }}>
              {formatBytes(progress.downloadedBytes)}
              {progress.totalBytes > 0
                ? ` of ${formatBytes(progress.totalBytes)} · ${Math.round(progress.percentage)}%`
                : ""}
            </p>
          </div>
        )}
        {status === "error" && error && (
          <div className="form-row" style={{ color: "var(--red)" }}>
            <AlertCircle size={15} />
            <p className="grow" style={{ color: "inherit", marginTop: 0 }}>
              {error}
            </p>
          </div>
        )}
        {versionInfo.releaseNotes && status === "update-available" && (
          <details className="notes form-row" style={{ display: "block" }}>
            <summary>What's new</summary>
            <pre>{versionInfo.releaseNotes}</pre>
          </details>
        )}
      </FormGroup>

      <FormGroup title="Channel">
        <fieldset style={{ border: 0, margin: 0, padding: 0 }}>
          <legend className="sr-only">Update channel</legend>
          {CHANNELS.map((channel) => (
            <label key={channel.id} className="channel">
              <input
                type="radio"
                name="update-channel"
                value={channel.id}
                checked={settings.updateChannel === channel.id}
                disabled={busy}
                onChange={() => updateSetting("updateChannel", channel.id)}
              />
              <span className="radio" aria-hidden="true" />
              <span className="grow">
                <span style={{ display: "block" }}>{channel.title}</span>
                <span className="footnote">{channel.detail}</span>
              </span>
            </label>
          ))}
        </fieldset>
      </FormGroup>
    </>
  );
}

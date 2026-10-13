import {
  Download,
  FlaskConical,
  Code2,
  RefreshCw,
  AlertCircle,
} from "lucide-react";
import { useSettings } from "./SettingsProvider";
import { useUpdater } from "../hooks/useUpdater";
import { Progress } from "./ui/Desktop";
import { formatBytes } from "../utils/formatters";

export function UpdatesSettings({
  installedVersion,
}: {
  installedVersion: string;
}) {
  const { settings, updateSetting } = useSettings();
  const updater = useUpdater();
  const { status, progress, versionInfo, lastCheckedAt, error } = updater.state;
  const busy = ["checking", "downloading", "installing"].includes(status);
  const messages = {
    idle: "Check for a new build when you're ready.",
    checking: "Checking the selected channel…",
    "no-update": "You're on the latest build for this channel.",
    "update-available": `Version ${versionInfo.availableVersion || ""} is available.`,
    downloading: "Downloading your update…",
    installing: "Installing your update…",
    "restart-required": "Your update is ready. Restart DropFlow to finish.",
    error: "Couldn't complete the update. Please try again.",
  };
  return (
    <div className="df-v2-stack">
      <section className="df-card df-card-pad">
        <div className="df-v2-note">
          <Download size={22} />
          <div>
            <h2 className="df-v2-section-title">Application updates</h2>
            <p>
              Choose which builds you receive. Updates are checked and installed
              only when you ask.
            </p>
          </div>
        </div>
        <fieldset className="df-v2-channel-fieldset">
          <legend>Update channel</legend>
          <div className="df-v2-channel-grid">
            <label
              className={`df-v2-channel${settings.updateChannel === "beta" ? " selected" : ""}`}
            >
              <input
                type="radio"
                name="update-channel"
                value="beta"
                checked={settings.updateChannel === "beta"}
                disabled={busy}
                onChange={() => updateSetting("updateChannel", "beta")}
              />
              <FlaskConical size={20} />
              <div>
                <strong>Beta</strong>
                <p>
                  Curated milestone builds.
                  <br />
                  Recommended for regular testing.
                </p>
              </div>
            </label>
            <label
              className={`df-v2-channel${settings.updateChannel === "development" ? " selected" : ""}`}
            >
              <input
                type="radio"
                name="update-channel"
                value="development"
                checked={settings.updateChannel === "development"}
                disabled={busy}
                onChange={() => updateSetting("updateChannel", "development")}
              />
              <Code2 size={20} />
              <div>
                <strong>Development</strong>
                <p>
                  The newest experimental builds.
                  <br />
                  May contain unfinished features or bugs.
                </p>
              </div>
            </label>
          </div>
        </fieldset>
      </section>
      <section className="df-card df-card-pad">
        <h2 className="df-v2-section-title">Current version</h2>
        <div className="df-v2-version-row">
          <div>
            <strong>{installedVersion}</strong>
            <p className="df-meta">
              Selected channel:{" "}
              {settings.updateChannel === "beta" ? "Beta" : "Development"}
            </p>
          </div>
          {status === "no-update" && (
            <span className="df-done">Up to date</span>
          )}
        </div>
        <div className="df-v2-update-status">
          <p role="status">{messages[status]}</p>
          {status === "error" && error && (
            <div className="df-v2-callout error">
              <AlertCircle size={18} />
              <p>{error}</p>
            </div>
          )}
          {progress && ["downloading", "installing"].includes(status) && (
            <>
              <Progress value={progress.percentage} label="Update download" />
              <span className="df-meta">
                {formatBytes(progress.downloadedBytes)}
                {progress.totalBytes > 0
                  ? ` / ${formatBytes(progress.totalBytes)} · ${Math.round(progress.percentage)}%`
                  : " downloaded"}
              </span>
            </>
          )}
          <div className="df-v2-update-actions">
            {status === "update-available" ? (
              <button
                type="button"
                className="df-btn df-btn-primary"
                onClick={updater.downloadUpdate}
              >
                <Download size={16} />
                Download update
              </button>
            ) : status === "restart-required" ? (
              <button
                type="button"
                className="df-btn df-btn-primary"
                onClick={updater.restartApplication}
              >
                Restart and install
              </button>
            ) : (
              <button
                type="button"
                className="df-btn df-btn-primary"
                disabled={busy}
                onClick={updater.checkForUpdates}
              >
                <RefreshCw size={16} />
                {status === "checking"
                  ? "Checking…"
                  : status === "downloading"
                    ? "Downloading…"
                    : status === "installing"
                      ? "Installing…"
                      : "Check for updates"}
              </button>
            )}
            <span className="df-meta">
              {lastCheckedAt
                ? `Last checked ${new Date(lastCheckedAt).toLocaleString()}`
                : "No check in this session"}
            </span>
          </div>
        </div>
        {versionInfo.releaseNotes && status === "update-available" && (
          <details className="df-v2-notes">
            <summary>What's new in {versionInfo.availableVersion}</summary>
            <div className="df-v2-release-notes">
              {versionInfo.releaseNotes}
            </div>
          </details>
        )}
      </section>
    </div>
  );
}

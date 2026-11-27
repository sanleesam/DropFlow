import { useEffect, useRef, useState } from "react";
import {
  ArrowDownLeft,
  ArrowUp,
  ArrowUpRight,
  ChevronDown,
  File,
  FolderOpen,
  Plus,
  RefreshCw,
  X,
  XCircle,
} from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { isResumable, platformLabel } from "../ui/artwork";
import {
  DeviceCard,
  EmptyState,
  Page,
  Progress,
  ToolGroup,
} from "../components/ui/Desktop";
import { DiscoveryState } from "../components/ui/DiscoveryState";
import { Glass } from "../ui/glass/LiquidGlass";
import {
  formatRelativeTimestamp,
  isLive,
  isPending,
  transferSubtitle,
} from "../utils/transferSessionManager";

export function HomePage() {
  const { settings } = useSettings();
  const flow = useDropFlow();
  const selected = flow.devices.find((device) => device.id === flow.selectedDeviceId);
  const active = Object.values(flow.sessionStore.activeTransfers).filter(isLive);
  const recent = flow.sessionStore.recentTransfers.slice(0, Math.max(0, 5 - active.length));

  useEffect(() => {
    if (!flow.selectedDeviceId && flow.devices[0]) flow.selectDevice(flow.devices[0].id);
  }, [flow.selectedDeviceId, flow.devices, flow.selectDevice]);

  return (
    <Page
      title="Home"
      toolbar={
        <ToolGroup label="Discovery">
          <button
            type="button"
            className="tool-btn"
            aria-label="Search for devices again"
            title="Search again"
            onClick={flow.retryDiscovery}
          >
            <RefreshCw size={15} strokeWidth={1.9} />
          </button>
        </ToolGroup>
      }
      overlay={<SendDock />}
    >
      {flow.lostDeviceName && (
        <div className="banner error" role="alert">
          <XCircle size={16} />
          <p>{flow.lostDeviceName} is no longer nearby.</p>
        </div>
      )}

      <section className="section" aria-labelledby="home-nearby">
        <div className="section-head">
          <h2 id="home-nearby">Nearby</h2>
          <span className="count">{flow.devices.length || ""}</span>
        </div>
        <div className="device-grid">
          <DeviceCard
            name={settings.deviceName || "This device"}
            type={flow.deviceType}
            local
            status={settings.deviceVisibility ? "Online" : "Hidden"}
            footer={platformLabel()}
            onClick={() => flow.setPage("devices")}
          />
          {flow.devices.map((device) => (
            <DeviceCard
              key={device.id}
              name={device.name}
              type={device.type}
              status="Ready"
              selected={selected?.id === device.id}
              onClick={() => flow.selectDevice(device.id)}
            />
          ))}
          {flow.devices.length === 0 && (
            <DiscoveryState state={flow.discovery} onRetry={flow.retryDiscovery} />
          )}
        </div>
      </section>

      <section className="section" aria-labelledby="home-recent">
        <div className="section-head">
          <h2 id="home-recent">Recent</h2>
          {(active.length > 0 || recent.length > 0) && (
            <button type="button" className="btn btn-plain link" onClick={() => flow.setPage("transfers")}>
              Show All
            </button>
          )}
        </div>
        <div className="card list">
          {active.map((session) => (
            <div className="row has-leading live-row" key={session.id}>
              <span className="tile blue">
                {session.direction === "receive" ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}
              </span>
              <div className="grow">
                <div className="row-title ellipsis">
                  {session.totalFiles > 1 ? `${session.totalFiles} files` : session.fileName}
                </div>
                <div className="row-sub ellipsis num">{transferSubtitle(session)}</div>
                <Progress
                  value={session.progress}
                  indeterminate={isPending(session)}
                  label={`Transfer of ${session.fileName}`}
                />
              </div>
              <span className="row-trail num">{isPending(session) ? "" : `${session.progress}%`}</span>
              <button
                type="button"
                className="icon-btn"
                aria-label={`Cancel transfer of ${session.fileName}`}
                onClick={() => flow.cancelTransfer(session.id)}
              >
                <X size={14} />
              </button>
            </div>
          ))}
          {recent.map((item) => {
            const failed = item.status === "failed";
            return (
              <div className="row has-leading" key={item.id}>
                <span className={`tile ${failed ? (isResumable(item.error) ? "orange" : "red") : "green"}`}>
                  {item.direction === "receive" ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}
                </span>
                <div className="grow">
                  <div className="row-title ellipsis">
                    {item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}
                  </div>
                  <div className="row-sub ellipsis">
                    {item.direction === "receive" ? "From" : "To"} {item.deviceName} · {item.size}
                  </div>
                </div>
                <span className="row-trail">
                  {failed &&
                    (isResumable(item.error) ? (
                      <span className="tag warn">Interrupted</span>
                    ) : (
                      <span className="tag bad">Failed</span>
                    ))}
                  <span className="num">
                    {item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp}
                  </span>
                </span>
              </div>
            );
          })}
          {!active.length && !recent.length && (
            <EmptyState compact icon={<ArrowUp size={20} />} title="Nothing sent yet">
              Drop files on this window to start.
            </EmptyState>
          )}
        </div>
      </section>
    </Page>
  );
}

/** Floating glass dock: what you're sending, where, and the one primary action. */
function SendDock() {
  const flow = useDropFlow();
  const [listOpen, setListOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLElement>(null);
  const selected = flow.devices.find((device) => device.id === flow.selectedDeviceId);
  const count = flow.files.length;

  useEffect(() => {
    if (!count) setListOpen(false);
  }, [count]);
  useEffect(() => {
    if (!listOpen) return;
    const close = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!trigger.current?.contains(target) && !menu.current?.contains(target)) setListOpen(false);
    };
    const esc = (event: KeyboardEvent) => event.key === "Escape" && setListOpen(false);
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("pointerdown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [listOpen]);

  const popover = listOpen && (
    <Glass
      ref={menu}
      size="md"
      variant="thick"
      className="popover"
      role="group"
      aria-label="Files selected for transfer"
    >
      {flow.files.map((file) => (
        <div className="row" key={file.uid}>
          <File size={15} className="tertiary" />
          <span className="grow ellipsis" title={file.path}>
            {file.name}
          </span>
          <button
            type="button"
            className="icon-btn"
            aria-label={`Remove ${file.name}`}
            onClick={() => flow.removeFile(file.uid)}
          >
            <X size={13} />
          </button>
        </div>
      ))}
      <div className="row">
        <button type="button" className="btn btn-plain" onClick={flow.browseFiles}>
          <Plus size={14} /> Add Files
        </button>
        <button type="button" className="btn btn-plain" onClick={flow.browseFolder}>
          <FolderOpen size={14} /> Add Folder
        </button>
      </div>
    </Glass>
  );

  return (
    <div className="dock-wrap">
      <div className="dock-stack">
        {popover}
        <Glass size="md" className="dock" role="toolbar" aria-label="Send files">
          {count === 0 ? (
            <>
              <button type="button" className="btn" onClick={flow.browseFiles}>
                <Plus size={16} strokeWidth={2} />
                Choose Files
              </button>
              <button
                type="button"
                className="btn"
                aria-label="Choose folder"
                title="Choose folder"
                onClick={flow.browseFolder}
                style={{ padding: "0 12px" }}
              >
                <FolderOpen size={16} />
              </button>
              <span className="dock-sep" aria-hidden="true" />
              <span className="dock-hint">or drop them anywhere</span>
            </>
          ) : (
            <>
              <button
                ref={trigger}
                type="button"
                className="btn"
                aria-expanded={listOpen}
                aria-haspopup="true"
                onClick={() => setListOpen((open) => !open)}
              >
                <File size={15} />
                <span className="ellipsis" style={{ maxWidth: 220 }}>
                  {count === 1 ? flow.files[0].name : `${count} items`}
                </span>
                <ChevronDown size={13} className="tertiary" />
              </button>
              <span className="dock-sep" aria-hidden="true" />
              <select
                id="send-device"
                aria-label="Send to"
                className="select"
                value={selected?.id || ""}
                onChange={(event) => flow.selectDevice(event.target.value)}
                disabled={flow.devices.length === 0}
              >
                <option value="" disabled>
                  {flow.devices.length ? "Choose device" : "No devices"}
                </option>
                {flow.devices.map((device) => (
                  <option key={device.id} value={device.id}>
                    {device.name}
                  </option>
                ))}
              </select>
              <button type="button" className="btn send" disabled={!selected} onClick={flow.sendSelected}>
                <ArrowUp size={16} strokeWidth={2.4} />
                Send
              </button>
            </>
          )}
        </Glass>
      </div>
    </div>
  );
}

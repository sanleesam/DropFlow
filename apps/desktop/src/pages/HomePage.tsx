import { useEffect } from "react";
import { ArrowLeftRight, CheckCircle2, File, FolderOpen, RefreshCw, Send, ShieldCheck, X, XCircle } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { deviceKindLabel, greeting, platformLabel } from "../ui/artwork";
import { DeviceCard, DeviceVisual, EmptyState, PageHeader, Progress } from "../components/ui/Desktop";
import { DiscoveryState } from "../components/ui/DiscoveryState";
import { formatRelativeTimestamp } from "../utils/transferSessionManager";

export function HomePage() {
  const { settings } = useSettings();
  const flow = useDropFlow();
  const selected = flow.devices.find((device) => device.id === flow.selectedDeviceId);
  const active = Object.values(flow.sessionStore.activeTransfers).filter((session) => ["Preparing...", "Sending...", "Receiving...", "Finishing..."].includes(session.status));
  const recent = flow.sessionStore.recentTransfers.slice(0, Math.max(0, 4 - active.length));
  useEffect(() => {
    if (!flow.selectedDeviceId && flow.devices[0]) flow.selectDevice(flow.devices[0].id);
  }, [flow.selectedDeviceId, flow.devices, flow.selectDevice]);

  return <div className="df-page">
    <PageHeader title={`${greeting()}.`} description="Send files directly to your nearby devices. No cloud required." />
    <div className="df-v2-workspace">
      <div className="df-v2-stack">
        <section aria-labelledby="home-nearby">
          <div className="df-v2-section-head"><h2 id="home-nearby">Nearby devices<span className="df-v2-count">{flow.devices.length}</span></h2><button className="df-link" type="button" onClick={() => flow.setPage("devices")}>View all</button></div>
          <div className="df-v2-devices">
            <DeviceCard name={settings.deviceName || "This device"} type={flow.deviceType} local status={settings.deviceVisibility ? "Online" : "Hidden"} footer={platformLabel()} onClick={() => flow.setPage("devices")} />
            {flow.devices.slice(0, 2).map((device) => <DeviceCard key={device.id} name={device.name} type={device.type} status="Online" selected={selected?.id === device.id} onClick={() => flow.selectDevice(device.id)} />)}
            {flow.devices.length === 0 && <DiscoveryState state={flow.discovery} onRetry={flow.retryDiscovery} />}
          </div>
        </section>
        <section className="df-card df-card-pad" aria-labelledby="home-send">
          <div className="df-card-head"><h2 id="home-send">Send files</h2><span className="df-meta">{flow.files.length ? `${flow.files.length} selected` : "Any file type"}</span></div>
          {flow.lostDeviceName && <div className="df-v2-callout error" role="alert"><XCircle size={18} /><p>{flow.lostDeviceName} is no longer nearby. Choose another device to continue.</p></div>}
          <div className={`df-v2-drop${flow.droppedOver ? " over" : ""}`}>
            <FolderOpen size={38} strokeWidth={1.4} /><strong>{flow.droppedOver ? "Drop to add your files" : "Drag & drop files here"}</strong><p>Files stay on your local network.</p>
            <div className="df-v2-drop-actions"><button type="button" className="df-btn df-btn-soft" onClick={flow.browseFiles}>Choose files</button><button type="button" className="df-btn df-btn-soft" onClick={flow.browseFolder}>Choose folder</button></div>
          </div>
          {flow.files.length > 0 && <div className="df-v2-queue" aria-label="Files selected for transfer">{flow.files.map((file) => <div className="df-file-row" key={file.uid}><File size={17} /><span className="df-grow df-ellipsis" title={file.name}>{file.name}</span><button type="button" className="df-quiet" aria-label={`Remove ${file.name}`} onClick={() => flow.removeFile(file.uid)}><X size={16} /></button></div>)}</div>}
          <div className="df-v2-sendbar"><label htmlFor="send-device">Send to<select id="send-device" className="df-select" value={selected?.id || ""} onChange={(event) => flow.selectDevice(event.target.value)} disabled={flow.devices.length === 0}><option value="" disabled>Choose a nearby device</option>{flow.devices.map((device) => <option key={device.id} value={device.id}>{device.name}</option>)}</select></label><button type="button" className="df-btn df-btn-primary" disabled={!selected || !flow.files.length} onClick={flow.sendSelected}><Send size={16} />Send {flow.files.length > 1 ? `${flow.files.length} files` : "files"}</button></div>
        </section>
        <section className="df-card df-card-pad" aria-labelledby="home-recent">
          <div className="df-card-head"><h2 id="home-recent">Recent activity</h2><button type="button" className="df-link" onClick={() => flow.setPage("transfers")}>All transfers</button></div>
          {active.map((session) => <div className="df-xfer df-v2-activity" key={session.id}><span className="df-tile" style={{ background: "var(--df-accent-soft)", color: "#75aaff" }}><ArrowLeftRight size={18} /></span><div className="df-grow"><div className="df-xfer-name">{session.totalFiles > 1 ? `${session.totalFiles} files` : session.fileName}</div><p className="df-xfer-meta">{session.direction === "receive" ? "From" : "To"} {session.deviceName} · {session.speed}</p><Progress value={session.progress} label={`Transfer of ${session.fileName}`} /></div><div className="df-xfer-side"><strong>{session.progress}%</strong><div>{session.status}</div></div><button type="button" className="df-quiet" aria-label={`Cancel transfer of ${session.fileName}`} onClick={() => flow.cancelTransfer(session.id)}><X size={16} /></button></div>)}
          {recent.map((item) => <div className="df-xfer df-v2-activity" key={item.id}><span className="df-tile" style={{ background: item.status === "failed" ? "var(--df-danger-soft)" : "var(--df-success-soft)", color: item.status === "failed" ? "var(--df-danger)" : "var(--df-success)" }}>{item.status === "failed" ? <XCircle size={18} /> : <CheckCircle2 size={18} />}</span><div className="df-grow"><div className="df-xfer-name">{item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}</div><div className="df-xfer-meta">{item.size} · {item.direction === "receive" ? "From" : "To"} {item.deviceName}</div></div><div className="df-xfer-side"><span className={`df-done${item.status === "failed" ? " bad" : ""}`}>{item.status === "failed" ? "Failed" : "Completed"}</span><div>{item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp}</div></div></div>)}
          {!active.length && !recent.length && <EmptyState compact icon={<ArrowLeftRight size={22} />} title="Your first transfer starts here">Files you send and receive will appear here with their progress and status.</EmptyState>}
        </section>
      </div>
      <aside className="df-v2-stack df-v2-rail">
        <section className="df-card df-card-pad df-v2-context"><h2>{selected ? "Selected device" : "This device"}</h2><DeviceVisual type={selected?.type || flow.deviceType} large /><h3>{selected?.name || settings.deviceName || "This device"}</h3><span className="df-status"><i className={`df-dot${!selected && !settings.deviceVisibility ? " idle" : ""}`} />{selected ? "Online · ready to send" : settings.deviceVisibility ? "Visible on this network" : "Hidden from discovery"}</span><dl><div><dt>Device type</dt><dd>{deviceKindLabel(selected?.type || flow.deviceType)}</dd></div><div><dt>{selected ? "Address" : "Platform"}</dt><dd>{selected ? selected.addresses.map((address) => address.address).join(", ") || "Not available" : platformLabel()}</dd></div>{selected && <div><dt>DropFlow version</dt><dd>{selected.version || "Not shared"}</dd></div>}</dl><div className="df-v2-context-actions"><button type="button" className="df-btn df-btn-soft" onClick={() => flow.openDeviceFiles(selected?.id || "this")}><FolderOpen size={16} />View files</button><button type="button" className="df-btn df-btn-soft" onClick={() => flow.setPage("devices")}><RefreshCw size={16} />Manage devices</button></div></section>
        <section className="df-card df-card-pad df-v2-note"><ShieldCheck size={19} /><p>Local by design. Keep DropFlow open on both devices and connect them to the same network.</p></section>
      </aside>
    </div>
  </div>;
}

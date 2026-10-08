import React, { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, File, FileArchive, FileText, Folder, Image as ImageIcon, MoreHorizontal, Music, Pause, Pencil, Play, Plus, Send } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { CornerActions } from "../components/shell/CornerActions";
import { useDropFlow } from "../session/DropFlowProvider";
import { ActiveTransferSession, RecentTransfer } from "../utils/transferSessionManager";
import { deviceArt, deviceKindLabel, greeting, platformLabel } from "../ui/artwork";
import { useDiskUsage } from "../ui/useDisk";
import { formatBytes } from "../utils/formatters";

function shortAgo(timestampMs?: number, fallback?: string): string {
  if (!timestampMs) return fallback || "Just now";
  const mins = Math.floor(Math.max(0, Date.now() - timestampMs) / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function shortLeft(text: string): string {
  if (!text || text.startsWith("Calc")) return "";
  const minute = text.match(/(\d+)\s*minute/);
  if (minute) return `${minute[1]}m left`;
  const second = text.match(/(\d+)\s*second/);
  if (second) return `${second[1]}s left`;
  return text.replace(" remaining", " left");
}

function fileTone(name: string): { bg: string; fg: string; icon: "zip" | "image" | "video" | "pdf" | "audio" | "doc" } {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return { bg: "#FFF1E6", fg: "#F08A24", icon: "zip" };
  if (["png", "jpg", "jpeg", "gif", "webp", "heic", "svg", "bmp"].includes(ext)) return { bg: "#E7F0FF", fg: "#3B82F6", icon: "image" };
  if (["mp4", "mov", "mkv", "avi", "webm", "m4v"].includes(ext)) return { bg: "#F3E8FF", fg: "#8B5CF6", icon: "video" };
  if (ext === "pdf") return { bg: "#FDECEC", fg: "#EF4444", icon: "pdf" };
  if (["mp3", "wav", "flac", "aac", "m4a"].includes(ext)) return { bg: "#E7F8F1", fg: "#16A34A", icon: "audio" };
  return { bg: "#EEF2FF", fg: "#6366F1", icon: "doc" };
}

function FileGlyph({ name }: { name: string }) {
  const tone = fileTone(name);
  const icon =
    tone.icon === "zip" ? <FileArchive size={16} /> :
    tone.icon === "image" ? <ImageIcon size={16} /> :
    tone.icon === "video" ? <Play size={16} /> :
    tone.icon === "pdf" ? <FileText size={16} /> :
    tone.icon === "audio" ? <Music size={16} /> :
    <File size={16} />;
  return <span className="df-tile" style={{ background: tone.bg, color: tone.fg }}>{icon}</span>;
}

function OsMark({ kind }: { kind: "apple" | "android" | "windows" | "linux" }) {
  if (kind === "apple") {
    return (
      <svg className="df-os" viewBox="0 0 16 16" aria-hidden>
        <path fill="currentColor" d="M12.4 8.3c0-1.9 1.6-2.8 1.6-2.9-1-.4-1.9-.2-2.4.2-.5.3-1 .4-1.6.4s-1.1-.1-1.7-.4c-.6-.4-1.4-.6-2.2-.2-1.3.6-2 2.3-2 3.9 0 1.5.6 3.1 1.4 4.1.6.8 1.3 1.5 2.2 1.5.8 0 1.1-.5 2.1-.5s1.2.5 2.1.5 1.5-.8 2.1-1.6c.5-.7.8-1.4.9-1.5-.1 0-1.7-.7-1.7-2.6zM10.6 3.4c.5-.6.8-1.4.7-2.2-.7.1-1.5.5-2 1.1-.4.5-.8 1.3-.7 2.1.8 0 1.5-.4 2-1z" />
      </svg>
    );
  }
  if (kind === "android") {
    return (
      <svg className="df-os" viewBox="0 0 16 16" aria-hidden>
        <path fill="#3DDC84" d="M4.2 6.2h7.6v5.2c0 .6-.4 1-1 1H5.2c-.6 0-1-.4-1-1V6.2z" />
        <path fill="#3DDC84" d="M6.1 4.2 5.2 2.8M9.9 4.2l.9-1.4M3.2 6.4H2.2v3.2h1M12.8 6.4h1v3.2h-1" stroke="#3DDC84" strokeWidth="1" />
        <circle cx="6.3" cy="8.2" r=".5" fill="#073" />
        <circle cx="9.7" cy="8.2" r=".5" fill="#073" />
      </svg>
    );
  }
  if (kind === "linux") {
    return <span className="df-os-dot" />;
  }
  return (
    <svg className="df-os" viewBox="0 0 16 16" aria-hidden>
      <path fill="#3B82F6" d="M1 2.4 6.7 1.6v6H1zm6.4-.9L15 .6v7H7.4zM1 8.4h5.7v6L1 13.6zm6.4 0H15v7l-7.6-1z" />
    </svg>
  );
}

function osFor(name: string, type: string, local: boolean): { kind: "apple" | "android" | "windows" | "linux"; label: string } {
  if (local) {
    const platform = platformLabel();
    if (platform === "macOS") return { kind: "apple", label: "macOS" };
    if (platform === "Linux") return { kind: "linux", label: "Linux" };
    return { kind: "windows", label: "Windows" };
  }
  const blob = `${name} ${type}`.toLowerCase();
  if (blob.includes("mac") || blob.includes("iphone") || blob.includes("ipad")) return { kind: "apple", label: blob.includes("iphone") ? "iPhone" : blob.includes("ipad") ? "iPad" : "macOS" };
  if (blob.includes("android") || type === "phone") return { kind: "android", label: "Android" };
  if (blob.includes("linux")) return { kind: "linux", label: "Linux" };
  if (blob.includes("win") || type === "desktop" || type === "laptop") return { kind: "windows", label: type === "laptop" ? "Windows" : "Windows" };
  return { kind: "windows", label: deviceKindLabel(type) };
}

function StorageDonut() {
  const disk = useDiskUsage();
  const total = disk?.totalBytes || 0;
  const free = disk?.freeBytes || 0;
  const used = Math.max(0, total - free);
  const r = 46;
  const c = 2 * Math.PI * r;
  const usedLen = total > 0 ? Math.max(0, (used / total) * c - 6) : 0;
  return (
    <div className="df-donut-wrap">
      <div className="df-donut">
        <svg viewBox="0 0 120 120" aria-hidden>
          <circle cx="60" cy="60" r={r} fill="none" stroke="#E6EBF2" strokeWidth="12" />
          {usedLen > 0 && (
            <circle cx="60" cy="60" r={r} fill="none" stroke="#2F7CF6" strokeWidth="12" strokeLinecap="round" strokeDasharray={`${usedLen} ${c - usedLen}`} transform="rotate(-90 60 60)" />
          )}
        </svg>
        <div className="df-donut-label">
          <strong>{disk ? formatBytes(used) : "—"}</strong>
          <span>{disk ? `of ${formatBytes(total)}` : "this disk"}</span>
        </div>
      </div>
      <ul className="df-legend">
        <li><i style={{ background: "#2F7CF6" }} /><span>Used</span><b>{disk ? formatBytes(used) : "—"}</b></li>
        <li><i style={{ background: "#C9D0DA" }} /><span>Free</span><b>{disk ? formatBytes(free) : "—"}</b></li>
      </ul>
    </div>
  );
}

export const HomePage: React.FC = () => {
  const { settings } = useSettings();
  const flow = useDropFlow();
  const [pack, setPack] = useState<"original" | "zip">("zip");
  const [customName, setCustomName] = useState("");
  const [naming, setNaming] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const pickerRef = useRef<HTMLDivElement>(null);
  const selected = flow.devices.find((device) => device.id === flow.selectedDeviceId) || null;
  const canSend = flow.files.length > 0 && !!selected;
  const heroPeers = flow.devices.slice(0, 2);
  const connected = flow.devices.slice(0, 2);
  const onlineCount = flow.devices.length + 1;
  const live = Object.values(flow.sessionStore.activeTransfers).filter((session) =>
    session.status === "Preparing..." || session.status === "Sending..." || session.status === "Receiving..." || session.status === "Finishing...",
  );
  const recent = flow.sessionStore.recentTransfers.slice(0, Math.max(0, 4 - live.length));

  useEffect(() => {
    if (!flow.selectedDeviceId && flow.devices[0]) flow.selectDevice(flow.devices[0].id);
  }, [flow.selectedDeviceId, flow.devices, flow.selectDevice]);

  useEffect(() => {
    if (!pickerOpen) return;
    const close = (event: MouseEvent) => {
      if (!pickerRef.current?.contains(event.target as Node)) setPickerOpen(false);
    };
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [pickerOpen]);

  const send = () => {
    if (!canSend) return;
    flow.sendSelected();
  };

  return (
    <div className="df-page df-home">
      <header className="df-home-head">
        <div>
          <h1 className="df-hello">{greeting()},</h1>
          <p className="df-hello-sub">Your devices are connected and ready to transfer.</p>
        </div>
        <CornerActions />
      </header>

      <div className="df-hero-row">
        <div className="df-hero selected">
          <img src={deviceArt(flow.deviceType)} alt="" />
          <div className="df-hero-copy">
            <div className="name">{settings.deviceName || "This device"}</div>
            <span className="df-status"><i className="df-dot" /> This device</span>
          </div>
        </div>
        {heroPeers.map((device) => (
          <button key={device.id} type="button" className="df-hero" onClick={() => flow.selectDevice(device.id)}>
            <img src={deviceArt(device.type)} alt="" />
            <div className="df-hero-copy">
              <div className="name">{device.name}</div>
              <span className="df-status"><i className="df-dot" /> Online</span>
            </div>
          </button>
        ))}
        <button type="button" className="df-hero df-hero-add" onClick={() => flow.setPage("devices")}>
          <span className="df-plus"><Plus size={16} strokeWidth={2.25} /></span>
          <strong>Add Device</strong>
          <span>Connect a new device</span>
        </button>
      </div>

      <div className="df-home-main">
        <section className="df-card df-quick">
          <h2>Quick Transfer</h2>
          <p>Select files or folders to send to a device</p>
          {flow.lostDeviceName && <p className="df-warn">{flow.lostDeviceName} is no longer nearby.</p>}
          <div className="df-quick-grid">
            <div className={flow.droppedOver ? "df-drop over" : "df-drop"}>
              <Folder size={52} strokeWidth={1.25} />
              <strong>Drag and drop files here</strong>
              <span className="df-browse-line">or <button type="button" onClick={flow.browseFiles}>click to browse</button></span>
              <div className="df-drop-actions">
                <button type="button" className="df-btn df-btn-soft" onClick={flow.browseFiles}>Select Files</button>
                <button type="button" className="df-btn df-btn-primary df-btn-compact" onClick={flow.browseFolder}>Select Folder</button>
              </div>
              {flow.files.length > 0 && (
                <div className="df-picks">
                  {flow.files.map((file) => (
                    <button key={file.uid} type="button" className="df-pick" onClick={() => flow.removeFile(file.uid)}>
                      {file.name}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="df-quick-side">
              <span className="df-field-label">Send to</span>
              <div className="df-picker" ref={pickerRef}>
                <button type="button" className="df-target" onClick={() => setPickerOpen((open) => !open)}>
                  {selected ? <img src={deviceArt(selected.type)} alt="" /> : <span className="df-target-ph" />}
                  <span className="df-grow">
                    <strong>{selected ? selected.name : "Choose a device"}</strong>
                    <span className="df-status"><i className={selected ? "df-dot" : "df-dot idle"} /> {selected ? "Online" : "None nearby"}</span>
                  </span>
                  <ChevronDown size={16} />
                </button>
                {pickerOpen && (
                  <div className="df-picker-menu">
                    {flow.devices.length === 0 && <div className="df-picker-empty">No devices nearby</div>}
                    {flow.devices.map((device) => (
                      <button
                        key={device.id}
                        type="button"
                        onClick={() => { flow.selectDevice(device.id); setPickerOpen(false); }}
                      >
                        <img src={deviceArt(device.type)} alt="" />
                        <span>{device.name}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <button type="button" className={pack === "original" ? "df-opt on" : "df-opt"} onClick={() => setPack("original")}>
                <File size={18} />
                <span className="df-grow">
                  <strong>Keep original files</strong>
                  <small>Send files as they are</small>
                </span>
                <i className={pack === "original" ? "df-radio on" : "df-radio"} />
              </button>
              <button type="button" className={pack === "zip" ? "df-opt on" : "df-opt"} onClick={() => setPack("zip")}>
                <FileArchive size={18} />
                <span className="df-grow">
                  <strong>Automatic ZIP</strong>
                  <small>Compress into a single file</small>
                </span>
                <i className={pack === "zip" ? "df-radio on" : "df-radio"} />
              </button>
              <button type="button" className="df-opt" onClick={() => setNaming(true)}>
                <Pencil size={18} />
                <span className="df-grow">
                  <strong>Custom name</strong>
                  {naming ? (
                    <input
                      className="df-name-input"
                      autoFocus
                      placeholder="Set a custom name for the transfer"
                      value={customName}
                      onChange={(event) => setCustomName(event.target.value)}
                      onClick={(event) => event.stopPropagation()}
                    />
                  ) : (
                    <small>{customName || "Set a custom name for the transfer"}</small>
                  )}
                </span>
              </button>
              <button type="button" className="df-send" disabled={!canSend} onClick={send}>
                <Send size={16} />
                Send Files
              </button>
            </div>
          </div>
        </section>

        <section className="df-card df-recent">
          <div className="df-card-head">
            <h2>Recent Transfers</h2>
            <button type="button" className="df-link" onClick={() => flow.setPage("transfers")}>See all</button>
          </div>
          <div className="df-recent-list">
            {live.map((session) => <LiveRow key={session.id} session={session} onPause={() => flow.cancelTransfer(session.id)} />)}
            {recent.map((item) => <HistoryRow key={item.id} item={item} />)}
            {live.length === 0 && recent.length === 0 && <p className="df-recent-empty">Files you send or receive will show up here.</p>}
          </div>
        </section>
      </div>

      <div className="df-home-bottom">
        <section className="df-card df-connected">
          <div className="df-card-head">
            <div>
              <h2>Connected Devices</h2>
              <p>{onlineCount} {onlineCount === 1 ? "device" : "devices"} online</p>
            </div>
            <button type="button" className="df-link" onClick={() => flow.setPage("devices")}>Manage Devices</button>
          </div>
          <div className="df-mini-row">
            <MiniDevice
              name={settings.deviceName || "This device"}
              art={deviceArt(flow.deviceType)}
              os={osFor(settings.deviceName, flow.deviceType, true)}
              here
              onMore={() => flow.setPage("devices")}
            />
            {connected.map((device) => (
              <MiniDevice
                key={device.id}
                name={device.name}
                art={deviceArt(device.type)}
                os={osFor(device.name, device.type, false)}
                onMore={() => { flow.selectDevice(device.id); flow.setPage("devices"); }}
              />
            ))}
          </div>
        </section>
        <section className="df-card df-storage">
          <h2>Storage Overview</h2>
          <StorageDonut />
        </section>
      </div>
    </div>
  );
};

function LiveRow({ session, onPause }: { session: ActiveTransferSession; onPause: () => void }) {
  const size = session.bytesInfo.includes("/") ? session.bytesInfo.split("/")[1].trim() : session.bytesInfo;
  const who = session.direction === "receive" ? "from" : "to";
  return (
    <div className="df-xfer">
      <FileGlyph name={session.fileName} />
      <div className="df-grow">
        <div className="df-xfer-name">{session.totalFiles > 1 ? `${session.totalFiles} files` : session.fileName}</div>
        <div className="df-xfer-meta">{size ? `${size} · ` : ""}{who} {session.deviceName}</div>
        <div className="df-thinbar"><span style={{ width: `${session.progress}%` }} /></div>
      </div>
      <div className="df-xfer-side">
        <div className="df-xfer-top">
          <b>{session.progress}%</b>
          <button type="button" className="df-pause" aria-label="Pause transfer" onClick={onPause}><Pause size={12} /></button>
        </div>
        <span>{shortLeft(session.timeRemaining)}</span>
      </div>
    </div>
  );
}

function HistoryRow({ item }: { item: RecentTransfer }) {
  const failed = item.status === "failed";
  const who = item.direction === "receive" ? "from" : "to";
  return (
    <div className="df-xfer">
      <FileGlyph name={item.fileName} />
      <div className="df-grow">
        <div className="df-xfer-name">{item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}</div>
        <div className="df-xfer-meta">{item.size} · {who} {item.deviceName}</div>
      </div>
      <div className="df-xfer-side">
        <span className={failed ? "df-done bad" : "df-done"}><Check size={13} strokeWidth={2.5} /> {failed ? "Failed" : "Completed"}</span>
        <span>{item.timestampMs ? shortAgo(item.timestampMs, item.timestamp) : item.timestamp}</span>
      </div>
    </div>
  );
}

function MiniDevice({ name, art, os, here, onMore }: { name: string; art: string; os: { kind: "apple" | "android" | "windows" | "linux"; label: string }; here?: boolean; onMore: () => void }) {
  return (
    <div className="df-mini">
      <img src={art} alt="" />
      <div className="df-grow">
        <div className="name">{name}</div>
        {here ? <span className="df-status"><i className="df-dot" /> This device</span> : <span className="df-status"><i className="df-dot" /> Online</span>}
        <span className="df-os-line"><OsMark kind={os.kind} /> {os.label}</span>
      </div>
      <button type="button" className="df-more" aria-label={`More about ${name}`} onClick={onMore}><MoreHorizontal size={16} /></button>
    </div>
  );
}

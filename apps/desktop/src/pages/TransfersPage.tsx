import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { Check, Clock, FileArchive, FileText, Folder, Gauge, Image as ImageIcon, Moon, Pause, Play, RotateCcw, Search, ShieldCheck, Wifi, X } from "lucide-react";
import { useToast } from "../components/ToastProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { deviceArt, friendlyTransferError } from "../ui/artwork";
import { ActiveTransferSession, RecentTransfer, formatRelativeTimestamp } from "../utils/transferSessionManager";

type Filter = "all" | "send" | "receive" | "completed" | "failed";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "send", label: "Sending" },
  { id: "receive", label: "Receiving" },
  { id: "completed", label: "Completed" },
  { id: "failed", label: "Failed" },
];

function live(session: ActiveTransferSession): boolean {
  return session.status === "Preparing..." || session.status === "Sending..." || session.status === "Receiving..." || session.status === "Finishing...";
}

function fileTone(name: string): { bg: string; fg: string; icon: React.ReactNode; label: string } {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return { bg: "#FFF1E6", fg: "#F08A24", icon: <FileArchive size={16} />, label: "ZIP Archive" };
  if (["png", "jpg", "jpeg", "gif", "webp", "heic", "svg"].includes(ext)) return { bg: "#E7F0FF", fg: "#3B82F6", icon: <ImageIcon size={16} />, label: "Image" };
  if (["mp4", "mov", "mkv", "avi", "webm"].includes(ext)) return { bg: "#F3E8FF", fg: "#8B5CF6", icon: <Play size={16} />, label: "Video" };
  if (ext === "pdf") return { bg: "#FDECEC", fg: "#EF4444", icon: <FileText size={16} />, label: "PDF" };
  return { bg: "#EEF2FF", fg: "#6366F1", icon: <FileText size={16} />, label: ext ? ext.toUpperCase() : "File" };
}

function AreaSpark({ values, color }: { values: number[]; color: string }) {
  const width = 220;
  const height = 32;
  const nums = values.length > 1 ? values : [1, 1];
  const max = Math.max(...nums, 1);
  const pts = nums.map((value, index) => {
    const x = (index / (nums.length - 1)) * width;
    const y = height - (value / max) * (height - 6) - 3;
    return `${x},${y}`;
  });
  const line = pts.join(" ");
  return (
    <svg className="df-area" viewBox={`0 0 ${width} ${height}`} aria-hidden>
      <polygon points={`0,${height} ${line} ${width},${height}`} fill={color} opacity="0.16" />
      <polyline points={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

export const TransfersPage: React.FC = () => {
  const flow = useDropFlow();
  const { addToast } = useToast();
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string | null>(null);

  const active = Object.values(flow.sessionStore.activeTransfers).filter(live);
  const history = flow.sessionStore.recentTransfers;

  const matches = (direction: string, status: string, name: string, device: string) => {
    const q = query.trim().toLowerCase();
    if (q && !`${name} ${device}`.toLowerCase().includes(q)) return false;
    if (filter === "send") return direction === "send";
    if (filter === "receive") return direction === "receive";
    if (filter === "failed") return status === "failed" || status === "Failed" || status === "Cancelled";
    if (filter === "completed") return status === "completed" || status === "Completed";
    return true;
  };

  const visibleActive = active.filter((session) => matches(session.direction, session.status, session.fileName, session.deviceName));
  const visibleHistory = history.filter((item) => matches(item.direction, item.status, item.fileName, item.deviceName));
  const selectedActive = visibleActive.find((session) => session.id === picked) || visibleActive[0];
  const selectedHistory = !selectedActive ? visibleHistory.find((item) => item.id === picked) || visibleHistory[0] : undefined;

  const openPathSafe = async (path: string) => {
    try {
      await openPath(path);
    } catch {
      try {
        await invoke("open_received_file", { path });
      } catch (err) {
        console.error(err);
        addToast("Couldn't open that file.", "error");
      }
    }
  };

  const revealSafe = async (path: string) => {
    try {
      await revealItemInDir(path);
    } catch {
      addToast("Couldn't show that file.", "error");
    }
  };

  const cancelAll = () => visibleActive.forEach((session) => flow.cancelTransfer(session.id));

  return (
    <div className="df-page df-transfers">
      <header className="df-page-bar">
        <div>
          <h1>Transfers</h1>
          <p>Send and receive files across your devices</p>
        </div>
        <div className="df-chips">
          {FILTERS.map((item) => (
            <button key={item.id} type="button" className={filter === item.id ? "df-chip active" : "df-chip"} onClick={() => setFilter(item.id)}>
              {item.label}
            </button>
          ))}
        </div>
      </header>

      <div className="df-tx-grid">
        <div className="df-tx-main">
          <section className="df-card df-tx-card">
            <div className="df-card-head">
              <h2>Active Transfers ({visibleActive.length})</h2>
              <div className="df-tx-bulk">
                <button type="button" disabled title="A transfer can be cancelled, not paused"><Pause size={13} /> Pause All</button>
                <button type="button" disabled={visibleActive.length === 0} onClick={cancelAll}><X size={13} /> Cancel All</button>
              </div>
            </div>
            {visibleActive.length === 0 && <p className="df-kicker">No transfer is running right now.</p>}
            {visibleActive.map((session) => (
              <ActiveRow
                key={session.id}
                session={session}
                selected={selectedActive?.id === session.id}
                speeds={flow.speedHistory[session.id] || []}
                onPick={() => setPicked(session.id)}
                onCancel={() => flow.cancelTransfer(session.id)}
              />
            ))}
          </section>

          <section className="df-card df-tx-card">
            <div className="df-card-head">
              <h2>Recent Transfers</h2>
              <label className="df-search df-search-sm">
                <Search size={14} />
                <input value={query} placeholder="Search transfers..." onChange={(event) => setQuery(event.target.value)} />
              </label>
            </div>
            {visibleHistory.length === 0 && <p className="df-kicker">{query ? `No transfers match “${query}”.` : "Completed transfers will be listed here."}</p>}
            {visibleHistory.map((item) => (
              <HistoryRow key={item.id} item={item} selected={selectedHistory?.id === item.id} onPick={() => setPicked(item.id)} onOpen={openPathSafe} onReveal={revealSafe} />
            ))}
          </section>
        </div>

        <aside className="df-card df-tx-side">
          {selectedActive ? (
            <ActiveDetail session={selectedActive} speeds={flow.speedHistory[selectedActive.id] || []} onCancel={() => flow.cancelTransfer(selectedActive.id)} onOpen={openPathSafe} onReveal={revealSafe} />
          ) : selectedHistory ? (
            <HistoryDetail item={selectedHistory} onOpen={openPathSafe} onReveal={revealSafe} />
          ) : (
            <div className="df-card-pad">
              <h2>Transfer details</h2>
              <p className="df-kicker">Select a transfer to see its files, progress, and where it went.</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
};

function ActiveRow({ session, selected, speeds, onPick, onCancel }: { session: ActiveTransferSession; selected: boolean; speeds: number[]; onPick: () => void; onCancel: () => void }) {
  const tone = fileTone(session.fileName);
  const color = session.direction === "receive" ? "#8B5CF6" : "#2F7CF6";
  return (
    <div className={selected ? "df-tx-row picked" : "df-tx-row"}>
      <button type="button" className="df-tx-hit" onClick={onPick}>
        <span className="df-tile" style={{ background: tone.bg, color: tone.fg }}>{tone.icon}</span>
        <span className="df-grow">
          <strong>{session.totalFiles > 1 ? `${session.totalFiles} files` : session.fileName}</strong>
          <span className="df-xfer-meta">{session.bytesInfo || session.speed} · {session.direction === "receive" ? "Receiving from" : "Sending to"} {session.deviceName}</span>
          <span className="df-tx-meter">
            <span className="df-thinbar"><i style={{ width: `${session.progress}%`, background: color }} /></span>
            <b>{session.progress}%</b>
          </span>
          <span className="df-tx-foot"><span>{session.speed}</span><AreaSpark values={speeds} color={color} /><span>{session.timeRemaining}</span></span>
        </span>
      </button>
      <span className="df-tx-row-actions">
        <button type="button" className="df-pause" disabled title="A transfer can be cancelled, not paused"><Pause size={12} /></button>
        <button type="button" className="df-pause" aria-label="Cancel transfer" onClick={onCancel}><X size={12} /></button>
      </span>
    </div>
  );
}

function HistoryRow({ item, selected, onPick, onOpen, onReveal }: { item: RecentTransfer; selected: boolean; onPick: () => void; onOpen: (path: string) => void; onReveal: (path: string) => void }) {
  const tone = fileTone(item.fileName);
  const failed = item.status === "failed";
  const path = item.files?.find((file) => file.finalPath)?.finalPath;
  return (
    <div className={selected ? "df-hist picked" : "df-hist"}>
      <button type="button" className="df-tx-hit" onClick={onPick}>
        <span className="df-tile" style={{ background: tone.bg, color: tone.fg }}>{tone.icon}</span>
        <span className="df-hist-name">
          <strong>{item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}</strong>
          <small>{item.size}{item.totalFiles > 1 ? ` · ${item.totalFiles} files` : ""}</small>
        </span>
        <span className="df-hist-who">{item.direction === "receive" ? "From" : "To"} {item.deviceName}</span>
        <span className={failed ? "df-done bad" : "df-done"}>{failed ? <X size={13} /> : <Check size={13} />} {failed ? "Failed" : "Completed"}<small>{item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp}</small></span>
      </button>
      <span className="df-hist-actions">
        {path && <button type="button" aria-label="Show in folder" onClick={() => onReveal(path)}><Folder size={15} /></button>}
        {failed && <button type="button" aria-label="Details" onClick={onPick}><RotateCcw size={15} /></button>}
        {path && <button type="button" aria-label="Open" onClick={() => onOpen(path)}><MoreDots /></button>}
      </span>
    </div>
  );
}

function MoreDots() {
  return <span className="df-dots">···</span>;
}

function ActiveDetail({ session, speeds, onCancel, onOpen, onReveal }: { session: ActiveTransferSession; speeds: number[]; onCancel: () => void; onOpen: (path: string) => void; onReveal: (path: string) => void }) {
  const flow = useDropFlow();
  const tone = fileTone(session.fileName);
  const color = session.direction === "receive" ? "#8B5CF6" : "#2F7CF6";
  const peer = flow.devices.find((device) => device.name === session.deviceName);
  const path = session.completedFiles.find((file) => file.finalPath)?.finalPath;
  const from = session.direction === "receive" ? peer : null;
  const to = session.direction === "receive" ? null : peer;
  return (
    <div className="df-tx-detail">
      <div className="df-side-hero">
        <span className="df-tile df-tile-lg" style={{ background: tone.bg, color: tone.fg }}>{tone.icon}</span>
        <div className="df-grow">
          <strong>{session.fileName}</strong>
          <span className="df-xfer-meta">{session.direction === "receive" ? "Receiving from" : "Sending to"} {session.deviceName}</span>
          <span className="df-xfer-meta">{session.bytesInfo}</span>
        </div>
        <button type="button" className="df-quiet" aria-label="Dismiss" onClick={() => flow.dismissTransfer(session.id)}><X size={16} /></button>
      </div>
      <div className="df-tx-meter">
        <span className="df-thinbar df-thinbar-lg"><i style={{ width: `${session.progress}%`, background: color }} /></span>
        <b>{session.progress}%</b>
      </div>
      <div className="df-tx-foot"><span>{session.speed}</span><AreaSpark values={speeds} color={color} /><span>{session.timeRemaining}</span></div>
      <div className="df-hop">
        <Hop art={session.direction === "receive" ? deviceArt(from?.type) : deviceArt(flow.deviceType)} name={session.direction === "receive" ? session.deviceName : "This device"} sub={session.direction === "receive" ? "Online" : "This device"} />
        <span className="df-hop-link"><Wifi size={16} /></span>
        <Hop art={session.direction === "receive" ? deviceArt(flow.deviceType) : deviceArt(to?.type)} name={session.direction === "receive" ? "This device" : session.deviceName} sub="Online" />
      </div>
      <h3>Transfer Details</h3>
      <DetailRows rows={[
        ["File name", session.fileName],
        ["Total size", session.bytesInfo || "—"],
        ["Files", String(session.totalFiles)],
        ["Type", tone.label],
        ["Estimated time", session.timeRemaining],
        ["Average speed", session.speed],
        ["Status", session.status],
      ]} />
      {session.status === "Failed" && <p className="df-kicker" style={{ color: "var(--df-danger)" }}>{friendlyTransferError(session.error)}</p>}
      <h3>Options</h3>
      <div className="df-info-row"><span><Moon size={14} /> Keep device awake until complete</span><button type="button" className="df-switch on" aria-checked disabled title="DropFlow already keeps this device awake during a transfer"><i /></button></div>
      <div className="df-info-row"><span><ShieldCheck size={14} /> Verify files after transfer</span><button type="button" className="df-switch on" aria-checked disabled title="Received files are checksummed by the transfer"><i /></button></div>
      <div className="df-tx-end">
        <button type="button" className="df-btn df-btn-soft" disabled title="A transfer can be cancelled, not paused"><Pause size={14} /> Pause</button>
        <button type="button" className="df-btn df-btn-danger" onClick={onCancel}><X size={14} /> Cancel</button>
      </div>
      {path && (
        <div className="df-tx-end">
          <button type="button" className="df-btn df-btn-primary" onClick={() => onOpen(path)}>Open</button>
          <button type="button" className="df-btn df-btn-soft" onClick={() => onReveal(path)}>Show</button>
        </div>
      )}
    </div>
  );
}

function HistoryDetail({ item, onOpen, onReveal }: { item: RecentTransfer; onOpen: (path: string) => void; onReveal: (path: string) => void }) {
  const tone = fileTone(item.fileName);
  const failed = item.status === "failed";
  return (
    <div className="df-tx-detail">
      <div className="df-side-hero">
        <span className="df-tile df-tile-lg" style={{ background: tone.bg, color: tone.fg }}>{tone.icon}</span>
        <div>
          <strong>{item.fileName}</strong>
          <span className={failed ? "df-done bad" : "df-done"}>{failed ? "Failed" : "Completed"}</span>
        </div>
      </div>
      <h3>Transfer Details</h3>
      <DetailRows rows={[
        ["File name", item.fileName],
        ["Total size", item.size],
        ["Files", String(item.totalFiles || 1)],
        ["Type", tone.label],
        ["Direction", item.direction === "receive" ? `From ${item.deviceName}` : `To ${item.deviceName}`],
        ["When", item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp],
        ["Status", failed ? friendlyTransferError(item.error) : "Completed"],
      ]} />
      <div className="df-file-stack">
        {(item.files || []).map((file, index) => (
          <div key={`${file.relativePath}-${index}`} className="df-file-row">
            <span className="df-grow df-ellipsis">{file.relativePath}</span>
            {file.finalPath && <button type="button" className="df-btn df-btn-soft df-btn-sm" onClick={() => onOpen(file.finalPath!)}>Open</button>}
            {file.finalPath && <button type="button" className="df-btn df-btn-soft df-btn-sm" onClick={() => onReveal(file.finalPath!)}>Show</button>}
          </div>
        ))}
      </div>
    </div>
  );
}

function Hop({ art, name, sub }: { art: string; name: string; sub: string }) {
  return (
    <div className="df-hop-node">
      <img src={art} alt="" />
      <strong>{name}</strong>
      <span>{sub}</span>
    </div>
  );
}

function DetailRows({ rows }: { rows: [string, string][] }) {
  const icons = [FileText, Gauge, Folder, FileArchive, Clock, Gauge, Check];
  return (
    <div>
      {rows.map(([label, value], index) => {
        const Icon = icons[index] || FileText;
        return (
          <div key={label} className="df-info-row">
            <span><Icon size={14} /> {label}</span>
            <b>{value}</b>
          </div>
        );
      })}
    </div>
  );
}

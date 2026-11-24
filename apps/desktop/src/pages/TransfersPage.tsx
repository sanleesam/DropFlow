import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import {
  ArrowDownLeft,
  ArrowLeftRight,
  ArrowUpRight,
  FileArchive,
  FileText,
  Folder,
  Image as ImageIcon,
  Play,
  X,
} from "lucide-react";
import { useToast } from "../components/ToastProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { friendlyTransferError } from "../ui/artwork";
import {
  DeviceVisual,
  EmptyState,
  Page,
  Progress,
  SearchField,
  Segmented,
} from "../components/ui/Desktop";
import {
  ActiveTransferSession,
  RecentTransfer,
  formatRelativeTimestamp,
} from "../utils/transferSessionManager";

type Filter = "all" | "send" | "receive" | "completed" | "failed";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "send", label: "Sent" },
  { id: "receive", label: "Received" },
  { id: "failed", label: "Failed" },
];

function live(session: ActiveTransferSession): boolean {
  return ["Preparing...", "Sending...", "Receiving...", "Finishing..."].includes(session.status);
}

function fileKind(name: string): { tone: string; icon: React.ReactNode; label: string } {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext))
    return { tone: "orange", icon: <FileArchive size={16} />, label: "Archive" };
  if (["png", "jpg", "jpeg", "gif", "webp", "heic", "svg"].includes(ext))
    return { tone: "blue", icon: <ImageIcon size={16} />, label: "Image" };
  if (["mp4", "mov", "mkv", "avi", "webm"].includes(ext))
    return { tone: "blue", icon: <Play size={16} />, label: "Video" };
  if (ext === "pdf") return { tone: "red", icon: <FileText size={16} />, label: "PDF" };
  return { tone: "", icon: <FileText size={16} />, label: ext && ext !== name.toLowerCase() ? ext.toUpperCase() : "File" };
}

function Spark({ values }: { values: number[] }) {
  const width = 240;
  const height = 34;
  if (values.length < 2) return <div className="spark" aria-hidden="true" />;
  const max = Math.max(...values, 1);
  const pts = values.map((value, index) => {
    const x = (index / (values.length - 1)) * width;
    const y = height - (value / max) * (height - 6) - 3;
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const line = pts.join(" ");
  return (
    <svg className="spark" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true">
      <polygon points={`0,${height} ${line} ${width},${height}`} fill="var(--accent)" opacity="0.12" />
      <polyline
        points={line}
        fill="none"
        stroke="var(--accent)"
        strokeWidth="1.6"
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
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
    if (filter === "failed") return ["failed", "Failed", "Cancelled"].includes(status);
    if (filter === "completed") return status === "completed" || status === "Completed";
    return true;
  };

  const visibleActive = active.filter((s) => matches(s.direction, s.status, s.fileName, s.deviceName));
  const visibleHistory = history.filter((i) => matches(i.direction, i.status, i.fileName, i.deviceName));
  const pickedHistory = visibleHistory.find((item) => item.id === picked);
  const selectedActive = pickedHistory
    ? undefined
    : visibleActive.find((session) => session.id === picked) || visibleActive[0];
  const selectedHistory = pickedHistory || (!selectedActive ? visibleHistory[0] : undefined);

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

  const nothing = active.length === 0 && history.length === 0;

  return (
    <Page
      title="Transfers"
      toolbar={
        <>
          <Segmented label="Filter transfers" options={FILTERS} value={filter} onChange={setFilter} />
          <SearchField value={query} onChange={setQuery} label="Search transfers" width={190} />
        </>
      }
    >
      {nothing ? (
        <div className="card">
          <EmptyState icon={<ArrowLeftRight size={20} />} title="No transfers yet">
            Sent and received files show up here.
          </EmptyState>
        </div>
      ) : (
        <div className="split">
          <div>
            {active.length > 0 && (
              <section className="section" aria-label="In progress">
                <div className="section-head">
                  <h2>In Progress</h2>
                  <span className="count">{visibleActive.length}</span>
                  <button
                    type="button"
                    className="btn btn-plain link"
                    disabled={visibleActive.length === 0}
                    onClick={() => visibleActive.forEach((s) => flow.cancelTransfer(s.id))}
                  >
                    Cancel All
                  </button>
                </div>
                <div className="card list">
                  {visibleActive.length === 0 && (
                    <EmptyState compact icon={<ArrowLeftRight size={18} />} title="No matches">
                      Nothing in progress matches.
                    </EmptyState>
                  )}
                  {visibleActive.map((session) => (
                    <ActiveRow
                      key={session.id}
                      session={session}
                      selected={selectedActive?.id === session.id}
                      onPick={() => setPicked(session.id)}
                      onCancel={() => flow.cancelTransfer(session.id)}
                    />
                  ))}
                </div>
              </section>
            )}

            <section className="section" aria-label="History">
              <div className="section-head">
                <h2>History</h2>
                <span className="count">{visibleHistory.length || ""}</span>
              </div>
              <div className="card list">
                {visibleHistory.length === 0 && (
                  <EmptyState compact icon={<Folder size={18} />} title={query || filter !== "all" ? "No matches" : "No history yet"}>
                    {query || filter !== "all" ? "Try another search or filter." : "Finished transfers appear here."}
                  </EmptyState>
                )}
                {visibleHistory.map((item) => (
                  <HistoryRow
                    key={item.id}
                    item={item}
                    selected={selectedHistory?.id === item.id}
                    onPick={() => setPicked(item.id)}
                    onReveal={revealSafe}
                  />
                ))}
              </div>
            </section>
          </div>

          <aside className="card inspector" aria-label="Transfer details">
            {selectedActive ? (
              <ActiveDetail
                session={selectedActive}
                speeds={flow.speedHistory[selectedActive.id] || []}
                onCancel={() => flow.cancelTransfer(selectedActive.id)}
                onOpen={openPathSafe}
                onReveal={revealSafe}
              />
            ) : selectedHistory ? (
              <HistoryDetail item={selectedHistory} onOpen={openPathSafe} onReveal={revealSafe} />
            ) : (
              <EmptyState compact icon={<ArrowLeftRight size={18} />} title="No selection">
                Pick a transfer.
              </EmptyState>
            )}
          </aside>
        </div>
      )}
    </Page>
  );
};

function ActiveRow({
  session,
  selected,
  onPick,
  onCancel,
}: {
  session: ActiveTransferSession;
  selected: boolean;
  onPick: () => void;
  onCancel: () => void;
}) {
  const kind = fileKind(session.fileName);
  return (
    <div className="row hit has-leading live-row" aria-selected={selected} onClick={onPick}>
      <span className={`tile ${kind.tone}`}>{kind.icon}</span>
      <button type="button" className="grow" style={{ textAlign: "left" }} onClick={onPick}>
        <div className="row-title ellipsis">
          {session.totalFiles > 1 ? `${session.totalFiles} files` : session.fileName}
        </div>
        <div className="row-sub ellipsis num">
          {session.direction === "receive" ? "From" : "To"} {session.deviceName} · {session.speed} ·{" "}
          {session.timeRemaining}
        </div>
        <Progress value={session.progress} label={`Transfer of ${session.fileName}`} />
      </button>
      <span className="row-trail num">{session.progress}%</span>
      <button
        type="button"
        className="icon-btn"
        aria-label="Cancel transfer"
        onClick={(event) => {
          event.stopPropagation();
          onCancel();
        }}
      >
        <X size={14} />
      </button>
    </div>
  );
}

function HistoryRow({
  item,
  selected,
  onPick,
  onReveal,
}: {
  item: RecentTransfer;
  selected: boolean;
  onPick: () => void;
  onReveal: (path: string) => void;
}) {
  const kind = fileKind(item.fileName);
  const failed = item.status === "failed";
  const path = item.files?.find((file) => file.finalPath)?.finalPath;
  return (
    <div className="row hit has-leading" aria-selected={selected} onClick={onPick}>
      <span className={`tile ${kind.tone}`}>{kind.icon}</span>
      <button type="button" className="grow" style={{ textAlign: "left" }} onClick={onPick}>
        <div className="row-title ellipsis">
          {item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}
        </div>
        <div className="row-sub ellipsis">
          {item.direction === "receive" ? (
            <ArrowDownLeft size={11} style={{ verticalAlign: "-1px" }} />
          ) : (
            <ArrowUpRight size={11} style={{ verticalAlign: "-1px" }} />
          )}{" "}
          {item.deviceName} · {item.size}
        </div>
      </button>
      <span className="row-trail">
        {path && (
          <span className="actions">
            <button
              type="button"
              className="icon-btn"
              aria-label="Show in folder"
              title="Show in folder"
              onClick={(event) => {
                event.stopPropagation();
                onReveal(path);
              }}
            >
              <Folder size={14} />
            </button>
          </span>
        )}
        {failed && <span className="tag bad">Failed</span>}
        <span className="num">
          {item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp}
        </span>
      </span>
    </div>
  );
}

function ActiveDetail({
  session,
  speeds,
  onCancel,
  onOpen,
  onReveal,
}: {
  session: ActiveTransferSession;
  speeds: number[];
  onCancel: () => void;
  onOpen: (path: string) => void;
  onReveal: (path: string) => void;
}) {
  const flow = useDropFlow();
  const kind = fileKind(session.fileName);
  const peer = flow.devices.find((device) => device.name === session.deviceName);
  const path = session.completedFiles.find((file) => file.finalPath)?.finalPath;
  const receiving = session.direction === "receive";
  return (
    <>
      <div className="hop" aria-label={receiving ? `From ${session.deviceName}` : `To ${session.deviceName}`}>
        <div className="hop-node">
          <DeviceVisual type={receiving ? peer?.type : flow.deviceType} local={!receiving} />
          <span>{receiving ? session.deviceName : "This device"}</span>
        </div>
        <span className="hop-link" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
        <div className="hop-node">
          <DeviceVisual type={receiving ? flow.deviceType : peer?.type} local={receiving} />
          <span>{receiving ? "This device" : session.deviceName}</span>
        </div>
      </div>
      <div>
        <div className="big-percent">{session.progress}%</div>
        <div style={{ marginTop: 12 }}>
          <Progress value={session.progress} label={`Transfer of ${session.fileName}`} />
        </div>
        <div className="stat-line">
          <span>{session.speed}</span>
          <span>{session.timeRemaining}</span>
        </div>
        <Spark values={speeds} />
      </div>
      <dl className="facts">
        <div>
          <dt>File</dt>
          <dd className="ellipsis" title={session.fileName}>{session.fileName}</dd>
        </div>
        <div>
          <dt>Size</dt>
          <dd>{session.bytesInfo || "—"}</dd>
        </div>
        <div>
          <dt>Files</dt>
          <dd>{session.totalFiles}</dd>
        </div>
        <div>
          <dt>Kind</dt>
          <dd>{kind.label}</dd>
        </div>
        <div>
          <dt>Status</dt>
          <dd>{session.status.replace("...", "")}</dd>
        </div>
      </dl>
      {session.status === "Failed" && (
        <p className="footnote" style={{ color: "var(--red)" }}>
          {friendlyTransferError(session.error)}
        </p>
      )}
      <div className="inspector-actions">
        {path && (
          <>
            <button type="button" className="btn btn-primary" onClick={() => onOpen(path)}>
              Open
            </button>
            <button type="button" className="btn" onClick={() => onReveal(path)}>
              Show
            </button>
          </>
        )}
        <button type="button" className="btn btn-destructive" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn" onClick={() => flow.dismissTransfer(session.id)}>
          Dismiss
        </button>
      </div>
    </>
  );
}

function HistoryDetail({
  item,
  onOpen,
  onReveal,
}: {
  item: RecentTransfer;
  onOpen: (path: string) => void;
  onReveal: (path: string) => void;
}) {
  const kind = fileKind(item.fileName);
  const failed = item.status === "failed";
  return (
    <>
      <div className="inspector-hero">
        <span className={`tile lg ${kind.tone}`}>{kind.icon}</span>
        <h2>{item.totalFiles > 1 ? `${item.totalFiles} files` : item.fileName}</h2>
        <span className={failed ? "tag bad" : "tag ok"}>{failed ? "Failed" : "Delivered"}</span>
      </div>
      <dl className="facts">
        <div>
          <dt>{item.direction === "receive" ? "From" : "To"}</dt>
          <dd>{item.deviceName}</dd>
        </div>
        <div>
          <dt>Size</dt>
          <dd>{item.size}</dd>
        </div>
        <div>
          <dt>Files</dt>
          <dd>{item.totalFiles || 1}</dd>
        </div>
        <div>
          <dt>Kind</dt>
          <dd>{kind.label}</dd>
        </div>
        <div>
          <dt>When</dt>
          <dd>{item.timestampMs ? formatRelativeTimestamp(item.timestampMs) : item.timestamp}</dd>
        </div>
      </dl>
      {failed && (
        <p className="footnote" style={{ color: "var(--red)" }}>
          {friendlyTransferError(item.error)}
        </p>
      )}
      {(item.files || []).length > 0 && (
        <div className="list" style={{ padding: 0 }}>
          {(item.files || []).map((file, index) => (
            <div key={`${file.relativePath}-${index}`} className="row" style={{ minHeight: 36, padding: "4px 0" }}>
              <span className="grow ellipsis" title={file.relativePath}>
                {file.relativePath}
              </span>
              {file.finalPath && (
                <>
                  <button type="button" className="btn btn-sm" onClick={() => onOpen(file.finalPath!)}>
                    Open
                  </button>
                  <button
                    type="button"
                    className="icon-btn"
                    aria-label={`Show ${file.relativePath} in folder`}
                    onClick={() => onReveal(file.finalPath!)}
                  >
                    <Folder size={14} />
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

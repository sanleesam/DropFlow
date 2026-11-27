import React, { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import {
  AlertCircle,
  ArrowUp,
  ChevronLeft,
  ChevronRight,
  Clock,
  Copy,
  Download,
  FileArchive,
  FileText,
  Folder,
  FolderOpen,
  FolderPlus,
  House,
  Image as ImageIcon,
  LayoutGrid,
  List,
  LoaderCircle,
  Monitor,
  Music,
  Pencil,
  SquareArrowOutUpRight,
  Trash2,
  Video,
} from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import {
  DeviceVisual,
  EmptyState,
  Page,
  SearchField,
  Segmented,
  ToolGroup,
} from "../components/ui/Desktop";
import { Glass } from "../ui/glass/LiquidGlass";
import { formatBytes } from "../utils/formatters";

interface ListedEntry {
  name: string;
  path: string;
  isDir: boolean;
  sizeBytes: number;
  modifiedMs: number;
}

interface FolderRoots {
  home: string;
  desktop: string;
  documents: string;
  downloads: string;
}

type Scope =
  | "browse"
  | "recent"
  | "images"
  | "videos"
  | "documents"
  | "music"
  | "archives";

const KINDS: Record<Exclude<Scope, "browse" | "recent">, string[]> = {
  images: ["png", "jpg", "jpeg", "gif", "webp", "heic", "svg", "bmp"],
  videos: ["mp4", "mov", "mkv", "avi", "webm", "m4v"],
  documents: ["pdf", "doc", "docx", "txt", "md", "rtf", "ppt", "pptx"],
  music: ["mp3", "wav", "flac", "aac", "m4a"],
  archives: ["zip", "rar", "7z", "tar", "gz"],
};

function extOf(name: string): string {
  return name.split(".").pop()?.toLowerCase() ?? "";
}

function typeLabel(name: string, isDir: boolean): string {
  if (isDir) return "Folder";
  const ext = extOf(name);
  if (KINDS.images.includes(ext)) return `Image (${ext.toUpperCase()})`;
  if (KINDS.videos.includes(ext)) return `Video (${ext.toUpperCase()})`;
  if (ext === "pdf") return "Document (PDF)";
  if (KINDS.archives.includes(ext)) return `Archive (${ext.toUpperCase()})`;
  if (KINDS.music.includes(ext)) return "Audio";
  if (!ext) return "File";
  return ext.toUpperCase();
}

function whenLabel(ms: number): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

function matchesKind(name: string, scope: Scope): boolean {
  if (scope === "browse" || scope === "recent") return true;
  return KINDS[scope].includes(extOf(name));
}

export const FilesPage: React.FC = () => {
  const flow = useDropFlow();
  const { settings } = useSettings();
  const { addToast } = useToast();
  const [roots, setRoots] = useState<FolderRoots | null>(null);
  const [cwd, setCwd] = useState("");
  const [back, setBack] = useState<string[]>([]);
  const [forward, setForward] = useState<string[]>([]);
  const [entries, setEntries] = useState<ListedEntry[]>([]);
  const [query, setQuery] = useState("");
  const [scope, setScope] = useState<Scope>("browse");
  const [picked, setPicked] = useState("");
  const [preview, setPreview] = useState("");
  const [grid, setGrid] = useState(false);
  const [making, setMaking] = useState(false);
  const [folderName, setFolderName] = useState("");
  const [renaming, setRenaming] = useState(false);
  const [nextName, setNextName] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const peer = flow.devices.find((device) => device.id === flow.filesDeviceId);
  const local = !peer;

  useEffect(() => {
    invoke<FolderRoots>("get_user_folders")
      .then((next) => {
        setRoots(next);
        // The browser is home-scoped; external receive folders open in the OS.
        const normalized = (path: string) => path.replace(/\\/g, "/").replace(/\/$/, "");
        const home = normalized(next.home);
        const receive = normalized(settings.receiveDirectory);
        const inHome = receive === home || receive.startsWith(home + "/");
        const start = settings.receiveDirectory && inHome ? settings.receiveDirectory : next.downloads || next.home;
        setCwd(start);
      })
      .catch(() =>
        setLoadError(
          "Couldn't load your local folders. Reopen this page to try again.",
        ),
      );
  }, [settings.receiveDirectory]);

  useEffect(() => {
    if (!local) setScope("recent");
  }, [flow.filesDeviceId, local]);

  useEffect(() => {
    if (!local || !cwd || scope === "recent") return;
    let current = true;
    setLoading(true);
    setLoadError(null);
    invoke<ListedEntry[]>("list_directory", { path: cwd })
      .then((next) => {
        if (current) setEntries(next);
      })
      .catch((err) => {
        console.error(err);
        if (current) {
          setEntries([]);
          setLoadError(
            "Couldn't read this folder. Check that it exists and you have access.",
          );
        }
      })
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [cwd, local, scope]);

  const historyRows = useMemo(() => {
    return flow.sessionStore.recentTransfers.flatMap((transfer) => {
      if (peer && transfer.deviceName !== peer.name) return [];
      const files =
        transfer.files && transfer.files.length > 0
          ? transfer.files.map((file, index) => ({
              key: `${transfer.id}-${index}`,
              name: file.relativePath.split(/[/\\]/).pop() || file.relativePath,
              path: file.finalPath,
              size: formatBytes(file.sizeBytes),
              when: transfer.timestampMs || 0,
            }))
          : [
              {
                key: transfer.id,
                name: transfer.fileName,
                path: undefined as string | undefined,
                size: transfer.size,
                when: transfer.timestampMs || 0,
              },
            ];
      return files.map((file) => ({
        ...file,
        deviceName: transfer.deviceName,
        direction: transfer.direction,
      }));
    });
  }, [flow.sessionStore.recentTransfers, peer]);

  const visibleEntries = entries.filter((entry) => {
    if (!matchesKind(entry.name, scope)) return false;
    const q = query.trim().toLowerCase();
    return !q || entry.name.toLowerCase().includes(q);
  });
  const folders =
    scope === "browse" ? visibleEntries.filter((entry) => entry.isDir) : [];
  const files =
    scope === "browse"
      ? visibleEntries.filter((entry) => !entry.isDir)
      : visibleEntries;
  const visibleHistory = historyRows.filter((row) => {
    if (!matchesKind(row.name, scope === "browse" ? "recent" : scope))
      return false;
    const q = query.trim().toLowerCase();
    return !q || row.name.toLowerCase().includes(q);
  });

  const currentEntry = entries.find((entry) => entry.path === picked);
  const currentHistory = historyRows.find((row) => row.key === picked);
  const currentPath = currentEntry?.path || currentHistory?.path || "";
  const currentName = currentEntry?.name || currentHistory?.name || "";

  useEffect(() => {
    setPreview("");
    setRenaming(false);
    setConfirmDelete(false);
    if (!currentPath) return;
    invoke<string>("read_image_preview", { path: currentPath })
      .then(setPreview)
      .catch(() => setPreview(""));
  }, [currentPath]);

  const go = (next: string, remember = true) => {
    if (!next || next === cwd) return;
    if (remember && cwd) setBack((prev) => [...prev, cwd]);
    setForward([]);
    setScope("browse");
    setCwd(next);
    setPicked("");
    flow.focusDeviceFiles("this");
  };

  const crumb = cwd.split(/[/\\]/).filter(Boolean);

  const reload = () => {
    if (!cwd) return;
    setLoading(true);
    setLoadError(null);
    invoke<ListedEntry[]>("list_directory", { path: cwd })
      .then(setEntries)
      .catch(() =>
        setLoadError(
          "Couldn't read this folder. Check that it exists and you have access.",
        ),
      )
      .finally(() => setLoading(false));
  };

  const createFolder = async () => {
    const name = folderName.trim();
    if (!name || !cwd) return;
    try {
      await invoke("create_folder", { parent: cwd, name });
      setFolderName("");
      setMaking(false);
      reload();
    } catch (err) {
      addToast(
        String(err).replace(/^Error:\s*/, "") || "Couldn't create that folder.",
        "error",
      );
    }
  };

  const rename = async () => {
    if (!currentPath || !nextName.trim()) return;
    try {
      const next = await invoke<string>("rename_path", {
        path: currentPath,
        newName: nextName.trim(),
      });
      setPicked(next);
      setRenaming(false);
      reload();
    } catch (err) {
      addToast("Couldn't rename that.", "error");
      console.error(err);
    }
  };

  const remove = async () => {
    if (!currentPath) return;
    try {
      await invoke("delete_path", { path: currentPath });
      setPicked("");
      setConfirmDelete(false);
      reload();
    } catch (err) {
      addToast(
        typeof err === "string" ? err : "Couldn't delete that.",
        "error",
      );
    }
  };

  const sendCurrent = () => {
    if (!currentPath) {
      addToast("Pick a file on this device to send.", "error");
      return;
    }
    if (peer) flow.selectDevice(peer.id);
    flow.queueFiles([currentPath]);
  };

  const copyPath = async () => {
    if (!currentPath) return;
    try {
      await navigator.clipboard.writeText(currentPath);
      addToast("Path copied.", "success");
    } catch {
      addToast("Couldn't copy that path.", "error");
    }
  };

  const goBack = () => {
    const prev = back[back.length - 1];
    setBack((stack) => stack.slice(0, -1));
    if (cwd) setForward((stack) => [cwd, ...stack]);
    setCwd(prev);
    setScope("browse");
  };
  const goForward = () => {
    const next = forward[0];
    setForward((stack) => stack.slice(1));
    if (cwd) setBack((stack) => [...stack, cwd]);
    setCwd(next);
    setScope("browse");
  };
  const browsing = local && scope !== "recent";
  const title = !local ? peer?.name || "Files" : scope === "browse" ? crumb[crumb.length - 1] || "Files" : SCOPE_LABEL[scope];

  return (
    <Page
      fill
      title={title}
      toolbar={
        <>
          <ToolGroup label="History">
            <button type="button" className="tool-btn" aria-label="Back" disabled={back.length === 0} onClick={goBack}>
              <ChevronLeft size={17} strokeWidth={2} />
            </button>
            <button type="button" className="tool-btn" aria-label="Forward" disabled={forward.length === 0} onClick={goForward}>
              <ChevronRight size={17} strokeWidth={2} />
            </button>
          </ToolGroup>
          <Segmented
            label="View"
            value={grid ? "grid" : "list"}
            onChange={(value) => setGrid(value === "grid")}
            options={[
              { id: "list", label: <List size={15} />, title: "List view" },
              { id: "grid", label: <LayoutGrid size={15} />, title: "Grid view" },
            ]}
          />
          <SearchField value={query} onChange={setQuery} label="Search files and folders" width={190} />
          <div style={{ position: "relative" }}>
            <ToolGroup label="Create">
              <button
                type="button"
                className="tool-btn"
                aria-label="New folder"
                title="New folder"
                aria-expanded={making}
                disabled={!browsing}
                onClick={() => setMaking((open) => !open)}
              >
                <FolderPlus size={16} strokeWidth={1.9} />
              </button>
            </ToolGroup>
            {making && (
              <Glass size="md" variant="thick" className="new-menu">
                <input
                  className="field"
                  aria-label="New folder name"
                  placeholder="Folder name"
                  autoFocus
                  value={folderName}
                  onChange={(event) => setFolderName(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") createFolder();
                    if (event.key === "Escape") setMaking(false);
                  }}
                />
                <button type="button" className="btn btn-primary" disabled={!folderName.trim()} onClick={createFolder}>
                  Create Folder
                </button>
              </Glass>
            )}
          </div>
        </>
      }
    >
      <nav className="card source-list" aria-label="Locations">
        <h3>Devices</h3>
        <button
          type="button"
          className="source"
          aria-current={local && scope === "browse"}
          onClick={() => {
            flow.focusDeviceFiles("this");
            setScope("browse");
          }}
        >
          <DeviceVisual type={flow.deviceType} small local />
          <span>{settings.deviceName || "This device"}</span>
        </button>
        {flow.devices.map((device) => (
          <button
            key={device.id}
            type="button"
            className="source"
            aria-current={flow.filesDeviceId === device.id}
            onClick={() => flow.focusDeviceFiles(device.id)}
          >
            <DeviceVisual type={device.type} small />
            <span>{device.name}</span>
          </button>
        ))}
        <h3>Locations</h3>
        <SourceButton icon={<Clock size={15} />} label="Recent" on={local && scope === "recent"} onClick={() => { flow.focusDeviceFiles("this"); setScope("recent"); }} />
        <SourceButton
          icon={<Download size={15} />}
          label="Downloads"
          on={local && cwd === (settings.receiveDirectory || roots?.downloads) && scope === "browse"}
          onClick={() => roots && go(settings.receiveDirectory || roots.downloads)}
        />
        <SourceButton icon={<Monitor size={15} />} label="Desktop" on={local && cwd === roots?.desktop && scope === "browse"} onClick={() => roots && go(roots.desktop)} />
        <SourceButton icon={<FileText size={15} />} label="Documents" on={local && cwd === roots?.documents && scope === "browse"} onClick={() => roots && go(roots.documents)} />
        <h3>Kinds</h3>
        <SourceButton icon={<ImageIcon size={15} />} label="Images" on={scope === "images"} onClick={() => setScope("images")} />
        <SourceButton icon={<Video size={15} />} label="Videos" on={scope === "videos"} onClick={() => setScope("videos")} />
        <SourceButton icon={<FileText size={15} />} label="Documents" on={scope === "documents"} onClick={() => setScope("documents")} />
        <SourceButton icon={<Music size={15} />} label="Music" on={scope === "music"} onClick={() => setScope("music")} />
        <SourceButton icon={<FileArchive size={15} />} label="Archives" on={scope === "archives"} onClick={() => setScope("archives")} />
      </nav>

      <section className="card browser" aria-label="Files">
        <div className="pathbar">
          {browsing ? (
            <>
              <button type="button" className="crumb" aria-label="Home folder" onClick={() => roots && go(roots.home)}>
                <House size={13} />
              </button>
              {crumb.map((part, index) => (
                <span key={`${part}-${index}`} style={{ display: "contents" }}>
                  <ChevronRight size={12} className="chev" />
                  <button type="button" className="crumb" onClick={() => go(buildPath(cwd, index))}>
                    {part}
                  </button>
                </span>
              ))}
            </>
          ) : (
            <span className="crumb" style={{ color: "var(--label)", fontWeight: 600 }}>
              {peer ? `Exchanged with ${peer.name}` : "Recent transfers"}
            </span>
          )}
        </div>
        <div className="browser-body">
          {browsing && loading ? (
            <EmptyState icon={<LoaderCircle size={20} />} title="Loading">
              Reading this folder…
            </EmptyState>
          ) : browsing && loadError ? (
            <EmptyState
              icon={<AlertCircle size={20} />}
              title="Folder unavailable"
              action={
                cwd ? (
                  <button type="button" className="btn" onClick={reload}>
                    Try Again
                  </button>
                ) : (
                  <button type="button" className="btn" onClick={() => flow.setPage("home")}>
                    Back to Home
                  </button>
                )
              }
            >
              {loadError}
            </EmptyState>
          ) : !browsing ? (
            visibleHistory.length === 0 ? (
              <Empty query={query} recent />
            ) : grid ? (
              <div className="icon-grid">
                {visibleHistory.map((row) => (
                  <button key={row.key} type="button" aria-selected={picked === row.key} onClick={() => setPicked(row.key)}>
                    <FileIcon name={row.name} size={30} />
                    <strong title={row.name}>{row.name}</strong>
                    <small>{row.size}</small>
                  </button>
                ))}
              </div>
            ) : (
              <table className="table">
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Size</th>
                    <th>Kind</th>
                    <th>Device</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleHistory.map((row) => (
                    <tr key={row.key} aria-selected={picked === row.key} onClick={() => setPicked(row.key)}>
                      <td>
                        <button type="button" className="name-cell" title={row.name} onClick={() => setPicked(row.key)}>
                          <FileIcon name={row.name} size={15} />
                          <span>{row.name}</span>
                        </button>
                      </td>
                      <td>{row.size}</td>
                      <td>{typeLabel(row.name, false)}</td>
                      <td>
                        {row.direction === "receive" ? "From" : "To"} {row.deviceName}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          ) : visibleEntries.length === 0 ? (
            <Empty query={query} />
          ) : grid ? (
            <div className="icon-grid">
              {visibleEntries.map((entry) => (
                <button
                  key={entry.path}
                  type="button"
                  aria-selected={picked === entry.path}
                  onClick={() => setPicked(entry.path)}
                  onDoubleClick={() => entry.isDir && go(entry.path)}
                >
                  {entry.isDir ? <Folder size={30} fill="currentColor" fillOpacity={0.18} /> : <FileIcon name={entry.name} size={30} />}
                  <strong title={entry.name}>{entry.name}</strong>
                  <small>{entry.isDir ? "Folder" : formatBytes(entry.sizeBytes)}</small>
                </button>
              ))}
            </div>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Size</th>
                  <th>Kind</th>
                  <th>Modified</th>
                </tr>
              </thead>
              <tbody>
                {[...folders, ...files].map((entry) => (
                  <tr
                    key={entry.path}
                    aria-selected={picked === entry.path}
                    onClick={() => setPicked(entry.path)}
                    onDoubleClick={() => entry.isDir && go(entry.path)}
                  >
                    <td>
                      <button
                        type="button"
                        className="name-cell"
                        title={entry.isDir ? `${entry.name} — double-click to open` : entry.name}
                        onClick={() => setPicked(entry.path)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter" && entry.isDir) go(entry.path);
                        }}
                      >
                        {entry.isDir ? <Folder size={15} fill="currentColor" fillOpacity={0.18} /> : <FileIcon name={entry.name} size={15} />}
                        <span>{entry.name}</span>
                      </button>
                    </td>
                    <td>{entry.isDir ? "—" : formatBytes(entry.sizeBytes)}</td>
                    <td>{typeLabel(entry.name, entry.isDir)}</td>
                    <td>{whenLabel(entry.modifiedMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </section>

      <aside className="card file-inspector" aria-label="File details">
        {currentName ? (
          <>
            {preview ? (
              <img className="preview" src={preview} alt="" />
            ) : (
              <div className="preview preview-empty">
                {currentEntry?.isDir ? <Folder size={40} strokeWidth={1.4} /> : <FileIcon name={currentName} size={40} />}
              </div>
            )}
            <div>
              <h2 style={{ font: "700 15px/1.3 var(--font-display)", overflowWrap: "anywhere" }}>{currentName}</h2>
              <p className="footnote" style={{ marginTop: 3 }}>
                {typeLabel(currentName, !!currentEntry?.isDir)}
                {currentEntry && !currentEntry.isDir ? ` · ${formatBytes(currentEntry.sizeBytes)}` : ""}
                {currentHistory ? ` · ${currentHistory.size}` : ""}
              </p>
            </div>
            <div className="quick-actions">
              <button type="button" disabled={!currentPath || !!currentEntry?.isDir} onClick={sendCurrent}>
                <ArrowUp size={16} />
                Send
              </button>
              <button
                type="button"
                disabled={!currentPath}
                onClick={() => currentPath && openPath(currentPath).catch(() => addToast("Couldn't open that file.", "error"))}
              >
                <SquareArrowOutUpRight size={16} />
                Open
              </button>
              <button
                type="button"
                disabled={!currentPath}
                onClick={() => currentPath && revealItemInDir(currentPath).catch(() => addToast("Couldn't show that file.", "error"))}
              >
                <FolderOpen size={16} />
                Show
              </button>
              <button type="button" disabled={!currentPath} onClick={copyPath}>
                <Copy size={16} />
                Copy
              </button>
            </div>
            <dl className="facts">
              <div>
                <dt>Kind</dt>
                <dd>{typeLabel(currentName, !!currentEntry?.isDir)}</dd>
              </div>
              {currentEntry && !currentEntry.isDir && (
                <div>
                  <dt>Size</dt>
                  <dd>{currentEntry.sizeBytes.toLocaleString()} bytes</dd>
                </div>
              )}
              <div>
                <dt>{currentEntry ? "Where" : "Device"}</dt>
                <dd className="ellipsis" title={currentEntry ? cwd : currentHistory?.deviceName}>
                  {currentEntry ? crumb[crumb.length - 1] || cwd : currentHistory?.deviceName}
                </dd>
              </div>
              {currentEntry && (
                <div>
                  <dt>Modified</dt>
                  <dd>{whenLabel(currentEntry.modifiedMs)}</dd>
                </div>
              )}
            </dl>
            {currentEntry && (
              <div className="menu-list">
                {renaming ? (
                  <div style={{ display: "flex", gap: 6 }}>
                    <input
                      className="field"
                      aria-label="Rename file"
                      autoFocus
                      value={nextName}
                      onChange={(event) => setNextName(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") rename();
                        if (event.key === "Escape") setRenaming(false);
                      }}
                    />
                    <button type="button" className="btn btn-primary" onClick={rename}>
                      Save
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    className="menu-item"
                    onClick={() => {
                      setNextName(currentName);
                      setRenaming(true);
                    }}
                  >
                    <Pencil size={14} /> Rename
                  </button>
                )}
                {confirmDelete ? (
                  <div style={{ display: "flex", gap: 6 }}>
                    <button type="button" className="menu-item confirm grow" onClick={remove}>
                      <Trash2 size={14} /> Delete “{currentName}”
                    </button>
                    <button type="button" className="btn" onClick={() => setConfirmDelete(false)}>
                      Keep
                    </button>
                  </div>
                ) : (
                  <button type="button" className="menu-item destructive" onClick={() => setConfirmDelete(true)}>
                    <Trash2 size={14} /> Delete…
                  </button>
                )}
              </div>
            )}
          </>
        ) : (
          <EmptyState compact icon={<FileText size={18} />} title="No selection">
            Select an item to see details.
          </EmptyState>
        )}
      </aside>
    </Page>
  );
};

const SCOPE_LABEL: Record<Scope, string> = {
  browse: "Files",
  recent: "Recent",
  images: "Images",
  videos: "Videos",
  documents: "Documents",
  music: "Music",
  archives: "Archives",
};

function FileIcon({ name, size }: { name: string; size: number }) {
  const ext = extOf(name);
  const Icon = KINDS.images.includes(ext)
    ? ImageIcon
    : KINDS.videos.includes(ext)
      ? Video
      : KINDS.music.includes(ext)
        ? Music
        : KINDS.archives.includes(ext)
          ? FileArchive
          : FileText;
  return <Icon size={size} strokeWidth={size > 20 ? 1.4 : 1.8} />;
}

function buildPath(cwd: string, index: number): string {
  const raw = cwd.split(/[/\\]/);
  const kept = raw.slice(0, index + 1);
  if (cwd.includes("\\")) {
    const joined = kept.join("\\");
    return joined.endsWith(":") ? `${joined}\\` : joined;
  }
  return kept.join("/") || "/";
}

function SourceButton({
  icon,
  label,
  on,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  on: boolean;
  onClick: () => void;
}) {
  return (
    <button type="button" className="source" aria-current={on} onClick={onClick}>
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Empty({ query, recent }: { query: string; recent?: boolean }) {
  return (
    <EmptyState
      icon={<Folder size={20} />}
      title={query ? "No matches" : recent ? "No files yet" : "Empty folder"}
    >
      {query
        ? `Nothing matches “${query}”.`
        : recent
          ? "Sent and received files appear here."
          : "Nothing in here yet."}
    </EmptyState>
  );
}

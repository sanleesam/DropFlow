import React, { useEffect, useMemo, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { ChevronLeft, ChevronRight, Clock, Copy, Download, FileArchive, FileText, Folder, Home, Image as ImageIcon, LayoutGrid, List, MoreHorizontal, Music, Plus, Search, Send, Star, Trash2, Video } from "lucide-react";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { useDropFlow } from "../session/DropFlowProvider";
import { deviceArt } from "../ui/artwork";
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

type Scope = "browse" | "recent" | "images" | "videos" | "documents" | "music" | "archives";

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
  return new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" });
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
  const [deviceOpen, setDeviceOpen] = useState(true);

  const peer = flow.devices.find((device) => device.id === flow.filesDeviceId);
  const local = !peer;

  useEffect(() => {
    invoke<FolderRoots>("get_user_folders").then((next) => {
      setRoots(next);
      const start = settings.receiveDirectory || next.desktop || next.home;
      setCwd(start);
    }).catch(() => undefined);
  }, [settings.receiveDirectory]);

  useEffect(() => {
    if (!local) setScope("recent");
  }, [flow.filesDeviceId, local]);

  useEffect(() => {
    if (!local || !cwd || scope === "recent") return;
    invoke<ListedEntry[]>("list_directory", { path: cwd }).then(setEntries).catch((err) => {
      console.error(err);
      setEntries([]);
    });
  }, [cwd, local, scope]);

  const historyRows = useMemo(() => {
    return flow.sessionStore.recentTransfers.flatMap((transfer) => {
      if (peer && transfer.deviceName !== peer.name) return [];
      const files = transfer.files && transfer.files.length > 0
        ? transfer.files.map((file, index) => ({ key: `${transfer.id}-${index}`, name: file.relativePath.split(/[/\\]/).pop() || file.relativePath, path: file.finalPath, size: formatBytes(file.sizeBytes), when: transfer.timestampMs || 0 }))
        : [{ key: transfer.id, name: transfer.fileName, path: undefined as string | undefined, size: transfer.size, when: transfer.timestampMs || 0 }];
      return files.map((file) => ({ ...file, deviceName: transfer.deviceName, direction: transfer.direction }));
    });
  }, [flow.sessionStore.recentTransfers, peer]);

  const visibleEntries = entries.filter((entry) => {
    if (!matchesKind(entry.name, scope)) return false;
    const q = query.trim().toLowerCase();
    return !q || entry.name.toLowerCase().includes(q);
  });
  const folders = scope === "browse" ? visibleEntries.filter((entry) => entry.isDir) : [];
  const files = scope === "browse" ? visibleEntries.filter((entry) => !entry.isDir) : visibleEntries;
  const visibleHistory = historyRows.filter((row) => {
    if (!matchesKind(row.name, scope === "browse" ? "recent" : scope)) return false;
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
    invoke<string>("read_image_preview", { path: currentPath }).then(setPreview).catch(() => setPreview(""));
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
    invoke<ListedEntry[]>("list_directory", { path: cwd }).then(setEntries).catch(() => undefined);
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
      addToast(String(err).replace(/^Error:\s*/, "") || "Couldn't create that folder.", "error");
    }
  };

  const rename = async () => {
    if (!currentPath || !nextName.trim()) return;
    try {
      const next = await invoke<string>("rename_path", { path: currentPath, newName: nextName.trim() });
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
      addToast(typeof err === "string" ? err : "Couldn't delete that.", "error");
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

  return (
    <div className="df-page df-files-page">
      <header className="df-page-bar">
        <div>
          <h1>Files</h1>
          <p>Browse, manage and transfer files across your devices</p>
        </div>
        <div className="df-page-tools">
          <label className="df-search df-search-wide">
            <Search size={15} />
            <input value={query} placeholder="Search files and folders..." onChange={(event) => setQuery(event.target.value)} />
          </label>
          <button type="button" className={!grid ? "df-icon-btn on" : "df-icon-btn"} aria-label="List" onClick={() => setGrid(false)}><List size={16} /></button>
          <button type="button" className={grid ? "df-icon-btn on" : "df-icon-btn"} aria-label="Grid" onClick={() => setGrid(true)}><LayoutGrid size={16} /></button>
          <div className="df-new">
            <button type="button" className="df-btn df-btn-primary" onClick={() => setMaking((open) => !open)}><Plus size={16} /> New</button>
            {making && (
              <div className="df-new-menu">
                <input className="df-input" placeholder="Folder name" value={folderName} onChange={(event) => setFolderName(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") createFolder(); }} />
                <button type="button" className="df-btn df-btn-primary df-btn-sm" onClick={createFolder}>Create folder</button>
                <button type="button" className="df-btn df-btn-soft df-btn-sm" onClick={flow.browseFiles}>Send files</button>
              </div>
            )}
          </div>
        </div>
      </header>

      <div className="df-files-layout">
        <aside className="df-card df-files-side">
          <button type="button" className="df-files-label" onClick={() => setDeviceOpen((open) => !open)}>My Devices <span>{deviceOpen ? "▾" : "▸"}</span></button>
          {deviceOpen && (
            <div className="df-files-devices">
              <button type="button" className={local ? "on" : ""} onClick={() => { flow.focusDeviceFiles("this"); setScope("browse"); }}>
                <img src={deviceArt(flow.deviceType)} alt="" />
                <span>{settings.deviceName || "This device"}</span>
              </button>
              {flow.devices.map((device) => (
                <button key={device.id} type="button" className={flow.filesDeviceId === device.id ? "on" : ""} onClick={() => flow.focusDeviceFiles(device.id)}>
                  <img src={deviceArt(device.type)} alt="" />
                  <span>{device.name}</span>
                </button>
              ))}
            </div>
          )}
          <p className="df-files-label">Locations</p>
          <NavButton icon={<Clock size={15} />} label="Recent" on={scope === "recent"} onClick={() => setScope("recent")} />
          <NavButton icon={<Star size={15} />} label="Downloads" on={cwd === (settings.receiveDirectory || roots?.downloads) && scope === "browse"} onClick={() => roots && go(settings.receiveDirectory || roots.downloads)} />
          <NavButton icon={<ImageIcon size={15} />} label="Images" on={scope === "images"} onClick={() => setScope("images")} />
          <NavButton icon={<Video size={15} />} label="Videos" on={scope === "videos"} onClick={() => setScope("videos")} />
          <NavButton icon={<FileText size={15} />} label="Documents" on={scope === "documents"} onClick={() => setScope("documents")} />
          <NavButton icon={<Music size={15} />} label="Music" on={scope === "music"} onClick={() => setScope("music")} />
          <NavButton icon={<FileArchive size={15} />} label="Archives" on={scope === "archives"} onClick={() => setScope("archives")} />
        </aside>

        <section className="df-card df-files-main">
          <div className="df-crumb">
            <button type="button" aria-label="Back" disabled={back.length === 0} onClick={() => { const prev = back[back.length - 1]; setBack((stack) => stack.slice(0, -1)); if (cwd) setForward((stack) => [cwd, ...stack]); setCwd(prev); setScope("browse"); }}><ChevronLeft size={16} /></button>
            <button type="button" aria-label="Forward" disabled={forward.length === 0} onClick={() => { const next = forward[0]; setForward((stack) => stack.slice(1)); if (cwd) setBack((stack) => [...stack, cwd]); setCwd(next); setScope("browse"); }}><ChevronRight size={16} /></button>
            <button type="button" aria-label="Home" onClick={() => roots && go(roots.desktop || roots.home)}><Home size={15} /></button>
            {local && scope !== "recent" ? crumb.map((part, index) => (
              <button key={`${part}-${index}`} type="button" onClick={() => go(buildPath(cwd, index))}>{part}</button>
            )) : <span>{peer ? peer.name : "Recent"}</span>}
          </div>

          {!local && <p className="df-kicker df-files-note">Files exchanged with {peer?.name}. DropFlow can't browse folders on the other device.</p>}

          {scope !== "recent" && local && folders.length > 0 && (
            <div className="df-folder-strip">
              {folders.slice(0, 8).map((folder) => (
                <button key={folder.path} type="button" className="df-folder-tile" onDoubleClick={() => go(folder.path)} onClick={() => setPicked(folder.path)}>
                  <Folder size={28} />
                  <strong>{folder.name}</strong>
                </button>
              ))}
            </div>
          )}

          {scope === "recent" || !local ? (
            visibleHistory.length === 0 ? <Empty query={query} recent /> : grid ? (
              <div className="df-file-grid">
                {visibleHistory.map((row) => (
                  <button key={row.key} type="button" className={picked === row.key ? "on" : ""} onClick={() => setPicked(row.key)}>
                    <FileText size={22} />
                    <strong>{row.name}</strong>
                    <small>{row.size}</small>
                  </button>
                ))}
              </div>
            ) : (
              <table className="df-table">
                <thead><tr><th>Name</th><th>Size</th><th>Type</th><th>Device</th></tr></thead>
                <tbody>
                  {visibleHistory.map((row) => (
                    <tr key={row.key} className={picked === row.key ? "picked" : ""} onClick={() => setPicked(row.key)}>
                      <td><span className="df-name-cell"><FileText size={15} /> {row.name}</span></td>
                      <td>{row.size}</td>
                      <td>{typeLabel(row.name, false)}</td>
                      <td>{row.direction === "receive" ? "From" : "To"} {row.deviceName}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )
          ) : files.length === 0 && folders.length === 0 ? <Empty query={query} /> : grid ? (
            <div className="df-file-grid">
              {visibleEntries.map((entry) => (
                <button key={entry.path} type="button" className={picked === entry.path ? "on" : ""} onClick={() => setPicked(entry.path)} onDoubleClick={() => entry.isDir && go(entry.path)}>
                  {entry.isDir ? <Folder size={22} /> : <FileText size={22} />}
                  <strong>{entry.name}</strong>
                  <small>{entry.isDir ? "Folder" : formatBytes(entry.sizeBytes)}</small>
                </button>
              ))}
            </div>
          ) : (
            <table className="df-table">
              <thead><tr><th>Name</th><th>Size</th><th>Type</th><th>Modified</th></tr></thead>
              <tbody>
                {files.map((entry) => (
                  <tr key={entry.path} className={picked === entry.path ? "picked" : ""} onClick={() => setPicked(entry.path)} onDoubleClick={() => entry.isDir && go(entry.path)}>
                    <td><span className="df-name-cell"><FileText size={15} /> {entry.name}</span></td>
                    <td>{formatBytes(entry.sizeBytes)}</td>
                    <td>{typeLabel(entry.name, false)}</td>
                    <td>{whenLabel(entry.modifiedMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <aside className="df-card df-files-detail">
          {currentName ? (
            <>
              {preview ? <img className="df-preview" src={preview} alt="" /> : <div className="df-preview df-preview-empty"><FileText size={28} /></div>}
              <h2>{currentName}</h2>
              <p className="df-kicker">{currentEntry ? (currentEntry.isDir ? "Folder" : formatBytes(currentEntry.sizeBytes)) : currentHistory?.size} · {typeLabel(currentName, !!currentEntry?.isDir)}</p>
              <div className="df-side-actions df-side-actions-4">
                <button type="button" onClick={sendCurrent}><Send size={16} /><span>Send</span></button>
                <button type="button" disabled={!currentPath} onClick={() => currentPath && revealItemInDir(currentPath).catch(() => addToast("Couldn't show that file.", "error"))}><Folder size={16} /><span>Show</span></button>
                <button type="button" disabled={!currentPath} onClick={() => currentPath && openPath(currentPath).catch(() => addToast("Couldn't open that file.", "error"))}><Download size={16} /><span>Open</span></button>
                <button type="button" onClick={copyPath}><MoreHorizontal size={16} /><span>Copy path</span></button>
              </div>
              <h3>File Information</h3>
              <div className="df-info-row"><span>Name</span><b>{currentName}</b></div>
              <div className="df-info-row"><span>Type</span><b>{typeLabel(currentName, !!currentEntry?.isDir)}</b></div>
              <div className="df-info-row"><span>Size</span><b>{currentEntry ? (currentEntry.isDir ? "—" : `${formatBytes(currentEntry.sizeBytes)} (${currentEntry.sizeBytes.toLocaleString()} bytes)`) : currentHistory?.size}</b></div>
              <div className="df-info-row"><span>Location</span><b className="df-ellipsis">{currentEntry ? cwd : currentHistory?.deviceName}</b></div>
              {currentEntry && <div className="df-info-row"><span>Modified</span><b>{whenLabel(currentEntry.modifiedMs)}</b></div>}
              <h3>Quick Actions</h3>
              {renaming ? (
                <div className="df-rename">
                  <input className="df-input" value={nextName} onChange={(event) => setNextName(event.target.value)} />
                  <button type="button" className="df-btn df-btn-primary df-btn-sm" onClick={rename}>Save</button>
                </div>
              ) : (
                <button type="button" className="df-action" disabled={!currentPath} onClick={() => { setNextName(currentName); setRenaming(true); }}><span>Rename</span></button>
              )}
              <button type="button" className="df-action" onClick={copyPath}><Copy size={14} /> Copy path</button>
              {confirmDelete ? (
                <button type="button" className="df-action danger" onClick={remove}>Confirm delete</button>
              ) : (
                <button type="button" className="df-action danger" disabled={!currentPath} onClick={() => setConfirmDelete(true)}><Trash2 size={14} /> Delete</button>
              )}
            </>
          ) : (
            <p className="df-kicker">Select a file to see it here.</p>
          )}
        </aside>
      </div>
    </div>
  );
};

function buildPath(cwd: string, index: number): string {
  const raw = cwd.split(/[/\\]/);
  const kept = raw.slice(0, index + 1);
  if (cwd.includes("\\")) {
    const joined = kept.join("\\");
    return joined.endsWith(":") ? `${joined}\\` : joined;
  }
  return kept.join("/") || "/";
}

function NavButton({ icon, label, on, onClick }: { icon: React.ReactNode; label: string; on: boolean; onClick: () => void }) {
  return (
    <button type="button" className={on ? "df-loc on" : "df-loc"} onClick={onClick}>{icon} {label}</button>
  );
}

function Empty({ query, recent }: { query: string; recent?: boolean }) {
  return (
    <div className="df-card-pad">
      <h2 className="df-section-title">{query ? "No matching files" : recent ? "No files yet" : "This folder is empty"}</h2>
      <p className="df-kicker">{query ? `Nothing matches “${query}”.` : recent ? "Send or receive a file and it will show up here." : "Open another folder, or create one."}</p>
    </div>
  );
}

import React, { useCallback, useEffect, useState } from "react";
import { X } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";
import { open } from "@tauri-apps/plugin-dialog";
import { getCurrentWindow } from "@tauri-apps/api/window";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SelectedFilePayload {
  /** Unique key for React list rendering */
  uid: string;
  /** Absolute filesystem path (e.g., "/Users/name/Desktop/file.png") */
  path: string;
  /** Display file name (e.g., "file.png") */
  name: string;
}

interface FileDropZoneProps {
  /** ID of the currently selected device (null = none) */
  selectedDeviceId: string | null;
  /** Callback fired when user initiates file sending with native absolute paths */
  onSend?: (selectedFiles: SelectedFilePayload[]) => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function uid(): string {
  return Math.random().toString(36).slice(2, 10);
}

function extractFilename(filePath: string): string {
  const normalized = filePath.replace(/\\/g, "/");
  const parts = normalized.split("/");
  return parts[parts.length - 1] || filePath;
}

// ─── File type icon ───────────────────────────────────────────────────────────

function fileIconColor(name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["jpg", "jpeg", "png", "gif", "webp", "svg", "avif"].includes(ext))
    return "text-emerald-400";
  if (["mp4", "mov", "avi", "mkv", "webm"].includes(ext))
    return "text-purple-400";
  if (["mp3", "wav", "flac", "aac"].includes(ext)) return "text-pink-400";
  if (["pdf"].includes(ext)) return "text-red-400";
  if (["zip", "tar", "gz", "7z", "rar"].includes(ext)) return "text-amber-400";
  if (["js", "ts", "tsx", "jsx", "py", "go", "rs", "json"].includes(ext))
    return "text-blue-400";
  return "text-neutral-400";
}

const IconFile: React.FC<{ name: string }> = ({ name }) => (
  <svg
    viewBox="0 0 24 24"
    className={`w-4 h-4 flex-shrink-0 ${fileIconColor(name)}`}
    fill="none"
    stroke="currentColor"
    strokeWidth="1.6"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>
);

const IconUploadLarge: React.FC = () => (
  <svg
    viewBox="0 0 48 48"
    className="w-7 h-7 text-neutral-400"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.5"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M24 32V12M16 20l8-8 8 8" />
    <path d="M8 36h32" opacity="0.4" />
  </svg>
);

// ─── FileDropZone ─────────────────────────────────────────────────────────────

const FileDropZone: React.FC<FileDropZoneProps> = ({ selectedDeviceId, onSend }) => {
  const { addToast } = useToast();
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [files, setFiles] = useState<SelectedFilePayload[]>([]);
  const [isDraggingOver, setIsDraggingOver] = useState(false);

  const hasFiles = files.length > 0;
  const hasDevice = selectedDeviceId !== null;
  const canSend = hasFiles && hasDevice;

  const addFilePaths = useCallback((incomingPaths: string[]) => {
    if (!incomingPaths || incomingPaths.length === 0) return;
    const newItems: SelectedFilePayload[] = incomingPaths.map((filePath) => ({
      uid: uid(),
      path: filePath,
      name: extractFilename(filePath),
    }));
    setFiles((prev) => [...prev, ...newItems]);
  }, []);

  const removeFile = useCallback((id: string) => {
    setFiles((prev) => prev.filter((f) => f.uid !== id));
  }, []);

  // ── Native Tauri drag-and-drop listener ────────────────────────────────────

  useEffect(() => {
    let unlisten: (() => void) | undefined;

    const setupNativeDragDrop = async () => {
      try {
        unlisten = await getCurrentWindow().onDragDropEvent((event) => {
          if (event.payload.type === "enter" || event.payload.type === "over") {
            setIsDraggingOver(true);
          } else if (event.payload.type === "drop") {
            setIsDraggingOver(false);
            const paths = event.payload.paths;
            if (paths && paths.length > 0) {
              addFilePaths(paths);
            }
          } else if (event.payload.type === "leave") {
            setIsDraggingOver(false);
          }
        });
      } catch (err) {
        console.error("[FileDropZone] Native drag drop listener error:", err);
      }
    };

    setupNativeDragDrop();

    return () => {
      if (unlisten) unlisten();
    };
  }, [addFilePaths]);

  // ── Click-to-browse via Tauri Native Dialog ────────────────────────────────

  const onZoneClick = useCallback(async () => {
    try {
      const selected = await open({
        multiple: true,
        directory: false,
        title: "Select Files to Send",
      });

      if (selected) {
        const paths = Array.isArray(selected) ? selected : [selected];
        addFilePaths(paths);
      }
    } catch (err) {
      console.error("[FileDropZone] Native file dialog error:", err);
      addToast("Failed to open native file dialog.", "error");
    }
  }, [addFilePaths, addToast]);

  // ── Send button handler ─────────────────────────────────────────────────────

  const handleSend = useCallback(() => {
    if (!hasDevice && !hasFiles) {
      addToast("Select a device and choose files before sending.", "error");
    } else if (!hasDevice) {
      addToast("Select a device before sending files.", "error");
    } else if (!hasFiles) {
      addToast("Choose one or more files first.", "error");
    } else {
      addToast("Preparing transfer...", "info");
      if (onSend) {
        onSend(files);
        setFiles([]); // Clear list after initiating transfer
      }
    }
  }, [hasDevice, hasFiles, addToast, onSend, files]);

  return (
    <div className="flex w-full flex-col gap-2.5">
      {/* ── Drop zone ── */}
      <div
        role="button"
        tabIndex={0}
        aria-label="Drop files here or click to browse"
        onClick={onZoneClick}
        onKeyDown={(e) => (e.key === "Enter" || e.key === " " ? onZoneClick() : undefined)}
        className={[
          "relative flex min-h-[130px] flex-col items-center justify-center gap-2 overflow-hidden",
          "rounded-xl border border-dashed px-4 py-5",
          "cursor-pointer select-none outline-none",
          "transition-all duration-150 ease-out",
          isDraggingOver ? accent.dropZoneActive : accent.dropZoneHover,
          accent.ringFocus,
        ].join(" ")}
      >
        <IconUploadLarge />

        <div className="flex flex-col items-center gap-0.5 text-center">
          <p className="text-xs font-medium text-neutral-200">
            Drop files here or <span className={accent.progressText}>choose files</span>
          </p>
        </div>
      </div>

      {/* ── File list preview ── */}
      {hasFiles && (
        <div className="w-full flex flex-col gap-1">
          <ul
            aria-label="Selected files"
            className="flex w-full flex-col gap-1 max-h-40 overflow-y-auto pr-0.5"
          >
            {files.map(({ uid: id, path, name }) => (
              <li
                key={id}
                className="
                  group flex items-center justify-between gap-3
                  rounded-lg border border-white/[0.06] bg-neutral-900/60 px-3 py-2
                  text-xs transition-colors hover:bg-neutral-800/60
                "
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  <IconFile name={name} />
                  <div className="flex flex-col min-w-0 flex-1">
                    <span className="truncate font-medium text-neutral-200">{name}</span>
                    <span className="truncate text-[10px] text-neutral-500 font-mono" title={path}>
                      {path}
                    </span>
                  </div>
                </div>

                {/* Remove button */}
                <button
                  type="button"
                  aria-label={`Remove ${name}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    removeFile(id);
                  }}
                  className="
                    flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-md
                    text-neutral-500 opacity-0 group-hover:opacity-100
                    hover:text-red-400 hover:bg-red-500/10
                    transition-all duration-150
                    focus-visible:opacity-100 focus-visible:ring-1 focus-visible:ring-neutral-500
                    outline-none
                  "
                >
                  <X
                    size={12}
                    strokeWidth={2}
                    aria-hidden="true"
                    className="block"
                  />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ── Send button + helper ── */}
      <div className="flex flex-col items-center gap-1.5 pt-0.5">
        <button
          id="send-files-btn"
          type="button"
          onClick={handleSend}
          aria-disabled={!canSend}
          className={[
            "w-[150px] rounded-lg px-4 py-2 text-xs font-medium",
            "transition-all duration-150 ease-out",
            "focus-visible:outline-none " + accent.ringFocus,
            canSend
              ? [
                  accent.buttonGrad,
                  accent.buttonShadow,
                  "active:scale-[0.99]",
                  "cursor-pointer",
                ].join(" ")
              : [
                  "border border-white/[0.06] bg-neutral-800 text-neutral-500",
                  "cursor-not-allowed",
                ].join(" "),
          ].join(" ")}
        >
          Send Files
        </button>

        {/* Helper text */}
        {!canSend && (
          <p className="text-[11px] text-neutral-500 select-none">
            {!hasDevice && !hasFiles
              ? "Select a device and files to continue"
              : !hasDevice
              ? "Select a device to send files"
              : "Add files to send"}
          </p>
        )}
      </div>
    </div>
  );
};

export default FileDropZone;

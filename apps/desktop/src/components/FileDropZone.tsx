import React, { useCallback, useRef, useState } from "react";
import { X } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

// ─── Types ────────────────────────────────────────────────────────────────────

interface SelectedFile {
  /** Unique key for React list rendering */
  uid: string;
  file: File;
}

interface FileDropZoneProps {
  /** ID of the currently selected device (null = none) */
  selectedDeviceId: string | null;
  /** Callback fired when user initiates file sending */
  onSend?: (selectedFiles: File[]) => void;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

function uid(): string {
  return Math.random().toString(36).slice(2, 10);
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
  const [files, setFiles] = useState<SelectedFile[]>([]);
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const hasFiles = files.length > 0;
  const hasDevice = selectedDeviceId !== null;
  const canSend = hasFiles && hasDevice;

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
        const rawFiles = files.map((f) => f.file);
        onSend(rawFiles);
        setFiles([]); // Clear list after initiating transfer
      }
    }
  }, [hasDevice, hasFiles, addToast, onSend, files]);

  // ── File ingestion ──────────────────────────────────────────────────────────

  const addFiles = useCallback((incoming: FileList | null) => {
    if (!incoming) return;
    const next: SelectedFile[] = Array.from(incoming).map((file) => ({
      uid: uid(),
      file,
    }));
    setFiles((prev) => [...prev, ...next]);
  }, []);

  const removeFile = useCallback((id: string) => {
    setFiles((prev) => prev.filter((f) => f.uid !== id));
  }, []);

  // ── Drag handlers ───────────────────────────────────────────────────────────

  const onDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDraggingOver(true);
  }, []);

  const onDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDraggingOver(false);
  }, []);

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setIsDraggingOver(false);
      addFiles(e.dataTransfer.files);
    },
    [addFiles],
  );

  // ── Click-to-browse ─────────────────────────────────────────────────────────

  const onZoneClick = useCallback(() => {
    inputRef.current?.click();
  }, []);

  const onInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      addFiles(e.target.files);
      e.target.value = "";
    },
    [addFiles],
  );

  return (
    <div className="flex w-full flex-col gap-2.5">
      {/* Hidden file input */}
      <input
        ref={inputRef}
        id="file-browse-input"
        type="file"
        multiple
        className="sr-only"
        aria-label="Browse files"
        onChange={onInputChange}
        tabIndex={-1}
      />

      {/* ── Drop zone ── */}
      <div
        role="button"
        tabIndex={0}
        aria-label="Drop files here or click to browse"
        onClick={onZoneClick}
        onKeyDown={(e) => e.key === "Enter" || e.key === " " ? onZoneClick() : undefined}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
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
            {files.map(({ uid: id, file }) => (
              <li
                key={id}
                className="
                  group flex items-center justify-between gap-3
                  rounded-lg border border-white/[0.06] bg-neutral-900/60 px-3 py-2
                  text-xs transition-colors hover:bg-neutral-800/60
                "
              >
                <div className="flex items-center gap-2.5 min-w-0 flex-1">
                  <IconFile name={file.name} />
                  <span className="truncate font-medium text-neutral-200">
                    {file.name}
                  </span>
                  <span className="flex-shrink-0 text-[11px] text-neutral-500 font-mono">
                    {formatBytes(file.size)}
                  </span>
                </div>

                {/* Remove button */}
                <button
                  type="button"
                  aria-label={`Remove ${file.name}`}
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

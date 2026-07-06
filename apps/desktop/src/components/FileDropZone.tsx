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
  onSend?: (fileName: string) => void;
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
  return "text-slate-400";
}

const IconFile: React.FC<{ name: string }> = ({ name }) => (
  <svg
    viewBox="0 0 24 24"
    className={`w-5 h-5 flex-shrink-0 ${fileIconColor(name)}`}
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

// ─── Empty / drop state ───────────────────────────────────────────────────────

const IconUploadLarge: React.FC = () => (
  <svg
    viewBox="0 0 48 48"
    className="w-12 h-12"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.4"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <rect x="4" y="30" width="40" height="14" rx="4" opacity="0.15" fill="currentColor" stroke="none" />
    <path d="M24 28V10" />
    <path d="M16 18l8-8 8 8" />
    <path d="M8 36h4M36 36h4M20 36h8" opacity="0.4" />
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
        const firstFileName = files[0]?.file.name || "movie.mp4";
        onSend(firstFileName);
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
      // Reset so the same file can be re-added after removal
      e.target.value = "";
    },
    [addFiles],
  );

  // ─────────────────────────────────────────────────────────────────────────────

  return (
    <div className="w-full flex flex-col gap-4 p-4">
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
          "relative flex flex-col items-center justify-center gap-3",
          "rounded-xl border-2 border-dashed px-6 py-10",
          "cursor-pointer select-none outline-none",
          "transition-all duration-200 ease-out",
          isDraggingOver
            ? "border-indigo-400/70 bg-indigo-500/10 scale-[1.01] shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
            : "border-white/10 hover:border-white/20 hover:bg-white/[0.02]",
          "focus-visible:ring-2 focus-visible:ring-indigo-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950",
        ].join(" ")}
      >
        {/* Icon */}
        <span
          className={[
            "transition-colors duration-200",
            isDraggingOver ? "text-indigo-400" : "text-slate-600",
          ].join(" ")}
        >
          <IconUploadLarge />
        </span>

        {/* Copy */}
        <div className="flex flex-col items-center gap-1 text-center">
          <span
            className={[
              "text-sm font-semibold transition-colors duration-200",
              isDraggingOver ? "text-indigo-300" : "text-slate-300",
            ].join(" ")}
          >
            {isDraggingOver ? "Release to add files" : "Drop files here"}
          </span>
          <span className="text-xs text-slate-600">
            or click to browse
          </span>
        </div>

        {/* Drag-over overlay glow */}
        {isDraggingOver && (
          <div
            className="absolute inset-0 rounded-xl bg-gradient-to-br from-indigo-500/5 to-purple-500/5 pointer-events-none"
            aria-hidden="true"
          />
        )}
      </div>

      {/* ── Selected files panel ── */}
      {hasFiles && (
        <div className="flex flex-col gap-1 rounded-xl border border-white/6 bg-slate-900/40 overflow-hidden">
          <ul
            aria-label="Selected files"
            className="divide-y divide-white/[0.04]"
          >
            {files.map(({ uid: id, file }) => (
              <li
                key={id}
                className="flex items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-white/[0.03] group"
              >
                {/* File icon */}
                <IconFile name={file.name} />

                {/* Name + size */}
                <div className="flex-1 min-w-0 flex flex-col gap-0.5">
                  <span className="text-sm text-slate-200 font-medium truncate leading-tight">
                    {file.name}
                  </span>
                  <span className="text-xs text-slate-500 leading-tight">
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
                    flex-shrink-0 w-6 h-6 rounded-md
                    flex items-center justify-center
                    text-slate-500 opacity-0 group-hover:opacity-100
                    hover:text-red-400 hover:bg-red-500/10
                    transition-all duration-150
                    focus-visible:opacity-100 focus-visible:ring-1 focus-visible:ring-slate-500
                    outline-none
                  "
                >
                  <X
                    size={12}
                    strokeWidth={2.5}
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
      <div className="flex flex-col items-center gap-2 pt-1">
        <button
          id="send-files-btn"
          type="button"
          onClick={handleSend}
          aria-disabled={!canSend}
          className={[
            "w-full max-w-xs py-2.5 px-6 rounded-xl text-sm font-semibold",
            "transition-all duration-200 ease-out",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950",
            accent.deviceFocus,
            canSend
              ? [
                  accent.buttonGrad,
                  "text-white",
                  "active:scale-[0.98]",
                  "cursor-pointer",
                ].join(" ")
              : [
                  "bg-slate-800/60 text-slate-600",
                  "border border-white/6",
                  "cursor-not-allowed",
                ].join(" "),
          ].join(" ")}
        >
          Send Files
        </button>

        {/* Helper text */}
        {!canSend && (
          <p
            role="status"
            aria-live="polite"
            className="text-xs text-slate-600 text-center select-none"
          >
            {!hasDevice && !hasFiles
              ? "Select a device and choose files."
              : !hasDevice
              ? "Select a device above to continue."
              : "Choose at least one file to send."}
          </p>
        )}
      </div>
    </div>
  );
};

export default FileDropZone;

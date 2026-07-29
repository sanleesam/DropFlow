import React, { useEffect, useState } from "react";
import { X, CheckCircle2, FolderOpen, FileText, Ban } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { invoke } from "@tauri-apps/api/core";
import { ActiveTransferSession } from "../utils/transferSessionManager";

interface TransferProgressProps {
  session: ActiveTransferSession;
  onDismiss: (sessionId: string) => void;
  onCancel: (sessionId: string) => void;
}

export const TransferProgress: React.FC<TransferProgressProps> = ({
  session,
  onDismiss,
  onCancel,
}) => {
  const { addToast } = useToast();
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  const [isDismissing, setIsDismissing] = useState(false);
  const [receiveDir, setReceiveDir] = useState<string>("");

  // Fetch receive directory path for Open Folder fallback
  useEffect(() => {
    if (session.direction === "receive") {
      if (session.receiveDir) {
        setReceiveDir(session.receiveDir);
      } else {
        invoke<string>("get_receive_dir")
          .then(setReceiveDir)
          .catch(console.error);
      }
    }
  }, [session.direction, session.receiveDir]);

  const handleDismiss = () => {
    setIsDismissing(true);
    setTimeout(() => onDismiss(session.id), 150);
  };

  const handleCancelTransfer = () => {
    onCancel(session.id);
  };

  const handleOpenFolder = async () => {
    try {
      const firstFile = session.completedFiles.find((f) => f.finalPath);
      if (firstFile?.finalPath) {
        await revealItemInDir(firstFile.finalPath);
        return;
      }

      const dirPath = session.receiveDir || receiveDir || (await invoke<string>("get_receive_dir"));
      await openPath(dirPath);
    } catch (err) {
      console.error("[TransferProgress] Failed to reveal/open receive folder:", err);
      try {
        const dirPath = session.receiveDir || receiveDir || (await invoke<string>("get_receive_dir"));
        await openPath(dirPath);
      } catch (fallbackErr) {
        console.error("[TransferProgress] Fallback openPath failed:", fallbackErr);
        addToast("Failed to open receive folder.", "error");
      }
    }
  };

  const handleOpenFile = async (specificFilePath?: string) => {
    try {
      let targetPath = specificFilePath;

      if (!targetPath) {
        const firstFile = session.completedFiles.find((f) => f.finalPath);
        if (firstFile?.finalPath) {
          targetPath = firstFile.finalPath;
        }
      }

      if (!targetPath) {
        throw new Error("No valid final file path available for received file");
      }

      await openPath(targetPath);
    } catch (err) {
      console.error("[TransferProgress] Failed to open received file:", err);
      addToast("Failed to open received file.", "error");
    }
  };

  const isCompleted = session.progress === 100 || session.status === "Completed";
  const isFailed = session.status === "Failed";

  return (
    <div
      className={`
        relative flex w-full flex-col gap-3.5 overflow-hidden rounded-xl border border-white/[0.08]
        bg-neutral-900/90 p-4 shadow-lg
        transition-all duration-150 ease-in-out
        ${isDismissing ? "opacity-0 translate-y-2 scale-95" : "opacity-100 translate-y-0 scale-100"}
        animate-slide-in-up
      `}
    >
      {/* Header Info & Actions */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 min-w-0">
          <span className={`h-2 w-2 rounded-full ${accent.progressBgDot} animate-pulse flex-shrink-0`} />
          <h3 className="text-sm font-semibold tracking-tight text-neutral-100 truncate">
            {session.direction === "receive" ? "Receiving" : "Sending"}{" "}
            {session.totalFiles > 1 ? `${session.totalFiles} files` : "file"}
          </h3>
          <span className="text-xs text-neutral-400 truncate">
            {session.direction === "receive" ? "from" : "to"}{" "}
            <span className="font-medium text-neutral-200">{session.deviceName}</span>
          </span>
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          {/* Status Label */}
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium bg-neutral-800 border border-white/[0.06] select-none">
            {!isCompleted && !isFailed ? (
              <>
                <span className={`h-1.5 w-1.5 rounded-full ${accent.progressBgDot} animate-pulse`} />
                <span className={accent.progressText}>{session.status}</span>
              </>
            ) : isCompleted ? (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                <span className="text-emerald-400">Completed</span>
              </>
            ) : (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-red-400" />
                <span className="text-red-400">Failed</span>
              </>
            )}
          </div>

          {/* Cancel button during active transfer */}
          {!isCompleted && !isFailed && (
            <button
              type="button"
              onClick={handleCancelTransfer}
              title="Cancel transfer"
              className="
                flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-medium
                text-red-400 bg-red-500/10 border border-red-500/20
                hover:bg-red-500/20 transition-all duration-150 outline-none
              "
            >
              <Ban size={11} />
              <span>Cancel</span>
            </button>
          )}

          {/* Dismiss Button */}
          <button
            type="button"
            onClick={handleDismiss}
            className="
              w-6 h-6 rounded-md
              flex items-center justify-center
              text-neutral-500 hover:text-neutral-200
              hover:bg-neutral-800
              transition-all duration-150
              outline-none focus-visible:ring-1 focus-visible:ring-neutral-500
            "
            aria-label="Dismiss progress panel"
          >
            <X size={14} strokeWidth={2} />
          </button>
        </div>
      </div>

      {/* File Progress Details Card */}
      <div className="flex items-center gap-3 rounded-lg border border-white/[0.06] bg-neutral-950/60 p-3">
        {/* File icon */}
        <div className={`flex h-9 w-9 items-center justify-center rounded-md ${accent.progressIconBg} ${accent.progressIconText}`}>
          <svg
            viewBox="0 0 24 24"
            className="h-4.5 w-4.5 flex-shrink-0"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
            <polyline points="14 2 14 8 20 8" />
          </svg>
        </div>

        {/* Text Details */}
        <div className="flex-1 min-w-0">
          <p className="truncate text-sm font-medium text-neutral-200">{session.currentFileLabel}</p>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-neutral-500 select-none truncate">
            <span>{session.speed}</span>
            <span>•</span>
            <span>{session.timeRemaining} remaining</span>
            {session.bytesInfo && (
              <>
                <span>•</span>
                <span className="font-mono">{session.bytesInfo}</span>
              </>
            )}
          </div>
        </div>

        {/* Progress Percent */}
        <div className="text-right select-none flex-shrink-0">
          <span className="text-lg font-semibold tracking-tight text-neutral-100">
            {session.progress}%
          </span>
        </div>
      </div>

      {/* Progress Bar / Completion Summary */}
      <div className="relative w-full">
        {!isCompleted && !isFailed ? (
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-800">
            <div
              className={`h-full rounded-full ${accent.switchBg} transition-all duration-75 ease-out`}
              style={{ width: `${session.progress}%` }}
            />
          </div>
        ) : isCompleted ? (
          <div className="flex flex-col gap-2.5 rounded-lg border border-emerald-500/20 bg-emerald-500/10 p-3 select-none">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-xs text-emerald-400 font-semibold">
                <CheckCircle2 size={16} strokeWidth={2} />
                <span>Transfer Complete ({session.totalFiles} {session.totalFiles === 1 ? "file" : "files"})</span>
              </div>
              <span className="text-[11px] font-mono text-neutral-400 truncate max-w-[200px]" title={receiveDir}>
                {receiveDir || "~/Downloads/DropFlow/"}
              </span>
            </div>

            {session.completedFiles.length > 1 && (
              <ul className="flex flex-col gap-1 max-h-24 overflow-y-auto py-1 pr-1 text-xs">
                {session.completedFiles.map((f, i) => (
                  <li
                    key={i}
                    onClick={() => f.finalPath && handleOpenFile(f.finalPath)}
                    className="flex items-center justify-between text-neutral-300 hover:text-white cursor-pointer hover:bg-emerald-500/10 px-1.5 py-0.5 rounded transition-colors"
                    title={f.finalPath ? `Click to open ${f.relativePath}` : f.relativePath}
                  >
                    <span className="truncate flex-1">{f.relativePath}</span>
                    <span className="font-mono text-[11px] text-neutral-400 ml-2">
                      {(f.sizeBytes / (1024 * 1024)).toFixed(1)} MB
                    </span>
                  </li>
                ))}
              </ul>
            )}

            {session.direction === "receive" && (
              <div className="flex items-center gap-2 pt-0.5">
                <button
                  type="button"
                  onClick={() => handleOpenFile()}
                  className="flex-1 flex items-center justify-center gap-1.5 rounded-md bg-emerald-500/20 border border-emerald-500/30 px-3 py-1.5 text-xs font-medium text-emerald-300 hover:bg-emerald-500/30 transition-colors"
                >
                  <FileText size={13} />
                  <span>Open File</span>
                </button>
                <button
                  type="button"
                  onClick={handleOpenFolder}
                  className="flex-1 flex items-center justify-center gap-1.5 rounded-md bg-neutral-800 border border-white/[0.08] px-3 py-1.5 text-xs font-medium text-neutral-200 hover:bg-neutral-700 transition-colors"
                >
                  <FolderOpen size={13} />
                  <span>Open Folder</span>
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className="flex items-center justify-between rounded-lg border border-red-500/20 bg-red-500/10 p-3 text-xs text-red-400 font-medium select-none">
            <span>Transfer failed: {session.error || "Unknown error"}</span>
            <button
              type="button"
              onClick={handleDismiss}
              className="px-2 py-1 rounded bg-neutral-800 text-neutral-300 hover:bg-neutral-700 text-[11px]"
            >
              Dismiss
            </button>
          </div>
        )}
      </div>
    </div>
  );
};

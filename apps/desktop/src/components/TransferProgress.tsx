import React, { useEffect, useRef, useState } from "react";
import { X, CheckCircle2, FolderOpen, FileText, Ban } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";
import { listen } from "@tauri-apps/api/event";
import { openPath } from "@tauri-apps/plugin-opener";
import { isPermissionGranted, requestPermission, sendNotification } from "@tauri-apps/plugin-notification";
import { invoke } from "@tauri-apps/api/core";

export interface FileMetadataPayload {
  fileIndex: number;
  relativePath: string;
  sizeBytes: number;
  sha256Checksum?: string;
}

interface TransferProgressProps {
  sessionId?: string;
  /** Name of the remote peer device */
  deviceName: string;
  /** Primary display name of the transfer (file name or batch summary) */
  fileName: string;
  /** Total number of files in session */
  totalFiles?: number;
  /** Transfer direction: "send" or "receive" */
  direction?: "send" | "receive";
  /** Callback fired when user closes or dismisses card */
  onClose: () => void;
  /** Callback fired when transfer reaches 100% */
  onComplete?: (payload?: any) => void;
}

type TransferStatus = "Preparing..." | "Sending..." | "Receiving..." | "Finishing..." | "Completed" | "Failed";

interface ProgressPayload {
  sessionId?: string;
  currentFileIndex?: number;
  currentFileName?: string;
  currentFileBytes?: number;
  currentFileTotalBytes?: number;
  sessionBytesSent?: number;
  sessionTotalBytes?: number;
  totalFiles?: number;
  percentage?: number;
  speedBytesPerSec?: number;
}

interface CompletedPayload {
  sessionId?: string;
  fileName?: string;
  deviceName?: string;
  size?: string;
  timestamp?: string;
  totalFiles?: number;
  totalSizeBytes?: number;
  files?: FileMetadataPayload[];
}

interface FailedPayload {
  sessionId?: string;
  error?: string;
}

async function triggerDesktopNotification(title: string, body: string) {
  try {
    let granted = await isPermissionGranted();
    if (!granted) {
      const permission = await requestPermission();
      granted = permission === "granted";
    }
    if (granted) {
      sendNotification({ title, body });
    }
  } catch (err) {
    console.error("[Notification] Failed to send desktop notification:", err);
  }
}

export const TransferProgress: React.FC<TransferProgressProps> = ({
  sessionId,
  deviceName,
  fileName,
  totalFiles = 1,
  direction = "send",
  onClose,
  onComplete,
}) => {
  const { addToast } = useToast();
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState<TransferStatus>(
    direction === "receive" ? "Receiving..." : "Preparing..."
  );
  const [speed, setSpeed] = useState("0 MB/s");
  const [timeRemaining, setTimeRemaining] = useState("Calculating...");
  const [bytesInfo, setBytesInfo] = useState("");
  const [currentFileLabel, setCurrentFileLabel] = useState(fileName);
  const [completedFileList, setCompletedFileList] = useState<FileMetadataPayload[]>([]);
  const [isDismissing, setIsDismissing] = useState(false);
  const [receiveDir, setReceiveDir] = useState<string>("");

  const onCompleteRef = useRef(onComplete);
  useEffect(() => {
    onCompleteRef.current = onComplete;
  }, [onComplete]);

  const hasCompletedRef = useRef(false);

  // Fetch receive directory path for Open Folder action
  useEffect(() => {
    if (direction === "receive") {
      invoke<string>("get_receive_dir")
        .then(setReceiveDir)
        .catch(console.error);
    }
  }, [direction]);

  const handleDismiss = () => {
    setIsDismissing(true);
    setTimeout(onClose, 150);
  };

  const handleCancelTransfer = async () => {
    if (sessionId) {
      try {
        await invoke("cancel_transfer", { sessionId });
        addToast("Transfer cancellation requested.", "info");
      } catch (err) {
        console.error("[TransferProgress] Failed to request cancellation:", err);
      }
    }
    handleDismiss();
  };

  const handleOpenFolder = async () => {
    try {
      const dirPath = receiveDir || (await invoke<string>("get_receive_dir"));
      await openPath(dirPath);
    } catch (err) {
      console.error("[TransferProgress] Failed to open folder:", err);
      addToast("Failed to open receive folder.", "error");
    }
  };

  const handleOpenFile = async (specificFileName?: string) => {
    try {
      const targetName = specificFileName || fileName;
      const dirPath = receiveDir || (await invoke<string>("get_receive_dir"));
      const normalizedDir = dirPath.replace(/\\/g, "/").replace(/\/$/, "");
      const fullPath = `${normalizedDir}/${targetName}`;
      await openPath(fullPath);
    } catch (err) {
      console.error("[TransferProgress] Failed to open file:", err);
      handleOpenFolder();
    }
  };

  useEffect(() => {
    hasCompletedRef.current = false;
    setProgress(0);
    setStatus(direction === "receive" ? "Receiving..." : "Preparing...");
    setSpeed("0 MB/s");
    setTimeRemaining("Calculating...");

    let unlistenProgress: (() => void) | undefined;
    let unlistenCompleted: (() => void) | undefined;
    let unlistenFailed: (() => void) | undefined;

    const setupListeners = async () => {
      try {
        unlistenProgress = await listen<ProgressPayload>("transfer-progress", (event) => {
          const payload = event.payload;

          // Filter by sessionId if available
          if (sessionId && payload.sessionId && payload.sessionId !== sessionId) {
            return;
          }

          const pct = payload.percentage ?? 0;
          const rounded = Math.min(100, Math.max(0, Math.round(pct)));
          setProgress(rounded);

          const speedBytes = payload.speedBytesPerSec ?? 0;
          const mbps = (speedBytes / (1024 * 1024)).toFixed(1);
          setSpeed(`${mbps} MB/s`);

          const sessionSent = payload.sessionBytesSent ?? 0;
          const sessionTotal = payload.sessionTotalBytes ?? 0;

          if (sessionTotal > 0) {
            const sentMb = (sessionSent / (1024 * 1024)).toFixed(1);
            const totalMb = (sessionTotal / (1024 * 1024)).toFixed(1);
            setBytesInfo(`${sentMb} / ${totalMb} MB`);
          }

          if (payload.currentFileName) {
            const fileIdx = (payload.currentFileIndex ?? 0) + 1;
            const fileCount = payload.totalFiles ?? totalFiles;
            if (fileCount > 1) {
              setCurrentFileLabel(`[${fileIdx}/${fileCount}] ${payload.currentFileName}`);
            } else {
              setCurrentFileLabel(payload.currentFileName);
            }
          }

          if (rounded < 100) {
            setStatus(direction === "receive" ? "Receiving..." : "Sending...");
            const remainingBytes = Math.max(0, sessionTotal - sessionSent);
            const remainingSecs = speedBytes > 0
              ? Math.ceil(remainingBytes / speedBytes)
              : 0;
            setTimeRemaining(`${remainingSecs} second${remainingSecs !== 1 ? "s" : ""}`);
          }
        });

        unlistenCompleted = await listen<CompletedPayload>("transfer-completed", (event) => {
          const payload = event.payload;
          if (sessionId && payload.sessionId && payload.sessionId !== sessionId) {
            return;
          }

          setProgress(100);
          setStatus("Completed");
          setSpeed("0 MB/s");
          setTimeRemaining("0 seconds");

          if (payload.files && payload.files.length > 0) {
            setCompletedFileList(payload.files);
          }

          if (!hasCompletedRef.current) {
            hasCompletedRef.current = true;
            const itemFileName = payload.fileName ?? fileName;
            const itemDeviceName = payload.deviceName ?? deviceName;

            if (direction === "receive") {
              addToast(`Received ${itemFileName} from ${itemDeviceName}`, "success");
              triggerDesktopNotification(
                "DropFlow — Transfer Complete",
                `${itemFileName}\nReceived from ${itemDeviceName}`
              );
            } else {
              addToast("Transfer completed successfully.", "success");
            }

            if (onCompleteRef.current) {
              onCompleteRef.current({
                sessionId: payload.sessionId ?? sessionId,
                fileName: itemFileName,
                deviceName: itemDeviceName,
                size: payload.size ?? "Complete",
                timestamp: payload.timestamp ?? "Just now",
                totalFiles: payload.totalFiles ?? totalFiles,
                totalSizeBytes: payload.totalSizeBytes ?? 0,
                files: payload.files ?? [],
                direction,
              });
            }
          }
        });

        unlistenFailed = await listen<FailedPayload>("transfer-failed", (event) => {
          const payload = event.payload;
          if (sessionId && payload.sessionId && payload.sessionId !== sessionId) {
            return;
          }
          setStatus("Failed");
          addToast(`Transfer failed: ${payload.error || "Unknown error"}`, "error");
        });
      } catch (err) {
        console.error("[TransferProgress] Failed to register event listeners:", err);
      }
    };

    setupListeners();

    return () => {
      if (unlistenProgress) unlistenProgress();
      if (unlistenCompleted) unlistenCompleted();
      if (unlistenFailed) unlistenFailed();
    };
  }, [addToast, fileName, deviceName, direction, sessionId, totalFiles]);

  const isCompleted = progress === 100;
  const isFailed = status === "Failed";

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
            {direction === "receive" ? "Receiving" : "Sending"}{" "}
            {totalFiles > 1 ? `${totalFiles} files` : "file"}
          </h3>
          <span className="text-xs text-neutral-400 truncate">
            {direction === "receive" ? "from" : "to"}{" "}
            <span className="font-medium text-neutral-200">{deviceName}</span>
          </span>
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          {/* Status Label */}
          <div className="flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium bg-neutral-800 border border-white/[0.06] select-none">
            {!isCompleted && !isFailed ? (
              <>
                <span className={`h-1.5 w-1.5 rounded-full ${accent.progressBgDot} animate-pulse`} />
                <span className={accent.progressText}>{status}</span>
              </>
            ) : isCompleted ? (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                <span className="text-emerald-400">{status}</span>
              </>
            ) : (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-red-400" />
                <span className="text-red-400">{status}</span>
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
          <p className="truncate text-sm font-medium text-neutral-200">{currentFileLabel}</p>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-neutral-500 select-none truncate">
            <span>{speed}</span>
            <span>•</span>
            <span>{timeRemaining} remaining</span>
            {bytesInfo && (
              <>
                <span>•</span>
                <span className="font-mono">{bytesInfo}</span>
              </>
            )}
          </div>
        </div>

        {/* Progress Percent */}
        <div className="text-right select-none flex-shrink-0">
          <span className="text-lg font-semibold tracking-tight text-neutral-100">
            {progress}%
          </span>
        </div>
      </div>

      {/* Progress Bar / Completion Summary */}
      <div className="relative w-full">
        {!isCompleted && !isFailed ? (
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-800">
            <div
              className={`h-full rounded-full ${accent.switchBg} transition-all duration-75 ease-out`}
              style={{ width: `${progress}%` }}
            />
          </div>
        ) : isCompleted ? (
          <div className="flex flex-col gap-2.5 rounded-lg border border-emerald-500/20 bg-emerald-500/10 p-3 select-none">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 text-xs text-emerald-400 font-semibold">
                <CheckCircle2 size={16} strokeWidth={2} />
                <span>Transfer Complete ({totalFiles} {totalFiles === 1 ? "file" : "files"})</span>
              </div>
              <span className="text-[11px] font-mono text-neutral-400 truncate max-w-[200px]" title={receiveDir}>
                {receiveDir || "~/Downloads/DropFlow/"}
              </span>
            </div>

            {completedFileList.length > 1 && (
              <ul className="flex flex-col gap-1 max-h-24 overflow-y-auto py-1 pr-1 text-xs">
                {completedFileList.map((f, i) => (
                  <li key={i} className="flex items-center justify-between text-neutral-300">
                    <span className="truncate flex-1">{f.relativePath}</span>
                    <span className="font-mono text-[11px] text-neutral-400 ml-2">
                      {(f.sizeBytes / (1024 * 1024)).toFixed(1)} MB
                    </span>
                  </li>
                ))}
              </ul>
            )}

            {direction === "receive" && (
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
            <span>Transfer failed.</span>
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

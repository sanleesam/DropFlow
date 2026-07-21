import React, { useEffect, useRef, useState } from "react";
import { X, CheckCircle2 } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";
import { listen } from "@tauri-apps/api/event";

interface TransferProgressProps {
  /** Name of the destination device */
  deviceName: string;
  /** Name of the file being sent */
  fileName: string;
  /** Callback fired when the user closes/dismisses the card */
  onClose: () => void;
  /** Callback fired when transfer reaches 100% */
  onComplete?: (payload?: any) => void;
}

type TransferStatus = "Preparing..." | "Sending..." | "Finishing..." | "Completed" | "Failed";

interface ProgressPayload {
  sessionId?: string;
  session_id?: string;
  fileName?: string;
  file_name?: string;
  bytesSent?: number;
  bytes_sent?: number;
  totalBytes?: number;
  total_bytes?: number;
  percentage?: number;
  speedBytesPerSec?: number;
  speed_bytes_per_sec?: number;
}

interface CompletedPayload {
  sessionId?: string;
  session_id?: string;
  fileName?: string;
  file_name?: string;
  deviceName?: string;
  device_name?: string;
  size?: string;
  timestamp?: string;
}

interface FailedPayload {
  sessionId?: string;
  session_id?: string;
  error?: string;
}

export const TransferProgress: React.FC<TransferProgressProps> = ({
  deviceName,
  fileName,
  onClose,
  onComplete,
}) => {
  const { addToast } = useToast();
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState<TransferStatus>("Preparing...");
  const [speed, setSpeed] = useState("0 MB/s");
  const [timeRemaining, setTimeRemaining] = useState("Calculating...");
  const [isDismissing, setIsDismissing] = useState(false);

  // Keep a stable ref to onComplete to prevent prop identity changes from restarting listeners
  const onCompleteRef = useRef(onComplete);
  useEffect(() => {
    onCompleteRef.current = onComplete;
  }, [onComplete]);

  // Track if completion logic has already executed for this mounted transfer
  const hasCompletedRef = useRef(false);

  const handleDismiss = () => {
    setIsDismissing(true);
    setTimeout(onClose, 150);
  };

  useEffect(() => {
    hasCompletedRef.current = false;
    setProgress(0);
    setStatus("Preparing...");
    setSpeed("0 MB/s");
    setTimeRemaining("Calculating...");

    let unlistenProgress: (() => void) | undefined;
    let unlistenCompleted: (() => void) | undefined;
    let unlistenFailed: (() => void) | undefined;

    const setupListeners = async () => {
      try {
        unlistenProgress = await listen<ProgressPayload>("transfer-progress", (event) => {
          const payload = event.payload;
          const pct = payload.percentage ?? 0;
          const rounded = Math.min(100, Math.max(0, Math.round(pct)));
          setProgress(rounded);

          const speedBytes = payload.speedBytesPerSec ?? payload.speed_bytes_per_sec ?? 0;
          const mbps = (speedBytes / (1024 * 1024)).toFixed(1);
          setSpeed(`${mbps} MB/s`);

          const bytesSent = payload.bytesSent ?? payload.bytes_sent ?? 0;
          const totalBytes = payload.totalBytes ?? payload.total_bytes ?? 0;

          if (rounded < 100) {
            setStatus("Sending...");
            const remainingBytes = Math.max(0, totalBytes - bytesSent);
            const remainingSecs = speedBytes > 0
              ? Math.ceil(remainingBytes / speedBytes)
              : 0;
            setTimeRemaining(`${remainingSecs} second${remainingSecs !== 1 ? "s" : ""}`);
          }
        });

        unlistenCompleted = await listen<CompletedPayload>("transfer-completed", (event) => {
          const payload = event.payload;
          setProgress(100);
          setStatus("Completed");
          setSpeed("0 MB/s");
          setTimeRemaining("0 seconds");

          if (!hasCompletedRef.current) {
            hasCompletedRef.current = true;
            addToast("Transfer completed successfully.", "success");
            if (onCompleteRef.current) {
              onCompleteRef.current({
                sessionId: payload.sessionId ?? payload.session_id,
                fileName: payload.fileName ?? payload.file_name ?? fileName,
                deviceName: payload.deviceName ?? payload.device_name ?? deviceName,
                size: payload.size ?? "Complete",
                timestamp: payload.timestamp ?? "Just now",
              });
            }
          }
        });

        unlistenFailed = await listen<FailedPayload>("transfer-failed", (event) => {
          setStatus("Failed");
          addToast(`Transfer failed: ${event.payload.error || "Unknown error"}`, "error");
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
  }, [addToast, fileName, deviceName]);

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
      {/* Header Info & Close Button */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full ${accent.progressBgDot} animate-pulse`} />
          <h3 className="text-sm font-semibold tracking-tight text-neutral-100">Sending file</h3>
          <span className="text-xs text-neutral-400">
            to <span className="font-medium text-neutral-200">{deviceName}</span>
          </span>
        </div>

        <div className="flex items-center gap-2">
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

          {/* Close Button */}
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
          <p className="truncate text-sm font-medium text-neutral-200">{fileName}</p>
          <div className="mt-0.5 flex items-center gap-2 text-xs text-neutral-500 select-none">
            <span>{speed}</span>
            <span>•</span>
            <span>{timeRemaining} remaining</span>
          </div>
        </div>

        {/* Progress Percent */}
        <div className="text-right select-none">
          <span className="text-lg font-semibold tracking-tight text-neutral-100">
            {progress}%
          </span>
        </div>
      </div>

      {/* Progress Bar / Success State */}
      <div className="relative w-full">
        {!isCompleted && !isFailed ? (
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-800">
            <div
              className={`h-full rounded-full ${accent.switchBg} transition-all duration-75 ease-out`}
              style={{ width: `${progress}%` }}
            />
          </div>
        ) : isCompleted ? (
          <div className="flex items-center gap-2 text-xs text-emerald-400 font-medium py-0.5 select-none">
            <CheckCircle2 size={14} strokeWidth={2} />
            <span>Transfer completed successfully.</span>
          </div>
        ) : (
          <div className="flex items-center gap-2 text-xs text-red-400 font-medium py-0.5 select-none">
            <span>Transfer failed.</span>
          </div>
        )}
      </div>
    </div>
  );
};

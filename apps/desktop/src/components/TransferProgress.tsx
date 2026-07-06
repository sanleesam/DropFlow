import React, { useEffect, useState } from "react";
import { X, CheckCircle2 } from "lucide-react";
import { useToast } from "./ToastProvider";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

interface TransferProgressProps {
  /** Name of the destination device */
  deviceName: string;
  /** Name of the file being sent */
  fileName: string;
  /** Callback fired when the user closes/dismisses the card */
  onClose: () => void;
  /** Callback fired when transfer reaches 100% */
  onComplete?: () => void;
}

type TransferStatus = "Preparing..." | "Sending..." | "Finishing..." | "Completed";

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
  const [speed, setSpeed] = useState("125 MB/s");
  const [timeRemaining, setTimeRemaining] = useState("12 seconds");

  useEffect(() => {
    // Reset state whenever file or device changes
    setProgress(0);
    setStatus("Preparing...");
    setSpeed("125 MB/s");
    setTimeRemaining("12 seconds");

    let currentProgress = 0;
    const duration = 5000; // 5 seconds duration
    const intervalTime = 50; // Update every 50ms
    const totalSteps = duration / intervalTime;
    const stepIncrement = 100 / totalSteps;

    const timer = setInterval(() => {
      currentProgress += stepIncrement;
      
      if (currentProgress >= 100) {
        currentProgress = 100;
        setProgress(100);
        setStatus("Completed");
        setSpeed("0 MB/s");
        setTimeRemaining("0 seconds");
        clearInterval(timer);
        addToast("Transfer completed.", "success");
        if (onComplete) {
          onComplete();
        }
      } else {
        const roundedProgress = Math.round(currentProgress);
        setProgress(roundedProgress);

        // Update status text based on progress
        if (roundedProgress < 15) {
          setStatus("Preparing...");
          setSpeed("125 MB/s");
          setTimeRemaining("12 seconds");
        } else if (roundedProgress < 85) {
          setStatus("Sending...");
          setSpeed("125 MB/s");
          // Calculate mock countdown from 12 seconds remaining
          const remainingSecs = Math.max(1, Math.round(((100 - roundedProgress) / 85) * 12));
          setTimeRemaining(`${remainingSecs} second${remainingSecs !== 1 ? "s" : ""}`);
        } else {
          setStatus("Finishing...");
          setSpeed("125 MB/s"); // Keep speed consistent or drop to show finalization
          setTimeRemaining("1 second");
        }
      }
    }, intervalTime);

    return () => clearInterval(timer);
  }, [deviceName, fileName, addToast, onComplete]);

  const isCompleted = progress === 100;

  return (
    <div
      className="
        w-full relative rounded-2xl border border-white/6 overflow-hidden
        bg-slate-900/50 backdrop-blur-md
        shadow-[0_8px_32px_rgba(0,0,0,0.4)]
        p-5 flex flex-col gap-4
        transition-all duration-300
        animate-slide-in-up
      "
    >
      {/* Subtle top-left gradient sheen */}
      <div className="absolute inset-0 bg-gradient-to-br from-white/[0.02] to-transparent pointer-events-none" />

      {/* Header Info & Close Button */}
      <div className="flex items-start justify-between z-10">
        <div className="flex flex-col gap-0.5">
          <h3 className="text-sm font-semibold uppercase tracking-widest text-slate-400 select-none">
            Sending Files
          </h3>
          <p className="text-xs text-slate-500">
            To <span className="text-slate-300 font-medium">{deviceName}</span>
          </p>
        </div>

        <div className="flex items-center gap-2">
          {/* Status Label */}
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold bg-white/5 border border-white/5 select-none">
            {!isCompleted ? (
              <>
                <span className={`w-1.5 h-1.5 rounded-full ${accent.progressBgDot} animate-pulse`} />
                <span className={accent.progressText}>{status}</span>
              </>
            ) : (
              <>
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                <span className="text-emerald-400">{status}</span>
              </>
            )}
          </div>

          {/* Close Button */}
          <button
            type="button"
            onClick={onClose}
            className="
              w-6 h-6 rounded-lg
              flex items-center justify-center
              text-slate-500 hover:text-slate-200
              hover:bg-white/5
              transition-all duration-150
              outline-none focus-visible:ring-1 focus-visible:ring-slate-500
            "
            aria-label="Dismiss progress panel"
          >
            <X size={14} strokeWidth={2.5} />
          </button>
        </div>
      </div>

      {/* File Progress Details Card */}
      <div className="flex items-center gap-3 bg-white/[0.02] border border-white/5 rounded-xl p-3.5 z-10">
        {/* File icon */}
        <div className={`flex items-center justify-center w-9 h-9 rounded-lg ${accent.progressIconBg} ${accent.progressIconText}`}>
          <svg
            viewBox="0 0 24 24"
            className="w-5 h-5 flex-shrink-0"
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
          <p className="text-sm font-medium text-slate-200 truncate">{fileName}</p>
          <div className="flex items-center gap-2 mt-0.5 text-xs text-slate-500 select-none">
            <span>{speed}</span>
            <span>•</span>
            <span>{timeRemaining} remaining</span>
          </div>
        </div>

        {/* Progress Percent */}
        <div className="text-right select-none">
          <span className="text-lg font-semibold tracking-tight text-slate-100">
            {progress}%
          </span>
        </div>
      </div>

      {/* Progress Bar / Success State Replacement */}
      <div className="relative w-full z-10">
        {!isCompleted ? (
          <div className="w-full h-1.5 bg-slate-800 rounded-full overflow-hidden">
            <div
              className={`h-full bg-gradient-to-r ${accent.progressFrom} ${accent.progressTo} rounded-full transition-all duration-75 ease-out`}
              style={{ width: `${progress}%` }}
            />
          </div>
        ) : (
          <div className="flex items-center gap-2 text-sm text-emerald-400 font-medium py-0.5 animate-[toast-slide-in_0.3s_ease-out] select-none">
            <CheckCircle2 size={16} strokeWidth={2.5} />
            <span>Transfer completed.</span>
          </div>
        )}
      </div>
    </div>
  );
};

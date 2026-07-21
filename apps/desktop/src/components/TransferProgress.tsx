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
  const [isDismissing, setIsDismissing] = useState(false);

  const handleDismiss = () => {
    setIsDismissing(true);
    setTimeout(onClose, 150);
  };

  useEffect(() => {
    setProgress(0);
    setStatus("Preparing...");
    setSpeed("125 MB/s");
    setTimeRemaining("12 seconds");

    let currentProgress = 0;
    const duration = 4000;
    const intervalTime = 40;
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

        if (roundedProgress < 15) {
          setStatus("Preparing...");
          setSpeed("125 MB/s");
          setTimeRemaining("12 seconds");
        } else if (roundedProgress < 85) {
          setStatus("Sending...");
          setSpeed("125 MB/s");
          const remainingSecs = Math.max(1, Math.round(((100 - roundedProgress) / 85) * 12));
          setTimeRemaining(`${remainingSecs} second${remainingSecs !== 1 ? "s" : ""}`);
        } else {
          setStatus("Finishing...");
          setSpeed("125 MB/s");
          setTimeRemaining("1 second");
        }
      }
    }, intervalTime);

    return () => clearInterval(timer);
  }, [deviceName, fileName, addToast, onComplete]);

  const isCompleted = progress === 100;

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
            {!isCompleted ? (
              <>
                <span className={`h-1.5 w-1.5 rounded-full ${accent.progressBgDot} animate-pulse`} />
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
        {!isCompleted ? (
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-neutral-800">
            <div
              className={`h-full rounded-full ${accent.switchBg} transition-all duration-75 ease-out`}
              style={{ width: `${progress}%` }}
            />
          </div>
        ) : (
          <div className="flex items-center gap-2 text-xs text-emerald-400 font-medium py-0.5 select-none">
            <CheckCircle2 size={14} strokeWidth={2} />
            <span>Transfer completed successfully.</span>
          </div>
        )}
      </div>
    </div>
  );
};

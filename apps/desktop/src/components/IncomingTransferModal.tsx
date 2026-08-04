import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { ShieldCheck, FileText, Check, X, Smartphone, Monitor } from "lucide-react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

export interface IncomingTransferRequestData {
  sessionId: string;
  senderId: string;
  senderName: string;
  senderPlatform?: string;
  totalFiles: number;
  totalSizeBytes: number;
  files: Array<{
    fileIndex: number;
    relativePath: string;
    sizeBytes: number;
  }>;
}

interface IncomingTransferModalProps {
  request: IncomingTransferRequestData;
  onClose: () => void;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

export const IncomingTransferModal: React.FC<IncomingTransferModalProps> = ({ request, onClose }) => {
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [trustDevice, setTrustDevice] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleRespond = async (accept: boolean) => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await invoke("respond_transfer_request", {
        sessionId: request.sessionId,
        accept,
        trustDevice: accept ? trustDevice : false,
      });
    } catch (err) {
      console.error("[IncomingTransferModal] Failed to respond to transfer request:", err);
    } finally {
      onClose();
    }
  };

  const rawPlatform = (request.senderPlatform || request.senderName).toLowerCase();
  const isMobile = rawPlatform.includes("mobile") || rawPlatform.includes("android") || rawPlatform.includes("phone");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 p-4 backdrop-blur-md animate-[backdrop-fade-in_0.15s_ease-out]">
      <div className="flex flex-col w-full max-w-md rounded-2xl border border-white/[0.12] bg-[#14161a] p-6 shadow-2xl animate-[modal-scale-in_0.2s_ease-out] select-none">
        {/* Header */}
        <div className="flex items-center gap-3 pb-4 border-b border-white/[0.08]">
          <div className="p-2.5 rounded-xl bg-neutral-800/80 border border-white/[0.08] text-neutral-200">
            {isMobile ? <Smartphone className="w-5 h-5 text-blue-400" /> : <Monitor className="w-5 h-5 text-blue-400" />}
          </div>
          <div className="flex flex-col">
            <span className="text-xs font-semibold uppercase tracking-wider text-neutral-400">Incoming File Transfer</span>
            <span className="text-sm font-bold text-white truncate max-w-[260px]">{request.senderName}</span>
          </div>
        </div>

        {/* Transfer details */}
        <div className="flex flex-col gap-3 py-4">
          <div className="flex items-center justify-between p-3 rounded-xl bg-neutral-950/60 border border-white/[0.06]">
            <div className="flex flex-col">
              <span className="text-xs text-neutral-400">Payload</span>
              <span className="text-sm font-semibold text-neutral-100 mt-0.5">
                {request.totalFiles} {request.totalFiles === 1 ? "file" : "files"} ({formatBytes(request.totalSizeBytes)})
              </span>
            </div>
            <div className="p-2 rounded-lg bg-neutral-800/60 text-neutral-400">
              <FileText className="w-4 h-4" />
            </div>
          </div>

          {/* Files preview list */}
          {request.files && request.files.length > 0 && (
            <div className="flex flex-col gap-1.5 max-h-32 overflow-y-auto pr-1">
              {request.files.slice(0, 4).map((file, idx) => (
                <div key={idx} className="flex items-center justify-between px-3 py-1.5 rounded-lg bg-neutral-900/40 text-xs text-neutral-300">
                  <span className="truncate max-w-[240px] font-mono">{file.relativePath}</span>
                  <span className="text-neutral-500 font-mono">{formatBytes(file.sizeBytes)}</span>
                </div>
              ))}
              {request.files.length > 4 && (
                <span className="text-[11px] text-neutral-500 text-center italic mt-1">
                  + {request.files.length - 4} more files
                </span>
              )}
            </div>
          )}

          {/* Trust device checkbox */}
          <label className="flex items-center gap-2.5 pt-2 cursor-pointer group select-none">
            <input
              type="checkbox"
              checked={trustDevice}
              onChange={(e) => setTrustDevice(e.target.checked)}
              className="w-4 h-4 rounded border-white/[0.2] bg-neutral-900 text-blue-600 focus:ring-blue-500/50 cursor-pointer"
            />
            <div className="flex items-center gap-1.5 text-xs text-neutral-300 group-hover:text-white transition-colors">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <span>Trust this device permanently</span>
            </div>
          </label>
        </div>

        {/* Action buttons */}
        <div className="flex items-center justify-end gap-3 pt-4 border-t border-white/[0.08]">
          <button
            type="button"
            disabled={isSubmitting}
            onClick={() => handleRespond(false)}
            className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl border border-white/[0.08] bg-neutral-800/80 hover:bg-neutral-700/80 text-xs font-medium text-neutral-300 transition-colors cursor-pointer"
          >
            <X className="w-4 h-4" />
            Decline
          </button>
          <button
            type="button"
            disabled={isSubmitting}
            onClick={() => handleRespond(true)}
            className={`flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-xl text-xs font-semibold ${accent.switchBg} text-white shadow-lg transition-colors cursor-pointer`}
          >
            <Check className="w-4 h-4" />
            Accept
          </button>
        </div>
      </div>
    </div>
  );
};

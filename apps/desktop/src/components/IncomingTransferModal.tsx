import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useSettings } from "./SettingsProvider";
import { deviceArt, fileArt } from "../ui/artwork";
import { formatBytes } from "../utils/formatters";

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

export const IncomingTransferModal: React.FC<IncomingTransferModalProps> = ({ request, onClose }) => {
  const { settings } = useSettings();
  const [trustDevice, setTrustDevice] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const art = deviceArt(request.senderPlatform || "");

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

  return (
    <div className="df-modal-back">
      <div className="df-dialog" role="dialog" aria-modal="true" aria-label="Incoming transfer">
        <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
          <img src={art} alt="" style={{ width: 72, height: 52, objectFit: "contain" }} />
          <div>
            <div className="df-meta">Incoming transfer</div>
            <h2 className="df-section-title">{request.senderName}</h2>
          </div>
        </div>
        <p className="df-kicker" style={{ marginTop: 12 }}>
          {request.totalFiles} {request.totalFiles === 1 ? "file" : "files"} · {formatBytes(request.totalSizeBytes)}
        </p>
        <p className="df-meta">Files will be saved on this device{settings.receiveDirectory ? ` in ${settings.receiveDirectory}` : ""}.</p>
        <div style={{ maxHeight: 160, overflow: "auto", marginTop: 10 }}>
          {request.files.slice(0, 6).map((file) => (
            <div key={file.fileIndex} className="df-file-row">
              <img src={fileArt(file.relativePath)} alt="" />
              <span className="df-grow df-ellipsis">{file.relativePath}</span>
              <span className="df-meta">{formatBytes(file.sizeBytes)}</span>
            </div>
          ))}
          {request.files.length > 6 && <p className="df-meta">+ {request.files.length - 6} more</p>}
        </div>
        <label style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 12, fontSize: 13 }}>
          <input type="checkbox" checked={trustDevice} onChange={(event) => setTrustDevice(event.target.checked)} />
          Don't ask again for this device
        </label>
        <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
          <button type="button" className="df-btn df-btn-ghost" style={{ flex: 1 }} disabled={isSubmitting} onClick={() => handleRespond(false)}>Decline</button>
          <button type="button" className="df-btn df-btn-primary" style={{ flex: 1 }} disabled={isSubmitting} onClick={() => handleRespond(true)}>Accept</button>
        </div>
      </div>
    </div>
  );
};

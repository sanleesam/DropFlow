import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useSettings } from "./SettingsProvider";
import { DeviceVisual, Modal } from "./ui/Desktop";
import { File } from "lucide-react";
import { useToast } from "./ToastProvider";
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

export const IncomingTransferModal: React.FC<IncomingTransferModalProps> = ({
  request,
  onClose,
}) => {
  const { settings } = useSettings();
  const { addToast } = useToast();
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
      console.error(
        "[IncomingTransferModal] Failed to respond to transfer request:",
        err,
      );
      addToast(
        "Couldn't respond to the incoming transfer. Ask the sender to try again.",
        "error",
      );
    } finally {
      onClose();
    }
  };

  return (
    <Modal
      title="Incoming transfer"
      onEscape={isSubmitting ? undefined : () => handleRespond(false)}
    >
      <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
        <div style={{ width: 72, flexShrink: 0 }}>
          <DeviceVisual type={request.senderPlatform} />
        </div>
        <div>
          <div className="df-meta">Incoming transfer</div>
          <h2 className="df-section-title">{request.senderName}</h2>
        </div>
      </div>
      <p className="df-kicker" style={{ marginTop: 12 }}>
        {request.totalFiles} {request.totalFiles === 1 ? "file" : "files"} ·{" "}
        {formatBytes(request.totalSizeBytes)}
      </p>
      <p className="df-meta">
        Files will be saved on this device
        {settings.receiveDirectory ? ` in ${settings.receiveDirectory}` : ""}.
      </p>
      <div style={{ maxHeight: 160, overflow: "auto", marginTop: 10 }}>
        {request.files.slice(0, 6).map((file) => (
          <div key={file.fileIndex} className="df-file-row">
            <File size={18} />
            <span className="df-grow df-ellipsis">{file.relativePath}</span>
            <span className="df-meta">{formatBytes(file.sizeBytes)}</span>
          </div>
        ))}
        {request.files.length > 6 && (
          <p className="df-meta">+ {request.files.length - 6} more</p>
        )}
      </div>
      <label
        style={{
          display: "flex",
          gap: 8,
          alignItems: "center",
          marginTop: 12,
          fontSize: 13,
        }}
      >
        <input
          type="checkbox"
          checked={trustDevice}
          onChange={(event) => setTrustDevice(event.target.checked)}
        />
        Don't ask again for this device
      </label>
      <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
        <button
          type="button"
          className="df-btn df-btn-ghost"
          style={{ flex: 1 }}
          disabled={isSubmitting}
          onClick={() => handleRespond(false)}
        >
          Decline
        </button>
        <button
          type="button"
          className="df-btn df-btn-primary"
          style={{ flex: 1 }}
          disabled={isSubmitting}
          onClick={() => handleRespond(true)}
        >
          {isSubmitting ? "Responding…" : "Accept files"}
        </button>
      </div>
    </Modal>
  );
};

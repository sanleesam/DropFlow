import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useSettings } from "./SettingsProvider";
import { DeviceVisual, Modal, Switch } from "./ui/Desktop";
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

  const folder = settings.receiveDirectory
    ? settings.receiveDirectory.split(/[/\\]/).filter(Boolean).pop()
    : "Downloads";
  return (
    <Modal
      title="Incoming transfer"
      onEscape={isSubmitting ? undefined : () => handleRespond(false)}
    >
      <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
        <DeviceVisual type={request.senderPlatform} />
        <div className="grow">
          <h2 className="ellipsis">{request.senderName}</h2>
          <p className="lede num">
            {request.totalFiles} {request.totalFiles === 1 ? "file" : "files"} ·{" "}
            {formatBytes(request.totalSizeBytes)} · to {folder}
          </p>
        </div>
      </div>
      <div className="card list">
        {request.files.slice(0, 6).map((file) => (
          <div key={file.fileIndex} className="row">
            <File size={15} className="tertiary" />
            <span className="grow ellipsis" title={file.relativePath}>
              {file.relativePath}
            </span>
            <span className="row-trail num">{formatBytes(file.sizeBytes)}</span>
          </div>
        ))}
        {request.files.length > 6 && (
          <div className="row footnote">+ {request.files.length - 6} more</div>
        )}
      </div>
      <div className="toggle-line">
        <span>
          Remember this device
          <span className="footnote" style={{ display: "block" }}>
            You'll still be asked each time.
          </span>
        </span>
        <Switch
          label="Remember this device"
          checked={trustDevice}
          onChange={() => setTrustDevice((value) => !value)}
        />
      </div>
      <div className="sheet-actions">
        <button
          type="button"
          className="btn"
          disabled={isSubmitting}
          onClick={() => handleRespond(false)}
        >
          Decline
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={isSubmitting}
          onClick={() => handleRespond(true)}
        >
          {isSubmitting ? "Responding…" : "Accept"}
        </button>
      </div>
    </Modal>
  );
};

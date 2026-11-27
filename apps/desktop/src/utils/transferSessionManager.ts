import { formatBytes, formatSpeed } from "./formatters";

export const MAX_HISTORY_CAPACITY = 500;

export interface FileMetadataPayload {
  fileIndex: number;
  relativePath: string;
  sizeBytes: number;
  sha256Checksum?: string;
  finalPath?: string;
}

export interface ProgressPayload {
  sessionId: string;
  direction?: "send" | "receive";
  deviceName?: string;
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

export interface CompletedPayload {
  sessionId: string;
  fileName?: string;
  deviceName?: string;
  size?: string;
  timestamp?: string;
  timestampMs?: number;
  totalFiles?: number;
  totalSizeBytes?: number;
  files?: FileMetadataPayload[];
  receiveDir?: string;
  direction?: "send" | "receive";
}

export interface FailedPayload {
  sessionId: string;
  error?: string;
}

export interface IncompleteTransfer {
  sessionId: string;
  fileName: string;
  deviceName: string;
  totalFiles: number;
  totalSizeBytes: number;
  bytesCompleted: number;
  direction: "send" | "receive";
  receiveDir?: string;
  filePaths?: string[];
  files?: FileMetadataPayload[];
  timestampMs?: number;
}

export interface ActiveTransferSession {
  id: string;
  deviceName: string;
  fileName: string;
  totalFiles: number;
  direction: "send" | "receive";
  progress: number;
  status:
    | "Preparing..."
    | "Waiting..."
    | "Sending..."
    | "Receiving..."
    | "Finishing..."
    | "Completed"
    | "Cancelled"
    | "Failed";
  speed: string;
  timeRemaining: string;
  bytesInfo: string;
  currentFileLabel: string;
  completedFiles: FileMetadataPayload[];
  receiveDir?: string;
  error?: string;
}

export interface RecentTransfer {
  id: string;
  fileName: string;
  deviceName: string;
  size: string;
  timestamp: string;
  timestampMs?: number;
  status: "completed" | "failed";
  direction: "send" | "receive";
  totalFiles: number;
  totalSizeBytes?: number;
  files?: FileMetadataPayload[];
  receiveDir?: string;
  error?: string;
}

export interface SessionStateStore {
  activeTransfers: Record<string, ActiveTransferSession>;
  recentTransfers: RecentTransfer[];
  completedSessionIds: Set<string>;
}

const LIVE_STATUSES = ["Preparing...", "Waiting...", "Sending...", "Receiving...", "Finishing..."];

/** Still running: not completed, failed or cancelled. */
export function isLive(session: Pick<ActiveTransferSession, "status">): boolean {
  return LIVE_STATUSES.includes(session.status);
}

/** Not moving bytes yet: nothing to measure. */
export function isPending(session: Pick<ActiveTransferSession, "status">): boolean {
  return session.status === "Preparing..." || session.status === "Waiting...";
}

/** One line under a running transfer: who, and what it is doing right now. */
export function transferSubtitle(session: ActiveTransferSession, withEta = false): string {
  const who = `${session.direction === "receive" ? "From" : "To"} ${session.deviceName}`;
  if (session.status === "Waiting...") return `Waiting for ${session.deviceName} to accept`;
  if (session.status === "Preparing...") return `${who} · Connecting`;
  if (session.status === "Finishing...") {
    return `${who} · ${session.direction === "receive" ? "Saving" : "Confirming"}`;
  }
  const parts = [who, session.speed];
  if (withEta && session.timeRemaining && session.timeRemaining !== "Calculating...") {
    parts.push(session.timeRemaining);
  }
  return parts.filter(Boolean).join(" · ");
}

export interface PhasePayload {
  sessionId: string;
  phase?: "waiting" | "transferring" | "finishing" | string;
}

/** Between progress updates: waiting for approval, or verifying and saving. */
export function applyPhaseEvent(store: SessionStateStore, payload: PhasePayload): SessionStateStore {
  const existing = store.activeTransfers[payload.sessionId];
  if (!existing || !isLive(existing) || store.completedSessionIds.has(payload.sessionId)) return store;
  let next: ActiveTransferSession;
  if (payload.phase === "waiting") {
    next = { ...existing, status: "Waiting...", speed: "", timeRemaining: "" };
  } else if (payload.phase === "finishing") {
    next = { ...existing, status: "Finishing...", progress: 100, speed: "", timeRemaining: "" };
  } else if (payload.phase === "transferring") {
    if (existing.status !== "Waiting..." && existing.status !== "Preparing...") return store;
    next = {
      ...existing,
      status: existing.direction === "receive" ? "Receiving..." : "Sending...",
      timeRemaining: "Calculating...",
    };
  } else {
    return store;
  }
  return { ...store, activeTransfers: { ...store.activeTransfers, [payload.sessionId]: next } };
}

export function createInitialSessionStore(): SessionStateStore {
  return {
    activeTransfers: {},
    recentTransfers: [],
    completedSessionIds: new Set(),
  };
}

export function startSendSession(
  store: SessionStateStore,
  sessionId: string,
  deviceName: string,
  displayFileName: string,
  totalFiles: number
): SessionStateStore {
  return {
    ...store,
    activeTransfers: {
      ...store.activeTransfers,
      [sessionId]: {
        id: sessionId,
        deviceName,
        fileName: displayFileName,
        totalFiles,
        direction: "send",
        progress: 0,
        status: "Preparing...",
        speed: "0 MB/s",
        timeRemaining: "Calculating...",
        bytesInfo: "",
        currentFileLabel: displayFileName,
        completedFiles: [],
      },
    },
  };
}

export function formatHumanEta(seconds: number, speedBytesPerSec: number): string {
  if (speedBytesPerSec <= 0 || !isFinite(seconds) || seconds < 0) {
    return "Calculating...";
  }

  const secs = Math.ceil(seconds);

  if (secs === 0) {
    return "0 seconds remaining";
  }

  if (secs < 60) {
    return `${secs} second${secs !== 1 ? "s" : ""} remaining`;
  }

  if (secs < 120) {
    return "1 minute remaining";
  }

  if (secs < 3600) {
    const mins = Math.floor(secs / 60);
    return `${mins} minutes remaining`;
  }

  if (secs < 7200) {
    const remMins = Math.floor((secs % 3600) / 60);
    if (remMins === 0) {
      return "1 hour remaining";
    }
    return `1 hour ${remMins} minute${remMins !== 1 ? "s" : ""} remaining`;
  }

  const hours = Math.floor(secs / 3600);
  const remMins = Math.floor((secs % 3600) / 60);
  if (remMins === 0) {
    return `${hours} hours remaining`;
  }
  return `${hours} hours ${remMins} minute${remMins !== 1 ? "s" : ""} remaining`;
}

export function applyProgressEvent(
  store: SessionStateStore,
  payload: ProgressPayload
): SessionStateStore {
  const { sessionId } = payload;
  if (!sessionId) return store;

  const existing = store.activeTransfers[sessionId];
  if (store.completedSessionIds.has(sessionId) || existing?.status === "Failed" || existing?.status === "Cancelled") return store;
  const pct = payload.percentage ?? 0;
  // 100% is shown only when every byte has moved; 99.6% is still 99%.
  const roundedProgress = Math.min(100, Math.max(0, Math.floor(pct + 1e-6)));

  const speedBytes = payload.speedBytesPerSec ?? 0;
  const speedStr = formatSpeed(speedBytes);

  const sessionSent = payload.sessionBytesSent ?? 0;
  const sessionTotal = payload.sessionTotalBytes ?? 0;

  let bytesStr = "";
  if (sessionTotal > 0) {
    bytesStr = `${formatBytes(sessionSent)} / ${formatBytes(sessionTotal)}`;
  }

  let label = existing?.fileName ?? payload.currentFileName ?? "File";
  const fileCount = payload.totalFiles ?? existing?.totalFiles ?? 1;
  if (payload.currentFileName) {
    const fileIdx = (payload.currentFileIndex ?? 0) + 1;
    if (fileCount > 1) {
      label = `[${fileIdx}/${fileCount}] ${payload.currentFileName}`;
    } else {
      label = payload.currentFileName;
    }
  }

  const remainingBytes = Math.max(0, sessionTotal - sessionSent);
  const remainingSecs = speedBytes > 0 ? remainingBytes / speedBytes : -1;
  const timeStr = formatHumanEta(remainingSecs, speedBytes);

  const direction = existing?.direction ?? payload.direction ?? "receive";
  const updatedSession: ActiveTransferSession = {
    id: sessionId,
    deviceName: existing?.deviceName ?? payload.deviceName ?? "Peer Device",
    fileName: existing?.fileName ?? label,
    totalFiles: fileCount,
    direction,
    progress: roundedProgress,
    status:
      roundedProgress >= 100 || existing?.status === "Finishing..."
        ? "Finishing..."
        : direction === "receive"
          ? "Receiving..."
          : "Sending...",
    speed: roundedProgress >= 100 ? "" : speedStr,
    timeRemaining: roundedProgress >= 100 ? "" : timeStr,
    bytesInfo: bytesStr,
    currentFileLabel: label,
    completedFiles: existing?.completedFiles ?? [],
  };

  return {
    ...store,
    activeTransfers: {
      ...store.activeTransfers,
      [sessionId]: updatedSession,
    },
  };
}

export function formatRelativeTimestamp(timestampMs?: number): string {
  if (!timestampMs || !isFinite(timestampMs) || timestampMs <= 0) {
    return "Just now";
  }

  const now = Date.now();
  const diffMs = Math.max(0, now - timestampMs);
  const diffSecs = Math.floor(diffMs / 1000);
  const diffMins = Math.floor(diffSecs / 60);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffSecs < 60) {
    return "Just now";
  }

  if (diffMins < 60) {
    return `${diffMins} minute${diffMins !== 1 ? "s" : ""} ago`;
  }

  if (diffHours < 24) {
    return `${diffHours} hour${diffHours !== 1 ? "s" : ""} ago`;
  }

  if (diffDays === 1) {
    return "Yesterday";
  }

  if (diffDays < 7) {
    return `${diffDays} days ago`;
  }

  const d = new Date(timestampMs);
  const monthNames = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const day = d.getDate();
  const month = monthNames[d.getMonth()];
  const year = d.getFullYear();
  return `${day} ${month} ${year}`;
}

export function applyCompletionEvent(
  store: SessionStateStore,
  payload: CompletedPayload
): SessionStateStore {
  const { sessionId } = payload;
  if (!sessionId) return store;

  // Idempotency check: if already completed, return unchanged
  if (store.completedSessionIds.has(sessionId)) {
    return store;
  }

  const active = store.activeTransfers[sessionId];
  const direction = payload.direction || active?.direction || "send";
  const totalFiles = payload.totalFiles ?? active?.totalFiles ?? 1;
  const deviceName = payload.deviceName || active?.deviceName || "Target Device";
  const receiveDir = payload.receiveDir || active?.receiveDir;

  let displayName = payload.fileName || active?.fileName || "File";
  if (totalFiles > 1 && payload.files && payload.files.length > 0) {
    // `files` may be a capped list; the count comes from the transfer itself.
    const first = payload.files[0].relativePath;
    const others = Math.max(totalFiles, payload.files.length) - 1;
    displayName = `${first} (+${others} other ${others === 1 ? "file" : "files"})`;
  }

  const rawCompletedFiles = payload.files ?? active?.completedFiles ?? [];
  const completedFiles: FileMetadataPayload[] = rawCompletedFiles.map((f) => ({
    ...f,
    sha256Checksum: f.sha256Checksum && f.sha256Checksum.trim() !== "" ? f.sha256Checksum : undefined,
  }));

  const computedTotalBytes =
    payload.totalSizeBytes ??
    (completedFiles.length > 0
      ? completedFiles.reduce((acc, f) => acc + (f.sizeBytes || 0), 0)
      : undefined);

  let formattedSize = payload.size;
  if (!formattedSize || formattedSize === "Complete") {
    if (computedTotalBytes && computedTotalBytes > 0) {
      formattedSize = formatBytes(computedTotalBytes);
    } else {
      formattedSize = "Complete";
    }
  }

  const timestampMs = payload.timestampMs ?? Date.now();
  const timestampStr = payload.timestamp || formatRelativeTimestamp(timestampMs);

  const newRecent: RecentTransfer = {
    id: sessionId,
    fileName: displayName,
    deviceName,
    size: formattedSize,
    timestamp: timestampStr,
    timestampMs,
    status: "completed",
    direction,
    totalFiles,
    totalSizeBytes: computedTotalBytes,
    files: completedFiles,
    receiveDir,
  };

  const updatedActiveSession: ActiveTransferSession = {
    id: sessionId,
    deviceName,
    fileName: displayName,
    totalFiles,
    direction,
    progress: 100,
    status: "Completed",
    speed: "0 MB/s",
    timeRemaining: "0 seconds",
    bytesInfo: active?.bytesInfo ?? "",
    currentFileLabel: displayName,
    completedFiles,
    receiveDir,
  };

  const nextCompletedIds = new Set(store.completedSessionIds);
  nextCompletedIds.add(sessionId);

  const nextRecentTransfers = [newRecent, ...store.recentTransfers].slice(
    0,
    MAX_HISTORY_CAPACITY
  );

  return {
    activeTransfers: {
      ...store.activeTransfers,
      [sessionId]: updatedActiveSession,
    },
    recentTransfers: nextRecentTransfers,
    completedSessionIds: nextCompletedIds,
  };
}

export function applyFailureEvent(
  store: SessionStateStore,
  payload: FailedPayload
): SessionStateStore {
  const { sessionId, error } = payload;
  if (!sessionId) return store;
  if (store.completedSessionIds.has(sessionId)) return store;

  const active = store.activeTransfers[sessionId];
  if (!active) return store;
  if (active.status === "Cancelled") return store;

  const isCancelled = error ? /cancell?ed/i.test(error) : false;

  const updatedActive: ActiveTransferSession = {
    ...active,
    status: isCancelled ? "Cancelled" : "Failed",
    error: error || (isCancelled ? "Transfer cancelled" : "Unknown error"),
  };

  return {
    ...store,
    activeTransfers: {
      ...store.activeTransfers,
      [sessionId]: updatedActive,
    },
    recentTransfers: isCancelled ? store.recentTransfers : [{
      id: sessionId, fileName: active.fileName, deviceName: active.deviceName,
      size: "Incomplete", timestamp: "Just now", timestampMs: Date.now(),
      status: "failed" as const, direction: active.direction, totalFiles: active.totalFiles,
      files: active.completedFiles, receiveDir: active.receiveDir, error: updatedActive.error,
    }, ...store.recentTransfers.filter((item) => item.id !== sessionId)].slice(0, MAX_HISTORY_CAPACITY),
  };
}

export function dismissActiveSession(
  store: SessionStateStore,
  sessionId: string
): SessionStateStore {
  const nextActive = { ...store.activeTransfers };
  delete nextActive[sessionId];
  return {
    ...store,
    activeTransfers: nextActive,
  };
}

export function clearHistorySessions(
  store: SessionStateStore
): SessionStateStore {
  return {
    ...store,
    recentTransfers: [],
  };
}

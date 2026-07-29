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

export interface ActiveTransferSession {
  id: string;
  deviceName: string;
  fileName: string;
  totalFiles: number;
  direction: "send" | "receive";
  progress: number;
  status: "Preparing..." | "Sending..." | "Receiving..." | "Finishing..." | "Completed" | "Cancelled" | "Failed";
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

export function applyProgressEvent(
  store: SessionStateStore,
  payload: ProgressPayload
): SessionStateStore {
  const { sessionId } = payload;
  if (!sessionId) return store;

  const existing = store.activeTransfers[sessionId];
  const pct = payload.percentage ?? 0;
  const roundedProgress = Math.min(100, Math.max(0, Math.round(pct)));

  const speedBytes = payload.speedBytesPerSec ?? 0;
  const speedMb = (speedBytes / (1024 * 1024)).toFixed(1);
  const speedStr = `${speedMb} MB/s`;

  const sessionSent = payload.sessionBytesSent ?? 0;
  const sessionTotal = payload.sessionTotalBytes ?? 0;

  let bytesStr = "";
  if (sessionTotal > 0) {
    const sentMb = (sessionSent / (1024 * 1024)).toFixed(1);
    const totalMb = (sessionTotal / (1024 * 1024)).toFixed(1);
    bytesStr = `${sentMb} / ${totalMb} MB`;
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
  const remainingSecs = speedBytes > 0 ? Math.ceil(remainingBytes / speedBytes) : 0;
  const timeStr = `${remainingSecs} second${remainingSecs !== 1 ? "s" : ""}`;

  const direction = existing?.direction ?? "receive";
  const updatedSession: ActiveTransferSession = {
    id: sessionId,
    deviceName: existing?.deviceName ?? "Peer Device",
    fileName: existing?.fileName ?? label,
    totalFiles: fileCount,
    direction,
    progress: roundedProgress,
    status: roundedProgress >= 100 ? "Completed" : direction === "receive" ? "Receiving..." : "Sending...",
    speed: speedStr,
    timeRemaining: timeStr,
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
    const first = payload.files[0].relativePath;
    const count = payload.files.length;
    displayName = `${first} (+${count - 1} other ${count - 1 === 1 ? "file" : "files"})`;
  }

  const completedFiles = payload.files ?? active?.completedFiles ?? [];

  const newRecent: RecentTransfer = {
    id: sessionId,
    fileName: displayName,
    deviceName,
    size: payload.size || "Complete",
    timestamp: payload.timestamp || "Just now",
    status: "completed",
    direction,
    totalFiles,
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

  const active = store.activeTransfers[sessionId];
  if (!active) return store;

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

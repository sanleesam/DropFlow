import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { open } from "@tauri-apps/plugin-dialog";
import { isPermissionGranted, requestPermission, sendNotification } from "@tauri-apps/plugin-notification";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { IncomingTransferRequestData } from "../components/IncomingTransferModal";
import { AppPage, Device } from "../types/device";
import { friendlyTransferError } from "../ui/artwork";
import {
  SessionStateStore,
  RecentTransfer,
  applyCompletionEvent,
  applyFailureEvent,
  applyProgressEvent,
  createInitialSessionStore,
  dismissActiveSession,
  startSendSession,
} from "../utils/transferSessionManager";

export interface SelectedFile {
  uid: string;
  path: string;
  name: string;
}

interface DropFlowContextValue {
  page: AppPage;
  setPage: (page: AppPage) => void;
  devices: Device[];
  selectedDeviceId: string | null;
  selectDevice: (id: string) => void;
  deviceType: string;
  discovery: "searching" | "empty" | "error" | "ready";
  retryDiscovery: () => void;
  droppedOver: boolean;
  files: SelectedFile[];
  removeFile: (uid: string) => void;
  browseFiles: () => void;
  browseFolder: () => void;
  queueFiles: (paths: string[]) => void;
  sendSelected: () => void;
  filesDeviceId: string;
  openDeviceFiles: (deviceId: string) => void;
  focusDeviceFiles: (deviceId: string) => void;
  sessionStore: SessionStateStore;
  speedHistory: Record<string, number[]>;
  cancelTransfer: (id: string) => void;
  dismissTransfer: (id: string) => void;
  incoming: IncomingTransferRequestData | null;
  closeIncoming: () => void;
  lostDeviceName: string | null;
}

const DropFlowContext = createContext<DropFlowContextValue | null>(null);

function fileNameFromPath(filePath: string): string {
  const parts = filePath.replace(/\\/g, "/").split("/");
  return parts[parts.length - 1] || filePath;
}

async function notifyDesktop(title: string, body: string) {
  try {
    let granted = await isPermissionGranted();
    if (!granted) granted = (await requestPermission()) === "granted";
    if (granted) sendNotification({ title, body });
  } catch (err) {
    console.error("[Notification] Failed to send desktop notification:", err);
  }
}

export const DropFlowProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { settings } = useSettings();
  const { addToast } = useToast();
  const [page, setPage] = useState<AppPage>("home");
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [localUuid, setLocalUuid] = useState("");
  const [deviceType, setDeviceType] = useState("desktop");
  const [discovery, setDiscovery] = useState<DropFlowContextValue["discovery"]>("searching");
  const [showEmpty, setShowEmpty] = useState(false);
  const [files, setFiles] = useState<SelectedFile[]>([]);
  const [droppedOver, setDroppedOver] = useState(false);
  const [sessionStore, setSessionStore] = useState<SessionStateStore>(createInitialSessionStore);
  const [speedHistory, setSpeedHistory] = useState<Record<string, number[]>>({});
  const [incoming, setIncoming] = useState<IncomingTransferRequestData | null>(null);
  const [lostDeviceName, setLostDeviceName] = useState<string | null>(null);
  const [filesDeviceId, setFilesDeviceId] = useState("this");
  const discoveryEventRevision = useRef(0);
  const devicesRef = useRef<Device[]>([]);
  devicesRef.current = devices;

  useEffect(() => {
    invoke<string>("get_local_uuid").then(setLocalUuid).catch(console.error);
    invoke<string>("get_system_device_type").then(setDeviceType).catch(() => setDeviceType("desktop"));
  }, []);

  useEffect(() => {
    if (devices.length > 0) {
      setShowEmpty(false);
      return;
    }
    if (discovery === "error") return;
    const timer = setTimeout(() => setShowEmpty(true), 15000);
    return () => clearTimeout(timer);
  }, [devices.length, discovery]);

  const reconcileDevices = useCallback((nextDevices: Device[]) => {
    setDevices(nextDevices);
    setSelectedDeviceId((selectedId) =>
      selectedId && !nextDevices.some((device) => device.id === selectedId) ? null : selectedId,
    );
  }, []);

  useEffect(() => {
    invoke<any>("get_app_state")
      .then((appState) => {
        if (appState && Array.isArray(appState.history) && appState.history.length > 0) {
          setSessionStore((prev) => {
            if (prev.recentTransfers.length > 0) return prev;
            return { ...prev, recentTransfers: appState.history };
          });
        }
      })
      .catch(console.error);
  }, []);

  useEffect(() => {
    if (sessionStore.recentTransfers.length > 0) {
      invoke("save_history", { history: sessionStore.recentTransfers }).catch(console.error);
    }
  }, [sessionStore.recentTransfers]);

  useEffect(() => {
    let unlistenProgress: (() => void) | undefined;
    let unlistenCompleted: (() => void) | undefined;
    let unlistenFailed: (() => void) | undefined;
    let unlistenRequest: (() => void) | undefined;
    let unlistenDismiss: (() => void) | undefined;
    let unlistenHistory: (() => void) | undefined;

    const setup = async () => {
      unlistenRequest = await listen<IncomingTransferRequestData>("incoming-transfer-request", (event) => {
        setIncoming(event.payload);
      });
      unlistenDismiss = await listen<string>("incoming-transfer-dismiss", (event) => {
        setIncoming((prev) => (prev?.sessionId === event.payload ? null : prev));
      });
      unlistenProgress = await listen<any>("transfer-progress", (event) => {
        const payload = event.payload;
        setSessionStore((prev) => applyProgressEvent(prev, payload));
        if (payload?.sessionId) {
          setSpeedHistory((prev) => {
            const next = [...(prev[payload.sessionId] ?? []), payload.speedBytesPerSec ?? 0].slice(-28);
            return { ...prev, [payload.sessionId]: next };
          });
        }
      });
      unlistenCompleted = await listen<any>("transfer-completed", (event) => {
        const payload = event.payload;
        setSessionStore((prev) => {
          const isReceive = payload.direction === "receive" || prev.activeTransfers[payload.sessionId]?.direction === "receive";
          const next = applyCompletionEvent(prev, payload);
          if (isReceive && !prev.completedSessionIds.has(payload.sessionId)) {
            addToast(`Received ${payload.fileName || "a file"} from ${payload.deviceName || "a nearby device"}`, "success");
            notifyDesktop("DropFlow", `${payload.fileName || "File"} received from ${payload.deviceName || "a nearby device"}`);
            if (settings.autoOpenCompleted) {
              const targetPath = payload.finalPath || payload.receiveDir || payload.filePath;
              if (targetPath) invoke("open_received_file", { path: targetPath }).catch(console.error);
            }
          }
          return next;
        });
      });
      unlistenFailed = await listen<any>("transfer-failed", (event) => {
        const payload = event.payload;
        console.error("[Transfer] failed:", payload?.error);
        setSessionStore((prev) => applyFailureEvent(prev, { sessionId: payload.sessionId, error: payload.error }));
        addToast(friendlyTransferError(payload?.error), "error");
      });
      unlistenHistory = await listen<RecentTransfer[]>("history-updated", (event) => {
        if (!Array.isArray(event.payload)) return;
        setSessionStore((prev) => {
          const incomingHistory = event.payload;
          const unchanged =
            incomingHistory.length === prev.recentTransfers.length &&
            incomingHistory.every((item, index) => item?.id === prev.recentTransfers[index]?.id);
          if (unchanged) return prev;
          return { ...prev, recentTransfers: incomingHistory };
        });
      });
    };

    setup().catch((err) => console.error("[DropFlow] Failed to listen for transfers:", err));
    return () => {
      unlistenRequest?.();
      unlistenDismiss?.();
      unlistenProgress?.();
      unlistenCompleted?.();
      unlistenFailed?.();
      unlistenHistory?.();
    };
  }, [addToast, settings.autoOpenCompleted]);

  const retryDiscovery = useCallback(() => {
    setDiscovery("searching");
    setShowEmpty(false);
    invoke("start_discovery").catch((error) => {
      console.error("Failed to look for devices", error);
      setDiscovery("error");
    });
  }, []);

  useEffect(() => {
    let disposed = false;
    let unlistenDiscovered: (() => void) | undefined;
    let unlistenLost: (() => void) | undefined;

    const applyDiscoveredPeer = (newDevice: Device) => {
      discoveryEventRevision.current += 1;
      setDevices((previousDevices) => {
        const existingIndex = previousDevices.findIndex((device) => device.id === newDevice.id);
        if (existingIndex === -1) return [...previousDevices, newDevice];
        return previousDevices.map((device) => (device.id === newDevice.id ? newDevice : device));
      });
    };

    const applyLostPeer = (lostId: string) => {
      discoveryEventRevision.current += 1;
      const gone = devicesRef.current.find((device) => device.id === lostId);
      setDevices((previousDevices) => previousDevices.filter((device) => device.id !== lostId));
      setSelectedDeviceId((selectedId) => {
        if (selectedId === lostId) {
          setLostDeviceName(gone?.name || "That device");
          return null;
        }
        return selectedId;
      });
    };

    const hydratePeers = async () => {
      for (let attempt = 0; attempt < 2 && !disposed; attempt += 1) {
        const revisionBeforeSnapshot = discoveryEventRevision.current;
        const snapshot = await invoke<Device[]>("get_current_peers");
        if (disposed) return;
        if (revisionBeforeSnapshot === discoveryEventRevision.current) {
          reconcileDevices(snapshot);
          return;
        }
      }
    };

    const setupDiscovery = async () => {
      const discoveredUnlisten = await listen<Device>("peer-discovered", (event) => applyDiscoveredPeer(event.payload));
      unlistenDiscovered = discoveredUnlisten;
      if (disposed) {
        discoveredUnlisten();
        return;
      }
      const lostUnlisten = await listen<string>("peer-lost", (event) => applyLostPeer(event.payload));
      if (disposed) {
        discoveredUnlisten();
        lostUnlisten();
        return;
      }
      unlistenLost = lostUnlisten;
      await invoke("start_discovery");
      await hydratePeers();
    };

    setupDiscovery().catch((error) => {
      if (!disposed) {
        console.error("Failed to initialize discovery", error);
        setDiscovery("error");
      }
    });

    return () => {
      disposed = true;
      unlistenDiscovered?.();
      unlistenLost?.();
    };
  }, [reconcileDevices]);

  useEffect(() => {
    if (!localUuid) return;
    if (settings.deviceVisibility) {
      invoke("update_advertisement", {
        deviceId: localUuid,
        deviceName: settings.deviceName,
        deviceType,
        port: 1,
      }).catch(console.error);
    } else {
      invoke("update_advertisement", { deviceId: localUuid, deviceName: "", deviceType: "", port: 0 }).catch(console.error);
    }
  }, [settings.deviceName, settings.deviceVisibility, localUuid, deviceType]);

  const addFilePaths = useCallback((paths: string[]) => {
    if (!paths?.length) return;
    setFiles((prev) => {
      const known = new Set(prev.map((file) => file.path));
      const next = paths
        .filter((path) => path && !known.has(path))
        .map((path) => ({ uid: Math.random().toString(36).slice(2, 10), path, name: fileNameFromPath(path) }));
      return [...prev, ...next];
    });
    setPage("home");
  }, []);

  useEffect(() => {
    let unlisten: (() => void) | undefined;
    try {
      getCurrentWindow()
        .onDragDropEvent((event) => {
          if (event.payload.type === "enter" || event.payload.type === "over") setDroppedOver(true);
          else if (event.payload.type === "leave") setDroppedOver(false);
          else if (event.payload.type === "drop") {
            setDroppedOver(false);
            addFilePaths(event.payload.paths || []);
          }
        })
        .then((fn) => {
          unlisten = fn;
        })
        .catch((err) => console.error("[DropFlow] Drag and drop is unavailable:", err));
    } catch (err) {
      console.error("[DropFlow] Drag and drop is unavailable:", err);
    }
    return () => unlisten?.();
  }, [addFilePaths]);

  const browseFiles = useCallback(async () => {
    try {
      const selected = await open({ multiple: true, directory: false, title: "Select files to send" });
      if (!selected) return;
      addFilePaths(Array.isArray(selected) ? selected : [selected]);
    } catch (err) {
      console.error("[DropFlow] File dialog failed:", err);
      addToast("Couldn't open the file picker.", "error");
    }
  }, [addFilePaths, addToast]);

  const browseFolder = useCallback(async () => {
    try {
      const selected = await open({ directory: true, multiple: false, title: "Select a folder to send" });
      if (!selected || Array.isArray(selected)) return;
      addFilePaths([selected]);
    } catch (err) {
      console.error("[DropFlow] Folder dialog failed:", err);
      addToast("Couldn't open the folder picker.", "error");
    }
  }, [addFilePaths, addToast]);

  const removeFile = useCallback((uid: string) => {
    setFiles((prev) => prev.filter((file) => file.uid !== uid));
  }, []);

  const selectDevice = useCallback((id: string) => {
    setSelectedDeviceId(id);
    setLostDeviceName(null);
  }, []);

  const sendSelected = useCallback(async () => {
    if (files.length === 0) return;
    const targetDevice = devices.find((device) => device.id === selectedDeviceId);
    if (!targetDevice) {
      addToast("That device is no longer nearby.", "error");
      return;
    }
    const peerAddress = targetDevice.addresses[0]?.address || "127.0.0.1";
    const totalCount = files.length;
    const primaryName = files[0].name;
    const displayFileName = totalCount > 1 ? `${primaryName} (+${totalCount - 1} more)` : primaryName;
    const sessionId = `tx-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const paths = files.map((file) => file.path);
    setSessionStore((prev) => startSendSession(prev, sessionId, targetDevice.name, displayFileName, totalCount));
    setFiles([]);
    try {
      await invoke<string>("send_files", {
        sessionId,
        peerAddress,
        peerPort: targetDevice.port,
        localUuid,
        localDeviceName: settings.deviceName || "DropFlow Device",
        filePaths: paths,
      });
      setSessionStore((prev) =>
        applyCompletionEvent(prev, {
          sessionId,
          fileName: displayFileName,
          deviceName: targetDevice.name,
          size: "Complete",
          timestamp: "Just now",
          direction: "send",
          totalFiles: totalCount,
        }),
      );
    } catch (err) {
      console.error("[DropFlow] Send failed:", err);
      const message = friendlyTransferError(String(err));
      setSessionStore((prev) => applyFailureEvent(prev, { sessionId, error: String(err) }));
      addToast(message, "error");
    }
  }, [files, devices, selectedDeviceId, localUuid, settings.deviceName, addToast]);

  const cancelTransfer = useCallback((id: string) => {
    invoke("cancel_transfer", { sessionId: id }).catch(console.error);
    setSessionStore((prev) => applyFailureEvent(prev, { sessionId: id, error: "Transfer cancelled by user" }));
  }, []);

  const dismissTransfer = useCallback((id: string) => {
    setSessionStore((prev) => dismissActiveSession(prev, id));
    setSpeedHistory((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
  }, []);

  const visibleDiscovery: DropFlowContextValue["discovery"] =
    devices.length > 0 ? "ready" : discovery === "error" ? "error" : showEmpty ? "empty" : "searching";

  const value: DropFlowContextValue = {
    page,
    setPage,
    devices,
    selectedDeviceId,
    selectDevice,
    deviceType,
    discovery: visibleDiscovery,
    retryDiscovery,
    droppedOver,
    files,
    removeFile,
    browseFiles,
    browseFolder,
    queueFiles: addFilePaths,
    sendSelected,
    filesDeviceId,
    openDeviceFiles: (deviceId: string) => {
      setFilesDeviceId(deviceId);
      setPage("files");
    },
    focusDeviceFiles: setFilesDeviceId,
    sessionStore,
    speedHistory,
    cancelTransfer,
    dismissTransfer,
    incoming,
    closeIncoming: () => setIncoming(null),
    lostDeviceName,
  };

  return <DropFlowContext.Provider value={value}>{children}</DropFlowContext.Provider>;
};

export function useDropFlow(): DropFlowContextValue {
  const ctx = useContext(DropFlowContext);
  if (!ctx) throw new Error("useDropFlow must be used within DropFlowProvider");
  return ctx;
}

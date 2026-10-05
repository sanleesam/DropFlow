import React, { useCallback, useEffect, useRef, useState } from "react";
import DeviceCard, { Device } from "../components/DeviceCard";
import FileDropZone, { SelectedFilePayload } from "../components/FileDropZone";
import { TransferProgress } from "../components/TransferProgress";
import { useSettings } from "../components/SettingsProvider";
import { useToast } from "../components/ToastProvider";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { isPermissionGranted, requestPermission, sendNotification } from "@tauri-apps/plugin-notification";
import { IncomingTransferModal, IncomingTransferRequestData } from "../components/IncomingTransferModal";

import {
  createInitialSessionStore,
  startSendSession,
  applyProgressEvent,
  applyCompletionEvent,
  applyFailureEvent,
  dismissActiveSession,
  SessionStateStore,
  RecentTransfer,
  formatRelativeTimestamp,
} from "../utils/transferSessionManager";

// ─── Section wrapper ────────────────────────────────────────────────────────

interface SectionProps {
  id: string;
  title: string;
  icon?: React.ReactNode;
  children?: React.ReactNode;
}

const Section: React.FC<SectionProps> = ({ id, title, icon, children }) => (
  <section id={id} aria-labelledby={`${id}-heading`} className="df-section">
    {/* Section header */}
    <div className="df-section-heading">
      <div className="flex items-center gap-2">
        {icon && (
          <span className="text-neutral-400 flex items-center">
            {icon}
          </span>
        )}
        <h2
          id={`${id}-heading`}
          className="df-section-label"
        >
          {title}
        </h2>
      </div>
    </div>

    {/* Section content */}
    <div className="w-full">
      {children}
    </div>
  </section>
);

// ─── Icons ───────────────────────────────────────────────────────────────────

const IconRadar: React.FC = () => (
  <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="8" cy="8" r="2" />
    <path d="M8 1a7 7 0 100 14A7 7 0 008 1z" opacity="0.4" />
    <path d="M8 4a4 4 0 100 8A4 4 0 008 4z" opacity="0.6" />
    <line x1="8" y1="8" x2="12" y2="4" />
  </svg>
);

const IconUpload: React.FC = () => (
  <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M8 10V3M5 6l3-3 3 3" />
    <path d="M2 11v1a2 2 0 002 2h8a2 2 0 002-2v-1" />
  </svg>
);

const IconActivity: React.FC = () => (
  <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <polyline points="2 8 5 8 7 3 9 13 11 8 14 8" />
  </svg>
);

const IconClock: React.FC = () => (
  <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="8" cy="8" r="6.5" />
    <path d="M8 4.5V8l2.5 2.5" />
  </svg>
);

async function triggerDesktopNotification(title: string, body: string) {
  try {
    let granted = await isPermissionGranted();
    if (!granted) {
      const permission = await requestPermission();
      granted = permission === "granted";
    }
    if (granted) {
      sendNotification({ title, body });
    }
  } catch (err) {
    console.error("[Notification] Failed to send desktop notification:", err);
  }
}

// ─── Home page ────────────────────────────────────────────────────────────────

interface HomeProps {
  onNavigateHistory?: () => void;
  sessionStore?: SessionStateStore;
}

export const Home: React.FC<HomeProps> = () => {
  const { settings } = useSettings();
  const { addToast } = useToast();
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);

  // Central Session Store managing active and recent transfers
  const [sessionStore, setSessionStore] = useState<SessionStateStore>(createInitialSessionStore);

  const [devices, setDevices] = useState<Device[]>([]);
  const [localUuid, setLocalUuid] = useState<string>("");
  const [deviceType, setDeviceType] = useState<string>("desktop");
  const discoveryEventRevision = useRef(0);

  // Friendly troubleshooting hint appears only after the search has genuinely
  // come up empty for a while — never a permanent wall of technical text.
  const [showDiscoveryTroubleshooting, setShowDiscoveryTroubleshooting] = useState(false);
  const DISCOVERY_TROUBLESHOOT_DELAY_MS = 15000;

  useEffect(() => {
    if (devices.length > 0) {
      setShowDiscoveryTroubleshooting(false);
      return;
    }

    const timer = setTimeout(() => {
      setShowDiscoveryTroubleshooting(true);
    }, DISCOVERY_TROUBLESHOOT_DELAY_MS);

    return () => clearTimeout(timer);
  }, [devices.length, DISCOVERY_TROUBLESHOOT_DELAY_MS]);

  // Retrieve persistent local UUID on mount
  useEffect(() => {
    invoke<string>("get_local_uuid").then(setLocalUuid).catch(console.error);
    invoke<string>("get_system_device_type")
      .then(setDeviceType)
      .catch(() => setDeviceType("desktop"));
  }, []);

  const reconcileDevices = useCallback((nextDevices: Device[]) => {
    setDevices(nextDevices);
    setSelectedDeviceId((selectedId) =>
      selectedId && !nextDevices.some((device) => device.id === selectedId)
        ? null
        : selectedId,
    );
  }, []);

  // Hydrate persistent history from Rust backend on startup
  useEffect(() => {
    invoke<any>("get_app_state")
      .then((appState) => {
        if (appState && Array.isArray(appState.history) && appState.history.length > 0) {
          setSessionStore((prev) => {
            if (prev.recentTransfers.length > 0) return prev;
            return {
              ...prev,
              recentTransfers: appState.history,
            };
          });
        }
      })
      .catch(console.error);
  }, []);

  // Save history to Rust backend whenever recentTransfers changes
  useEffect(() => {
    if (sessionStore.recentTransfers.length > 0) {
      invoke("save_history", { history: sessionStore.recentTransfers }).catch(console.error);
    }
  }, [sessionStore.recentTransfers]);

  const [incomingRequest, setIncomingRequest] = useState<IncomingTransferRequestData | null>(null);

  // Long-lived central IPC event listeners registered once on mount
  useEffect(() => {
    let unlistenProgress: (() => void) | undefined;
    let unlistenCompleted: (() => void) | undefined;
    let unlistenFailed: (() => void) | undefined;
    let unlistenRequest: (() => void) | undefined;
    let unlistenDismiss: (() => void) | undefined;
    let unlistenHistory: (() => void) | undefined;

    const setupCentralListeners = async () => {
      try {
        unlistenRequest = await listen<IncomingTransferRequestData>("incoming-transfer-request", (event) => {
          setIncomingRequest(event.payload);
        });

        unlistenDismiss = await listen<string>("incoming-transfer-dismiss", (event) => {
          setIncomingRequest((prev) => (prev?.sessionId === event.payload ? null : prev));
        });

        unlistenProgress = await listen<any>("transfer-progress", (event) => {
          setSessionStore((prev) => applyProgressEvent(prev, event.payload));
        });

        unlistenCompleted = await listen<any>("transfer-completed", (event) => {
          const payload = event.payload;
          setSessionStore((prev) => {
            const isReceive =
              payload.direction === "receive" ||
              prev.activeTransfers[payload.sessionId]?.direction === "receive";

            const next = applyCompletionEvent(prev, payload);

            if (isReceive && !prev.completedSessionIds.has(payload.sessionId)) {
              addToast(
                `Received ${payload.fileName || "file"} from ${payload.deviceName || "Peer Device"}`,
                "success"
              );
              triggerDesktopNotification(
                "DropFlow — Transfer Complete",
                `${payload.fileName || "file"}\nReceived from ${payload.deviceName || "Peer Device"}`
              );

              if (settings.autoOpenCompleted) {
                const targetPath = payload.finalPath || payload.receiveDir || payload.filePath;
                if (targetPath) {
                  invoke("open_received_file", { path: targetPath }).catch(console.error);
                }
              }
            }
            return next;
          });
        });

        unlistenFailed = await listen<any>("transfer-failed", (event) => {
          const payload = event.payload;
          setSessionStore((prev) => applyFailureEvent(prev, payload));
          addToast(`Transfer failed: ${payload.error || "Unknown error"}`, "error");
        });

        // The backend is the source of truth for persisted history (e.g. after a
        // "Clear history" in Settings). Reconcile so cleared or trimmed entries
        // disappear here too instead of being resurrected by the next save.
        unlistenHistory = await listen<RecentTransfer[]>("history-updated", (event) => {
          if (!Array.isArray(event.payload)) return;
          setSessionStore((prev) => {
            const incoming = event.payload;
            const unchanged =
              incoming.length === prev.recentTransfers.length &&
              incoming.every((item, index) => item?.id === prev.recentTransfers[index]?.id);
            if (unchanged) return prev;
            return { ...prev, recentTransfers: incoming };
          });
        });
      } catch (err) {
        console.error("[Home] Failed to setup central event listeners:", err);
      }
    };

    setupCentralListeners();

    return () => {
      if (unlistenRequest) unlistenRequest();
      if (unlistenDismiss) unlistenDismiss();
      if (unlistenProgress) unlistenProgress();
      if (unlistenCompleted) unlistenCompleted();
      if (unlistenFailed) unlistenFailed();
      if (unlistenHistory) unlistenHistory();
    };
  }, [addToast, settings.autoOpenCompleted]);

  // Subscribe before browsing, then hydrate from the backend source of truth.
  useEffect(() => {
    let disposed = false;
    let unlistenDiscovered: (() => void) | undefined;
    let unlistenLost: (() => void) | undefined;

    const applyDiscoveredPeer = (newDevice: Device) => {
      discoveryEventRevision.current += 1;
      setDevices((previousDevices) => {
        const existingIndex = previousDevices.findIndex((device) => device.id === newDevice.id);
        if (existingIndex === -1) {
          return [...previousDevices, newDevice];
        }
        return previousDevices.map((device) =>
          device.id === newDevice.id ? newDevice : device,
        );
      });
    };

    const applyLostPeer = (lostId: string) => {
      discoveryEventRevision.current += 1;
      setDevices((previousDevices) => previousDevices.filter((device) => device.id !== lostId));
      setSelectedDeviceId((selectedId) => (selectedId === lostId ? null : selectedId));
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
      try {
        const discoveredUnlisten = await listen<Device>(
          "peer-discovered",
          (event) => {
            applyDiscoveredPeer(event.payload);
          },
        );
        unlistenDiscovered = discoveredUnlisten;

        if (disposed) {
          discoveredUnlisten();
          unlistenDiscovered = undefined;
          return;
        }

        const lostUnlisten = await listen<string>("peer-lost", (event) => {
          applyLostPeer(event.payload);
        });

        if (disposed) {
          discoveredUnlisten();
          lostUnlisten();
          unlistenDiscovered = undefined;
          return;
        }

        unlistenLost = lostUnlisten;
        await invoke("start_discovery");
        await hydratePeers();
      } catch (error) {
        unlistenDiscovered?.();
        unlistenLost?.();
        unlistenDiscovered = undefined;
        unlistenLost = undefined;
        throw error;
      }
    };

    setupDiscovery().catch((error) => {
      if (!disposed) {
        console.error("Failed to initialize discovery", error);
      }
    });

    return () => {
      disposed = true;
      unlistenDiscovered?.();
      unlistenLost?.();
    };
  }, [reconcileDevices]);

  // Sync settings visibility and deviceName with mDNS backend advertisement
  useEffect(() => {
    if (!localUuid) return;

    if (settings.deviceVisibility) {
      invoke("update_advertisement", {
        deviceId: localUuid,
        deviceName: settings.deviceName,
        deviceType,
        port: 1, // Non-zero value instructing Rust to advertise bound receiver port
      }).catch(console.error);
    } else {
      invoke("update_advertisement", {
        deviceId: localUuid,
        deviceName: "",
        deviceType: "",
        port: 0,
      }).catch(console.error);
    }
  }, [settings.deviceName, settings.deviceVisibility, localUuid, deviceType]);

  const handleSend = useCallback(
    async (selectedFiles: SelectedFilePayload[]) => {
      if (selectedFiles.length === 0) return;

      const targetDevice = devices.find((d) => d.id === selectedDeviceId);
      if (!targetDevice) {
        addToast("Selected device is no longer available.", "error");
        return;
      }

      const peerAddress = targetDevice.addresses[0]?.address || "127.0.0.1";
      const peerPort = targetDevice.port;

      const filePaths = selectedFiles.map((f) => f.path);
      const totalCount = selectedFiles.length;
      const primaryName = selectedFiles[0].name;

      const displayFileName = totalCount > 1
        ? `${primaryName} (+${totalCount - 1} other ${totalCount - 1 === 1 ? "file" : "files"})`
        : primaryName;

      // Single canonical session ID created by frontend and passed to Rust
      const sessionId = `tx-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;

      setSessionStore((prev) =>
        startSendSession(prev, sessionId, targetDevice.name, displayFileName, totalCount)
      );

      try {
        await invoke<string>("send_files", {
          sessionId,
          peerAddress,
          peerPort,
          localUuid,
          localDeviceName: settings.deviceName || "DropFlow Device",
          filePaths,
        });

        // Robust fallback completion upon invoke resolution
        setSessionStore((prev) =>
          applyCompletionEvent(prev, {
            sessionId,
            fileName: displayFileName,
            deviceName: targetDevice.name,
            size: "Complete",
            timestamp: "Just now",
            direction: "send",
            totalFiles: totalCount,
          })
        );
      } catch (err: any) {
        console.error("[Home] Send failed:", err);
        setSessionStore((prev) =>
          applyFailureEvent(prev, {
            sessionId,
            error: String(err),
          })
        );
        addToast(`Transfer error: ${err}`, "error");
      }
    },
    [devices, selectedDeviceId, localUuid, settings.deviceName, addToast],
  );

  const activeTransferList = Object.values(sessionStore.activeTransfers);

  return (
    <div className="flex flex-col gap-5 w-full transition-opacity duration-150 ease-[cubic-bezier(0.22,1,0.36,1)]">
      {/* ── Nearby Devices ── */}
      <Section
        id="nearby-devices"
        title="Nearby devices"
        icon={<IconRadar />}
      >
        <div
          role="radiogroup"
          aria-label="Nearby devices"
          className="w-full"
        >
          {devices.length > 0 ? (
            <div className="grid w-full grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
              {devices.map((device) => (
                <DeviceCard
                  key={device.id}
                  device={device}
                  selected={selectedDeviceId === device.id}
                  onSelect={setSelectedDeviceId}
                />
              ))}
            </div>
          ) : (
            <>
              <div
                className="flex items-center gap-2.5 py-3 select-none"
                aria-live="polite"
              >
                <span
                  className="h-1.5 w-1.5 flex-shrink-0 rounded-full bg-neutral-400 animate-pulse"
                  aria-hidden="true"
                />
                <p className="text-xs font-medium text-neutral-400">
                  {showDiscoveryTroubleshooting
                    ? "Still looking for devices…"
                    : "Looking for devices…"}
                </p>
              </div>

              {showDiscoveryTroubleshooting && (
                <div className="flex items-start gap-3 rounded-xl border border-white/[0.06] bg-neutral-900/40 px-4 py-3.5 select-none">
                  <span className="mt-0.5 flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-lg bg-neutral-800 text-neutral-400">
                    <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <circle cx="11" cy="11" r="7" />
                      <line x1="21" y1="21" x2="16.5" y2="16.5" />
                    </svg>
                  </span>
                  <div className="flex flex-col gap-0.5">
                    <span className="text-xs font-semibold text-neutral-300">No devices found yet</span>
                    <span className="text-[11px] leading-relaxed text-neutral-500">
                      Make sure DropFlow is open on your other device and that both
                      devices are connected to the same Wi-Fi network.
                    </span>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </Section>

      {/* ── Send Files ── */}
      <Section
        id="send-files"
        title="Send files"
        icon={<IconUpload />}
      >
        <FileDropZone selectedDeviceId={selectedDeviceId} onSend={handleSend} />
      </Section>

      {/* ── Active Transfer Progress Cards ── */}
      {activeTransferList.length > 0 && (
        <Section
          id="active-transfers"
          title="Active transfers"
          icon={<IconActivity />}
        >
          <div className="w-full flex flex-col gap-3">
            {activeTransferList.map((session) => (
              <TransferProgress
                key={session.id}
                session={session}
                onDismiss={(id) => setSessionStore((prev) => dismissActiveSession(prev, id))}
                onCancel={(id) => {
                  invoke("cancel_transfer", { sessionId: id }).catch(console.error);
                  setSessionStore((prev) =>
                    applyFailureEvent(prev, { sessionId: id, error: "Transfer cancelled by user" })
                  );
                }}
              />
            ))}
          </div>
        </Section>
      )}

      {/* ── Recent Transfer Summary ── */}
      <Section
        id="recent-transfers"
        title="Recent transfer"
        icon={<IconClock />}
      >
        {sessionStore.recentTransfers.length > 0 ? (
          (() => {
            const latest = sessionStore.recentTransfers[0];
            const isReceive = latest.direction === "receive";
            const isFailed = latest.status === "failed";
            const displayTime = latest.timestampMs
              ? formatRelativeTimestamp(latest.timestampMs)
              : latest.timestamp;

            return (
              <div className="w-full flex items-center justify-between gap-3 p-3.5 rounded-xl border border-white/[0.07] bg-neutral-900/40 select-none">
                <div className="flex items-center gap-3 min-w-0">
                  <span
                    className={`flex-shrink-0 flex items-center justify-center w-8 h-8 rounded-lg border ${
                      isFailed
                        ? "bg-red-500/10 border-red-500/20 text-red-400"
                        : isReceive
                        ? "bg-blue-500/10 border-blue-500/20 text-blue-400"
                        : "bg-emerald-500/10 border-emerald-500/20 text-emerald-400"
                    }`}
                  >
                    <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                      <polyline points="14 2 14 8 20 8" />
                    </svg>
                  </span>

                  <div className="flex flex-col min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-neutral-100 truncate">
                        {latest.totalFiles > 1 ? `${latest.totalFiles} files` : latest.fileName}
                      </span>
                      <span className="text-[10px] font-mono text-neutral-400">{latest.size}</span>
                    </div>
                    <span className="text-[11px] text-neutral-400 mt-0.5 flex items-center gap-1.5 truncate">
                      <span>{isReceive ? "↓ Received from" : "↑ Sent to"}</span>
                      <span className="text-neutral-200 font-medium">{latest.deviceName}</span>
                      <span>•</span>
                      <span>{displayTime}</span>
                    </span>
                  </div>
                </div>
              </div>
            );
          })()
        ) : (
          <div className="flex items-center gap-3 p-3.5 rounded-xl border border-white/[0.07] bg-neutral-900/30 select-none">
            <span className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg border border-white/[0.06] bg-neutral-800/60 text-neutral-500">
              <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
            </span>
            <div className="flex flex-col">
              <span className="text-xs font-medium text-neutral-300">No recent transfers</span>
              <span className="text-[11px] text-neutral-500 mt-0.5">Files you send or receive will appear here</span>
            </div>
          </div>
        )}
      </Section>

      {/* ── Incoming Transfer Authorization Dialog ── */}
      {incomingRequest && (
        <IncomingTransferModal
          request={incomingRequest}
          onClose={() => setIncomingRequest(null)}
        />
      )}
    </div>
  );
};

export default Home;

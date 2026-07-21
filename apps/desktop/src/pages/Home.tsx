import React, { useCallback, useEffect, useRef, useState } from "react";
import Header from "../components/Header";
import DeviceCard, { Device } from "../components/DeviceCard";
import FileDropZone from "../components/FileDropZone";
import { TransferProgress } from "../components/TransferProgress";
import { SettingsModal } from "../components/SettingsModal";
import { useSettings } from "../components/SettingsProvider";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

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

const IconClock: React.FC = () => (
  <svg viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="8" cy="8" r="6.5" />
    <path d="M8 4.5V8l2.5 2.5" />
  </svg>
);

// ─── Home page ────────────────────────────────────────────────────────────────

interface RecentTransfer {
  id: string;
  fileName: string;
  deviceName: string;
  size: string;
  timestamp: string;
  status: "completed" | "failed";
}

const Home: React.FC = () => {
  const { settings } = useSettings();
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [activeTransfer, setActiveTransfer] = useState<{
    deviceName: string;
    fileName: string;
  } | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const wasSettingsOpenRef = useRef(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [localUuid, setLocalUuid] = useState<string>("");
  const discoveryEventRevision = useRef(0);

  // Production state starts clean with zero mock transfers
  const [recentTransfers, setRecentTransfers] = useState<RecentTransfer[]>([]);

  // Restore focus to Settings button after modal is closed
  useEffect(() => {
    if (isSettingsOpen) {
      wasSettingsOpenRef.current = true;
    } else if (wasSettingsOpenRef.current) {
      document.getElementById("settings-btn")?.focus();
      wasSettingsOpenRef.current = false;
    }
  }, [isSettingsOpen]);

  // Retrieve persistent local UUID on mount
  useEffect(() => {
    invoke<string>("get_local_uuid").then(setLocalUuid).catch(console.error);
  }, []);

  const reconcileDevices = useCallback((nextDevices: Device[]) => {
    setDevices(nextDevices);
    setSelectedDeviceId((selectedId) =>
      selectedId && !nextDevices.some((device) => device.id === selectedId)
        ? null
        : selectedId,
    );
  }, []);

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
        deviceType: "laptop",
        port: 42382,
      }).catch(console.error);
    } else {
      invoke("update_advertisement", {
        deviceId: localUuid,
        deviceName: "",
        deviceType: "",
        port: 0,
      }).catch(console.error);
    }
  }, [settings.deviceName, settings.deviceVisibility, localUuid]);

  const handleSend = (fileName: string) => {
    const device = devices.find((d) => d.id === selectedDeviceId);
    const deviceName = device ? device.name : "Unknown Device";
    setActiveTransfer({
      deviceName,
      fileName,
    });
  };

  const handleTransferComplete = () => {
    if (activeTransfer) {
      const newTransfer: RecentTransfer = {
        id: `tx-${Date.now()}`,
        fileName: activeTransfer.fileName,
        deviceName: activeTransfer.deviceName,
        size: "Complete",
        timestamp: "Just now",
        status: "completed",
      };
      setRecentTransfers((prev) => [newTransfer, ...prev]);
    }
  };

  return (
    <div className="df-app-shell flex h-screen w-screen flex-col overflow-hidden text-neutral-100">
      <Header onSettingsClick={() => setIsSettingsOpen(true)} />

      <main
        id="main-content"
        className="df-main flex-1 overflow-y-auto"
        style={{
          scrollbarWidth: "none",
          msOverflowStyle: "none",
        }}
      >
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
              <div className="py-2.5 text-left select-none">
                <p className="text-xs font-medium text-neutral-400">Looking for devices…</p>
                <p className="mt-0.5 text-[11px] text-neutral-500">
                  Make sure DropFlow is open on your other device.
                </p>
              </div>
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

        {/* ── Active Transfer Progress ── */}
        {activeTransfer && (
          <TransferProgress
            deviceName={activeTransfer.deviceName}
            fileName={activeTransfer.fileName}
            onClose={() => setActiveTransfer(null)}
            onComplete={handleTransferComplete}
          />
        )}

        {/* ── Recent Transfers ── */}
        <Section
          id="recent-transfers"
          title="Recent transfers"
          icon={<IconClock />}
        >
          {recentTransfers.length > 0 ? (
            <div className="w-full flex flex-col divide-y divide-white/[0.06] rounded-xl border border-white/[0.07] bg-neutral-900/40 overflow-hidden">
              {recentTransfers.map((tx) => (
                <div key={tx.id} className="flex items-center justify-between px-3 py-2 hover:bg-white/[0.02] transition-colors duration-150">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className={`flex-shrink-0 flex items-center justify-center w-6 h-6 rounded-md bg-neutral-800 border border-white/[0.06] ${
                      tx.status === "completed" ? "text-emerald-400" : "text-red-400"
                    }`}>
                      <svg viewBox="0 0 24 24" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                      </svg>
                    </span>
                    <div className="flex flex-col min-w-0">
                      <span className="text-xs font-medium text-neutral-200 truncate leading-tight">{tx.fileName}</span>
                      <span className="text-[11px] text-neutral-500 mt-0.5 leading-tight">
                        {tx.status === "completed" ? "Sent to" : "Failed sending to"} <span className="text-neutral-300 font-medium">{tx.deviceName}</span>
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center gap-3 flex-shrink-0 text-right select-none">
                    <div className="flex flex-col items-end">
                      <span className="text-xs text-neutral-400 font-mono">{tx.size}</span>
                      <span className="text-[10px] text-neutral-500 mt-0.5">{tx.timestamp}</span>
                    </div>
                    <span className={`px-2 py-0.5 rounded text-[10px] font-medium border ${
                      tx.status === "completed" 
                        ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20" 
                        : "bg-red-500/10 text-red-400 border-red-500/20"
                    }`}>
                      {tx.status === "completed" ? "Completed" : "Failed"}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="py-1 select-none">
              <p className="text-xs text-neutral-500">No transfers yet</p>
            </div>
          )}
        </Section>
      </main>

      {/* ── Settings Modal ── */}
      <SettingsModal isOpen={isSettingsOpen} onClose={() => setIsSettingsOpen(false)} />
    </div>
  );
};

export default Home;

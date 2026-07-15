import React, { useEffect, useRef, useState } from "react";
import Header from "../components/Header";
import DeviceCard, { Device } from "../components/DeviceCard";
import FileDropZone from "../components/FileDropZone";
import { TransferProgress } from "../components/TransferProgress";
import { SettingsModal } from "../components/SettingsModal";
import { useSettings, ACCENT_COLOR_MAPS } from "../components/SettingsProvider";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

// ─── Section wrapper ────────────────────────────────────────────────────────

interface SectionProps {
  id: string;
  title: string;
  icon: React.ReactNode;
  children?: React.ReactNode;
}

const Section: React.FC<SectionProps> = ({ id, title, icon, children }) => (
  <section id={id} aria-labelledby={`${id}-heading`} className="df-section">
    {/* Section header */}
    <div className="df-section-heading">
      <div className="df-section-title">
      <span className="df-section-icon">
        {icon}
      </span>
      <h2
        id={`${id}-heading`}
        className="df-section-label"
      >
        {title}
      </h2>
      </div>
      <span className="df-section-hint">{id === "nearby-devices" ? "On your local network" : id === "send-files" ? "Fast, private, local" : "Your latest activity"}</span>
    </div>

    {/* Card container */}
    <div
      className="df-panel"
    >
      {/* Subtle gradient sheen */}
      <div className="pointer-events-none absolute inset-0 bg-gradient-to-br from-white/[0.035] to-transparent" />
      {/* Placeholder content */}
      {children ?? (
        <p className="text-sm text-slate-500 select-none tracking-wide">
          Nothing here yet
        </p>
      )}
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

// Discovered devices are loaded dynamically via Tauri and mDNS

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
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [activeTransfer, setActiveTransfer] = useState<{
    deviceName: string;
    fileName: string;
  } | null>(null);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const wasSettingsOpenRef = useRef(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [localUuid, setLocalUuid] = useState<string>("");
  const [recentTransfers, setRecentTransfers] = useState<RecentTransfer[]>([
    {
      id: "tx-1",
      fileName: "IMG_4920.jpg",
      deviceName: "Pixel 8 Pro",
      size: "4.2 MB",
      timestamp: "10 min ago",
      status: "completed",
    },
    {
      id: "tx-2",
      fileName: "presentation_draft.pdf",
      deviceName: "Windows Desktop",
      size: "18.7 MB",
      timestamp: "1 hour ago",
      status: "completed",
    },
  ]);

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

  // Start mDNS discovery and setup listeners on mount
  useEffect(() => {
    invoke("start_discovery").catch(console.error);

    let unlistenDiscovered: () => void;
    let unlistenLost: () => void;

    const setupListeners = async () => {
      unlistenDiscovered = await listen<Device>("peer-discovered", (event) => {
        const newDevice = event.payload;
        setDevices((prev) => {
          if (prev.some((d) => d.id === newDevice.id)) {
            return prev.map((d) => (d.id === newDevice.id ? newDevice : d));
          }
          return [...prev, newDevice];
        });
      });

      unlistenLost = await listen<string>("peer-lost", (event) => {
        const lostId = event.payload;
        setDevices((prev) => prev.filter((d) => d.id !== lostId));
      });
    };

    setupListeners();

    return () => {
      if (unlistenDiscovered) unlistenDiscovered();
      if (unlistenLost) unlistenLost();
    };
  }, []);

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
        size: "125 MB",
        timestamp: "Just now",
        status: "completed",
      };
      setRecentTransfers((prev) => [newTransfer, ...prev]);
    }
  };

  return (
    <div className="df-app-shell flex h-screen w-screen flex-col overflow-hidden text-white">
      <Header onSettingsClick={() => setIsSettingsOpen(true)} />

      <main
        id="main-content"
        className="df-main flex-1 overflow-y-auto"
        style={{
          scrollbarWidth: "none",
          msOverflowStyle: "none",
        }}
      >
        <div className="mb-8 flex items-end justify-between gap-4" aria-label="Welcome">
          <div>
            <p className={`mb-2 text-xs font-semibold uppercase tracking-[.16em] ${accent.progressIconText}`}>Local workspace</p>
            <h1 className="m-0 text-2xl font-semibold tracking-tight text-slate-100 sm:text-3xl">Send something anywhere.</h1>
            <p className="mt-2 max-w-xl text-sm leading-6 text-slate-400">Choose a nearby device, drop in your files, and let DropFlow handle the rest.</p>
          </div>
          <div className="hidden rounded-full border border-emerald-400/15 bg-emerald-400/8 px-3 py-1.5 text-xs font-medium text-emerald-300 sm:flex sm:items-center sm:gap-2">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 shadow-[0_0_10px_rgba(52,211,153,.75)]" />
            Ready to transfer
          </div>
        </div>

        {/* ── Nearby Devices ── */}
        <Section
          id="nearby-devices"
          title="Nearby Devices"
          icon={<IconRadar />}
        >
          <div
            role="radiogroup"
            aria-label="Nearby devices"
            className="w-full"
          >
            {devices.length > 0 ? (
              <div className="grid w-full grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-3 animate-[toast-slide-in_0.3s_ease-out]">
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
              <div className="flex w-full flex-col items-center px-6 py-10 text-center">
                <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-[14px] border border-white/[.08] bg-white/[.04] text-slate-500">
                  <IconRadar />
                </div>
                <p className="text-sm font-medium text-slate-300">Looking for nearby devices</p>
                <p className="mt-1 max-w-xs text-xs leading-5 text-slate-500">Keep DropFlow open on another device connected to this network.</p>
                <span className={`mt-4 flex items-center gap-2 text-[11px] font-medium ${accent.progressIconText}`}><span className={`h-1.5 w-1.5 animate-pulse rounded-full ${accent.progressBgDot}`} />Scanning local network</span>
              </div>
            )}
          </div>
        </Section>

        {/* ── Send Files ── */}
        <Section
          id="send-files"
          title="Send Files"
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
          title="Recent Transfers"
          icon={<IconClock />}
        >
          {recentTransfers.length > 0 ? (
            <div className="w-full flex flex-col divide-y divide-white/[.04] p-1.5 animate-[toast-slide-in_0.3s_ease-out]">
              {recentTransfers.map((tx) => (
                <div key={tx.id} className="flex items-center justify-between px-4 py-3 hover:bg-white/[0.02] rounded-xl transition-colors duration-150">
                  <div className="flex items-center gap-3.5 min-w-0">
                    {/* File Icon */}
                    <span className={`flex-shrink-0 flex items-center justify-center w-9 h-9 rounded-xl bg-white/[0.03] border border-white/[0.05] ${
                      tx.status === "completed" ? "text-emerald-400" : "text-red-400"
                    }`}>
                      <svg viewBox="0 0 24 24" className="w-4.5 h-4.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                      </svg>
                    </span>
                    {/* Name + Target */}
                    <div className="flex flex-col min-w-0">
                      <span className="text-[14px] font-semibold text-slate-200 truncate leading-normal">{tx.fileName}</span>
                      <span className="text-[11px] text-slate-500 mt-0.5 leading-normal">
                        {tx.status === "completed" ? "Sent to" : "Failed sending to"} <span className="text-slate-400 font-semibold">{tx.deviceName}</span>
                      </span>
                    </div>
                  </div>

                  {/* Stats & Status */}
                  <div className="flex items-center gap-5 flex-shrink-0 text-right select-none">
                    <div className="flex flex-col items-end">
                      <span className="text-[12px] text-slate-400 font-mono font-medium">{tx.size}</span>
                      <span className="text-[10px] text-slate-500 mt-0.5">{tx.timestamp}</span>
                    </div>
                    <span className={`px-2.5 py-0.5 rounded-full text-[10px] font-semibold border ${
                      tx.status === "completed" 
                        ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/15" 
                        : "bg-red-500/10 text-red-400 border-red-500/15"
                    }`}>
                      {tx.status === "completed" ? "Completed" : "Failed"}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="flex w-full flex-col items-center py-8 text-center">
              <p className="text-sm text-slate-500">No transfers recorded</p>
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

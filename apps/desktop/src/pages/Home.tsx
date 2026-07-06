import React, { useEffect, useRef, useState } from "react";
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
  icon: React.ReactNode;
  accent: string;
  children?: React.ReactNode;
}

const Section: React.FC<SectionProps> = ({ id, title, icon, accent, children }) => (
  <section id={id} aria-labelledby={`${id}-heading`} className="flex flex-col gap-4">
    {/* Section header */}
    <div className="flex items-center gap-2.5">
      <span className={`flex items-center justify-center w-7 h-7 rounded-lg ${accent} text-white shadow-sm`}>
        {icon}
      </span>
      <h2
        id={`${id}-heading`}
        className="text-sm font-semibold uppercase tracking-widest text-slate-400 select-none"
      >
        {title}
      </h2>
    </div>

    {/* Card container */}
    <div
      className="
        relative rounded-2xl border border-white/6 overflow-hidden
        bg-slate-900/50 backdrop-blur-md
        shadow-[0_8px_32px_rgba(0,0,0,0.4)]
        min-h-[148px]
        flex items-center justify-center
        transition-all duration-300
      "
    >
      {/* Subtle gradient sheen */}
      <div className="absolute inset-0 bg-gradient-to-br from-white/[0.03] to-transparent pointer-events-none" />
      {/* Placeholder content */}
      {children ?? (
        <p className="text-sm text-slate-600 select-none tracking-wide">
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

  return (
    <div className="flex flex-col h-screen w-screen bg-slate-950 text-white overflow-hidden">
      <Header onSettingsClick={() => setIsSettingsOpen(true)} />

      <main
        id="main-content"
        className="flex-1 overflow-y-auto px-6 py-8 space-y-8"
        style={{
          scrollbarWidth: "none",
          msOverflowStyle: "none",
        }}
      >
        {/* Ambient background blobs */}
        <div className="pointer-events-none fixed inset-0 overflow-hidden" aria-hidden="true">
          <div className="absolute -top-32 -left-32 w-96 h-96 rounded-full bg-blue-600/10 blur-3xl" />
          <div className="absolute top-1/2 -right-24 w-72 h-72 rounded-full bg-purple-600/10 blur-3xl" />
          <div className="absolute -bottom-24 left-1/3 w-64 h-64 rounded-full bg-indigo-600/8 blur-3xl" />
        </div>

        {/* ── Nearby Devices ── */}
        <Section
          id="nearby-devices"
          title="Nearby Devices"
          icon={<IconRadar />}
          accent="bg-gradient-to-br from-blue-500 to-cyan-500"
        >
          <div
            role="radiogroup"
            aria-label="Nearby devices"
            className="w-full grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 p-4"
          >
            {devices.length > 0 ? (
              devices.map((device) => (
                <DeviceCard
                  key={device.id}
                  device={device}
                  selected={selectedDeviceId === device.id}
                  onSelect={setSelectedDeviceId}
                />
              ))
            ) : (
              <div className="col-span-full py-8 text-center select-none">
                <p className="text-sm text-slate-500 tracking-wide">
                  No nearby DropFlow devices found.
                </p>
              </div>
            )}
          </div>
        </Section>

        {/* ── Send Files ── */}
        <Section
          id="send-files"
          title="Send Files"
          icon={<IconUpload />}
          accent="bg-gradient-to-br from-indigo-500 to-purple-500"
        >
          <FileDropZone selectedDeviceId={selectedDeviceId} onSend={handleSend} />
        </Section>

        {/* ── Active Transfer Progress ── */}
        {activeTransfer && (
          <TransferProgress
            deviceName={activeTransfer.deviceName}
            fileName={activeTransfer.fileName}
            onClose={() => setActiveTransfer(null)}
          />
        )}

        {/* ── Recent Transfers ── */}
        <Section
          id="recent-transfers"
          title="Recent Transfers"
          icon={<IconClock />}
          accent="bg-gradient-to-br from-purple-500 to-pink-500"
        />
      </main>

      {/* ── Settings Modal ── */}
      <SettingsModal isOpen={isSettingsOpen} onClose={() => setIsSettingsOpen(false)} />
    </div>
  );
};

export default Home;

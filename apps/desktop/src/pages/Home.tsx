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
            <p className="mb-2 text-xs font-semibold uppercase tracking-[.16em] text-blue-400">Local workspace</p>
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
        />
      </main>

      {/* ── Settings Modal ── */}
      <SettingsModal isOpen={isSettingsOpen} onClose={() => setIsSettingsOpen(false)} />
    </div>
  );
};

export default Home;

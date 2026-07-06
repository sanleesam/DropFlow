import React, { useState } from "react";
import Header from "../components/Header";
import DeviceCard, { Device } from "../components/DeviceCard";
import FileDropZone from "../components/FileDropZone";

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

// ─── Mock devices ────────────────────────────────────────────────────────────

const MOCK_DEVICES: Device[] = [
  {
    id: "macbook-pro",
    name: "Sanlee's MacBook Pro",
    type: "laptop",
    status: "online",
    lastSeen: "Now",
  },
  {
    id: "gaming-pc",
    name: "Gaming PC",
    type: "desktop",
    status: "recently-seen",
    lastSeen: "Last seen 2 min ago",
  },
  {
    id: "pixel-9-pro",
    name: "Pixel 9 Pro",
    type: "phone",
    status: "offline",
    lastSeen: "Offline",
  },
];

// ─── Home page ────────────────────────────────────────────────────────────────

const Home: React.FC = () => {
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  return (
    <div className="flex flex-col h-screen w-screen bg-slate-950 text-white overflow-hidden">
      <Header />

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
            {MOCK_DEVICES.map((device) => (
              <DeviceCard
                key={device.id}
                device={device}
                selected={selectedDeviceId === device.id}
                onSelect={setSelectedDeviceId}
              />
            ))}
          </div>
        </Section>

        {/* ── Send Files ── */}
        <Section
          id="send-files"
          title="Send Files"
          icon={<IconUpload />}
          accent="bg-gradient-to-br from-indigo-500 to-purple-500"
        >
          <FileDropZone selectedDeviceId={selectedDeviceId} />
        </Section>

        {/* ── Recent Transfers ── */}
        <Section
          id="recent-transfers"
          title="Recent Transfers"
          icon={<IconClock />}
          accent="bg-gradient-to-br from-purple-500 to-pink-500"
        />
      </main>
    </div>
  );
};

export default Home;

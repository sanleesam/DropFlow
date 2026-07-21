import React from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

// ─── Types ────────────────────────────────────────────────────────────────────

export type DeviceType = "laptop" | "desktop" | "phone";
export type DeviceStatus = "online" | "recently-seen" | "offline";

export interface DeviceAddress {
  address: string;
  family: "ipv4" | "ipv6";
  interface?: string | null;
}

export interface Device {
  id: string;
  name: string;
  type: DeviceType | string;
  status: DeviceStatus | string;
  lastSeen: string;
  addresses: DeviceAddress[];
  port: number;
  version: string;
}

interface DeviceCardProps {
  device: Device;
  selected: boolean;
  onSelect: (id: string) => void;
}

// ─── Device icons ─────────────────────────────────────────────────────────────

const IconLaptop: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="2" y="4" width="20" height="13" rx="2" />
    <path d="M1 20h22" />
    <path d="M9 17h6" strokeWidth="2" />
  </svg>
);

const IconDesktop: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="2" y="3" width="20" height="14" rx="2" />
    <line x1="8" y1="21" x2="16" y2="21" />
    <line x1="12" y1="17" x2="12" y2="21" />
  </svg>
);

const IconPhone: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="5" y="2" width="14" height="20" rx="3" />
    <circle cx="12" cy="17.5" r="1" fill="currentColor" stroke="none" />
  </svg>
);

const IconDevice: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="14" rx="2" />
    <path d="M8 21h8M12 18v3" />
  </svg>
);

const DEVICE_ICONS: Record<DeviceType, React.FC> = {
  laptop: IconLaptop,
  desktop: IconDesktop,
  phone: IconPhone,
};

const DEVICE_TYPE_LABELS: Record<DeviceType, string> = {
  laptop: "Laptop",
  desktop: "Desktop",
  phone: "Mobile",
};

function isKnownDeviceType(type: string): type is DeviceType {
  return type in DEVICE_ICONS;
}

// ─── Checkmark badge ──────────────────────────────────────────────────────────

const CheckBadge: React.FC<{ bgClass: string }> = ({ bgClass }) => (
  <span
    className={`absolute top-2.5 right-2.5 w-5 h-5 rounded-full ${bgClass} flex items-center justify-center shadow-sm animate-[badge-pop_0.15s_ease-out]`}
    aria-hidden="true"
  >
    <svg viewBox="0 0 12 12" className="w-3 h-3" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 6l3 3 5-5" />
    </svg>
  </span>
);

// ─── Status config ────────────────────────────────────────────────────────────

const STATUS_CONFIG: Record<
  DeviceStatus,
  { dot: string; text: string; label: string }
> = {
  online: {
    dot: "bg-emerald-400",
    text: "text-emerald-400",
    label: "Online",
  },
  "recently-seen": {
    dot: "bg-amber-400",
    text: "text-amber-400",
    label: "Last seen recently",
  },
  offline: {
    dot: "bg-neutral-500",
    text: "text-neutral-500",
    label: "Offline",
  },
};

// ─── DeviceCard ───────────────────────────────────────────────────────────────

const DeviceCard: React.FC<DeviceCardProps> = ({ device, selected, onSelect }) => {
  const deviceType = isKnownDeviceType(device.type) ? device.type : undefined;
  const Icon = deviceType ? DEVICE_ICONS[deviceType] : IconDevice;
  const typeLabel = deviceType ? DEVICE_TYPE_LABELS[deviceType] : "Device";
  const status = STATUS_CONFIG[device.status as DeviceStatus] ?? STATUS_CONFIG.offline;
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  return (
    <button
      id={`device-card-${device.id}`}
      role="radio"
      aria-checked={selected}
      aria-label={`${device.name}, ${typeLabel}, ${status.label}`}
      onClick={() => onSelect(device.id)}
      className={[
        "group relative flex min-h-[160px] w-full cursor-pointer select-none flex-col gap-3.5 rounded-xl border p-4 text-left outline-none",
        "transition-all duration-150 ease-out",
        selected
          ? accent.deviceSelected
          : "border-white/[0.08] bg-neutral-900/60 hover:border-neutral-700 hover:bg-neutral-800/60",
        selected
          ? accent.deviceFocus
          : "focus-visible:ring-2 focus-visible:ring-blue-500/50 focus-visible:ring-offset-2 focus-visible:ring-offset-[#0b0c0e]",
      ].join(" ")}
    >
      {/* Checkmark badge */}
      {selected && <CheckBadge bgClass={accent.deviceBadgeBg} />}

      {/* Icon */}
      <span
        className={[
          "flex h-10 w-10 items-center justify-center rounded-lg",
          selected
            ? accent.deviceIconBg
            : "bg-neutral-800 text-neutral-300 group-hover:bg-neutral-700/70",
          "transition-colors duration-150",
        ].join(" ")}
      >
        <Icon />
      </span>

      {/* Name + type */}
      <div className="flex flex-col gap-0.5">
        <span
          className={[
            "truncate text-sm font-semibold leading-snug",
            selected ? "text-white" : "text-neutral-200",
          ].join(" ")}
        >
          {device.name}
        </span>
        <span className="text-xs text-neutral-500">
          {typeLabel}
        </span>
      </div>

      {/* Status row */}
      <div className="mt-auto flex items-center gap-2">
        <span
          className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${status.dot}`}
          aria-hidden="true"
        />
        <span className={`text-xs font-medium ${status.text}`}>
          {status.label}
        </span>
      </div>
    </button>
  );
};

export default DeviceCard;

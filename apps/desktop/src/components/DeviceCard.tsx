import React from "react";
import { useSettings, ACCENT_COLOR_MAPS } from "./SettingsProvider";

// ─── Types ────────────────────────────────────────────────────────────────────

export type DeviceType = "laptop" | "desktop" | "phone";
export type DeviceStatus = "online" | "recently-seen" | "offline";

export interface Device {
  id: string;
  name: string;
  type: DeviceType;
  status: DeviceStatus;
  lastSeen: string;
}

interface DeviceCardProps {
  device: Device;
  selected: boolean;
  onSelect: (id: string) => void;
}

// ─── Device icons ─────────────────────────────────────────────────────────────

const IconLaptop: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="2" y="4" width="20" height="13" rx="2" />
    <path d="M1 20h22" />
    <path d="M9 17h6" strokeWidth="2" />
  </svg>
);

const IconDesktop: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="2" y="3" width="20" height="14" rx="2" />
    <line x1="8" y1="21" x2="16" y2="21" />
    <line x1="12" y1="17" x2="12" y2="21" />
  </svg>
);

const IconPhone: React.FC = () => (
  <svg viewBox="0 0 24 24" className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="5" y="2" width="14" height="20" rx="3" />
    <circle cx="12" cy="17.5" r="1" fill="currentColor" stroke="none" />
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

// ─── Checkmark badge ──────────────────────────────────────────────────────────

const CheckBadge: React.FC<{ bgClass: string }> = ({ bgClass }) => (
  <span
    className={`absolute top-2.5 right-2.5 w-5 h-5 rounded-full ${bgClass} flex items-center justify-center shadow-lg animate-[badge-pop_0.2s_ease-out]`}
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
  { dot: string; glow: string; text: string; label: string }
> = {
  online: {
    dot: "bg-[#57F287]",
    glow: "shadow-[0_0_6px_rgba(87,242,135,0.7)]",
    text: "text-[#57F287]",
    label: "Online",
  },
  "recently-seen": {
    dot: "bg-[#F5C363]",
    glow: "shadow-[0_0_6px_rgba(245,195,99,0.65)]",
    text: "text-[#F5C363]",
    label: "Last seen 2 min ago",
  },
  offline: {
    dot: "bg-slate-600",
    glow: "",
    text: "text-slate-500",
    label: "Offline",
  },
};

// ─── DeviceCard ───────────────────────────────────────────────────────────────

const DeviceCard: React.FC<DeviceCardProps> = ({ device, selected, onSelect }) => {
  const Icon = DEVICE_ICONS[device.type];
  const status = STATUS_CONFIG[device.status];
  const { settings } = useSettings();
  const accent = ACCENT_COLOR_MAPS[settings.accentColor];

  return (
    <button
      id={`device-card-${device.id}`}
      role="radio"
      aria-checked={selected}
      aria-label={`${device.name}, ${DEVICE_TYPE_LABELS[device.type]}, ${status.label}`}
      onClick={() => onSelect(device.id)}
      className={[
        "group relative flex min-h-[178px] w-full cursor-pointer select-none flex-col gap-4 rounded-[16px] border p-4 text-left outline-none",
        "transition-all duration-200 ease-out hover:-translate-y-0.5 hover:shadow-[0_14px_30px_rgba(0,0,0,.24)]",
        selected ? accent.deviceSelected : "border-white/[.09] bg-[#151c29] hover:border-white/[.18] hover:bg-[#192231]",
        selected ? "scale-[1.01]" : "scale-100",
        selected ? accent.deviceFocus : "focus-visible:ring-2 focus-visible:ring-blue-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-[#080b12]",
      ].join(" ")}
    >
      {/* Checkmark badge */}
      {selected && <CheckBadge bgClass={accent.deviceBadgeBg} />}

      {/* Icon */}
      <span
        className={[
          "flex h-11 w-11 items-center justify-center rounded-[13px]",
          selected
            ? accent.deviceIconBg
            : "bg-white/[.06] text-slate-300 group-hover:bg-white/[.1]",
          "transition-colors duration-200",
        ].join(" ")}
      >
        <Icon />
      </span>

      {/* Name + type */}
      <div className="flex flex-col gap-0.5">
        <span
          className={[
            "truncate text-[15px] font-semibold leading-tight",
            selected ? "text-white" : "text-slate-200",
          ].join(" ")}
        >
          {device.name}
        </span>
        <span className="text-xs leading-tight text-slate-500">
          {DEVICE_TYPE_LABELS[device.type]}
        </span>
      </div>

      {/* Status row */}
      <div className="mt-auto flex items-center gap-2">
        {/* Status dot */}
        <span
          className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${status.dot} ${status.glow}`}
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

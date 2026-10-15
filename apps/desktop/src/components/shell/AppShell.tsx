import React, { useLayoutEffect, useRef } from "react";
import { useSettings } from "../SettingsProvider";
import { mountPageMotion } from "../../ui/motion";
import {
  ArrowLeftRight,
  Folder,
  Home,
  Settings,
  Smartphone,
} from "lucide-react";
import { IncomingTransferModal } from "../IncomingTransferModal";
import { useDropFlow } from "../../session/DropFlowProvider";
import { AppPage } from "../../types/device";
import { HomePage } from "../../pages/HomePage";
import { DevicesPage } from "../../pages/DevicesPage";
import { TransfersPage } from "../../pages/TransfersPage";
import { FilesPage } from "../../pages/FilesPage";
import { SettingsPage } from "../../pages/SettingsPage";
import { useDiskUsage } from "../../ui/useDisk";
import { formatBytes } from "../../utils/formatters";
import { BrandMark } from "../ui/BrandMark";

const NAV: {
  id: AppPage;
  label: string;
  icon: (active: boolean) => React.ReactNode;
}[] = [
  {
    id: "home",
    label: "Home",
    icon: (active) => (
      <Home
        size={18}
        strokeWidth={1.75}
        fill={active ? "currentColor" : "none"}
      />
    ),
  },
  {
    id: "devices",
    label: "Devices",
    icon: () => <Smartphone size={18} strokeWidth={1.75} />,
  },
  {
    id: "transfers",
    label: "Transfers",
    icon: () => <ArrowLeftRight size={18} strokeWidth={1.75} />,
  },
  {
    id: "files",
    label: "Files",
    icon: () => <Folder size={18} strokeWidth={1.75} />,
  },
  {
    id: "settings",
    label: "Settings",
    icon: () => <Settings size={18} strokeWidth={1.75} />,
  },
];

export const AppShell: React.FC = () => {
  const {
    page,
    setPage,
    incoming,
    closeIncoming,
    discovery,
    devices,
    sessionStore,
  } = useDropFlow();
  const running = Object.values(sessionStore.activeTransfers).filter((item) =>
    ["Preparing...", "Sending...", "Receiving...", "Finishing..."].includes(
      item.status,
    ),
  ).length;
  const disk = useDiskUsage();
  const { settings } = useSettings();
  const contentRef = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    if (settings.reduceAnimations || !contentRef.current) return;
    return mountPageMotion(contentRef.current);
  }, [page, settings.reduceAnimations]);
  const usedPct =
    disk && disk.totalBytes > 0
      ? Math.min(
          100,
          ((disk.totalBytes - disk.freeBytes) / disk.totalBytes) * 100,
        )
      : 0;
  const storageLine = disk
    ? `${formatBytes(disk.totalBytes - disk.freeBytes)} of ${formatBytes(disk.totalBytes)} used`
    : "Checking this disk";

  return (
    <div className="df-shell">
      <div className="df-native-titlebar" data-tauri-drag-region aria-hidden="true" />
      <aside className="df-sidebar">
        <div className="df-brand">
          <BrandMark />
          <div>
            <strong>DropFlow</strong>
            <small>Send files. Simply.</small>
          </div>
        </div>
        <nav className="df-nav" aria-label="Main">
          {NAV.map((item) => (
            <button
              key={item.id}
              type="button"
              aria-label={item.label}
              className={
                page === item.id ? "df-nav-item active" : "df-nav-item"
              }
              aria-current={page === item.id ? "page" : undefined}
              onClick={() => setPage(item.id)}
            >
              {item.icon(page === item.id)}
              <span>{item.label}</span>
              {item.id === "transfers" && running > 0 && (
                <span className="df-nav-count">{running}</span>
              )}
            </button>
          ))}
        </nav>
        <div className="df-storage-side">
          <div className="df-storage-label">
            <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden>
              <rect
                x="1.5"
                y="3"
                width="13"
                height="10"
                rx="2"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
              />
              <path d="M1.5 6.2h13" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Local Storage
          </div>
          <div className="df-storage-track">
            <span style={{ width: `${usedPct}%` }} />
          </div>
          <div className="df-storage-meta">{storageLine}</div>
        </div>
        <div className="df-v2-network" role="status">
          <i
            className={`df-dot${discovery === "error" ? " bad" : discovery === "ready" ? "" : " idle"}`}
          />
          <div>
            {discovery === "error"
              ? "Discovery unavailable"
              : discovery === "ready"
                ? "Devices nearby"
                : "Discovering devices"}
            <small>
              {devices.length
                ? `${devices.length} nearby on your network`
                : "Local network only"}
            </small>
          </div>
        </div>
      </aside>
      <div className="df-stage">
        <div className="df-scroll" ref={contentRef}>
          {page === "home" && <HomePage />}
          {page === "devices" && <DevicesPage />}
          {page === "transfers" && <TransfersPage />}
          {page === "files" && <FilesPage />}
          {page === "settings" && <SettingsPage />}
        </div>
      </div>
      {incoming && (
        <IncomingTransferModal request={incoming} onClose={closeIncoming} />
      )}
    </div>
  );
};

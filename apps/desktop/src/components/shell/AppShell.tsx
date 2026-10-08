import React from "react";
import { ArrowLeftRight, Folder, Home, Settings, Smartphone } from "lucide-react";
import { IncomingTransferModal } from "../IncomingTransferModal";
import { useDropFlow } from "../../session/DropFlowProvider";
import { AppPage } from "../../types/device";
import { HomePage } from "../../pages/HomePage";
import { DevicesPage } from "../../pages/DevicesPage";
import { TransfersPage } from "../../pages/TransfersPage";
import { FilesPage } from "../../pages/FilesPage";
import { SettingsPage } from "../../pages/SettingsPage";
import { CornerActions } from "./CornerActions";
import { useDiskUsage } from "../../ui/useDisk";
import { formatBytes } from "../../utils/formatters";

const NAV: { id: AppPage; label: string; icon: (active: boolean) => React.ReactNode }[] = [
  { id: "home", label: "Home", icon: (active) => <Home size={18} strokeWidth={1.75} fill={active ? "currentColor" : "none"} /> },
  { id: "devices", label: "Devices", icon: () => <Smartphone size={18} strokeWidth={1.75} /> },
  { id: "transfers", label: "Transfers", icon: () => <ArrowLeftRight size={18} strokeWidth={1.75} /> },
  { id: "files", label: "Files", icon: () => <Folder size={18} strokeWidth={1.75} /> },
  { id: "settings", label: "Settings", icon: () => <Settings size={18} strokeWidth={1.75} /> },
];

function BrandMark() {
  return (
    <svg className="df-mark" viewBox="0 0 34 34" aria-hidden>
      <rect width="34" height="34" rx="10" fill="#2F7CF6" />
      <path d="M10 13.2h9.2" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M16.6 10.4 19.6 13.2 16.6 16" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      <path d="M24 20.8H14.8" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M17.4 23.6 14.4 20.8 17.4 18" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  );
}

export const AppShell: React.FC = () => {
  const { page, setPage, incoming, closeIncoming } = useDropFlow();
  const disk = useDiskUsage();
  const usedPct = disk && disk.totalBytes > 0 ? Math.min(100, ((disk.totalBytes - disk.freeBytes) / disk.totalBytes) * 100) : 0;
  const storageLine = disk ? `${formatBytes(disk.totalBytes - disk.freeBytes)} of ${formatBytes(disk.totalBytes)} used` : "Checking this disk";

  return (
    <div className="df-shell">
      <aside className="df-sidebar">
        <div className="df-brand">
          <BrandMark />
          <strong>DropFlow</strong>
        </div>
        <nav className="df-nav" aria-label="Main">
          {NAV.map((item) => (
            <button
              key={item.id}
              type="button"
              className={page === item.id ? "df-nav-item active" : "df-nav-item"}
              aria-current={page === item.id ? "page" : undefined}
              onClick={() => setPage(item.id)}
            >
              {item.icon(page === item.id)}
              {item.label}
            </button>
          ))}
        </nav>
        <div className="df-storage-side">
          <div className="df-storage-label">
            <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden>
              <rect x="1.5" y="3" width="13" height="10" rx="2" fill="none" stroke="currentColor" strokeWidth="1.4" />
              <path d="M1.5 6.2h13" stroke="currentColor" strokeWidth="1.4" />
            </svg>
            Local Storage
          </div>
          <div className="df-storage-track"><span style={{ width: `${usedPct}%` }} /></div>
          <div className="df-storage-meta">{storageLine}</div>
        </div>
      </aside>
      <div className="df-stage">
        {page === "settings" && (
          <div className="df-topbar">
            <CornerActions />
          </div>
        )}
        <div className="df-scroll">
          {page === "home" && <HomePage />}
          {page === "devices" && <DevicesPage />}
          {page === "transfers" && <TransfersPage />}
          {page === "files" && <FilesPage />}
          {page === "settings" && <SettingsPage />}
        </div>
      </div>
      {incoming && <IncomingTransferModal request={incoming} onClose={closeIncoming} />}
    </div>
  );
};

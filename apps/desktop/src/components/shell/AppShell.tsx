import React, { useEffect, useState } from "react";
import {
  ArrowLeftRight,
  Folder,
  House,
  PanelLeft,
  Plus,
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
import { DeviceVisual, useGlide } from "../ui/Desktop";
import { Glass, installGlassLight } from "../../ui/glass/LiquidGlass";

const NAV: { id: AppPage; label: string; icon: React.ElementType }[] = [
  { id: "home", label: "Home", icon: House },
  { id: "devices", label: "Devices", icon: Smartphone },
  { id: "transfers", label: "Transfers", icon: ArrowLeftRight },
  { id: "files", label: "Files", icon: Folder },
  { id: "settings", label: "Settings", icon: Settings },
];

const LIVE = ["Preparing...", "Sending...", "Receiving...", "Finishing..."];

export const AppShell: React.FC = () => {
  const {
    page,
    setPage,
    incoming,
    closeIncoming,
    discovery,
    devices,
    selectedDeviceId,
    selectDevice,
    sessionStore,
    droppedOver,
  } = useDropFlow();
  const running = Object.values(sessionStore.activeTransfers).filter((item) =>
    LIVE.includes(item.status),
  ).length;
  const disk = useDiskUsage();
  const [sidebar, setSidebar] = useState(true);
  const { container, indicator } = useGlide<HTMLElement>(page, "y");

  useEffect(installGlassLight, []);

  // ⌃⌘S toggles the sidebar (macOS); Ctrl+Shift+S elsewhere. ⌘1–5 switch sections.
  useEffect(() => {
    const keydown = (event: KeyboardEvent) => {
      const mod = event.metaKey || event.ctrlKey;
      if (!mod) return;
      if (event.key.toLowerCase() === "s" && (event.metaKey ? event.ctrlKey : event.shiftKey)) {
        event.preventDefault();
        setSidebar((open) => !open);
        return;
      }
      const index = Number(event.key) - 1;
      if (!event.shiftKey && !event.altKey && index >= 0 && index < NAV.length) {
        event.preventDefault();
        setPage(NAV[index].id);
      }
      if (event.key === ",") {
        event.preventDefault();
        setPage("settings");
      }
    };
    window.addEventListener("keydown", keydown);
    return () => window.removeEventListener("keydown", keydown);
  }, [setPage]);

  const usedPct =
    disk && disk.totalBytes > 0
      ? Math.min(100, ((disk.totalBytes - disk.freeBytes) / disk.totalBytes) * 100)
      : 0;

  return (
    <div className="win" data-sidebar={sidebar ? "shown" : "hidden"}>
      <Glass as="aside" size="lg" className="sidebar" aria-label="Sidebar">
        <div className="sidebar-drag" data-tauri-drag-region aria-hidden="true" />
        <nav ref={container} className="side-nav" aria-label="Main">
          <span ref={indicator} className="side-lens" aria-hidden="true" />
          {NAV.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              className="side-item"
              data-active={page === id}
              aria-current={page === id ? "page" : undefined}
              onClick={() => setPage(id)}
            >
              <Icon size={16} strokeWidth={1.9} />
              <span>{label}</span>
              {id === "transfers" && running > 0 && (
                <span className="badge" aria-label={`${running} active`}>
                  {running}
                </span>
              )}
            </button>
          ))}
        </nav>

        <h2 className="side-heading">Nearby</h2>
        <div className="side-devices">
          {devices.map((device) => (
            <button
              key={device.id}
              type="button"
              className="side-device"
              aria-pressed={selectedDeviceId === device.id}
              title={`Send to ${device.name}`}
              onClick={() => {
                selectDevice(device.id);
                setPage("home");
              }}
            >
              <DeviceVisual type={device.type} small />
              <span>{device.name}</span>
            </button>
          ))}
          {devices.length === 0 && (
            <p className="side-empty">
              {discovery === "error" ? "Discovery unavailable" : "Searching…"}
            </p>
          )}
        </div>

        <div className="side-foot">
          {disk && (
            <>
              <div
                className="meter"
                role="meter"
                aria-label="Disk used"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={Math.round(usedPct)}
              >
                <span style={{ width: `${usedPct}%` }} />
              </div>
              <small className="num">{formatBytes(disk.freeBytes)} available</small>
            </>
          )}
          <small role="status">
            <i
              className={`dot${discovery === "error" ? " bad" : discovery === "ready" ? "" : " idle"}`}
            />
            {discovery === "error"
              ? "Offline"
              : devices.length
                ? `${devices.length} on this network`
                : "Local network"}
          </small>
        </div>
      </Glass>

      <button
        type="button"
        className="sb-toggle icon-btn"
        aria-label={sidebar ? "Hide sidebar" : "Show sidebar"}
        aria-expanded={sidebar}
        title={sidebar ? "Hide sidebar" : "Show sidebar"}
        onClick={() => setSidebar((open) => !open)}
      >
        <PanelLeft size={16} strokeWidth={1.8} />
      </button>

      <main className="main">
        {page === "home" && <HomePage key="home" />}
        {page === "devices" && <DevicesPage key="devices" />}
        {page === "transfers" && <TransfersPage key="transfers" />}
        {page === "files" && <FilesPage key="files" />}
        {page === "settings" && <SettingsPage key="settings" />}
        {droppedOver && (
          <div className="drop-overlay" aria-live="polite">
            <Glass size="md">
              <Plus size={18} strokeWidth={2.2} />
              Drop to add files
            </Glass>
          </div>
        )}
      </main>

      {incoming && (
        <IncomingTransferModal key={incoming.sessionId} request={incoming} onClose={closeIncoming} />
      )}
    </div>
  );
};

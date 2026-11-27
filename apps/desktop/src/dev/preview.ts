/**
 * Design preview — dev server only, outside Tauri. Mocks the IPC surface with
 * sample data so the interface can be reviewed in a plain browser. Never
 * bundled into a release (imported behind import.meta.env.DEV).
 */
import { emit } from "@tauri-apps/api/event";
import { mockIPC, mockWindows } from "@tauri-apps/api/mocks";

const now = Date.now();
const min = 60_000;

const peers = [
  { id: "p1", name: "Sam's Pixel 9", type: "phone", status: "online", lastSeen: "Just now", addresses: [{ address: "192.168.1.24", family: "ipv4" }], port: 5300, version: "0.1.2" },
  { id: "p2", name: "Studio PC", type: "desktop", status: "online", lastSeen: "Just now", addresses: [{ address: "192.168.1.31", family: "ipv4" }], port: 5300, version: "0.1.2" },
  { id: "p3", name: "Galaxy Tab S9", type: "tablet", status: "online", lastSeen: "2 min ago", addresses: [{ address: "192.168.1.40", family: "ipv4" }], port: 5300, version: "0.1.1" },
];

const history = [
  { id: "h1", fileName: "Site survey.pdf", deviceName: "Studio PC", size: "4.2 MB", timestamp: "", timestampMs: now - 12 * min, status: "completed", direction: "send", totalFiles: 1, files: [{ fileIndex: 0, relativePath: "Site survey.pdf", sizeBytes: 4_400_000, finalPath: "/Users/preview/Downloads/Site survey.pdf" }] },
  { id: "h2", fileName: "IMG_4471.HEIC", deviceName: "Sam's Pixel 9", size: "38.6 MB", timestamp: "", timestampMs: now - 54 * min, status: "completed", direction: "receive", totalFiles: 14, files: [] },
  { id: "h3", fileName: "render-final.mov", deviceName: "Studio PC", size: "1.4 GB", timestamp: "", timestampMs: now - 3 * 60 * min, status: "failed", direction: "send", totalFiles: 1, error: "connection reset", files: [] },
  { id: "h4", fileName: "invoice-0193.pdf", deviceName: "Galaxy Tab S9", size: "220 KB", timestamp: "", timestampMs: now - 26 * 60 * min, status: "completed", direction: "receive", totalFiles: 1, files: [] },
];

const entries = [
  { name: "Projects", path: "/Users/preview/Downloads/Projects", isDir: true, sizeBytes: 0, modifiedMs: now - 2 * 86_400_000 },
  { name: "Screenshots", path: "/Users/preview/Downloads/Screenshots", isDir: true, sizeBytes: 0, modifiedMs: now - 5 * 86_400_000 },
  { name: "Site survey.pdf", path: "/Users/preview/Downloads/Site survey.pdf", isDir: false, sizeBytes: 4_400_000, modifiedMs: now - 12 * min },
  { name: "beach.jpg", path: "/Users/preview/Downloads/beach.jpg", isDir: false, sizeBytes: 3_100_000, modifiedMs: now - 86_400_000 },
  { name: "archive-2026.zip", path: "/Users/preview/Downloads/archive-2026.zip", isDir: false, sizeBytes: 812_000_000, modifiedMs: now - 3 * 86_400_000 },
  { name: "notes.md", path: "/Users/preview/Downloads/notes.md", isDir: false, sizeBytes: 9_200, modifiedMs: now - 4 * 86_400_000 },
];

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** A send that goes through every phase the real engine reports. */
async function simulateSend(args: { sessionId: string; filePaths: string[] }) {
  const { sessionId, filePaths } = args;
  const total = 640_000_000;
  const fail = new URLSearchParams(location.search).get("send");
  await pause(400);
  await emit("transfer-phase", { sessionId, phase: "waiting" });
  await pause(2600);
  if (fail === "declined") throw "Transfer declined by recipient";
  await emit("transfer-phase", { sessionId, phase: "transferring" });
  for (let sent = 0; sent < total; ) {
    sent = Math.min(total, sent + 42_000_000);
    await emit("transfer-progress", {
      sessionId,
      direction: "send",
      currentFileIndex: 0,
      currentFileName: filePaths[0]?.split("/").pop(),
      totalFiles: filePaths.length,
      percentage: (sent / total) * 100,
      speedBytesPerSec: 61_000_000 + Math.random() * 9_000_000,
      sessionBytesSent: sent,
      sessionTotalBytes: total,
    });
    await pause(260);
    if (fail === "lost" && sent > total * 0.6) throw "Connection lost: the other device closed the connection [resumable]";
  }
  await emit("transfer-phase", { sessionId, phase: "finishing" });
  await pause(1500);
  await emit("transfer-completed", {
    sessionId,
    fileName: filePaths[0]?.split("/").pop(),
    deviceName: "",
    size: "610.4 MB",
    totalFiles: filePaths.length,
    totalSizeBytes: total,
    files: filePaths.map((path, fileIndex) => ({ fileIndex, relativePath: path.split("/").pop(), sizeBytes: total / filePaths.length, finalPath: path })),
    receiveDir: "",
  });
  return sessionId;
}

export function installPreview() {
  mockWindows("main");
  mockIPC(
    (cmd, args) => {
      switch (cmd) {
        case "send_files":
          return simulateSend(args as { sessionId: string; filePaths: string[] });
        case "get_local_uuid":
          return "preview-local";
        case "get_system_device_type":
          return "laptop";
        case "get_system_computer_name":
          return "MacBook Pro";
        case "get_app_state":
          return { settings: { deviceName: "MacBook Pro", theme: new URLSearchParams(location.search).get("theme") || "dark", requireConfirmation: true, updateChannel: "beta" }, history, trustedDevices: [{ deviceId: "p2", deviceName: "Studio PC", platform: "Desktop", firstSeen: "", lastSeen: "" }] };
        case "get_current_peers":
          return new URLSearchParams(location.search).has("empty") ? [] : peers;
        case "get_disk_usage":
          return { totalBytes: 994_000_000_000, freeBytes: 412_000_000_000 };
        case "get_user_folders":
          return { home: "/Users/preview", desktop: "/Users/preview/Desktop", documents: "/Users/preview/Documents", downloads: "/Users/preview/Downloads", localIp: "192.168.1.12" };
        case "list_directory":
          return entries;
        case "get_release_info":
          return { display_version: "0.2.0-beta.1", channel: "Beta" };
        case "get_receive_dir":
          return "/Users/preview/Downloads";
        case "nearby_link_lab_status":
          return { phase: "off", message: "Off", ssid: null, passphrase: null };
        case "direct_link_status": {
          const role = new URLSearchParams(location.search).get("link");
          return role === "host"
            ? { role: "host", ready: false, detail: "This PC's Wi-Fi adapter does not support Wi-Fi Direct." }
            : { role: "join", ready: true, detail: "" };
        }
        case "plugin:dialog|open":
          return [
            "/Users/preview/Downloads/Holiday footage.mov",
            "/Users/preview/Downloads/beach.jpg",
            "/Users/preview/Downloads/a-very-long-file-name-that-should-truncate-gracefully-in-the-dock.pdf",
          ];
        case "read_image_preview":
          throw new Error("no preview");
        default:
          return null;
      }
    },
    { shouldMockEvents: true },
  );

  (window as unknown as { dfPreview: unknown }).dfPreview = { emit };

  // One live transfer so progress UI is visible.
  let sent = 0;
  const total = 2_400_000_000;
  const tick = () => {
    sent = Math.min(total, sent + 38_000_000 + Math.random() * 18_000_000);
    void emit("transfer-progress", {
      sessionId: "live-1",
      direction: "send",
      deviceName: "Sam's Pixel 9",
      currentFileName: "Holiday footage.mov",
      totalFiles: 1,
      percentage: (sent / total) * 100,
      speedBytesPerSec: 52_000_000 + Math.random() * 30_000_000,
      sessionBytesSent: sent,
      sessionTotalBytes: total,
    });
    if (sent < total * 0.92) setTimeout(tick, 900);
  };
  setTimeout(tick, 1200);
}

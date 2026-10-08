import laptop from "../assets/pack/laptop.png";
import phone from "../assets/pack/phone.png";
import desktop from "../assets/pack/desktop.png";
import tablet from "../assets/pack/tablet.png";
import folder from "../assets/pack/file-folder.png";
import image from "../assets/pack/file-image.png";
import pdf from "../assets/pack/file-pdf.png";
import video from "../assets/pack/file-video.png";
import zip from "../assets/pack/file-zip.png";
import sheet from "../assets/pack/file-sheet.png";
import doc from "../assets/pack/file-doc.png";
import chart from "../assets/pack/file-chart.png";
import audio from "../assets/pack/file-audio.png";

const DEVICE_ART: Record<string, string> = {
  laptop,
  phone,
  desktop,
  tablet,
};

export function deviceArt(type: string | undefined): string {
  const value = (type || "").toLowerCase();
  if (value.includes("tablet") || value.includes("ipad")) return DEVICE_ART.tablet;
  if (value.includes("phone") || value.includes("android") || value.includes("mobile")) return DEVICE_ART.phone;
  if (value.includes("laptop") || value.includes("book") || value.includes("macbook")) return DEVICE_ART.laptop;
  return DEVICE_ART.desktop;
}

export function deviceKindLabel(type: string | undefined): string {
  const value = (type || "").toLowerCase();
  if (value.includes("tablet")) return "Tablet";
  if (value.includes("phone") || value.includes("android") || value.includes("mobile")) return "Phone";
  if (value.includes("laptop") || value.includes("book")) return "Laptop";
  if (value.includes("desktop") || value.includes("pc")) return "Desktop";
  return "Device";
}

export function fileArt(name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["png", "jpg", "jpeg", "gif", "webp", "svg", "heic", "avif", "bmp"].includes(ext)) return image;
  if (ext === "pdf") return pdf;
  if (["mp4", "mov", "mkv", "avi", "webm", "m4v"].includes(ext)) return video;
  if (["zip", "rar", "7z", "tar", "gz", "bz2"].includes(ext)) return zip;
  if (["xls", "xlsx", "csv", "numbers", "ods"].includes(ext)) return sheet;
  if (["ppt", "pptx", "key", "odp"].includes(ext)) return chart;
  if (["mp3", "wav", "flac", "aac", "m4a", "ogg"].includes(ext)) return audio;
  if (!ext || name.endsWith("/") || name.endsWith("\\")) return folder;
  return doc;
}

export function fileKindLabel(name: string): string {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (!ext || ext === name.toLowerCase()) return "File";
  return ext.toUpperCase();
}

export function greeting(date = new Date()): string {
  const hour = date.getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

export function friendlyTransferError(raw: string | undefined): string {
  const text = (raw || "").toLowerCase();
  if (!text) return "The transfer didn't finish.";
  if (text.includes("cancel")) return "This transfer was cancelled.";
  if (text.includes("declin") || text.includes("reject")) return "The other device declined this transfer.";
  if (text.includes("timeout") || text.includes("timed out")) return "The other device didn't respond in time.";
  if (
    text.includes("connection") ||
    text.includes("reset") ||
    text.includes("refused") ||
    text.includes("offline") ||
    text.includes("broken pipe")
  ) {
    return "The connection was lost. The device may have gone offline.";
  }
  return "The transfer didn't finish. Try again if the device is still nearby.";
}

export function platformLabel(): string {
  if (typeof navigator === "undefined") return "Desktop";
  const probe = `${navigator.userAgent || ""} ${navigator.platform || ""}`;
  if (/mac/i.test(probe)) return "macOS";
  if (/win/i.test(probe)) return "Windows";
  if (/linux/i.test(probe)) return "Linux";
  return "Desktop";
}

export function deviceKindLabel(type: string | undefined): string {
  const value = (type || "").toLowerCase();
  if (value.includes("tablet")) return "Tablet";
  if (value.includes("phone") || value.includes("android") || value.includes("mobile")) return "Phone";
  if (value.includes("laptop") || value.includes("book")) return "Laptop";
  if (value.includes("desktop") || value.includes("pc")) return "Desktop";
  return "Device";
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
  if (text.includes("busy") || text.includes("transfer limit")) return "The other device is busy. Try again shortly.";
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

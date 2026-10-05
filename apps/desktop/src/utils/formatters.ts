/**
 * DropFlow — Shared Formatting Helpers
 *
 * Single source of truth for human-readable byte counts and transfer speeds.
 * UI components should import from here instead of re-implementing local copies.
 */

/** Formats a byte count as a human-readable string, e.g. "1.5 MB". */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";

  const units = ["B", "KB", "MB", "GB", "TB"];
  const exponent = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1,
  );
  const value = bytes / Math.pow(1024, exponent);

  // Whole numbers under 10 stay compact ("8 KB"); everything else gets one decimal.
  const formatted =
    exponent === 0 || value >= 100
      ? Math.round(value).toString()
      : value.toFixed(1);

  return `${formatted} ${units[exponent]}`;
}

/** Formats a transfer speed in bytes per second, e.g. "2.3 MB/s". */
export function formatSpeed(bytesPerSec: number): string {
  if (!Number.isFinite(bytesPerSec) || bytesPerSec <= 0) return "0 B/s";

  const units = ["B/s", "KB/s", "MB/s", "GB/s"];
  const exponent = Math.min(
    Math.floor(Math.log(bytesPerSec) / Math.log(1024)),
    units.length - 1,
  );
  const value = bytesPerSec / Math.pow(1024, exponent);

  const formatted =
    exponent === 0 || value >= 100
      ? Math.round(value).toString()
      : value.toFixed(1);

  return `${formatted} ${units[exponent]}`;
}

import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

export interface DiskUsage {
  totalBytes: number;
  freeBytes: number;
}

export function useDiskUsage(): DiskUsage | null {
  const [disk, setDisk] = useState<DiskUsage | null>(null);
  useEffect(() => {
    invoke<DiskUsage>("get_disk_usage", { path: "" }).then(setDisk).catch(() => setDisk(null));
  }, []);
  return disk;
}

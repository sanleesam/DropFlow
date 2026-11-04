import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";

export interface DiskUsage {
  totalBytes: number;
  freeBytes: number;
}

export function useDiskUsage(): DiskUsage | null | undefined {
  const [disk, setDisk] = useState<DiskUsage | null | undefined>(undefined);
  useEffect(() => {
    invoke<DiskUsage>("get_disk_usage", { path: "" }).then(setDisk).catch(() => setDisk(null));
  }, []);
  return disk;
}

export interface DeviceAddress {
  address: string;
  family: "ipv4" | "ipv6" | string;
  interface?: string | null;
}

export interface Device {
  id: string;
  name: string;
  type: string;
  status: string;
  lastSeen: string;
  addresses: DeviceAddress[];
  port: number;
  version: string;
}

export type AppPage = "home" | "devices" | "transfers" | "files" | "settings";

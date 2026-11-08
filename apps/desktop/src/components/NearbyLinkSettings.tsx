import { invoke } from "@tauri-apps/api/core";
import { useEffect, useRef, useState } from "react";
import { SettingRow, Switch } from "./ui/Desktop";

type Snapshot = {
  phase: "unavailable" | "off" | "starting" | "ready" | "testing" | "stopping" | "verified" | "error";
  message: string;
  ssid: string | null;
  passphrase: string | null;
};

export function NearbyLinkSettings() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const changing = useRef(false);
  const checked = snapshot != null && ["starting", "ready", "testing", "stopping"].includes(snapshot.phase);
  const refresh = snapshot == null || checked;

  useEffect(() => {
    if (!refresh || pending) return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      try {
        const state = await invoke<Snapshot>("nearby_link_lab_status");
        if (!disposed && !changing.current) {
          setSnapshot(state);
          setError("");
        }
      } catch {
        if (!disposed && !changing.current) setError("Couldn't read Nearby Link status.");
      } finally {
        if (!disposed) timer = setTimeout(poll, 500);
      }
    };
    void poll();
    return () => {
      disposed = true;
      clearTimeout(timer);
    };
  }, [refresh, pending]);

  const toggle = async () => {
    if (changing.current || !snapshot || snapshot.phase === "stopping") return;
    changing.current = true;
    setPending(true);
    setError("");
    try {
      setSnapshot(await invoke<Snapshot>("set_nearby_link_lab", { enabled: !checked }));
    } catch (error) {
      setError(String(error));
    } finally {
      changing.current = false;
      setPending(false);
    }
  };

  return (
    <section className="df-card df-card-pad">
      <h2 className="df-v2-section-title">Nearby Link · Experimental</h2>
      <SettingRow
        title="Windows-to-Android link test"
        description="Start a temporary Wi-Fi session. Bluetooth is optional. Only test data is sent."
      >
        <Switch
          label="Nearby Link"
          checked={checked}
          disabled={!snapshot || pending || snapshot.phase === "stopping" || snapshot.phase === "unavailable"}
          onChange={toggle}
        />
      </SettingRow>
      <p role="status" className="df-meta">{snapshot?.message ?? "Checking availability…"}</p>
      {error && <p role="alert">{error}</p>}
      {snapshot?.phase === "ready" && (
        <div className="df-v2-stack">
          <p>On Android, open Nearby Link in Settings and enter these temporary credentials. Don’t join the network from system settings.</p>
          <div><strong>Network name</strong> <code>{snapshot.ssid}</code></div>
          <div><strong>Passphrase</strong> <code>{snapshot.passphrase}</code></div>
        </div>
      )}
    </section>
  );
}

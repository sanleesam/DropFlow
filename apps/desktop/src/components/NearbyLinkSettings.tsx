import { invoke } from "@tauri-apps/api/core";
import { useEffect, useRef, useState } from "react";
import { FormGroup, SettingRow, Switch } from "./ui/Desktop";

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
    <FormGroup
      title="Nearby Link · Experimental"
      footer="Windows ↔ Android test. Temporary Wi-Fi session; only test data is sent."
    >
      <SettingRow title="Link test" description={snapshot?.message ?? "Checking availability…"}>
        <Switch
          label="Nearby Link"
          checked={checked}
          disabled={!snapshot || pending || snapshot.phase === "stopping" || snapshot.phase === "unavailable"}
          onChange={toggle}
        />
      </SettingRow>
      <span role="status" className="sr-only">{snapshot?.message ?? ""}</span>
      {error && (
        <p role="alert" className="footnote" style={{ color: "var(--red)", padding: "0 0 10px" }}>
          {error}
        </p>
      )}
      {snapshot?.phase === "ready" && (
        <div className="form-row">
          <div className="creds">
            <p className="footnote">On Android, open Nearby Link in Settings and enter these. Don't join from system Wi-Fi.</p>
            <div>
              <span className="secondary">Network </span>
              <code>{snapshot.ssid}</code>
            </div>
            <div>
              <span className="secondary">Passphrase </span>
              <code>{snapshot.passphrase}</code>
            </div>
          </div>
        </div>
      )}
    </FormGroup>
  );
}

import { AlertCircle, Wifi, WifiOff } from "lucide-react";

export function DiscoveryState({
  state,
  onRetry,
}: {
  state: "searching" | "empty" | "error" | "ready";
  onRetry: () => void;
  /** Kept for call-site compatibility; motion follows CSS preferences. */
  reduceMotion?: boolean;
}) {
  if (state === "ready") return null;
  return (
    <div className="discovery" data-state={state} role="status">
      {state === "searching" ? (
        <span className="df-discovery-beacon" aria-hidden="true">
          <span className="df-discovery-ring" />
          <span className="df-discovery-ring" />
          <span className="df-discovery-beacon-core">
            <Wifi size={20} strokeWidth={1.8} />
          </span>
        </span>
      ) : (
        <span className="icon" aria-hidden="true">
          {state === "error" ? <AlertCircle size={22} /> : <WifiOff size={22} />}
        </span>
      )}
      <strong>
        {state === "error"
          ? "Discovery unavailable"
          : state === "searching"
            ? "Looking for devices"
            : "No devices nearby"}
      </strong>
      <p>
        {state === "error"
          ? "Check your network connection."
          : "Open DropFlow on the same network."}
      </p>
      {state !== "searching" && (
        <button type="button" className="btn btn-plain" onClick={onRetry}>
          Search again
        </button>
      )}
    </div>
  );
}

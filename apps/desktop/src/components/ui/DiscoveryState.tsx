import { useEffect, useRef } from "react";
import { AlertCircle, RefreshCw, Wifi } from "lucide-react";
import { mountDiscoveryMotion } from "../../ui/motion";
export function DiscoveryState({
  state,
  onRetry,
  reduceMotion = false,
}: {
  state: "searching" | "empty" | "error" | "ready";
  onRetry: () => void;
  reduceMotion?: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (state !== "searching" || reduceMotion || !ref.current) return;
    return mountDiscoveryMotion(ref.current);
  }, [state, reduceMotion]);
  if (state === "ready") return null;
  return (
    <div ref={ref} className="df-v2-discovery" data-state={state} role="status">
      {state === "error" ? (
        <AlertCircle size={26} />
      ) : state === "searching" ? (
        <>
          <span className="df-discovery-edge" aria-hidden="true" />
          <span className="df-discovery-beacon" aria-hidden="true">
            <span className="df-discovery-ring" />
            <span className="df-discovery-ring" />
            <span className="df-discovery-beacon-core">
              <Wifi size={21} strokeWidth={1.7} />
            </span>
          </span>
        </>
      ) : (
        <Wifi size={26} />
      )}
      <strong>
        {state === "error"
          ? "Discovery unavailable"
          : state === "searching"
            ? "Looking for nearby devices"
            : "No other devices nearby"}
      </strong>
      <p>
        {state === "error"
          ? "Check your network connection, then try again."
          : "Open DropFlow on another device connected to the same local network."}
      </p>
      {state !== "searching" && (
        <button type="button" className="df-link" onClick={onRetry}>
          <RefreshCw size={14} />
          Search again
        </button>
      )}
    </div>
  );
}

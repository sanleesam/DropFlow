import { AlertCircle, LoaderCircle, RefreshCw, Wifi } from "lucide-react";
export function DiscoveryState({
  state,
  onRetry,
}: {
  state: "searching" | "empty" | "error" | "ready";
  onRetry: () => void;
}) {
  if (state === "ready") return null;
  return (
    <div className="df-v2-discovery" data-state={state} role="status">
      {state === "error" ? (
        <AlertCircle size={26} />
      ) : state === "searching" ? (
        <LoaderCircle
          className="df-v2-discovery-spinner"
          size={24}
          strokeWidth={2.25}
          aria-hidden="true"
        />
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

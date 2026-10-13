import { renderToStaticMarkup } from "react-dom/server";
import {
  DeviceCard,
  DeviceVisual,
  EmptyState,
  Progress,
  Switch,
} from "./Desktop";
import { DiscoveryState } from "./DiscoveryState";

function assert(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
}

const progress = renderToStaticMarkup(
  <Progress value={160} label="Example transfer" />,
);
assert(progress.includes('aria-valuenow="100"'), "Progress must clamp to 100");
assert(
  progress.includes('aria-label="Example transfer"'),
  "Progress must have an accessible label",
);
assert(
  renderToStaticMarkup(<Progress value={NaN} label="Loading" />).includes(
    'aria-valuenow="0"',
  ),
  "Invalid progress must be safe",
);
assert(
  renderToStaticMarkup(<Progress value={-10} label="Loading" />).includes(
    'aria-valuenow="0"',
  ),
  "Negative progress must clamp to zero",
);

const selected = renderToStaticMarkup(
  <DeviceCard
    name="Test <device>"
    type="phone"
    status="Online"
    selected
    onClick={() => undefined}
  />,
);
assert(
  selected.includes('aria-pressed="true"'),
  "Device selection must be announced",
);
assert(
  selected.includes("Test &lt;device&gt;"),
  "Device names must be escaped",
);
const hidden = renderToStaticMarkup(
  <DeviceCard
    name="Local device"
    type="desktop"
    status="Hidden"
    local
    onClick={() => undefined}
  />,
);
assert(
  hidden.includes("This device · hidden"),
  "Hidden local state must not be presented as online",
);
assert(
  renderToStaticMarkup(<DeviceVisual type="tablet" />).includes(
    'aria-hidden="true"',
  ),
  "Decorative device artwork should not be announced",
);

assert(
  renderToStaticMarkup(
    <Switch label="Ask before accepting" checked onChange={() => undefined} />,
  ).includes('role="switch"'),
  "Settings toggles must have a switch role",
);
assert(
  renderToStaticMarkup(
    <EmptyState title="No transfers" icon={null}>
      Send a file to start.
    </EmptyState>,
  ).includes("Send a file to start."),
  "Empty states must provide guidance",
);
assert(
  renderToStaticMarkup(
    <DiscoveryState state="ready" onRetry={() => undefined} />,
  ) === "",
  "Discovery feedback must not replace real devices",
);
assert(
  renderToStaticMarkup(
    <DiscoveryState state="error" onRetry={() => undefined} />,
  ).includes("Search again"),
  "Discovery failures must offer recovery",
);
console.log("Desktop UI primitive checks passed (12 assertions).");

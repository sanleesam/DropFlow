import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Check, Laptop, Monitor, Search, Smartphone, Tablet } from "lucide-react";
import { deviceKindLabel } from "../../ui/artwork";
import { Glass } from "../../ui/glass/LiquidGlass";

/* ---------- page scaffold ---------- */

/**
 * A page owns its scroll view and its floating toolbar. The large title
 * dissolves into the toolbar as content scrolls beneath the glass.
 */
export function Page({
  title,
  toolbar,
  children,
  fill = false,
  overlay,
}: {
  title: string;
  toolbar?: ReactNode;
  children: ReactNode;
  fill?: boolean;
  overlay?: ReactNode;
}) {
  const [scrolled, setScrolled] = useState(false);
  return (
    <section
      className={`page${fill ? " page-fill-host" : ""}`}
      data-scrolled={scrolled || undefined}
      aria-label={title}
    >
      <header className="toolbar" data-tauri-drag-region>
        <span className="toolbar-title" aria-hidden={!fill && !scrolled}>
          {title}
        </span>
        <span className="toolbar-spacer" data-tauri-drag-region />
        {toolbar}
      </header>
      {fill ? (
        <div className="page-fill">{children}</div>
      ) : (
        <div
          className="page-scroll"
          onScroll={(event) => setScrolled(event.currentTarget.scrollTop > 28)}
        >
          <div className="page-body">
            <h1 className="large-title">{title}</h1>
            {children}
          </div>
        </div>
      )}
      {overlay}
    </section>
  );
}

export function ToolGroup({
  children,
  tint,
  label,
}: {
  children: ReactNode;
  tint?: boolean;
  label?: string;
}) {
  return (
    <Glass className="tool-group" size="sm" tint={tint} role="group" aria-label={label}>
      {children}
    </Glass>
  );
}

export function SearchField({
  value,
  onChange,
  label,
  placeholder = "Search",
  width,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  placeholder?: string;
  width?: number;
}) {
  return (
    <Glass as="label" size="sm" className="search" style={width ? { width } : undefined}>
      <Search size={14} strokeWidth={2} />
      <input
        aria-label={label}
        value={value}
        placeholder={placeholder}
        spellCheck={false}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Escape") onChange("");
        }}
      />
    </Glass>
  );
}

/* ---------- gliding selection ---------- */

/**
 * Measures the active child and glides a single indicator to it with a spring,
 * squashing briefly mid-flight like a drop of liquid.
 */
export function useGlide<T extends HTMLElement>(
  activeKey: string | number | undefined,
  axis: "x" | "y",
) {
  const container = useRef<T>(null);
  const indicator = useRef<HTMLSpanElement>(null);
  const first = useRef(true);
  useLayoutEffect(() => {
    const host = container.current;
    const lens = indicator.current;
    if (!host || !lens) return;
    const place = () => {
      const target = host.querySelector<HTMLElement>("[data-active='true']");
      if (!target) {
        lens.style.opacity = "0";
        return;
      }
      lens.style.opacity = "1";
      if (axis === "y") {
        lens.style.transform = `translateY(${target.offsetTop}px)`;
        lens.style.height = `${target.offsetHeight}px`;
      } else {
        lens.style.transform = `translateX(${target.offsetLeft}px)`;
        lens.style.width = `${target.offsetWidth}px`;
      }
    };
    let done: ReturnType<typeof setTimeout> | undefined;
    if (first.current) {
      lens.style.transition = "none";
      place();
      void lens.offsetWidth;
      lens.style.transition = "";
      first.current = false;
    } else {
      lens.classList.add("moving");
      place();
      done = setTimeout(() => lens.classList.remove("moving"), 170);
    }
    const observer = new ResizeObserver(place);
    observer.observe(host);
    return () => {
      observer.disconnect();
      if (done) clearTimeout(done);
    };
  }, [activeKey, axis]);
  return { container, indicator };
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
  glass = true,
}: {
  options: { id: T; label: ReactNode; title?: string }[];
  value: T;
  onChange: (value: T) => void;
  label: string;
  glass?: boolean;
}) {
  const { container, indicator } = useGlide<HTMLDivElement>(value, "x");
  const buttons = options.map((option) => (
    <button
      key={option.id}
      type="button"
      aria-pressed={value === option.id}
      aria-label={option.title}
      title={option.title}
      data-active={value === option.id}
      onClick={() => onChange(option.id)}
    >
      {option.label}
    </button>
  ));
  const thumb = <span ref={indicator} className="seg-thumb" aria-hidden="true" />;
  if (!glass)
    return (
      <div ref={container} className="seg flat" role="group" aria-label={label}>
        {thumb}
        {buttons}
      </div>
    );
  return (
    <Glass ref={container} size="sm" className="seg" role="group" aria-label={label}>
      {thumb}
      {buttons}
    </Glass>
  );
}

/* ---------- devices ---------- */

function DeviceGlyph({ kind, size }: { kind: string; size: number }) {
  const Icon =
    kind === "Phone"
      ? Smartphone
      : kind === "Tablet"
        ? Tablet
        : kind === "Desktop"
          ? Monitor
          : Laptop;
  return <Icon size={size} strokeWidth={1.6} />;
}

/** Monogram-style device avatar. Decorative: adjacent text names the device. */
export function DeviceVisual({
  type,
  large = false,
  small = false,
  medium = false,
  local = false,
}: {
  type?: string;
  large?: boolean;
  small?: boolean;
  medium?: boolean;
  local?: boolean;
}) {
  const kind = deviceKindLabel(type || "");
  const size = large ? " large" : medium ? " medium" : small ? " small" : "";
  return (
    <span className={`avatar${size}${local ? " local" : ""}`} aria-hidden="true">
      <DeviceGlyph kind={kind} size={large ? 38 : medium ? 18 : 24} />
    </span>
  );
}

export function DeviceCard({
  name,
  type,
  status,
  selected,
  local,
  onClick,
  footer,
}: {
  name: string;
  type: string;
  status: string;
  selected?: boolean;
  local?: boolean;
  onClick: () => void;
  footer?: ReactNode;
}) {
  return (
    <button
      type="button"
      className="device"
      onClick={onClick}
      aria-pressed={!!selected}
    >
      <DeviceVisual type={type} local={local} />
      {selected && (
        <span className="check" aria-hidden="true">
          <Check size={12} strokeWidth={3} />
        </span>
      )}
      <strong title={name}>{name}</strong>
      <span className="status">
        <i className={`dot${status === "Hidden" ? " idle" : ""}`} />
        {local
          ? status === "Hidden"
            ? "This device · hidden"
            : "This device"
          : status}
      </span>
      {footer && <span className="meta">{footer}</span>}
    </button>
  );
}

/* ---------- feedback ---------- */

export function Progress({
  value,
  label,
  indeterminate = false,
}: {
  value: number;
  label: string;
  /** No measurable progress yet (connecting, waiting to be accepted). */
  indeterminate?: boolean;
}) {
  const percent = Math.max(
    0,
    Math.min(100, Number.isFinite(value) ? value : 0),
  );
  return (
    <div
      className={indeterminate ? "progress indeterminate" : "progress"}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={indeterminate ? undefined : percent}
    >
      <span style={indeterminate ? undefined : { width: `${percent}%` }} />
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  children,
  action,
  compact = false,
}: {
  icon: ReactNode;
  title: string;
  children: ReactNode;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div className={`empty${compact ? " compact" : ""}`}>
      <span className="empty-icon">{icon}</span>
      <strong>{title}</strong>
      <p>{children}</p>
      {action}
    </div>
  );
}

/* ---------- forms ---------- */

export function FormGroup({
  title,
  footer,
  children,
}: {
  title?: string;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="form-group">
      {title && <h2>{title}</h2>}
      <div className="card">{children}</div>
      {footer && <p className="form-foot">{footer}</p>}
    </section>
  );
}

export function SettingRow({
  title,
  description,
  children,
}: {
  title: string;
  description?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="form-row">
      <div>
        <h3>{title}</h3>
        {description && <p>{description}</p>}
      </div>
      {children}
    </div>
  );
}

export function Switch({
  label,
  checked,
  onChange,
  disabled = false,
}: {
  label: string;
  checked: boolean;
  onChange: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-label={label}
      aria-checked={checked}
      disabled={disabled}
      className={`switch${checked ? " on" : ""}`}
      onClick={onChange}
    >
      <i />
    </button>
  );
}

/* ---------- sheet ---------- */

export function Modal({
  title,
  children,
  onEscape,
  alert = false,
  quietFocus = false,
}: {
  title: string;
  children: ReactNode;
  onEscape?: () => void;
  alert?: boolean;
  /** Start with no control focused, so a stray Return or Space does nothing. */
  quietFocus?: boolean;
}) {
  const ref = useRef<HTMLElement>(null);
  const escape = useRef(onEscape);
  escape.current = onEscape;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    const elements = () =>
      Array.from(
        dialog?.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]',
        ) || [],
      );
    (quietFocus ? dialog : elements()[0] || dialog)?.focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && escape.current) {
        event.preventDefault();
        escape.current();
      }
      if (event.key !== "Tab") return;
      const items = elements();
      if (!items.length) {
        event.preventDefault();
        dialog?.focus();
        return;
      }
      if (
        event.shiftKey &&
        (document.activeElement === items[0] ||
          document.activeElement === dialog)
      ) {
        event.preventDefault();
        items[items.length - 1]?.focus();
      }
      if (
        !event.shiftKey &&
        document.activeElement === items[items.length - 1]
      ) {
        event.preventDefault();
        items[0].focus();
      }
    };
    document.addEventListener("keydown", keydown);
    return () => {
      document.removeEventListener("keydown", keydown);
      previous?.focus();
    };
  }, []);
  return (
    <div className="scrim">
      <Glass
        ref={ref}
        size="lg"
        variant="thick"
        className="sheet"
        role={alert ? "alertdialog" : "dialog"}
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
      >
        {children}
      </Glass>
    </div>
  );
}

import { useEffect, useRef, type ReactNode } from "react";
import { Check, Laptop, Monitor, Smartphone, Tablet } from "lucide-react";
import { CornerActions } from "../shell/CornerActions";
import { deviceKindLabel } from "../../ui/artwork";

export function PageHeader({ title, description, actions }: { title: string; description: string; actions?: ReactNode }) {
  return <header className="df-v2-header"><div><h1>{title}</h1><p>{description}</p></div><div className="df-v2-header-actions">{actions}<CornerActions /></div></header>;
}

/** Original vector artwork: no external imagery or platform assumptions. */
export function DeviceVisual({ type, large = false }: { type?: string; large?: boolean }) {
  const kind = deviceKindLabel(type || "").toLowerCase();
  const phone = kind === "phone" || kind === "tablet";
  return <svg className={`df-v2-device-art${large ? " large" : ""}`} viewBox="0 0 160 108" aria-hidden="true">
    {phone ? <>
      <rect x={kind === "tablet" ? 43 : 57} y="7" width={kind === "tablet" ? 74 : 46} height="92" rx="10" fill="var(--df-art-frame)" stroke="var(--df-art-edge)" />
      <rect x={kind === "tablet" ? 48 : 62} y="13" width={kind === "tablet" ? 64 : 36} height="79" rx="6" fill="var(--df-art-screen)" />
      <path d={kind === "tablet" ? "M48 67 112 35v57H48z" : "M62 65 98 42v50H62z"} fill="#3B82F6" opacity=".65" />
      <rect x="73" y="16" width="14" height="3" rx="1.5" fill="var(--df-art-frame)" />
    </> : <>
      <rect x="24" y="12" width="112" height="73" rx="6" fill="var(--df-art-frame)" stroke="var(--df-art-edge)" />
      <rect x="29" y="17" width="102" height="62" rx="2" fill="var(--df-art-screen)" />
      <path d="M29 63 131 29v50H29z" fill="#3B82F6" opacity=".6" />
      <path d="m29 75 102-31v35H29z" fill="#2563EB" opacity=".6" />
      {kind === "desktop" ? <><path d="M74 85h12l3 12H71z" fill="var(--df-art-edge)" /><rect x="57" y="97" width="46" height="3" rx="1.5" fill="var(--df-art-edge)" /></> : <><path d="M24 85h112l17 10H7z" fill="var(--df-art-edge)" /><path d="M7 95h146l-7 4H14z" fill="var(--df-art-frame)" /><path d="M67 86h26l4 4H63z" fill="var(--df-art-frame)" /></>}
    </>}
  </svg>;
}

export function DeviceCard({ name, type, status, selected, local, onClick, footer }: { name: string; type: string; status: string; selected?: boolean; local?: boolean; onClick: () => void; footer?: ReactNode }) {
  const label = deviceKindLabel(type);
  const Icon = label === "Phone" ? Smartphone : label === "Tablet" ? Tablet : label === "Desktop" ? Monitor : Laptop;
  return <button type="button" className={`df-v2-device-card${selected ? " selected" : ""}`} onClick={onClick} aria-pressed={!!selected}>
    {selected && <span className="df-v2-check"><Check size={12} /></span>}
    <DeviceVisual type={type} />
    <strong title={name}>{name}</strong>
    <span className="df-status"><i className={`df-dot${status === "Hidden" ? " idle" : ""}`} />{local ? "This device" : status}</span>
    <span className="df-v2-device-meta"><Icon size={14} />{footer || label}</span>
  </button>;
}

export function Progress({ value, label }: { value: number; label: string }) {
  const percent = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
  return <div className="df-progress" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}><span style={{ width: `${percent}%` }} /></div>;
}

export function EmptyState({ icon, title, children, action, compact = false }: { icon: ReactNode; title: string; children: ReactNode; action?: ReactNode; compact?: boolean }) {
  return <div className={`df-v2-empty${compact ? " compact" : ""}`}><span className="df-v2-empty-icon">{icon}</span><strong>{title}</strong><p>{children}</p>{action}</div>;
}

export function SettingRow({ title, description, children }: { title: string; description: ReactNode; children: ReactNode }) {
  return <div className="df-setting"><div><h3>{title}</h3><p>{description}</p></div>{children}</div>;
}

export function Switch({ label, checked, onChange }: { label: string; checked: boolean; onChange: () => void }) {
  return <button type="button" role="switch" aria-label={label} aria-checked={checked} className={`df-switch${checked ? " on" : ""}`} onClick={onChange}><i /></button>;
}

export function Modal({ title, children, onEscape, alert = false }: { title: string; children: ReactNode; onEscape?: () => void; alert?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const escape = useRef(onEscape);
  escape.current = onEscape;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const dialog = ref.current;
    const elements = () => Array.from(dialog?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled), [tabindex="0"]') || []);
    (elements()[0] || dialog)?.focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && escape.current) { event.preventDefault(); escape.current(); }
      if (event.key !== "Tab") return;
      const items = elements();
      if (!items.length) { event.preventDefault(); dialog?.focus(); return; }
      if (event.shiftKey && (document.activeElement === items[0] || document.activeElement === dialog)) { event.preventDefault(); items[items.length - 1]?.focus(); }
      if (!event.shiftKey && document.activeElement === items[items.length - 1]) { event.preventDefault(); items[0].focus(); }
    };
    document.addEventListener("keydown", keydown);
    return () => { document.removeEventListener("keydown", keydown); previous?.focus(); };
  }, []);
  return <div className="df-modal-back"><div ref={ref} className="df-dialog" role={alert ? "alertdialog" : "dialog"} aria-modal="true" aria-label={title} tabIndex={-1}>{children}</div></div>;
}

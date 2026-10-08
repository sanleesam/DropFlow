import React, { useEffect } from "react";
import { X } from "lucide-react";

export type ToastVariant = "info" | "success" | "warning" | "error";

export interface ToastMessage {
  id: string;
  message: string;
  variant: ToastVariant;
  duration?: number;
}

interface ToastProps {
  toast: ToastMessage;
  onDismiss: (id: string) => void;
}

const VARIANT_STYLES: Record<ToastVariant, { border: string; icon: string }> = {
  info: {
    border: "border-blue-500/50",
    icon: "text-blue-400",
  },
  success: {
    border: "border-emerald-500/50",
    icon: "text-emerald-400",
  },
  warning: {
    border: "border-amber-500/50",
    icon: "text-amber-400",
  },
  error: {
    border: "border-red-500/50",
    icon: "text-red-400",
  },
};

const VARIANT_ICONS: Record<ToastVariant, React.ReactNode> = {
  info: (
    <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <line x1="12" y1="16" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12.01" y2="8" />
    </svg>
  ),
  success: (
    <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
      <polyline points="22 4 12 14.01 9 11.01" />
    </svg>
  ),
  warning: (
    <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
      <line x1="12" y1="9" x2="12" y2="13" />
      <line x1="12" y1="17" x2="12.01" y2="17" />
    </svg>
  ),
  error: (
    <svg viewBox="0 0 24 24" className="w-5 h-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <line x1="15" y1="9" x2="9" y2="15" />
      <line x1="9" y1="9" x2="15" y2="15" />
    </svg>
  ),
};

const Toast: React.FC<ToastProps> = ({ toast, onDismiss }) => {
  const { id, message, variant, duration = 3000 } = toast;
  const style = VARIANT_STYLES[variant];
  const icon = VARIANT_ICONS[variant];

  useEffect(() => {
    const timer = setTimeout(() => {
      onDismiss(id);
    }, duration);

    return () => clearTimeout(timer);
  }, [id, duration, onDismiss]);

  return (
    <div
      role="alert"
      className={[
        "df-toast",
        style.border,
      ].join(" ")}
    >
      {/* Variant indicator bar */}
      <div
        className={[
          "absolute bottom-0 left-0 top-0 w-1",
          variant === "info" && "bg-blue-500",
          variant === "success" && "bg-emerald-500",
          variant === "warning" && "bg-amber-500",
          variant === "error" && "bg-red-500",
        ].join(" ")}
      />

      {/* Icon */}
      <span className={`flex-shrink-0 ${style.icon}`}>{icon}</span>

      {/* Message */}
      <p className="flex-1 text-sm leading-5">{message}</p>

      {/* Close button */}
      <button
        onClick={() => onDismiss(id)}
        className={[
          "flex-shrink-0 w-6 h-6 rounded-lg",
          "flex items-center justify-center",
          "text-slate-500 hover:text-slate-200",
          "hover:bg-white/5",
          "transition-all duration-150",
          "opacity-0 group-hover:opacity-100",
          "focus-visible:opacity-100 focus-visible:ring-1 focus-visible:ring-slate-500",
          "outline-none",
        ].join(" ")}
        aria-label="Dismiss notification"
      >
        <X size={14} strokeWidth={2.5} />
      </button>
    </div>
  );
};

export default Toast;

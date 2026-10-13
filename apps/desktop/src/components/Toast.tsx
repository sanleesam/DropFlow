import { useEffect } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Info,
  TriangleAlert,
  X,
} from "lucide-react";

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
const ICONS = {
  info: Info,
  success: CheckCircle2,
  warning: TriangleAlert,
  error: AlertCircle,
};

export default function Toast({ toast, onDismiss }: ToastProps) {
  const { id, message, variant, duration = 3000 } = toast;
  const Icon = ICONS[variant];
  useEffect(() => {
    const timer = setTimeout(() => onDismiss(id), duration);
    return () => clearTimeout(timer);
  }, [id, duration, onDismiss]);
  return (
    <div
      role={variant === "error" || variant === "warning" ? "alert" : "status"}
      className={`df-toast ${variant}`}
    >
      <Icon size={20} aria-hidden="true" />
      <p className="df-grow">{message}</p>
      <button
        type="button"
        className="df-quiet"
        onClick={() => onDismiss(id)}
        aria-label="Dismiss notification"
      >
        <X size={16} />
      </button>
    </div>
  );
}

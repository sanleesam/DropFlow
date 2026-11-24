import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

// Platform hint for chrome-dependent spacing (traffic lights, scrollbars).
{
  const probe = `${navigator.userAgent} ${navigator.platform}`;
  document.documentElement.dataset.platform = /Mac/i.test(probe)
    ? "mac"
    : /Win/i.test(probe)
      ? "windows"
      : "linux";
}

class AppErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { message: string | null }
> {
  state = { message: null as string | null };

  static getDerivedStateFromError(error: unknown) {
    console.error("[DropFlow] UI failed to render:", error);
    return { message: "DropFlow ran into a problem opening this screen." };
  }

  render() {
    if (this.state.message) {
      return (
        <div className="fatal" role="alert">
          <h1>Let's try that again.</h1>
          <p>{this.state.message}</p>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => window.location.reload()}
          >
            Reload DropFlow
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

async function boot() {
  // Design preview: plain browser on the dev server only. Stripped from builds.
  if (import.meta.env.DEV && !("__TAURI_INTERNALS__" in window)) {
    (await import("./dev/preview")).installPreview();
  }
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <AppErrorBoundary>
        <App />
      </AppErrorBoundary>
    </React.StrictMode>,
  );
}
void boot();

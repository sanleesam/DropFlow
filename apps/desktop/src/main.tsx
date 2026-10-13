import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

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
        <div className="df-v2-fatal" role="alert">
          <h1>Let's try that again.</h1>
          <p>{this.state.message}</p>
          <button
            type="button"
            className="df-btn df-btn-primary"
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

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <AppErrorBoundary>
      <App />
    </AppErrorBoundary>
  </React.StrictMode>,
);

import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";

class AppErrorBoundary extends React.Component<{ children: React.ReactNode }, { message: string | null }> {
  state = { message: null as string | null };

  static getDerivedStateFromError(error: unknown) {
    console.error("[DropFlow] UI failed to render:", error);
    return { message: "DropFlow ran into a problem opening this screen." };
  }

  render() {
    if (this.state.message) {
      return (
        <p style={{ margin: 24, fontFamily: "ui-sans-serif, sans-serif", color: "#0f172a" }}>{this.state.message}</p>
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

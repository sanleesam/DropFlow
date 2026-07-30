import { useState, useEffect } from "react";
import "./App.css";
import { ToastProvider } from "./components/ToastProvider";
import { SettingsProvider } from "./components/SettingsProvider";
import Header from "./components/Header";
import { SettingsModal } from "./components/SettingsModal";
import { Home } from "./pages/Home";
import { HistoryPage } from "./pages/HistoryPage";
import { invoke } from "@tauri-apps/api/core";
import { RecentTransfer } from "./utils/transferSessionManager";

function MainContent() {
  const [activePage, setActivePage] = useState<"home" | "history">("home");
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [recentTransfers, setRecentTransfers] = useState<RecentTransfer[]>([]);

  // Hydrate persistent history on startup
  useEffect(() => {
    invoke<any>("get_app_state")
      .then((appState) => {
        if (appState && Array.isArray(appState.history)) {
          setRecentTransfers(appState.history);
        }
      })
      .catch(console.error);
  }, []);

  return (
    <div className="df-app-shell flex h-screen w-screen flex-col overflow-hidden text-neutral-100">
      <Header
        activePage={activePage}
        onNavigate={setActivePage}
        onSettingsClick={() => setIsSettingsOpen(true)}
      />

      <main
        id="main-content"
        className="df-main flex-1 overflow-y-auto"
        style={{
          scrollbarWidth: "none",
          msOverflowStyle: "none",
        }}
      >
        {activePage === "home" ? (
          <Home onNavigateHistory={() => setActivePage("history")} />
        ) : (
          <HistoryPage recentTransfers={recentTransfers} />
        )}
      </main>

      <SettingsModal isOpen={isSettingsOpen} onClose={() => setIsSettingsOpen(false)} />
    </div>
  );
}

function App() {
  return (
    <SettingsProvider>
      <ToastProvider>
        <MainContent />
      </ToastProvider>
    </SettingsProvider>
  );
}

export default App;

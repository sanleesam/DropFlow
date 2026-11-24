import { SettingsProvider } from "./components/SettingsProvider";
import { ToastProvider } from "./components/ToastProvider";
import { AppShell } from "./components/shell/AppShell";
import { DropFlowProvider } from "./session/DropFlowProvider";
import "./ui/theme.css";

function App() {
  return (
    <SettingsProvider>
      <ToastProvider>
        <DropFlowProvider>
          <AppShell />
        </DropFlowProvider>
      </ToastProvider>
    </SettingsProvider>
  );
}

export default App;

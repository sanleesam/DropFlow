import "./App.css";
import { ToastProvider } from "./components/ToastProvider";
import { SettingsProvider } from "./components/SettingsProvider";
import Home from "./pages/Home";

function App() {
  return (
    <SettingsProvider>
      <ToastProvider>
        <Home />
      </ToastProvider>
    </SettingsProvider>
  );
}

export default App;

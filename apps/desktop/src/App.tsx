import "./App.css";
import { ToastProvider } from "./components/ToastProvider";
import Home from "./pages/Home";

function App() {
  return (
    <ToastProvider>
      <Home />
    </ToastProvider>
  );
}

export default App;

import { Routes, Route, Navigate } from 'react-router-dom';
import AppShell from './components/AppShell';
import LaunchesPage from './pages/LaunchesPage';
import AskPage from './pages/AskPage';
import GraphPage from './pages/GraphPage';
import JurisdictionsPage from './pages/JurisdictionsPage';

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<Navigate to="/ask" replace />} />
        <Route path="/ask" element={<AskPage />} />
        <Route path="/launches" element={<LaunchesPage />} />
        <Route path="/graph" element={<GraphPage />} />
        <Route path="/jurisdictions" element={<JurisdictionsPage />} />
      </Route>
    </Routes>
  );
}

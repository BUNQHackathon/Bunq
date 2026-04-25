import { Routes, Route, Navigate } from 'react-router-dom';
import AppShell from './components/AppShell';
import LaunchesPage from './pages/LaunchesPage';
import AskPage from './pages/AskPage';
import GraphPage from './pages/GraphPage';
import JurisdictionsPage from './pages/JurisdictionsPage';
import LaunchNewPage from './pages/LaunchNewPage';
import LaunchDetailPage from './pages/LaunchDetailPage';
import DataPage from './pages/DataPage';
import DocPage from './pages/DocPage';
import LibraryDocPage from './pages/LibraryDocPage';
import SessionDetailPage from './pages/SessionDetailPage';
import ObligationDetailPage from './pages/ObligationDetailPage';
import ControlDetailPage from './pages/ControlDetailPage';
import { useMode } from './components/ModeToggle';

function ModeAwareRedirect() {
  const [mode] = useMode();
  return <Navigate to={mode === 'regulator' ? '/jurisdictions' : '/launches'} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/" element={<ModeAwareRedirect />} />
        <Route path="/ask" element={<AskPage />} />
        <Route path="/launches" element={<LaunchesPage />} />
        <Route path="/launches/new" element={<LaunchNewPage />} />
        <Route path="/launches/:id" element={<LaunchDetailPage />} />
        <Route path="/graph" element={<GraphPage />} />
        <Route path="/data" element={<DataPage />} />
        <Route path="/doc/:docId" element={<DocPage />} />
        <Route path="/library/:docId" element={<LibraryDocPage />} />
        <Route path="/jurisdictions" element={<JurisdictionsPage />} />
        <Route path="/jurisdictions/:code/launches/:id" element={<GraphPage />} />
        <Route path="/session/:id" element={<SessionDetailPage />} />
        <Route path="/obligation/:id" element={<ObligationDetailPage />} />
        <Route path="/control/:id" element={<ControlDetailPage />} />
      </Route>
    </Routes>
  );
}

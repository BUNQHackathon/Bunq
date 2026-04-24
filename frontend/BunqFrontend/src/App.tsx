import { Routes, Route, Navigate } from 'react-router-dom';
import LaunchesPage from './pages/LaunchesPage';
import AskPage from './pages/AskPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/ask" replace />} />
      <Route path="/ask" element={<AskPage />} />
      <Route path="/launches" element={<LaunchesPage />} />
    </Routes>
  );
}

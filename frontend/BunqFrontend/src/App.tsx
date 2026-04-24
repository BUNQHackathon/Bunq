import { Routes, Route, Navigate } from 'react-router-dom';
import LaunchesPage from './pages/LaunchesPage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/launches" replace />} />
      <Route path="/launches" element={<LaunchesPage />} />
    </Routes>
  );
}

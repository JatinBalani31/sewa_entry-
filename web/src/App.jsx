import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './lib/auth.jsx';
import { LoadingScreen } from './components/ui.jsx';
import Shell from './components/Shell.jsx';
import LoginPage from './pages/Login.jsx';
import MySewaPage from './pages/MySewa.jsx';
import MyHistoryPage from './pages/MyHistory.jsx';
import KioskPage from './pages/Kiosk.jsx';
import DashboardPage from './pages/admin/Dashboard.jsx';
import RegisterPage from './pages/admin/Register.jsx';
import SewadarsPage from './pages/admin/Sewadars.jsx';
import DepartmentsPage from './pages/admin/Departments.jsx';
import ReportsPage from './pages/admin/Reports.jsx';
import KioskSetupPage from './pages/admin/KioskSetup.jsx';

/** Gate for anything behind a sign-in. */
function Protected({ admin = false, children }) {
  const { user, loading, isAdmin } = useAuth();

  if (loading) return <LoadingScreen label="Restoring your session…" />;
  if (!user) return <Navigate to="/login" replace />;
  if (admin && !isAdmin) return <Navigate to="/" replace />;

  return <Shell>{children}</Shell>;
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* The counter tablet runs on its own station token, with no personal
            session — so it deliberately sits outside <Protected>. */}
        <Route path="/kiosk" element={<KioskPage />} />

        <Route path="/" element={<Protected><MySewaPage /></Protected>} />
        <Route path="/history" element={<Protected><MyHistoryPage /></Protected>} />

        <Route path="/admin" element={<Protected admin><DashboardPage /></Protected>} />
        <Route path="/admin/register" element={<Protected admin><RegisterPage /></Protected>} />
        <Route path="/admin/sewadars" element={<Protected admin><SewadarsPage /></Protected>} />
        <Route path="/admin/departments" element={<Protected admin><DepartmentsPage /></Protected>} />
        <Route path="/admin/reports" element={<Protected admin><ReportsPage /></Protected>} />
        <Route path="/admin/kiosk-setup" element={<Protected admin><KioskSetupPage /></Protected>} />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}

import { useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth.jsx';
import { Alert } from './ui.jsx';
import ChangeSecretModal from './ChangeSecretModal.jsx';

const SEWADAR_TABS = [
  { to: '/', end: true, icon: '🙏', label: 'My sewa' },
  { to: '/history', icon: '📋', label: 'History' },
];

const ADMIN_TABS = [
  { to: '/admin', end: true, icon: '📊', label: 'Today' },
  { to: '/admin/register', icon: '✍️', label: 'Register' },
  { to: '/admin/sewadars', icon: '👥', label: 'Sewadars' },
  { to: '/admin/reports', icon: '📤', label: 'Reports' },
];

const ADMIN_LINKS = [
  ...ADMIN_TABS,
  { to: '/admin/departments', icon: '🏷️', label: 'Departments' },
  { to: '/admin/kiosk-setup', icon: '🖥️', label: 'Counter' },
];

export default function Shell({ children }) {
  const { user, logout, isAdmin } = useAuth();
  const navigate = useNavigate();

  const [changingSecret, setChangingSecret] = useState(false);
  const [notice, setNotice] = useState('');

  const signOut = () => {
    logout();
    navigate('/login', { replace: true });
  };

  // An admin needs the admin screens close at hand; a Sewadar only ever needs
  // their own two. The bottom bar holds at most four, which is all a phone
  // has room for — the rest live in the wide-screen top nav.
  const tabs = isAdmin ? ADMIN_TABS : SEWADAR_TABS;
  const links = isAdmin ? [...SEWADAR_TABS, ...ADMIN_LINKS] : SEWADAR_TABS;

  return (
    <div className="shell">
      <header className="topbar">
        <div className="topbar-brand">
          Sewa Entry <small>Attendance</small>
        </div>

        <nav>
          {links.map((link) => (
            <NavLink key={link.to} to={link.to} end={link.end}>{link.label}</NavLink>
          ))}
        </nav>

        <div className="topbar-actions">
          <div className="topbar-who">
            <strong>{user.full_name}</strong>
            <span>{isAdmin ? 'Administrator' : user.department_name || 'Sewadar'} · {user.id}</span>
          </div>
          <button type="button" className="btn-sm" onClick={() => setChangingSecret(true)}>
            {isAdmin ? 'Password' : 'PIN'}
          </button>
          <button type="button" className="btn-sm" onClick={signOut}>Sign out</button>
        </div>
      </header>

      <main className="page">
        {notice && <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>}
        {children}
      </main>

      <nav className="tabbar">
        {tabs.map((tab) => (
          <NavLink key={tab.to} to={tab.to} end={tab.end}>
            <span className="ico" aria-hidden="true">{tab.icon}</span>
            {tab.label}
          </NavLink>
        ))}
      </nav>

      {changingSecret && (
        <ChangeSecretModal
          onClose={() => setChangingSecret(false)}
          onDone={(message) => { setChangingSecret(false); setNotice(message); }}
        />
      )}
    </div>
  );
}

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, tokenStore } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [sewa, setSewa] = useState(null);
  const [loading, setLoading] = useState(Boolean(tokenStore.user));

  // A stored token may have expired while the app was closed, so it is
  // revalidated against the server before anything trusts it.
  useEffect(() => {
    if (!tokenStore.user) {
      setLoading(false);
      return undefined;
    }
    let cancelled = false;
    api.get('/auth/me')
      .then((data) => {
        if (cancelled) return;
        setUser(data.user);
        setSewa(data.sewa);
      })
      .catch(() => {
        tokenStore.user = null;
        if (!cancelled) setUser(null);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, []);

  const login = useCallback(async (identifier, secret) => {
    const data = await api.post('/auth/login', { body: { identifier, secret } });
    tokenStore.user = data.token;
    setUser(data.user);
    // The schedule and tolerance live on /auth/me, so fetch them right after.
    try {
      const me = await api.get('/auth/me');
      setSewa(me.sewa);
    } catch { /* the app still works without the schedule blurb */ }
    return data.user;
  }, []);

  const logout = useCallback(() => {
    tokenStore.user = null;
    setUser(null);
    setSewa(null);
  }, []);

  const value = useMemo(() => ({
    user,
    sewa,
    loading,
    login,
    logout,
    setUser,
    isAdmin: user?.role === 'ADMIN',
  }), [user, sewa, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside an AuthProvider.');
  return context;
}

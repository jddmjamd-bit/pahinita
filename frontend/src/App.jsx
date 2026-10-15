import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { useAppStore } from './store/useAppStore'
import Auth from './pages/Auth'
import Lobby from './pages/Lobby'
import Admin from './pages/Admin'
import Match from './pages/Match'
import Chat from './pages/Chat'
import Finance from './pages/Finance'
import Leaderboard from './pages/Leaderboard'
import Sorteos from './pages/Sorteos'
import Chatbot from './pages/Chatbot'
import Soporte from './pages/Soporte'
import Legal from './pages/Legal'      // ← NUEVO
import Faq from './pages/Faq'

import { useCapacitor } from './hooks/useCapacitor'

const RUTAS_PUBLICAS = ['/auth', '/terminos', '/privacidad'];

function App() {
  const theme = useAppStore(state => state.theme);
  const toggleTheme = useAppStore(state => state.toggleTheme);
  const user = useAppStore(state => state.user);
  const sincronizarSesion = useAppStore(state => state.sincronizarSesion);
  const navigate = useNavigate();
  const { pathname } = useLocation();

  useCapacitor();

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  // Al cargar la app se pide el usuario real al servidor (saldo actual, rol, estado). Sin esto el front arranca con el
  // saldo viejo del localStorage. Si el servidor responde 401 se cierra la sesión (ver sincronizarSesion).
  useEffect(() => {
    sincronizarSesion();
  }, [sincronizarSesion]);

  // Sin usuario no hay nada que mostrar fuera de /auth y las páginas legales (cubre también el cierre por 401).
  useEffect(() => {
    if (!user && !RUTAS_PUBLICAS.includes(pathname)) {
      navigate('/auth', { replace: true });
    }
  }, [user, pathname, navigate]);

  return (
    <>
      <button
        onClick={toggleTheme}
        style={{
          position: 'fixed',
          bottom: '20px',
          right: '20px',
          zIndex: 9999,
          width: '50px',
          height: '50px',
          borderRadius: '50%',
          padding: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: '1.5rem',
          boxShadow: 'var(--shadow-md)',
          background: 'var(--bg-surface-elevated)',
          color: 'var(--text-primary)'
        }}
        title="Cambiar Modo Claro/Oscuro"
      >
        {theme === 'dark' ? '☀️' : '🌙'}
      </button>
      <Routes>
        <Route path="/auth" element={<Auth />} />
        <Route path="/terminos" element={<Legal tipo="terminos" />} />        {/* ← NUEVO */}
        <Route path="/privacidad" element={<Legal tipo="privacidad" />} />   {/* ← NUEVO */}
        <Route path="/" element={<Lobby />}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="chat" element={<Chat />} />
          <Route path="match" element={<Match />} />
          <Route path="finance" element={<Finance />} />
          <Route path="leaderboard" element={<Leaderboard />} />
          <Route path="sorteos" element={<Sorteos />} />
          <Route path="chatbot" element={<Chatbot />} />
          <Route path="soporte" element={<Soporte />} />
          <Route path="faq" element={<Faq />} />
          <Route path="admin" element={<Admin />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export default App

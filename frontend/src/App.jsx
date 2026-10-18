import { Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { useEffect, lazy, Suspense } from 'react'
import { useAppStore } from './store/useAppStore'
import { useCapacitor } from './hooks/useCapacitor'
import Auth from './pages/Auth'
import Lobby from './pages/Lobby'

// F6: Code splitting por ruta (React.lazy) para reducir el bundle inicial
const Admin = lazy(() => import('./pages/Admin'))
const Match = lazy(() => import('./pages/Match'))
const Chat = lazy(() => import('./pages/Chat'))
const Finance = lazy(() => import('./pages/Finance'))
const Leaderboard = lazy(() => import('./pages/Leaderboard'))
const Sorteos = lazy(() => import('./pages/Sorteos'))
const Chatbot = lazy(() => import('./pages/Chatbot'))
const Soporte = lazy(() => import('./pages/Soporte'))
const Legal = lazy(() => import('./pages/Legal'))
const Faq = lazy(() => import('./pages/Faq'))

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
      <Suspense fallback={<div style={{ padding: 24, textAlign: 'center', color: 'var(--text-secondary)' }}>Cargando...</div>}>
        <Routes>
          <Route path="/auth" element={<Auth />} />
          <Route path="/terminos" element={<Legal tipo="terminos" />} />
          <Route path="/privacidad" element={<Legal tipo="privacidad" />} />
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
      </Suspense>
    </>
  )
}

export default App

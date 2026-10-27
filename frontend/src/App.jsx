import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
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
  const sessionReady = useAppStore(state => state.sessionReady);
  const sincronizarSesion = useAppStore(state => state.sincronizarSesion);
  const { pathname } = useLocation();

  useCapacitor();

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  // Al cargar se valida la cookie httpOnly; ningún dato de usuario se hidrata desde localStorage.
  useEffect(() => {
    sincronizarSesion();
  }, [sincronizarSesion]);

  // No se muestra contenido protegido hasta terminar la validación de sesión en el servidor.
  if (!sessionReady && !RUTAS_PUBLICAS.includes(pathname)) {
    return <div role="status" style={{ padding: 24, textAlign: 'center' }}>Verificando sesión…</div>;
  }
  if (sessionReady && !user && !RUTAS_PUBLICAS.includes(pathname)) {
    return <Navigate to="/auth" replace />;
  }

  return (
    <>
      <button 
        onClick={toggleTheme} 
        style={{
          position: 'fixed',
          bottom: '88px',
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

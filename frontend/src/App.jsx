import { Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Component, useEffect, lazy, Suspense } from 'react'
import { useAppStore } from './store/useAppStore'
import { useCapacitor } from './hooks/useCapacitor'
import { limpiarPushVisibles } from './services/browserPush'
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

const RUTAS_PUBLICAS = ['/auth', '/terminos', '/privacidad', '/faq'];

// Evita pantallas vacías en celular si una vista diferida falla al reanudarse o queda un chunk viejo tras desplegar.
class AppErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hayError: false };
  }

  static getDerivedStateFromError() {
    return { hayError: true };
  }

  componentDidCatch() {
    if (typeof window !== 'undefined' && performance.now() > 3000) {
      window.location.reload();
    }
  }

  render() {
    if (this.state.hayError) {
      return (
        <div className="center-screen" style={{ padding: '24px' }}>
          <div style={{
            maxWidth: '380px',
            width: '100%',
            background: 'var(--bg-surface)',
            border: '1px solid var(--border-color)',
            borderRadius: '14px',
            padding: '24px',
            textAlign: 'center',
            boxShadow: 'var(--shadow-md)'
          }}>
            <h2 style={{ margin: '0 0 10px', fontSize: '1.15rem', color: 'var(--text-primary)' }}>
              La vista necesitaba recargarse
            </h2>
            <p style={{ margin: '0 0 18px', fontSize: '0.9rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
              Recarga la página para recuperar la conexión y la versión más reciente.
            </p>
            <button
              type="button"
              onClick={() => window.location.reload()}
              style={{
                width: '100%',
                padding: '10px 16px',
                borderRadius: '10px',
                background: 'var(--accent-primary)',
                color: '#fff',
                fontWeight: 600
              }}
            >
              Recargar página
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

function AppContent() {
  const theme = useAppStore(state => state.theme);
  const toggleTheme = useAppStore(state => state.toggleTheme);
  const user = useAppStore(state => state.user);
  const modoVistaPrevia = useAppStore(state => state.modoVistaPrevia);
  const sessionReady = useAppStore(state => state.sessionReady);
  const sincronizarSesion = useAppStore(state => state.sincronizarSesion);
  const marcarNotificacionLeida = useAppStore(state => state.marcarNotificacionLeida);
  const location = useLocation();
  const navigate = useNavigate();
  const { pathname, search, hash } = location;

  useCapacitor();

  useEffect(() => {
    const manejarNavegacionExterna = (evento) => {
      const ruta = evento?.detail?.ruta;
      if (typeof ruta === 'string' && ruta.startsWith('/')) {
        navigate(ruta);
      }
    };
    window.addEventListener('torneos:navegar', manejarNavegacionExterna);
    return () => window.removeEventListener('torneos:navegar', manejarNavegacionExterna);
  }, [navigate]);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  // Al cargar se valida la cookie httpOnly; ningún dato de usuario se hidrata desde localStorage.
  useEffect(() => {
    sincronizarSesion();
  }, [sincronizarSesion]);

  useEffect(() => {
    void limpiarPushVisibles();
  }, []);

  // Al volver a la pestaña/app móvil evita vista previa congelada (bfcache) o chunks viejos y despierta el socket.
  useEffect(() => {
    const alMostrarPagina = (evento) => {
      if (evento?.persisted) {
        window.location.reload();
      }
    };
    const alFallarPrecarga = (evento) => {
      evento.preventDefault();
      if (performance.now() > 3000) {
        window.location.reload();
      }
    };
    const alCambiarVisibilidad = () => {
      if (document.visibilityState !== 'visible') return;
      const socketActual = useAppStore.getState().socket;
      if (socketActual && !socketActual.connected) {
        socketActual.connect();
      }
    };

    window.addEventListener('pageshow', alMostrarPagina);
    window.addEventListener('vite:preloadError', alFallarPrecarga);
    document.addEventListener('visibilitychange', alCambiarVisibilidad);
    return () => {
      window.removeEventListener('pageshow', alMostrarPagina);
      window.removeEventListener('vite:preloadError', alFallarPrecarga);
      document.removeEventListener('visibilitychange', alCambiarVisibilidad);
    };
  }, []);

  useEffect(() => {
    const id = new URLSearchParams(search).get('notificacionId');
    if (!user?.id || !id || !/^\d+$/.test(id)) return;
    void marcarNotificacionLeida(id);
    const parametros = new URLSearchParams(search);
    parametros.delete('notificacionId');
    navigate({ pathname, search: parametros.toString() ? `?${parametros}` : '', hash }, { replace: true });
  }, [hash, marcarNotificacionLeida, navigate, pathname, search, user?.id]);

  // No se muestra contenido protegido hasta terminar la validación de sesión en el servidor.
  if (!sessionReady && !RUTAS_PUBLICAS.includes(pathname)) {
    return <div role="status" style={{ padding: 24, textAlign: 'center' }}>Verificando sesión…</div>;
  }
  if (sessionReady && !user && !RUTAS_PUBLICAS.includes(pathname)) {
    if (!modoVistaPrevia || pathname.startsWith('/admin')) {
      return <Navigate to="/auth" replace />;
    }
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
          {!user && !modoVistaPrevia && <Route path="/faq" element={<Faq />} />}
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

function App() {
  return (
    <AppErrorBoundary>
      <AppContent />
    </AppErrorBoundary>
  )
}

export default App

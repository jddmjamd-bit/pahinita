import { Routes, Route, Navigate } from 'react-router-dom'
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

function App() {
  const theme = useAppStore(state => state.theme);
  const toggleTheme = useAppStore(state => state.toggleTheme);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

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
        <Route path="/" element={<Lobby />}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="chat" element={<Chat />} />
          <Route path="match" element={<Match />} />
          <Route path="finance" element={<Finance />} />
          <Route path="leaderboard" element={<Leaderboard />} />
          <Route path="sorteos" element={<Sorteos />} />
          <Route path="admin" element={<Admin />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export default App

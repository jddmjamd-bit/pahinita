import { Routes, Route, Navigate } from 'react-router-dom'
import Auth from './pages/Auth'
import Lobby from './pages/Lobby'
import Admin from './pages/Admin'
import Match from './pages/Match'
import Chat from './pages/Chat'
import Finance from './pages/Finance'
import Leaderboard from './pages/Leaderboard'
import Sorteos from './pages/Sorteos'

function App() {
  return (
    <Routes>
      <Route path="/auth" element={<Auth />} />
      <Route path="/lobby" element={<Lobby />} />
      <Route path="/admin" element={<Admin />} />
      <Route path="/match" element={<Match />} />
      <Route path="/chat" element={<Chat />} />
      <Route path="/finance" element={<Finance />} />
      <Route path="/leaderboard" element={<Leaderboard />} />
      <Route path="/sorteos" element={<Sorteos />} />
      <Route path="/" element={<Navigate to="/auth" replace />} />
    </Routes>
  )
}

export default App

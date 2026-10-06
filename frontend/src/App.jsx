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
  )
}

export default App

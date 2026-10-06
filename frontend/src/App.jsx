import { Routes, Route, Navigate } from 'react-router-dom'
import Auth from './pages/Auth'
import Lobby from './pages/Lobby'
import Admin from './pages/Admin'

function App() {
  return (
    <Routes>
      <Route path="/auth" element={<Auth />} />
      <Route path="/lobby" element={<Lobby />} />
      <Route path="/admin" element={<Admin />} />
      <Route path="/" element={<Navigate to="/auth" replace />} />
    </Routes>
  )
}

export default App

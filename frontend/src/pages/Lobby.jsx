import React, { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import './Lobby.css';

export default function Lobby() {
    const navigate = useNavigate();
    const location = useLocation();
    const currentUser = JSON.parse(localStorage.getItem('currentUser')) || { username: 'Usuario', saldo: 0, estado: 'normal' };
    const [isMobileMenuOpen, setMobileMenuOpen] = useState(false);
    const [clashMenuOpen, setClashMenuOpen] = useState(false);

    const handleLogout = async () => {
        // TODO: F3 logout logic
        localStorage.removeItem('currentUser');
        navigate('/auth');
    };

    const isActive = (path) => location.pathname === path ? 'active' : '';

    const handleNavigation = (path) => {
        navigate(path);
        setMobileMenuOpen(false);
    };

    return (
        <div className="discord-layout">
            <button className="mobile-menu-btn" onClick={() => setMobileMenuOpen(!isMobileMenuOpen)}>
                ☰
            </button>
            
            {isMobileMenuOpen && (
                <div className="mobile-overlay" onClick={() => setMobileMenuOpen(false)}></div>
            )}

            <aside className={`sidebar ${isMobileMenuOpen ? 'open' : ''}`}>
                <div className="profile-box">
                    <h3>{currentUser.username}</h3>
                    <div className="status-indicator">
                        <span className="status-dot"></span>
                        <span>{currentUser.estado === 'normal' ? 'Libre' : 'Jugando'}</span>
                    </div>
                    <p className="money">${currentUser.saldo.toLocaleString()}</p>
                    
                    <button className="mini-btn" onClick={() => handleNavigation('/finance')}>+ Recargar</button>
                    <button className="mini-btn" style={{background: '#ed4245', marginTop: 5}} onClick={() => handleNavigation('/finance')}>- Retirar</button>
                    {currentUser.tipo_suscripcion === 'admin' && (
                        <button className="mini-btn admin-btn" onClick={() => handleNavigation('/admin')} style={{marginTop: 5}}>👑 Admin</button>
                    )}
                </div>
                
                <div className="channels-list">
                    <div className={`channel ${isActive('/chat')}`} onClick={() => handleNavigation('/chat')}># 💬 Chat / Anuncios</div>
                    <div className="category">JUEGOS</div>
                    
                    <div className="dropdown-group">
                        <div className="channel category-title" onClick={() => setClashMenuOpen(!clashMenuOpen)}>
                            <span>⚔️ CLASH ROYALE</span>
                            <span>{clashMenuOpen ? '▲' : '▼'}</span>
                        </div>
                        
                        {clashMenuOpen && (
                            <div className="submenu">
                                <div className={`channel sub-channel ${isActive('/match')}`} onClick={() => handleNavigation('/match')}>⚔️ Jugar / Privada</div>
                                <div className={`channel sub-channel ${isActive('/sorteos')}`} onClick={() => handleNavigation('/sorteos')}>🎁 Sorteos</div>
                            </div>
                        )}
                    </div>
                    
                    <div className={`channel ${isActive('/leaderboard')}`} onClick={() => handleNavigation('/leaderboard')}>🏆 Rankings</div>
                </div>
                
                <button className="logout-btn" onClick={handleLogout}>Salir</button>
            </aside>

            <main className="chat-area">
                <Outlet />
            </main>
        </div>
    );
}

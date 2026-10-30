import React, { useState, useEffect } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { activarPushNavegador, cerrarPushBusqueda } from '../services/browserPush';
import './Lobby.css';

export default function Lobby() {
    const navigate = useNavigate();
    const location = useLocation();
    
    const currentUser = useAppStore(state => state.user) || { username: 'Usuario', saldo: 0, estado: 'normal' };
    const logout = useAppStore(state => state.logout);
    const connectSocket = useAppStore(state => state.connectSocket);
    const disconnectSocket = useAppStore(state => state.disconnectSocket);
    const matchState = useAppStore(state => state.matchState);
    const socket = useAppStore(state => state.socket);
    const notificaciones = useAppStore(state => state.notificaciones);
    const notificacionesSinLeer = useAppStore(state => state.notificacionesSinLeer);
    const usuariosOnline = useAppStore(state => state.usuariosOnline);
    const cargarNotificaciones = useAppStore(state => state.cargarNotificaciones);
    const marcarCategoriaLeida = useAppStore(state => state.marcarCategoriaLeida);
    const marcarNotificacionLeida = useAppStore(state => state.marcarNotificacionLeida);
    const descartarNotificacion = useAppStore(state => state.descartarNotificacion);
    const setPushToken = useAppStore(state => state.setPushToken);
    const inMatch = matchState.inMatch;
    const esNavegadorWeb = !window.Capacitor?.isNativePlatform?.()
        && window.location?.protocol !== 'capacitor:';

    // Al encontrar partida (o restaurarla) el usuario va a la sala, esté donde esté. Solo al cambiar a "en partida":
    // después puede moverse libremente por la app.
    useEffect(() => {
        if (inMatch && location.pathname !== '/match') navigate('/match');
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [inMatch]);

    useEffect(() => {
        if (currentUser && currentUser.username !== 'Usuario') {
            connectSocket();
        } else {
            disconnectSocket();
        }
    }, [currentUser, connectSocket, disconnectSocket]);

    const [isMobileMenuOpen, setMobileMenuOpen] = useState(false);
    const [clashMenuOpen, setClashMenuOpen] = useState(false);
    const [pushEstado, setPushEstado] = useState('');

    useEffect(() => {
        if (!currentUser?.id) return;
        void cargarNotificaciones();
        if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            void activarPushNavegador(currentUser.id).then(resultado => {
                if (resultado.token) setPushToken(resultado.token);
            });
        }
    }, [currentUser?.id, cargarNotificaciones, setPushToken]);

    useEffect(() => {
        const categoria = location.pathname === '/chat' ? 'chat'
            : location.pathname === '/soporte' ? 'soporte'
                : location.pathname === '/chatbot' ? 'asistente'
                    : location.pathname === '/leaderboard' ? 'ranking'
                        : location.pathname === '/sorteos' ? 'sorteos'
                            : location.pathname === '/match' ? 'partida'
                                : location.pathname === '/finance' ? 'finanzas' : '';
        if (categoria) void marcarCategoriaLeida(categoria);
    }, [location.pathname, currentUser?.id, marcarCategoriaLeida]);

    useEffect(() => {
        if (!socket || !esNavegadorWeb) return undefined;
        const alEliminar = datos => {
            if (datos?.eventoClave) void cerrarPushBusqueda(datos.eventoClave);
        };
        socket.on('notificacion_eliminada', alEliminar);
        return () => socket.off('notificacion_eliminada', alEliminar);
    }, [esNavegadorWeb, socket]);

    const activarPush = async () => {
        setPushEstado('Activando…');
        const resultado = await activarPushNavegador(currentUser?.id, true);
        if (resultado.token) setPushToken(resultado.token);
        const mensajes = {
            activo: 'Avisos del navegador activados.',
            rechazado: 'El permiso está bloqueado en la configuración del navegador.',
            'sin-configuracion': 'Falta configurar Firebase Web Push en el despliegue.',
            'no-disponible': 'Este navegador no admite avisos push.',
            pendiente: 'Permite los avisos para activarlos.',
            error: 'No se pudieron activar los avisos.'
        };
        setPushEstado(mensajes[resultado.estado] || mensajes.error);
    };

    const notificacionAbierta = (aviso) => {
        void marcarNotificacionLeida(aviso.id);
        navigate(aviso.ruta || '/chat');
    };

    const punto = (categoria) => Number(notificacionesSinLeer[categoria] || 0) > 0
        ? <span className="notification-dot" aria-label={`${notificacionesSinLeer[categoria]} avisos nuevos`} />
        : null;
    const avisosEnVivo = notificaciones.filter(aviso => !aviso.leida_en).slice(0, 6);

    const handleLogout = async () => {
        logout();
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
                        <span>{matchState.status === 'searching'
                            ? 'Buscando partida'
                            : matchState.inMatch && matchState.status === 'playing'
                                ? 'Jugando'
                                : matchState.inMatch
                                    ? 'En sala'
                                    : currentUser.estado === 'normal' ? 'Libre' : 'Disponible'}</span>
                    </div>
                    <p className="money">${currentUser.saldo.toLocaleString()}</p>
                    
                    <button className="mini-btn" onClick={() => handleNavigation('/finance')}>+ Recargar</button>
                    <button className="mini-btn" style={{background: '#ed4245', marginTop: 5}} onClick={() => handleNavigation('/finance')}>- Retirar</button>
                    {esNavegadorWeb && (
                        <div className="browser-push-control">
                            <button className="mini-btn" onClick={activarPush}>🔔 Activar avisos</button>
                            {pushEstado && <small role="status">{pushEstado}</small>}
                        </div>
                    )}
                    {currentUser.tipo_suscripcion === 'admin' && (
                        <button className="mini-btn admin-btn" onClick={() => handleNavigation('/admin')} style={{marginTop: 5}}>👑 Admin</button>
                    )}
                </div>
                
                <div className="channels-list">
                    <div className={`channel ${isActive('/chat')}`} onClick={() => handleNavigation('/chat')}>
                        <span># 💬 Chat / Anuncios</span>{punto('chat')}
                    </div>
                    <div className="category">JUEGOS</div>
                    
                    <div className="dropdown-group">
                        <div className="channel category-title" onClick={() => setClashMenuOpen(!clashMenuOpen)}>
                            <span>⚔️ CLASH ROYALE</span>
                            <span>{clashMenuOpen ? '▲' : '▼'}</span>
                        </div>
                        
                        {clashMenuOpen && (
                            <div className="submenu">
                                <div className={`channel sub-channel ${isActive('/match')}`} onClick={() => handleNavigation('/match')}>
                                    <span>⚔️ Jugar / Privada</span>{punto('partida')}
                                </div>
                                <div className={`channel sub-channel ${isActive('/sorteos')}`} onClick={() => handleNavigation('/sorteos')}>
                                    <span>🎁 Sorteos</span>{punto('sorteos')}
                                </div>
                            </div>
                        )}
                    </div>
                    
                    <div className={`channel ${isActive('/leaderboard')}`} onClick={() => handleNavigation('/leaderboard')}>
                        <span>🏆 Rankings</span>{punto('ranking')}
                    </div>
                    <div className={`channel ${isActive('/chatbot')}`} onClick={() => handleNavigation('/chatbot')}>
                        <span>🤖 Asistente</span>{punto('asistente')}
                    </div>
                    <div className={`channel ${isActive('/faq')}`} onClick={() => handleNavigation('/faq')}>❓ Ayuda / FAQ</div>
                    <div className={`channel ${isActive('/soporte')}`} onClick={() => handleNavigation('/soporte')}>
                        <span>✉️ Hablar con el admin</span>{punto('soporte')}
                    </div>
                </div>

                <div className="online-count"><span className="status-dot" /> {usuariosOnline} usuarios en línea</div>
                
                <button className="logout-btn" onClick={handleLogout}>Salir</button>
            </aside>

            <main className="chat-area">
                <Outlet />
            </main>

            {avisosEnVivo.length > 0 && (
                <section className="live-notifications" aria-label="Avisos nuevos" aria-live="polite">
                    {avisosEnVivo.map(aviso => (
                        <article className="live-notification" key={aviso.id}>
                            <button className="live-notification-open" onClick={() => notificacionAbierta(aviso)}>
                                <strong>{aviso.titulo}</strong>
                                <span>{aviso.mensaje}</span>
                            </button>
                            <button className="live-notification-dismiss" onClick={() => void descartarNotificacion(aviso.id)}
                                aria-label="Eliminar aviso">×</button>
                        </article>
                    ))}
                </section>
            )}
        </div>
    );
}

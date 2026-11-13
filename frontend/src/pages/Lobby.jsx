import React, { useState, useEffect, useCallback } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { registrarPushNavegador, cerrarPushBusqueda } from '../services/browserPush';
import {
    calcularRestanteSegundos,
    formatearTiempoReloj,
    parsearDatosAviso
} from '../utils/tiempoMatchmaking';
import './Lobby.css';

// Solo la notificación que está encima (activa) muestra y corre el temporizador de 5 s.
// Las demás esperan sin contador; al quedar encima retoman con los segundos que les quedaban.
function NotificacionEnVivo({ aviso, activa, onExpire, onOpen, onDismiss }) {
    const [restantes, setRestantes] = useState(5);
    const [ahoraMs, setAhoraMs] = useState(() => Date.now());
    const datosAviso = parsearDatosAviso(aviso?.datos);
    const segAceptarTotal = Number(datosAviso.segundosRestantesAceptar ?? datosAviso.expiraEnSegundos ?? 0);
    const tiempoAceptar = aviso?.categoria === 'partida' && (segAceptarTotal > 0 || Number(datosAviso.expiraEnEpochMs) > 0)
        ? calcularRestanteSegundos(segAceptarTotal, aviso?.recibidoEn, ahoraMs, datosAviso.expiraEnEpochMs)
        : 0;

    useEffect(() => {
        if (!activa) return undefined;
        const timer = setInterval(() => {
            setRestantes(actual => Math.max(0, actual - 1));
            setAhoraMs(Date.now());
        }, 1000);
        return () => clearInterval(timer);
    }, [activa]);

    useEffect(() => {
        if (activa && restantes <= 0) onExpire(aviso.id);
    }, [activa, restantes, aviso.id, onExpire]);

    return (
        <article className="live-notification" key={aviso.id}>
            <button className="live-notification-open" onClick={() => onOpen(aviso)}>
                <strong>{aviso.titulo}</strong>
                <span>{aviso.mensaje}</span>
                {tiempoAceptar > 0 && (
                    <small className="live-notification-accept-left">
                        ⏱ Te quedan {formatearTiempoReloj(tiempoAceptar)} para aceptar
                    </small>
                )}
            </button>
            {activa && (
                <span className="live-notification-countdown" aria-label={`Se oculta en ${restantes} segundos`}>
                    {restantes}s
                </span>
            )}
            <button className="live-notification-dismiss" onClick={() => onDismiss(aviso.id)}
                aria-label="Eliminar aviso">×</button>
        </article>
    );
}

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
    const retoRecibido = useAppStore(state => state.retoRecibido);
    const setRetoRecibido = useAppStore(state => state.setRetoRecibido);
    const cargarNotificaciones = useAppStore(state => state.cargarNotificaciones);
    const cargarEstadoMatchmaking = useAppStore(state => state.cargarEstadoMatchmaking);
    const marcarCategoriaLeida = useAppStore(state => state.marcarCategoriaLeida);
    const marcarNotificacionLeida = useAppStore(state => state.marcarNotificacionLeida);
    const descartarNotificacion = useAppStore(state => state.descartarNotificacion);
    const setPushToken = useAppStore(state => state.setPushToken);
    const inMatch = matchState.inMatch;
    const enZonaPruebas = Boolean(
        currentUser?.en_zona_pruebas
        || currentUser?.zona_activa === 'prueba'
        || currentUser?.tipo_suscripcion === 'prueba'
    );
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
    const [avisosOcultos, setAvisosOcultos] = useState(() => new Set());
    const [retoAhoraMs, setRetoAhoraMs] = useState(() => Date.now());

    useEffect(() => {
        if (!retoRecibido) return undefined;
        const timer = setInterval(() => setRetoAhoraMs(Date.now()), 1000);
        return () => clearInterval(timer);
    }, [retoRecibido]);

    const retoRestanteSeg = retoRecibido
        ? calcularRestanteSegundos(
            retoRecibido.expiraEnSegundos ?? retoRecibido.segundosRestantesAceptar ?? 90,
            retoRecibido.recibidoEn,
            retoAhoraMs,
            retoRecibido.expiraEnEpochMs
        )
        : 0;

    useEffect(() => {
        if (!currentUser?.id) return;
        void cargarNotificaciones();
        void cargarEstadoMatchmaking();
        if (esNavegadorWeb && typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            void registrarPushNavegador(currentUser.id).then(resultado => {
                if (resultado.token) setPushToken(resultado.token);
            });
        }
    }, [currentUser?.id, cargarNotificaciones, cargarEstadoMatchmaking, esNavegadorWeb, setPushToken]);

    useEffect(() => {
        const categoria = location.pathname === '/soporte' ? 'soporte'
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

    const notificacionAbierta = (aviso) => {
        void marcarNotificacionLeida(aviso.id);
        navigate(aviso.ruta || '/chat');
    };

    const expirarNotificacion = useCallback((id) => {
        setAvisosOcultos(actuales => new Set(actuales).add(Number(id)));
        void descartarNotificacion(id);
    }, [descartarNotificacion]);

    const avisosEnVivo = notificaciones
        .filter(aviso => !aviso.leida_en && aviso.categoria !== 'chat' && !avisosOcultos.has(Number(aviso.id)))
        .slice(0, 6);

    const punto = (categoria) => Number(notificacionesSinLeer[categoria] || 0) > 0
        ? <span className="notification-dot" aria-label={`${notificacionesSinLeer[categoria]} avisos nuevos`} />
        : null;
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
                    {(enZonaPruebas || currentUser.tipo_suscripcion === 'admin') && (
                        <div className={`zone-badge ${enZonaPruebas ? 'is-test' : 'is-normal'}`}>
                            {enZonaPruebas ? '🧪 Zona de Pruebas' : '🟢 Zona Normal'}
                        </div>
                    )}
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
                    {currentUser.tipo_suscripcion === 'admin' && (
                        <button className="mini-btn admin-btn" onClick={() => handleNavigation('/admin')} style={{marginTop: 5}}>👑 Admin</button>
                    )}
                </div>
                
                <div className="channels-list">
                    <div className={`channel ${isActive('/chat')}`} onClick={() => handleNavigation('/chat')}>
                        <span># 💬 Chat / Anuncios</span>{punto('chat')}
                    </div>
                    <div className={`channel ${isActive('/match')}`} onClick={() => handleNavigation('/match')}>
                        <span>⚔️ Jugar / Privada</span>{punto('partida')}
                    </div>
                    <div className={`channel ${isActive('/sorteos')}`} onClick={() => handleNavigation('/sorteos')}>
                        <span>🎁 Sorteos</span>{punto('sorteos')}
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
                {enZonaPruebas && (
                    <div className="test-zone-banner" role="status">
                        <span>
                            🧪 <strong>Copia Temporal de Pruebas</strong> — Historial, saldo y partidas aislados en la base de pruebas.
                        </span>
                        {currentUser.tipo_suscripcion === 'admin' && location.pathname !== '/admin' && (
                            <button type="button" className="test-zone-banner-btn" onClick={() => handleNavigation('/admin')}>
                                Gestionar zona
                            </button>
                        )}
                    </div>
                )}
                <Outlet />
            </main>

            {retoRecibido && (
                <div className="mm-challenge-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="mm-reto-titulo">
                    <div className="mm-challenge-modal">
                        <h3 id="mm-reto-titulo">
                            {retoRecibido.esAutomatico ? '⚠️ Emparejamiento con rival superior' : '⚠️ Rival superior quiere jugar contra ti'}
                        </h3>
                        <p>
                            <strong>{retoRecibido.retadorUsername}</strong> es mejor que tú
                            {retoRecibido.etiquetaNivel ? ` (${retoRecibido.etiquetaNivel})` : ''}.
                        </p>
                        <p className="mm-challenge-question">¿Aun así quieres jugar contra él?</p>
                        <p className="mm-challenge-countdown">
                            ⏳ Tiempo para aceptar: <strong>{formatearTiempoReloj(retoRestanteSeg)}</strong>
                        </p>
                        <div className="mm-challenge-actions">
                            <button
                                type="button"
                                className="mm-challenge-accept"
                                onClick={() => {
                                    socket?.emit('responder_reto_matchmaking', {
                                        challengeId: retoRecibido.challengeId,
                                        acepta: true
                                    });
                                    setRetoRecibido(null);
                                }}
                            >
                                ✅ Sí, jugar igual
                            </button>
                            <button
                                type="button"
                                className="mm-challenge-decline"
                                onClick={() => {
                                    socket?.emit('responder_reto_matchmaking', {
                                        challengeId: retoRecibido.challengeId,
                                        acepta: false
                                    });
                                    setRetoRecibido(null);
                                }}
                            >
                                ❌ No, seguir buscando
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {avisosEnVivo.length > 0 && (
                <section className="live-notifications" aria-label="Avisos nuevos" aria-live="polite">
                    {avisosEnVivo.map((aviso, indice) => (
                        <NotificacionEnVivo
                            key={aviso.id}
                            aviso={aviso}
                            activa={indice === 0}
                            onExpire={expirarNotificacion}
                            onOpen={notificacionAbierta}
                            onDismiss={descartarNotificacion}
                        />
                    ))}
                </section>
            )}
        </div>
    );
}

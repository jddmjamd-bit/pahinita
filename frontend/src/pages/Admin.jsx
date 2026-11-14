import React, { useState, useEffect } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import AdminChatbot from './AdminChatbot';
import AdminSoporte from './AdminSoporte';
import './Admin.css';

const CATEGORIAS_COMISION = [
    { clave: 'ganancia', campoUsuario: 'ganancia_generada', icono: '💰', nombre: 'Ganancia' },
    { clave: 'sorteos', campoUsuario: 'gen_sorteos', icono: '🎰', nombre: 'Sorteos' },
    { clave: 'misiones', campoUsuario: 'gen_misiones', icono: '📋', nombre: 'Misiones' },
    { clave: 'logros', campoUsuario: 'gen_logros', icono: '🏅', nombre: 'Logros' },
    { clave: 'leaderboard', campoUsuario: 'gen_leaderboard', icono: '🏆', nombre: 'Leaderboard' },
    { clave: 'devolucion', campoUsuario: 'gen_devolucion', icono: '🔄', nombre: 'Devolución' },
    { clave: 'referidos', campoUsuario: 'gen_referidos', icono: '👥', nombre: 'Referidos' }
];

const formatearMonto = (valor) => Number(valor ?? 0).toLocaleString('es-CO', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
});

export default function Admin() {
    const [view, setView] = useState('transactions'); // 'transactions', 'stats', 'chatbot', 'soporte' o 'clash-verifications'
    
    // Transactions & Disputes
    const [transactions, setTransactions] = useState([]);
    const [disputes, setDisputes] = useState([]);
    const [loadingTx, setLoadingTx] = useState(false);
    
    // Stats
    const [stats, setStats] = useState(null);
    const [loadingStats, setLoadingStats] = useState(false);
    // D4: porcentaje de la comisión por categoría, tal como lo define el servidor (ComisionService)
    const [distribucion, setDistribucion] = useState({});

    // Form inputs for disputes
    const [disputeResolutions, setDisputeResolutions] = useState({});

    // Verificación manual de tags Clash Royale
    const [verificacionesClash, setVerificacionesClash] = useState([]);
    const [loadingVerificacionesClash, setLoadingVerificacionesClash] = useState(false);

    // Zona de pruebas (copia temporal respaldada por TEST_DATABASE_URL)
    const [testZoneData, setTestZoneData] = useState(null);
    const [loadingTestZone, setLoadingTestZone] = useState(false);
    const [switchingZone, setSwitchingZone] = useState(false);
    const [resettingTestZone, setResettingTestZone] = useState(false);
    const [updatingSubUserId, setUpdatingSubUserId] = useState(null);
    const [filtroUsuariosPrueba, setFiltroUsuariosPrueba] = useState('');

    const currentUser = useAppStore(state => state.user);
    const recargarZonaActiva = useAppStore(state => state.recargarZonaActiva);
    const zonaActivaAdmin = currentUser?.zona_activa
        || (currentUser?.en_zona_pruebas ? 'prueba' : (currentUser?.zona_admin || 'normal'));
    const enZonaPruebasAdmin = zonaActivaAdmin === 'prueba';

    useEffect(() => {
        if (currentUser?.tipo_suscripcion === 'admin') {
            if (view === 'transactions') {
                cargarTransaccionesAdmin();
                cargarDisputasAdmin();
            } else if (view === 'stats') {
                cargarEstadisticasAdmin();
            } else if (view === 'clash-verifications') {
                cargarVerificacionesClash();
            } else if (view === 'test-zone') {
                cargarZonaPruebasAdmin();
            } else {
                setVerificacionesClash([]);
            }
        }
    }, [view, zonaActivaAdmin]);

    if (!currentUser || currentUser.tipo_suscripcion !== 'admin') {
        return <div style={{padding: 20, color: 'white', textAlign: 'center'}}>Acceso denegado.</div>;
    }

    // S3: las rutas /api/admin/* exigen la sesión (cookie httpOnly). llamarApi siempre envía la cookie y no lanza;
    // un 401/403 llega como { ok: false, data: { error } } y no se confunde con una lista vacía.
    const cargarTransaccionesAdmin = async () => {
        setLoadingTx(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/transactions');
            if (!ok || !Array.isArray(data)) {
                console.error(data?.error || 'No se pudieron cargar las transacciones');
                setTransactions([]);
                return;
            }
            setTransactions(data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingTx(false);
        }
    };

    const cargarDisputasAdmin = async () => {
        try {
            const { ok, data } = await llamarApi('/api/admin/disputes');
            if (!ok || !Array.isArray(data)) {
                console.error(data?.error || 'No se pudieron cargar las disputas');
                setDisputes([]);
                return;
            }
            setDisputes(data);
            
            // Init resolution state
            const initialRes = {};
            data.forEach(d => {
                initialRes[d.id] = { ganador: d.jugador1, culpable: 'nadie' };
            });
            setDisputeResolutions(initialRes);
        } catch (e) {
            console.error(e);
        }
    };

    const procesarTransaccionAdmin = async (id, act) => {
        if (!window.confirm(`¿${act === 'approve' ? 'Aprobar' : 'Rechazar'} esta solicitud?`)) return;
        try {
            const { ok, data: d } = await llamarApi('/api/admin/transaction/process', {
                method: 'POST',
                body: JSON.stringify({ transId: id, action: act })
            });
            if (!ok || !d || d.error) return alert('Error: ' + (d?.error || 'No se pudo procesar'));
            
            setTransactions(prev => prev.filter(t => t.id !== id));
        } catch (e) {
            alert('Error de conexión');
        }
    };

    const resolverDisputa = async (matchId) => {
        const resolution = disputeResolutions[matchId];
        if (!resolution) return;

        if (!window.confirm(`SENTENCIA:\n\n🏆 Gana: ${resolution.ganador}\n💀 Culpable: ${resolution.culpable}\n\n¿Confirmar?`)) return;

        try {
            const { ok, data: d } = await llamarApi('/api/admin/resolve-dispute', {
                method: 'POST', 
                body: JSON.stringify({
                    matchId,
                    ganadorNombre: resolution.ganador,
                    culpableNombre: resolution.culpable
                })
            });
            if (!ok || !d || d.error) return alert('Error: ' + (d?.error || 'No se pudo aplicar la sentencia'));
            alert("Sentencia aplicada.");
            cargarDisputasAdmin();
        } catch (e) {
            console.error(e);
            alert("Error");
        }
    };

    const handleDisputeChange = (matchId, field, value) => {
        setDisputeResolutions(prev => ({
            ...prev,
            [matchId]: { ...prev[matchId], [field]: value }
        }));
    };

    const cargarDistribucionComisiones = async () => {
        const { ok, data } = await llamarApi('/api/comisiones/distribucion');
        if (ok && data?.success) {
            setDistribucion(data.distribucion || {});
        }
    };

    const cargarEstadisticasAdmin = async () => {
        setLoadingStats(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/stats');
            if (!ok || !data || data.error) {
                console.error(data?.error || 'No se pudieron cargar las estadísticas');
                return;
            }
            setStats(data);
            cargarDistribucionComisiones();
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingStats(false);
        }
    };

    const cargarVerificacionesClash = async () => {
        setLoadingVerificacionesClash(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/clash-verifications');
            if (!ok || !Array.isArray(data)) {
                console.error(data?.error || 'No se pudieron cargar las verificaciones');
                setVerificacionesClash([]);
                return;
            }
            setVerificacionesClash(data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingVerificacionesClash(false);
        }
    };

    const copiarTokenVerificacion = async (token) => {
        try {
            await navigator.clipboard.writeText(token);
            alert('Token copiado. Úsalo para comprobar el tag con el bot de Discord.');
        } catch (e) {
            window.prompt('Copia el token para comprobarlo con el bot de Discord:', token);
        }
    };

    const marcarTagVerificado = async (verificacion) => {
        if (!window.confirm(`¿Ya comprobaste ${verificacion.player_tag} con el bot de Discord y quieres habilitar sus retiros?`)) return;
        try {
            const { ok, data } = await llamarApi('/api/admin/clash-verifications/confirm', {
                method: 'POST',
                body: JSON.stringify({ userId: verificacion.id })
            });
            if (!ok || data?.error) return alert('Error: ' + (data?.error || 'No se pudo confirmar la cuenta'));
            setVerificacionesClash(prev => prev.filter(item => item.id !== verificacion.id));
            alert('Cuenta verificada. El token fue eliminado.');
        } catch (e) {
            alert('Error de conexión');
        }
    };

    const cargarZonaPruebasAdmin = async () => {
        setLoadingTestZone(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/test-zone');
            if (!ok || !data || data.error) {
                console.error(data?.error || 'No se pudo cargar la zona de pruebas');
                return;
            }
            setTestZoneData(data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingTestZone(false);
        }
    };

    const cambiarZonaAdmin = async (nuevaZona) => {
        if (switchingZone || nuevaZona === zonaActivaAdmin) return;
        setSwitchingZone(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/test-zone/switch', {
                method: 'POST',
                body: JSON.stringify({ zona: nuevaZona })
            });
            if (!ok || !data?.success) {
                alert('Error: ' + (data?.error || 'No se pudo cambiar de zona'));
                return;
            }
            await recargarZonaActiva();
            if (view === 'test-zone') {
                await cargarZonaPruebasAdmin();
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión al cambiar de zona');
        } finally {
            setSwitchingZone(false);
        }
    };

    const cambiarSuscripcionUsuario = async (usuario, nuevaSuscripcion) => {
        if (!usuario?.id || updatingSubUserId) return;
        const etiquetaDestino = nuevaSuscripcion === 'prueba' ? 'prueba (Copia Temporal)' : 'free (Zona Normal)';
        if (!window.confirm(`¿Cambiar la suscripción de ${usuario.username} a '${etiquetaDestino}'?`)) return;
        setUpdatingSubUserId(usuario.id);
        try {
            const { ok, data } = await llamarApi('/api/admin/users/subscription', {
                method: 'POST',
                body: JSON.stringify({
                    userId: usuario.id,
                    tipoSuscripcion: nuevaSuscripcion
                })
            });
            if (!ok || !data?.success) {
                alert('Error: ' + (data?.error || 'No se pudo actualizar la suscripción'));
                return;
            }
            setTestZoneData(prev => prev ? {
                ...prev,
                usuarios: Array.isArray(prev.usuarios)
                    ? prev.usuarios.map(u => Number(u.id) === Number(usuario.id)
                        ? { ...u, tipo_suscripcion: nuevaSuscripcion }
                        : u)
                    : []
            } : prev);
            setStats(prev => prev ? {
                ...prev,
                listaUsuarios: Array.isArray(prev.listaUsuarios)
                    ? prev.listaUsuarios.map(u => Number(u.id) === Number(usuario.id)
                        ? { ...u, tipo_suscripcion: nuevaSuscripcion }
                        : u)
                    : []
            } : prev);
            if (view === 'test-zone') {
                await cargarZonaPruebasAdmin();
            }
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        } finally {
            setUpdatingSubUserId(null);
        }
    };

    const reiniciarDatosZonaPruebas = async () => {
        if (resettingTestZone) return;
        if (!window.confirm(
            '⚠️ ¿Borrar TODOS los datos de la Copia Temporal de Pruebas?\n\n'
            + '• Se vaciarán partidas, chat, recargas/retiros, sorteos, notificaciones y soporte de la base de pruebas.\n'
            + '• Los saldos y estadísticas de prueba volverán a $0.\n'
            + '• La base de datos principal (Zona Normal) NO se tocará.'
        )) return;
        setResettingTestZone(true);
        try {
            const { ok, data } = await llamarApi('/api/admin/test-zone/reset', {
                method: 'POST'
            });
            if (!ok || !data?.success) {
                alert('Error: ' + (data?.error || 'No se pudo reiniciar la zona de pruebas'));
                return;
            }
            if (data.resumen) {
                setTestZoneData(prev => prev ? { ...prev, resumen: data.resumen } : prev);
            }
            await recargarZonaActiva();
            await cargarZonaPruebasAdmin();
            if (enZonaPruebasAdmin && view === 'stats') {
                await cargarEstadisticasAdmin();
            }
            alert(data.mensaje || 'Datos de la zona de pruebas eliminados.');
        } catch (e) {
            console.error(e);
            alert('Error de conexión');
        } finally {
            setResettingTestZone(false);
        }
    };

    const usuariosFiltradosPrueba = (testZoneData?.usuarios || []).filter(u => {
        const q = filtroUsuariosPrueba.trim().toLowerCase();
        if (!q) return true;
        return (u.username || '').toLowerCase().includes(q)
            || (u.email || '').toLowerCase().includes(q)
            || (u.tipo_suscripcion || '').toLowerCase().includes(q);
    });

    return (
        <div className="admin-container">
            <header className="admin-header">
                <h2>👑 Panel Admin</h2>
                <div className="admin-tabs">
                    <button className={`admin-tab ${view === 'transactions' ? 'active' : ''}`} onClick={() => setView('transactions')}>💸 Pagos / Disputas</button>
                    <button className={`admin-tab ${view === 'stats' ? 'active' : ''}`} onClick={() => setView('stats')}>📊 Finanzas</button>
                    <button className={`admin-tab ${view === 'test-zone' ? 'active' : ''}`} onClick={() => setView('test-zone')}>🧪 Zona de Pruebas</button>
                    <button className={`admin-tab ${view === 'chatbot' ? 'active' : ''}`} onClick={() => setView('chatbot')}>🤖 Chatbot</button>
                    <button className={`admin-tab ${view === 'soporte' ? 'active' : ''}`} onClick={() => setView('soporte')}>🎫 Soporte</button>
                    <button className={`admin-tab ${view === 'clash-verifications' ? 'active' : ''}`} onClick={() => setView('clash-verifications')}>⚔️ Verificar Clash</button>
                </div>
            </header>

            <section className={`admin-zone-bar ${enZonaPruebasAdmin ? 'is-test' : 'is-normal'}`} aria-label="Selector de zona del administrador">
                <div className="admin-zone-info">
                    <span className="admin-zone-pill">
                        {enZonaPruebasAdmin ? '🧪 Operando en: ZONA DE PRUEBAS (Copia Temporal)' : '🟢 Operando en: ZONA NORMAL (Producción)'}
                    </span>
                    <small className="admin-zone-desc">
                        {enZonaPruebasAdmin
                            ? 'Todo lo que hagas o apruebes ahora usa la base de datos de pruebas JUnit (TEST_DATABASE_URL) y sus salas/chats aislados.'
                            : 'Estás viendo y operando sobre la base de datos principal de producción.'}
                    </small>
                </div>
                <div className="admin-zone-actions">
                    <button
                        type="button"
                        className={`zone-switch-btn ${!enZonaPruebasAdmin ? 'active-normal' : ''}`}
                        disabled={switchingZone || !enZonaPruebasAdmin}
                        onClick={() => cambiarZonaAdmin('normal')}
                    >
                        🟢 Zona Normal
                    </button>
                    <button
                        type="button"
                        className={`zone-switch-btn ${enZonaPruebasAdmin ? 'active-test' : ''}`}
                        disabled={switchingZone || enZonaPruebasAdmin}
                        onClick={() => cambiarZonaAdmin('prueba')}
                    >
                        🧪 Zona de Pruebas
                    </button>
                </div>
            </section>

            {view === 'transactions' && (
                <div className="admin-view">
                    <div className="admin-section">
                        <div className="section-header">
                            <h3>👑 Transacciones (Recargas / Retiros)</h3>
                            <button className="refresh-btn" onClick={cargarTransaccionesAdmin}>🔄 Actualizar</button>
                        </div>
                        <div className="admin-list">
                            {loadingTx ? <p>Cargando...</p> : transactions.length === 0 ? <p className="empty">Nada pendiente.</p> : (
                                transactions.map(t => {
                                    const esRetiro = t.tipo === 'retiro';
                                    const color = esRetiro ? '#ed4245' : '#43b581';
                                    return (
                                        <div key={t.id} className="trans-item">
                                            <div className="trans-info">
                                                <strong style={{color}}>{esRetiro ? '💸 RETIRO' : '💰 RECARGA'}</strong><br/>
                                                Usuario: <strong>{t.usuario_nombre}</strong><br/>
                                                Monto: <span style={{color, fontSize:'1.1em'}}>${t.monto}</span><br/>
                                                <span style={{fontSize:'0.8em', color:'#bbb'}}>{t.referencia}</span>
                                            </div>
                                            <div className="trans-actions">
                                                <button className="btn-approve" onClick={() => procesarTransaccionAdmin(t.id, 'approve')}>✅</button>
                                                <button className="btn-reject" onClick={() => procesarTransaccionAdmin(t.id, 'reject')}>❌</button>
                                            </div>
                                        </div>
                                    );
                                })
                            )}
                        </div>
                    </div>

                    <div className="admin-section" style={{marginTop: 30}}>
                        <div className="section-header">
                            <h3>🚨 Disputas Activas</h3>
                            <button className="refresh-btn" onClick={cargarDisputasAdmin}>🔄 Actualizar</button>
                        </div>
                        <div className="admin-list">
                            {disputes.length === 0 ? <p className="empty">Sin disputas.</p> : (
                                disputes.map(m => (
                                    <div key={m.id} className="dispute-item">
                                        <div className="dispute-info">
                                            <strong>Partida #{m.id}</strong>: <span style={{color:'#4ecca3'}}>{m.jugador1}</span> vs <span style={{color:'#ed4245'}}>{m.jugador2}</span><br/>
                                            Monto: ${m.monto}
                                        </div>
                                        <div className="dispute-controls">
                                            <div className="dispute-field">
                                                <label>GANADOR (Recibe $):</label>
                                                <select value={disputeResolutions[m.id]?.ganador || m.jugador1} onChange={e => handleDisputeChange(m.id, 'ganador', e.target.value)}>
                                                    <option value={m.jugador1}>{m.jugador1}</option>
                                                    <option value={m.jugador2}>{m.jugador2}</option>
                                                </select>
                                            </div>
                                            <div className="dispute-field">
                                                <label>CULPABLE (Falta):</label>
                                                <select value={disputeResolutions[m.id]?.culpable || 'nadie'} onChange={e => handleDisputeChange(m.id, 'culpable', e.target.value)} style={{borderColor: '#ed4245'}}>
                                                    <option value="nadie">-- Nadie --</option>
                                                    <option value={m.jugador1}>{m.jugador1}</option>
                                                    <option value={m.jugador2}>{m.jugador2}</option>
                                                </select>
                                            </div>
                                        </div>
                                        <button className="btn-resolve" onClick={() => resolverDisputa(m.id)}>⚖️ DICTAR SENTENCIA</button>
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
            )}

            {view === 'chatbot' && <AdminChatbot />}
            {view === 'soporte' && <AdminSoporte />}

            {view === 'clash-verifications' && (
                <div className="admin-view">
                    <div className="section-header">
                        <h3>⚔️ Verificación manual de Clash Royale</h3>
                        <button className="refresh-btn" onClick={cargarVerificacionesClash}>🔄 Actualizar</button>
                    </div>
                    <p className="clash-verification-help">
                        Copia cada token y compruébalo con el bot de Discord. Después de confirmar el resultado aquí, el token se elimina y se habilitan los retiros.
                    </p>
                    <div className="admin-list">
                        {loadingVerificacionesClash ? <p>Cargando...</p> : verificacionesClash.length === 0 ? (
                            <p className="empty">No hay cuentas pendientes.</p>
                        ) : verificacionesClash.map(verificacion => (
                            <div key={verificacion.id} className="clash-verification-item">
                                <div>
                                    <strong>{verificacion.username}</strong> · {verificacion.player_tag}
                                    <div className="clash-verification-token">{verificacion.token}</div>
                                </div>
                                <div className="clash-verification-actions">
                                    <button className="refresh-btn" onClick={() => copiarTokenVerificacion(verificacion.token)}>Copiar token</button>
                                    <button className="btn-approve" onClick={() => marcarTagVerificado(verificacion)}>✅ Marcar verificada</button>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {view === 'stats' && (
                <div className="admin-view">
                    <div className="section-header">
                        <h3>📊 Contabilidad del Dueño</h3>
                        <button className="refresh-btn" onClick={cargarEstadisticasAdmin}>🔄 Actualizar</button>
                    </div>

                    {loadingStats || !stats ? <p>Cargando estadísticas...</p> : (
                        <>
                            <div className="stats-summary">
                                <div className="stat-box">
                                    <small>Dinero en Circulación (Usuarios)</small>
                                    <h2 className="stat-amount-warning">${formatearMonto(stats.totalUsuarios)}</h2>
                                </div>
                                <div className="stat-box stat-box-success">
                                    <small>TOTAL HISTÓRICO (Comisiones · 7 categorías)</small>
                                    <h2 className="stat-amount-success">${formatearMonto(stats.totalGanancias)}</h2>
                                </div>
                                <div className="stat-box stat-box-primary">
                                    <small>GANANCIA ADMIN (Sobrantes de rankings)</small>
                                    <h2 className="stat-amount-primary">${formatearMonto(stats.gananciaAdminLeaderboard)}</h2>
                                </div>
                            </div>

                            <section className="admin-earnings-zone" aria-label="Resumen combinado de ganancias del admin">
                                <div className="admin-earnings-header">
                                    <div>
                                        <h4>💎 Ganancias del Admin (Separadas + Suma Total)</h4>
                                        <p className="admin-earnings-subtitle">
                                            Cada apartado mantiene su registro propio sin descontar ni alterar al otro.
                                        </p>
                                    </div>
                                    <span className="admin-earnings-badge">Cuentas independientes</span>
                                </div>
                                <div className="admin-earnings-grid">
                                    <div className="admin-earning-card">
                                        <span className="admin-earning-label">
                                            💰 Ganancia por Partidas{distribucion.ganancia !== undefined ? ` (${distribucion.ganancia}%)` : ''}
                                        </span>
                                        <strong className="admin-earning-value val-green">
                                            ${formatearMonto(stats.gananciaComisionesAdmin ?? stats.desglose?.ganancia)}
                                        </strong>
                                        <small className="admin-earning-note">Solo categoría Ganancia de comisiones</small>
                                    </div>
                                    <div className="admin-earning-operator" aria-hidden="true">+</div>
                                    <div className="admin-earning-card">
                                        <span className="admin-earning-label">🏆 Sobrantes de Rankings</span>
                                        <strong className="admin-earning-value val-primary">
                                            ${formatearMonto(stats.gananciaAdminLeaderboard)}
                                        </strong>
                                        <small className="admin-earning-note">Pagos acreditados al cerrar rankings</small>
                                    </div>
                                    <div className="admin-earning-operator" aria-hidden="true">=</div>
                                    <div className="admin-earning-card is-total">
                                        <span className="admin-earning-label">👑 Total Combinado Admin</span>
                                        <strong className="admin-earning-value val-gold">
                                            ${formatearMonto(stats.gananciaTotalCombinadaAdmin)}
                                        </strong>
                                        <small className="admin-earning-note">Suma de ambas ganancias del dueño</small>
                                    </div>
                                </div>
                            </section>

                            <section className="admin-earnings-zone" aria-label="Resumen del sistema de referidos">
                                <div className="admin-earnings-header">
                                    <div>
                                        <h4>👥 Sistema de Referidos (Categoría Referidos)</h4>
                                        <p className="admin-earnings-subtitle">
                                            Jugadores sin referidor acreditan esta parte al admin; los referidos reparten entre referidor y cashback configurado.
                                        </p>
                                    </div>
                                    <span className="admin-earnings-badge">
                                        {stats.resumenReferidos?.totalRelaciones ?? 0} referidos vinculados
                                    </span>
                                </div>
                                <div className="admin-earnings-grid">
                                    <div className="admin-earning-card">
                                        <span className="admin-earning-label">🏦 Admin (Sin referidor)</span>
                                        <strong className="admin-earning-value val-green">
                                            ${formatearMonto(stats.gananciaAdminReferidos)}
                                        </strong>
                                        <small className="admin-earning-note">Acreditado directo al saldo del admin</small>
                                    </div>
                                    <div className="admin-earning-card">
                                        <span className="admin-earning-label">🤝 Pagado a Referidores</span>
                                        <strong className="admin-earning-value val-primary">
                                            ${formatearMonto(stats.resumenReferidos?.totalPagadoReferidores)}
                                        </strong>
                                        <small className="admin-earning-note">
                                            Devuelto a referidos: ${formatearMonto(stats.resumenReferidos?.totalDevueltoReferidos)}
                                        </small>
                                    </div>
                                    <div className="admin-earning-card is-total">
                                        <span className="admin-earning-label">💎 Total Admin con Referidos</span>
                                        <strong className="admin-earning-value val-gold">
                                            ${formatearMonto(stats.gananciaTotalConReferidosAdmin ?? stats.gananciaTotalCombinadaAdmin)}
                                        </strong>
                                        <small className="admin-earning-note">Comisiones + Rankings + Referidos sin referidor</small>
                                    </div>
                                </div>
                            </section>

                            <h4 className="admin-subsection-title">Desglose de Comisiones</h4>
                            <div className="admin-category-breakdown">
                                {Object.entries(stats.desglose || {}).map(([cat, monto]) => {
                                    const meta = CATEGORIAS_COMISION.find(c => c.clave === cat);
                                    const icono = meta ? meta.icono : '📌';
                                    const nombre = meta ? meta.nombre : cat;
                                    return (
                                        <div key={cat} className="breakdown-box">
                                            <div className="bd-icon">{icono}</div>
                                            <div className="bd-title">{nombre + (distribucion[cat] !== undefined ? ` (${distribucion[cat]}%)` : '')}</div>
                                            <div className="bd-actual">Actual: ${formatearMonto(monto)}</div>
                                            <div className="bd-hist">Histórico: ${formatearMonto(monto)}</div>
                                        </div>
                                    );
                                })}
                            </div>

                            <h4>Usuarios Registrados</h4>
                            <div className="admin-list users-list">
                                {stats.listaUsuarios?.map(u => (
                                    <div key={u.id} className="user-card">
                                        <div className="user-header-row">
                                            <div className="user-basic">
                                                <span className="user-title-line">
                                                    {u.tipo_suscripcion === 'admin' ? '👑' : u.tipo_suscripcion === 'prueba' ? '🧪' : '👤'}{' '}
                                                    <strong>{u.username}</strong>{' '}
                                                    <span className={`sub-badge sub-${u.tipo_suscripcion || 'free'}`}>
                                                        {u.tipo_suscripcion || 'free'}
                                                    </span>
                                                </span>
                                                <span className="user-email">{u.email}</span>
                                                {u.tipo_suscripcion !== 'admin' && (
                                                    <div className="user-sub-actions">
                                                        <button
                                                            type="button"
                                                            className="sub-toggle-btn"
                                                            disabled={updatingSubUserId === u.id}
                                                            onClick={() => cambiarSuscripcionUsuario(
                                                                u,
                                                                u.tipo_suscripcion === 'prueba' ? 'free' : 'prueba'
                                                            )}
                                                        >
                                                            {u.tipo_suscripcion === 'prueba'
                                                                ? '🟢 Pasar a Normal (free)'
                                                                : '🧪 Pasar a Copia de Pruebas (prueba)'}
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                            <div className="user-financials">
                                                <div className="user-balance-line">Saldo: <span className="val-green">${formatearMonto(u.saldo)}</span></div>
                                                <div className="user-total-contrib-line">Total comisiones (÷2): <span className="val-gold">+${formatearMonto(u.comision_total_aportada)}</span></div>
                                            </div>
                                        </div>
                                        <div className="user-contributions">
                                            <div className="user-contributions-title">
                                                Aporte por categoría (dividido entre 2 por jugador)
                                            </div>
                                            <div className="user-contributions-grid">
                                                {CATEGORIAS_COMISION.map(cat => (
                                                    <div
                                                        key={cat.clave}
                                                        className={`user-contrib-item ${cat.clave === 'ganancia' ? 'is-ganancia' : ''}`}
                                                    >
                                                        <span className="user-contrib-name">
                                                            {cat.icono} {cat.nombre}
                                                            {distribucion[cat.clave] !== undefined ? ` (${distribucion[cat.clave]}%)` : ''}
                                                        </span>
                                                        <strong className="user-contrib-amount">
                                                            +${formatearMonto(u[cat.campoUsuario])}
                                                        </strong>
                                                    </div>
                                                ))}
                                            </div>
                                        </div>
                                        <div className="stats-grid">
                                            <div className="stat-item"><span className="stat-label">PARTIDAS</span><span className="stat-val">{u.total_partidas || 0}</span></div>
                                            <div className="stat-item"><span className="stat-label">VICTORIAS</span><span className="stat-val val-green">{u.total_victorias || 0}</span> <span className="stat-subdetail">({u.victorias_normales}/{u.victorias_disputa})</span></div>
                                            <div className="stat-item"><span className="stat-label">DERROTAS</span><span className="stat-val val-red">{u.total_derrotas || 0}</span> <span className="stat-subdetail">({u.derrotas_normales}/{u.derrotas_disputa})</span></div>
                                            <div className="stat-item"><span className="stat-label">FALTAS (JUEZ)</span><span className="stat-val val-red">{u.faltas || 0}</span></div>
                                            <div className="stat-item"><span className="stat-label">HUIDAS TOTALES</span><span className="stat-val val-gold">{u.salidas_chat || 0}</span></div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </>
                    )}
                </div>
            )}

            {view === 'test-zone' && (
                <div className="admin-view">
                    <div className="section-header">
                        <h3>🧪 Copia Temporal / Zona de Pruebas (Base JUnit)</h3>
                        <button className="refresh-btn" onClick={cargarZonaPruebasAdmin}>🔄 Actualizar</button>
                    </div>

                    <div className="test-zone-explainer">
                        <p>
                            Los usuarios con suscripción <code>prueba</code> entran automáticamente a esta copia temporal respaldada por la base de datos de pruebas de JUnit (<code>TEST_DATABASE_URL</code>). Su saldo, historial de chat, partidas, transacciones, sorteos y rankings funcionan de forma totalmente independiente de producción.
                        </p>
                        <p>
                            <strong>Nota:</strong> El cambio de <code>tipo_suscripcion</code> a <code>prueba</code> (o de regreso a <code>free</code>) está restringido exclusivamente a este panel de administración. Los datos de prueba se conservan hasta que uses el botón de borrado manual.
                        </p>
                    </div>

                    {loadingTestZone || !testZoneData ? (
                        <p>Cargando estado de la zona de pruebas...</p>
                    ) : (
                        <>
                            <div className="stats-summary">
                                <div className="stat-box">
                                    <small>Usuarios en Copia</small>
                                    <h2 className="stat-amount-warning">{testZoneData.resumen?.usuarios_en_copia ?? 0}</h2>
                                </div>
                                <div className="stat-box">
                                    <small>Partidas de Prueba</small>
                                    <h2 className="stat-amount-primary">{testZoneData.resumen?.matches ?? 0}</h2>
                                </div>
                                <div className="stat-box">
                                    <small>Mensajes de Chat</small>
                                    <h2 className="stat-amount-success">{testZoneData.resumen?.messages ?? 0}</h2>
                                </div>
                                <div className="stat-box">
                                    <small>Transacciones / Sorteos</small>
                                    <h2 className="stat-amount-warning">
                                        {(testZoneData.resumen?.transactions ?? 0)} / {(testZoneData.resumen?.raffles ?? 0)}
                                    </h2>
                                </div>
                            </div>

                            <div className="test-zone-reset-card">
                                <div>
                                    <h4>🗑️ Reiniciar / Borrar datos de la Zona de Pruebas</h4>
                                    <p>
                                        Vacía el historial de chat, partidas, transacciones, sorteos, notificaciones, soporte y evidencias de la copia temporal y reinicia los saldos/estadísticas de prueba a $0 sin afectar la Zona Normal.
                                    </p>
                                </div>
                                <button
                                    type="button"
                                    className="btn-reject test-zone-reset-btn"
                                    disabled={resettingTestZone}
                                    onClick={reiniciarDatosZonaPruebas}
                                >
                                    {resettingTestZone ? 'Borrando...' : '🗑️ Borrar datos de prueba'}
                                </button>
                            </div>

                            <div className="section-header" style={{ marginTop: 28 }}>
                                <h4>👥 Suscripción de Usuarios (Normal vs Prueba)</h4>
                                <input
                                    type="search"
                                    className="test-zone-search"
                                    placeholder="Buscar por usuario, correo o suscripción..."
                                    value={filtroUsuariosPrueba}
                                    onChange={e => setFiltroUsuariosPrueba(e.target.value)}
                                />
                            </div>

                            <div className="admin-list users-list">
                                {usuariosFiltradosPrueba.length === 0 ? (
                                    <p className="empty">No se encontraron usuarios.</p>
                                ) : usuariosFiltradosPrueba.map(u => {
                                    const esAdmin = u.tipo_suscripcion === 'admin';
                                    const esPrueba = u.tipo_suscripcion === 'prueba';
                                    return (
                                        <div key={u.id} className="test-zone-user-row">
                                            <div className="user-basic">
                                                <span className="user-title-line">
                                                    {esAdmin ? '👑' : esPrueba ? '🧪' : '👤'}{' '}
                                                    <strong>{u.username}</strong>{' '}
                                                    <span className={`sub-badge sub-${u.tipo_suscripcion || 'free'}`}>
                                                        {u.tipo_suscripcion || 'free'}
                                                    </span>
                                                </span>
                                                <span className="user-email">
                                                    {u.email} · Saldo principal: ${formatearMonto(u.saldo)}
                                                </span>
                                            </div>
                                            <div className="test-zone-user-actions">
                                                {esAdmin ? (
                                                    <span className="admin-zone-note">
                                                        Admin (zona actual: <strong>{u.zona_admin || 'normal'}</strong>)
                                                    </span>
                                                ) : (
                                                    <>
                                                        <button
                                                            type="button"
                                                            className={`zone-switch-btn ${!esPrueba ? 'active-normal' : ''}`}
                                                            disabled={updatingSubUserId === u.id || !esPrueba}
                                                            onClick={() => cambiarSuscripcionUsuario(u, 'free')}
                                                        >
                                                            🟢 Normal (free)
                                                        </button>
                                                        <button
                                                            type="button"
                                                            className={`zone-switch-btn ${esPrueba ? 'active-test' : ''}`}
                                                            disabled={updatingSubUserId === u.id || esPrueba}
                                                            onClick={() => cambiarSuscripcionUsuario(u, 'prueba')}
                                                        >
                                                            🧪 Prueba (copia)
                                                        </button>
                                                    </>
                                                )}
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </>
                    )}
                </div>
            )}
        </div>
    );
}

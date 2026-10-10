import React, { useState, useEffect } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Admin.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Admin() {
    const [view, setView] = useState('transactions'); // 'transactions' or 'stats'
    
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

    const currentUser = useAppStore(state => state.user);

    useEffect(() => {
        if (currentUser?.tipo_suscripcion === 'admin') {
            if (view === 'transactions') {
                cargarTransaccionesAdmin();
                cargarDisputasAdmin();
            } else if (view === 'stats') {
                cargarEstadisticasAdmin();
            }
        }
    }, [view]);

    if (!currentUser || currentUser.tipo_suscripcion !== 'admin') {
        return <div style={{padding: 20, color: 'white', textAlign: 'center'}}>Acceso denegado.</div>;
    }

    const cargarTransaccionesAdmin = async () => {
        setLoadingTx(true);
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/transactions`);
            const data = await res.json();
            setTransactions(data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingTx(false);
        }
    };

    const cargarDisputasAdmin = async () => {
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/disputes`);
            const data = await res.json();
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
            const res = await fetch(`${API_BASE_URL}/api/admin/transaction/process`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ transId: id, action: act })
            });
            const d = await res.json();
            if (d.error) return alert('Error: ' + d.error);
            
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
            await fetch(`${API_BASE_URL}/api/admin/resolve-dispute`, {
                method: 'POST', 
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    matchId,
                    ganadorNombre: resolution.ganador,
                    culpableNombre: resolution.culpable
                })
            });
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
        try {
            const res = await fetch(`${API_BASE_URL}/api/comisiones/distribucion`);
            const data = await res.json();
            if (data.success) setDistribucion(data.distribucion || {});
        } catch (e) {
            console.error(e);
        }
    };

    const cargarEstadisticasAdmin = async () => {
        setLoadingStats(true);
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/stats`);
            const data = await res.json();
            setStats(data);
            cargarDistribucionComisiones();
        } catch (e) {
            console.error(e);
        } finally {
            setLoadingStats(false);
        }
    };

    return (
        <div className="admin-container">
            <header className="admin-header">
                <h2>👑 Panel Admin</h2>
                <div className="admin-tabs">
                    <button className={`admin-tab ${view === 'transactions' ? 'active' : ''}`} onClick={() => setView('transactions')}>💸 Pagos / Disputas</button>
                    <button className={`admin-tab ${view === 'stats' ? 'active' : ''}`} onClick={() => setView('stats')}>📊 Finanzas</button>
                </div>
            </header>

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
                                    <h2 style={{color:'#faa61a'}}>${stats.totalUsuarios?.toLocaleString() || 0}</h2>
                                </div>
                                <div className="stat-box" style={{borderColor: '#43b581'}}>
                                    <small>TOTAL HISTÓRICO (Comisiones)</small>
                                    <h2 style={{color:'#43b581'}}>${stats.totalGanancias?.toLocaleString() || 0}</h2>
                                </div>
                            </div>

                            <h4 style={{marginBottom: 10, color: '#bbb'}}>Desglose de Comisiones</h4>
                            <div className="admin-category-breakdown">
                                {Object.entries(stats.desglose || {}).map(([cat, monto]) => {
                                    const iconos = { sorteos: '🎰', misiones: '📋', logros: '🏅', leaderboard: '🏆', devolucion: '🔄', ganancia: '💰', referidos: '👥' };
                                    const nombres = { sorteos: 'Sorteos', misiones: 'Misiones', logros: 'Logros', leaderboard: 'Leaderboard', devolucion: 'Devolución', ganancia: 'Ganancia', referidos: 'Referidos' };
                                    return (
                                        <div key={cat} className="breakdown-box">
                                            <div className="bd-icon">{iconos[cat] || '📌'}</div>
                                            <div className="bd-title">{(nombres[cat] || cat) + (distribucion[cat] !== undefined ? ` (${distribucion[cat]}%)` : '')}</div>
                                            <div className="bd-actual">Actual: ${monto.toLocaleString()}</div>
                                            <div className="bd-hist">Histórico: ${monto.toLocaleString()}</div>
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
                                                <span style={{fontSize:'1.1rem'}}>{u.tipo_suscripcion === 'admin' ? '👑' : '👤'} <strong>{u.username}</strong></span><br/>
                                                <span style={{color:'#bbb', fontSize:'0.8rem'}}>{u.email}</span>
                                            </div>
                                            <div className="user-financials">
                                                <div>Saldo: <span style={{color:'#4ecca3'}}>${u.saldo?.toLocaleString() || 0}</span></div>
                                                <div style={{fontSize:'0.8rem'}}>Generado: <span style={{color:'#faa61a'}}>+${(u.ganancia_generada || 0).toLocaleString()}</span></div>
                                            </div>
                                        </div>
                                        <div className="stats-grid">
                                            <div className="stat-item"><span className="stat-label">PARTIDAS</span><span className="stat-val">{u.total_partidas || 0}</span></div>
                                            <div className="stat-item"><span className="stat-label">VICTORIAS</span><span className="stat-val val-green">{u.total_victorias || 0}</span> <span style={{fontSize:'0.6em'}}>({u.victorias_normales}/{u.victorias_disputa})</span></div>
                                            <div className="stat-item"><span className="stat-label">DERROTAS</span><span className="stat-val val-red">{u.total_derrotas || 0}</span> <span style={{fontSize:'0.6em'}}>({u.derrotas_normales}/{u.derrotas_disputa})</span></div>
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
        </div>
    );
}

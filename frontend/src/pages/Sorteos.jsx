import React, { useState, useEffect } from 'react';
import './Sorteos.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Sorteos() {
    const [userTickets, setUserTickets] = useState(0);
    const [userAcumulado, setUserAcumulado] = useState(0);
    const [poolAmount, setPoolAmount] = useState(0);
    const [sorteos, setSorteos] = useState([]);
    const [encuesta, setEncuesta] = useState([]);
    const [miVoto, setMiVoto] = useState(null);
    const [loading, setLoading] = useState(true);

    const currentUser = JSON.parse(localStorage.getItem('currentUser')) || null;
    const esAdmin = currentUser && currentUser.tipo_suscripcion === 'admin';

    // Formulario crear sorteo
    const [showCreateModal, setShowCreateModal] = useState(false);
    const [newNombre, setNewNombre] = useState('');
    const [newCategoria, setNewCategoria] = useState('gemas');
    const [newPrecio, setNewPrecio] = useState('');
    const [newDuracion, setNewDuracion] = useState('');

    const categoriasEmoji = {
        'gemas': '💎', 'pass': '👑', 'evoluciones': '✨',
        'emotes': '😄', 'cartas': '🃏', 'comodines': '🎴', 'especial': '🎁'
    };
    const categoriasEncuesta = {
        'gemas': '💎 Gemas', 'pass': '👑 Pass Royale', 'evoluciones': '✨ Evoluciones',
        'emotes': '😄 Emotes', 'cartas': '🃏 Cartas', 'comodines': '🎴 Comodines', 'especial': '🎁 Oferta Especial'
    };

    const cargarSorteos = async () => {
        if (!currentUser) return;
        try {
            // Tickets
            const ticketsRes = await fetch(`${API_BASE_URL}/api/raffle/tickets/${currentUser.id}`, { credentials: 'include' });
            if(ticketsRes.ok) {
                const ticketsData = await ticketsRes.json();
                setUserTickets(ticketsData.tickets || 0);
                setUserAcumulado(ticketsData.acumulado || 0);
            }

            // Pool
            try {
                const poolRes = await fetch(`${API_BASE_URL}/api/raffle/pool`, { credentials: 'include' });
                const poolData = await poolRes.json();
                setPoolAmount(poolData.pool || 0);
            } catch (e) { }

            // Encuesta
            await cargarEncuesta();

            // Sorteos activos
            const res = await fetch(`${API_BASE_URL}/api/raffle/offers`, { credentials: 'include' });
            if(res.ok) {
                const data = await res.json();
                setSorteos(data);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const cargarEncuesta = async () => {
        try {
            const res = await fetch(`${API_BASE_URL}/api/raffle/poll`, { credentials: 'include' });
            const data = await res.json();
            setEncuesta(data.resultados || []);
            setMiVoto(data.miVoto);
        } catch (e) { }
    };

    useEffect(() => {
        cargarSorteos();
        
        // TODO: En F3, conectar eventos del socket aquí
        // socket.on('nuevo_sorteo', cargarSorteos)
        // socket.on('sorteo_actualizado', cargarSorteos)
        // socket.on('sorteo_ganador', cargarSorteos)
        // socket.on('sorteo_eliminado', cargarSorteos)
        // socket.on('poll_reset', cargarEncuesta)
        // socket.on('poll_updated', cargarEncuesta)
        // socket.on('tickets_ganados', (data) => setUserTickets(prev => prev + data.cantidad))
    }, []);

    // Countdown effect
    const [now, setNow] = useState(new Date());
    useEffect(() => {
        const interval = setInterval(() => setNow(new Date()), 1000);
        return () => clearInterval(interval);
    }, []);

    const renderCountdown = (fechaLimite) => {
        const fecha = new Date(fechaLimite);
        const diff = fecha - now;
        if (diff <= 0) return <span className="sorteo-countdown urgente">⏰ ¡Expirado!</span>;
        
        const horas = Math.floor(diff / (1000 * 60 * 60));
        const minutos = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
        const segundos = Math.floor((diff % (1000 * 60)) / 1000);

        if (horas > 0) return <span className="sorteo-countdown">⏰ {horas}h {minutos}m</span>;
        if (minutos > 0) return <span className={`sorteo-countdown ${minutos < 5 ? 'urgente' : ''}`}>⏰ {minutos}m {segundos}s</span>;
        return <span className="sorteo-countdown urgente">⏰ {segundos}s</span>;
    };

    const ajustarTickets = async (raffleId, delta) => {
        if (!currentUser) return alert("Debes iniciar sesión");
        try {
            const res = await fetch(`${API_BASE_URL}/api/raffle/participate`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ userId: currentUser.id, raffleId, ticketsDelta: delta })
            });
            const data = await res.json();
            if (!res.ok) return alert(data.error || 'Error al participar');
            setUserTickets(data.ticketsUsuario);
            cargarSorteos();
        } catch (e) {
            alert("Error de conexión");
        }
    };

    const handleCreateSorteo = async (e) => {
        e.preventDefault();
        if (!newNombre || !newPrecio || !newDuracion) return alert("Completa todos los campos");
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/raffle/create`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ nombre: newNombre, categoria: newCategoria, precio: Number(newPrecio), duracionMinutos: Number(newDuracion) })
            });
            const data = await res.json();
            if (data.success) {
                alert(`✅ Sorteo "${newNombre}" creado`);
                setShowCreateModal(false);
                setNewNombre(''); setNewPrecio(''); setNewDuracion('');
                cargarSorteos();
            } else {
                alert(data.error || 'Error creando sorteo');
            }
        } catch (e) { alert("Error de conexión"); }
    };

    const eliminarSorteo = async (id) => {
        if (!window.confirm("¿Eliminar este sorteo? Los tickets serán devueltos.")) return;
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/raffle/${id}`, { method: 'DELETE', credentials: 'include' });
            const data = await res.json();
            if (data.success) { alert(data.message); cargarSorteos(); }
            else alert(data.error);
        } catch (e) { alert("Error eliminando sorteo"); }
    };

    const votarSorteo = async (categoria) => {
        if (!currentUser) return;
        try {
            const res = await fetch(`${API_BASE_URL}/api/raffle/vote`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ userId: currentUser.id, categoria })
            });
            const data = await res.json();
            if (data.error) alert(data.error);
            else cargarEncuesta();
        } catch (e) { console.error(e); }
    };

    const reiniciarEncuesta = async () => {
        if (!window.confirm("¿Estás seguro de reiniciar la encuesta?")) return;
        try {
            const res = await fetch(`${API_BASE_URL}/api/admin/raffle/poll`, { method: 'DELETE', credentials: 'include' });
            const data = await res.json();
            if (data.error) alert(data.error);
            else cargarEncuesta();
        } catch (e) { }
    };

    const progresoSiguiente = Math.min(100, (userAcumulado / 1000) * 100);
    
    let totalVotos = 0;
    const votosPorCat = {};
    encuesta.forEach(r => {
        totalVotos += parseInt(r.votos) || 0;
        votosPorCat[r.categoria] = parseInt(r.votos) || 0;
    });

    return (
        <div className="sorteos-container">
            <header className="sorteos-header">
                <div className="raffle-pool-box">
                    <span>Pozo Acumulado</span>
                    <h2 id="raffle-pool-amount">${poolAmount.toLocaleString()}</h2>
                </div>
                <div className="user-tickets-box">
                    <span>Mis Tickets 🎟️</span>
                    <h2 id="user-tickets-count">{userTickets}</h2>
                    <div className="next-ticket-progress">
                        <div className="nt-bar"><div className="nt-fill" style={{width: `${progresoSiguiente}%`}}></div></div>
                        <span className="nt-text">${Math.round(userAcumulado)} / $1000</span>
                    </div>
                </div>
            </header>

            {esAdmin && (
                <div className="sorteos-admin-controls">
                    <button onClick={() => setShowCreateModal(true)}>➕ Crear Sorteo</button>
                    <button onClick={reiniciarEncuesta} style={{background:'#ed4245'}}>🔄 Reiniciar Encuesta</button>
                </div>
            )}

            <div className="sorteos-layout">
                <div className="sorteos-main">
                    <h3>🎁 Sorteos Activos</h3>
                    {loading ? <p>Cargando sorteos...</p> : sorteos.length === 0 ? (
                        <p className="no-sorteos">No hay sorteos activos en este momento.<br/>¡Juega partidas para ganar tickets!</p>
                    ) : (
                        <div className="sorteos-list">
                            {sorteos.map(sorteo => {
                                const emoji = categoriasEmoji[sorteo.categoria] || '🎁';
                                const misTickets = sorteo.mis_tickets || 0;
                                const progreso = Math.min(100, (sorteo.tickets_actuales / sorteo.tickets_necesarios) * 100);
                                const esCompletado = sorteo.estado === 'completado';

                                if (esCompletado) {
                                    return (
                                        <div key={sorteo.id} className="sorteo-card sorteo-ganador">
                                            <div className="sorteo-header-card">
                                                <div>
                                                    <span className="sorteo-nombre">{emoji} {sorteo.nombre}</span>
                                                    <span className="sorteo-categoria">{sorteo.categoria}</span>
                                                </div>
                                                {esAdmin && <span className="sorteo-precio">${sorteo.precio.toLocaleString()}</span>}
                                            </div>
                                            <div className="sorteo-ganador-info">
                                                🏆 GANADOR: <strong>{sorteo.ganador_nombre}</strong>
                                            </div>
                                            <div className="ticket-progress">
                                                <div className="ticket-progress-bar">
                                                    <div className="ticket-progress-fill" style={{width: '100%'}}></div>
                                                    <span className="ticket-progress-text">✅ {sorteo.tickets_necesarios} / {sorteo.tickets_necesarios} tickets</span>
                                                </div>
                                            </div>
                                            {esAdmin && <button className="btn-eliminar-sorteo" onClick={() => eliminarSorteo(sorteo.id)}>🗑️ Eliminar</button>}
                                        </div>
                                    );
                                }

                                return (
                                    <div key={sorteo.id} className="sorteo-card nuevo">
                                        <div className="sorteo-header-card">
                                            <div>
                                                <span className="sorteo-nombre">{emoji} {sorteo.nombre}</span>
                                                <span className="sorteo-categoria">{sorteo.categoria}</span>
                                            </div>
                                            {esAdmin && <span className="sorteo-precio">${sorteo.precio.toLocaleString()}</span>}
                                        </div>
                                        <div className="ticket-progress">
                                            <div className="ticket-progress-bar">
                                                <div className="ticket-progress-fill" style={{width: `${progreso}%`}}></div>
                                                <span className="ticket-progress-text">{sorteo.tickets_actuales} / {sorteo.tickets_necesarios} tickets</span>
                                            </div>
                                            <div className="ticket-info">
                                                {renderCountdown(sorteo.fecha_limite)}
                                                <span>Mis tickets: {misTickets}</span>
                                            </div>
                                        </div>
                                        <div className="ticket-controls">
                                            <button className="ticket-btn minus" onClick={() => ajustarTickets(sorteo.id, -1)} disabled={misTickets <= 0}>−</button>
                                            <span className="my-tickets-count">{misTickets} 🎟️</span>
                                            <button className="ticket-btn plus" onClick={() => ajustarTickets(sorteo.id, 1)} disabled={userTickets <= 0 || sorteo.tickets_actuales >= sorteo.tickets_necesarios}>+</button>
                                        </div>
                                        {esAdmin && <button className="btn-eliminar-sorteo" onClick={() => eliminarSorteo(sorteo.id)}>🗑️ Eliminar</button>}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>

                <div className="sorteos-poll">
                    <h3>📊 ¿Qué sorteamos luego?</h3>
                    <p className="poll-desc">Vota por el próximo premio.</p>
                    <div className="poll-list">
                        {Object.keys(categoriasEncuesta).map(cat => {
                            const votos = votosPorCat[cat] || 0;
                            const porcentaje = totalVotos > 0 ? Math.round((votos / totalVotos) * 100) : 0;
                            const isVoted = miVoto === cat;
                            return (
                                <div key={cat} className={`poll-item ${isVoted ? 'voted' : ''}`} onClick={() => votarSorteo(cat)}>
                                    <div className="poll-fill" style={{width: `${porcentaje}%`}}></div>
                                    <div className="poll-content">
                                        <span className="poll-title">{categoriasEncuesta[cat]}</span>
                                        <span className="poll-stats">{porcentaje}% ({votos})</span>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>
            </div>

            {/* Modal Crear Sorteo */}
            {showCreateModal && (
                <div className="modal-overlay">
                    <div className="modal-content">
                        <header className="modal-header">
                            <h3>➕ Crear Sorteo</h3>
                            <button className="close-btn" onClick={() => setShowCreateModal(false)}>&times;</button>
                        </header>
                        <form id="create-raffle-form" onSubmit={handleCreateSorteo}>
                            <label>Nombre del Premio</label>
                            <input type="text" value={newNombre} onChange={e => setNewNombre(e.target.value)} required placeholder="Ej: 500 Gemas" style={{width:'100%', padding:'8px', marginBottom:'10px'}}/>
                            
                            <label>Categoría</label>
                            <select value={newCategoria} onChange={e => setNewCategoria(e.target.value)} style={{width:'100%', padding:'8px', marginBottom:'10px'}}>
                                {Object.entries(categoriasEmoji).map(([k, v]) => <option key={k} value={k}>{v} {k}</option>)}
                            </select>
                            
                            <label>Precio del Premio (COP)</label>
                            <input type="number" value={newPrecio} onChange={e => setNewPrecio(e.target.value)} required min="1000" placeholder="Ej: 20000" style={{width:'100%', padding:'8px', marginBottom:'10px'}}/>
                            <div style={{color:'#7289da', marginBottom:'10px'}}>Tickets necesarios: {Math.ceil(Number(newPrecio)/1000) || 0}</div>
                            
                            <label>Duración (Minutos)</label>
                            <input type="number" value={newDuracion} onChange={e => setNewDuracion(e.target.value)} required min="1" placeholder="Ej: 1440 (24h)" style={{width:'100%', padding:'8px', marginBottom:'15px'}}/>
                            
                            <button type="submit" style={{width:'100%', padding:'10px', background:'#43b581', color:'white', border:'none', borderRadius:'4px'}}>Crear</button>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}

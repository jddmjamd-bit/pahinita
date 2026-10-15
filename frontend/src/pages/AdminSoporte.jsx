import React, { useState, useEffect, useCallback } from 'react';
import { llamarApi } from '../store/llamarApi';
import { etiquetaCategoria, formatearFecha } from './soporteComun';
import './AdminChatbot.css'; // reutiliza .achat-* (tarjetas, textarea, acciones)
import './Soporte.css';      // reutiliza .sop-estado, .sop-meta, .sop-texto, .sop-respuesta

const FILTROS = [
    { id: 'abierto', etiqueta: '⏳ Pendientes' },
    { id: 'resuelto', etiqueta: '✅ Resueltos' },
    { id: 'todos', etiqueta: '📋 Todos' }
];

const MAX_RESPUESTA = 3000;

// Bandeja de tickets de soporte del panel admin (roadmap 24.5). Las rutas /api/admin/* exigen sesión de admin.
export default function AdminSoporte() {
    const [filtro, setFiltro] = useState('abierto');
    const [tickets, setTickets] = useState([]);
    const [cargando, setCargando] = useState(true);
    const [borradores, setBorradores] = useState({}); // respuesta en edición, por id de ticket
    const [ocupado, setOcupado] = useState(false);
    const [error, setError] = useState('');

    const cargar = useCallback(async () => {
        setCargando(true);
        const { ok, data } = await llamarApi(`/api/admin/soporte/tickets?estado=${filtro}`);
        if (!ok || !Array.isArray(data)) {
            setError(data?.error || 'No se pudieron cargar los tickets');
            setTickets([]);
        } else {
            setTickets(data);
            setError('');
        }
        setCargando(false);
    }, [filtro]);

    useEffect(() => { cargar(); }, [cargar]);

    const responder = async (id) => {
        const respuesta = (borradores[id] || '').trim();
        if (!respuesta || ocupado) return;
        setOcupado(true);
        setError('');
        const { ok, data } = await llamarApi(`/api/admin/soporte/tickets/${id}/responder`, {
            method: 'POST',
            body: JSON.stringify({ respuesta })
        });
        setOcupado(false);
        if (!ok || data?.error) {
            setError(data?.error || 'No se pudo enviar la respuesta');
            return;
        }
        setBorradores(prev => { const copia = { ...prev }; delete copia[id]; return copia; });
        cargar();
    };

    return (
        <div className="admin-view achat">
            {error && <div className="achat-error">⚠️ {error}</div>}

            <div className="admin-section">
                <div className="section-header">
                    <h3>🎫 Soporte: consultas de usuarios ({tickets.length})</h3>
                    <button className="refresh-btn" onClick={cargar}>🔄 Actualizar</button>
                </div>

                <div className="achat-tonos" style={{ marginBottom: 14 }}>
                    {FILTROS.map(f => (
                        <button
                            key={f.id}
                            className={`admin-tab ${filtro === f.id ? 'active' : ''}`}
                            onClick={() => setFiltro(f.id)}
                        >
                            {f.etiqueta}
                        </button>
                    ))}
                </div>

                <div className="admin-list">
                    {cargando ? <p>Cargando...</p> : tickets.length === 0 ? (
                        <p className="empty">{filtro === 'abierto' ? 'Nada pendiente.' : 'No hay tickets.'}</p>
                    ) : tickets.map(t => (
                        <div key={t.id} className="achat-card">
                            <div className="achat-pregunta">
                                #{t.id} · {t.asunto}{' '}
                                <span className={`sop-estado ${t.estado === 'resuelto' ? 'resuelto' : 'abierto'}`}>
                                    {t.estado === 'resuelto' ? '✅ Resuelto' : '⏳ Pendiente'}
                                </span>
                            </div>
                            <div className="sop-meta">
                                👤 {t.username} (id {t.user_id}) · {etiquetaCategoria(t.categoria)} · {formatearFecha(t.fecha_creacion)}
                            </div>
                            <div className="sop-texto">{t.mensaje}</div>

                            {t.estado === 'resuelto' ? (
                                <div className="sop-respuesta">
                                    <div className="sop-meta">Tu respuesta · {formatearFecha(t.fecha_respuesta)}</div>
                                    <div className="sop-texto">{t.respuesta}</div>
                                </div>
                            ) : (
                                <>
                                    <textarea
                                        rows={4}
                                        maxLength={MAX_RESPUESTA}
                                        placeholder="Tu respuesta (le llega al correo del usuario y la ve en su panel de soporte)"
                                        value={borradores[t.id] || ''}
                                        onChange={e => setBorradores(prev => ({ ...prev, [t.id]: e.target.value }))}
                                    />
                                    <div className="achat-acciones">
                                        <button
                                            className="btn-approve"
                                            disabled={ocupado || !(borradores[t.id] || '').trim()}
                                            onClick={() => responder(t.id)}
                                        >
                                            ✅ Responder y marcar resuelto
                                        </button>
                                    </div>
                                </>
                            )}
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}

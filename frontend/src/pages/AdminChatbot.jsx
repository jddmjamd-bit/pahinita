import React, { useState, useEffect, useCallback } from 'react';
import { API_BASE_URL } from '../store/llamarApi';
import './AdminChatbot.css';

const TONOS_ADMIN = [
    { id: 'adaptativo', etiqueta: '🪄 Adaptativo', ayuda: 'Imita cómo escribe cada usuario' },
    { id: 'formal', etiqueta: '🎩 Formal', ayuda: 'Trata de usted' },
    { id: 'informal', etiqueta: '😎 Informal', ayuda: 'Cercano, tutea' }
];

// La sesión viaja en la cookie httpOnly: siempre credentials 'include'. Lanza Error con el mensaje del servidor.
async function api(ruta, opciones = {}) {
    const res = await fetch(`${API_BASE_URL}${ruta}`, {
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        ...opciones
    });
    let data = null;
    try { data = await res.json(); } catch (e) { /* respuesta sin cuerpo JSON */ }
    if (!res.ok || (data && data.error)) throw new Error((data && data.error) || 'Error del servidor');
    return data;
}

export default function AdminChatbot() {
    const [config, setConfig] = useState(null);
    const [pendientes, setPendientes] = useState([]);   // preguntas sin responder (de usuarios y de la IA)
    const [conocimiento, setConocimiento] = useState([]); // respondidas = lo que sabe el bot
    const [preguntaIa, setPreguntaIa] = useState(null);   // la pregunta de entrenamiento en curso
    const [avisoEntrenamiento, setAvisoEntrenamiento] = useState('');
    const [respuestaIa, setRespuestaIa] = useState('');
    const [borradores, setBorradores] = useState({});     // respuestas a dudas de usuarios, por id
    const [edicion, setEdicion] = useState(null);         // { id, texto } mientras se corrige una respuesta
    const [nueva, setNueva] = useState({ pregunta: '', respuesta: '' });
    const [ocupado, setOcupado] = useState(false);
    const [error, setError] = useState('');

    const dudasUsuarios = pendientes.filter(p => p.origen === 'usuario');

    const cargarTodo = useCallback(async () => {
        try {
            const [cfg, pend, resp] = await Promise.all([
                api('/api/admin/chatbot/config'),
                api('/api/admin/chatbot/conocimiento?estado=pendiente'),
                api('/api/admin/chatbot/conocimiento?estado=respondida')
            ]);
            setConfig(cfg);
            setPendientes(pend);
            setConocimiento(resp);
            // Si ya había una pregunta de la IA esperando respuesta, se retoma
            const abierta = pend.find(p => p.origen === 'ia');
            setPreguntaIa(abierta || null);
            setError('');
        } catch (e) {
            setError(e.message);
        }
    }, []);

    useEffect(() => { cargarTodo(); }, [cargarTodo]);

    // Envuelve una acción: bloquea botones mientras corre y muestra el error si falla
    const ejecutar = async (accion) => {
        setOcupado(true);
        setError('');
        try {
            await accion();
        } catch (e) {
            setError(e.message);
        } finally {
            setOcupado(false);
        }
    };

    const cambiarTonoDefecto = (tono) => ejecutar(async () => {
        await api('/api/admin/chatbot/config', { method: 'PUT', body: JSON.stringify({ tono }) });
        setConfig(prev => ({ ...prev, tonoDefecto: tono }));
    });

    // --- Entrenamiento: la IA pregunta, el admin responde ---
    const pedirPregunta = async () => {
        const data = await api('/api/admin/chatbot/entrenamiento/siguiente', { method: 'POST' });
        setPreguntaIa(data.pregunta || null);
        setAvisoEntrenamiento(data.pregunta ? '' : (data.mensaje || 'No hay más preguntas por ahora.'));
        setRespuestaIa('');
        const pend = await api('/api/admin/chatbot/conocimiento?estado=pendiente');
        setPendientes(pend);
    };

    const iniciarEntrenamiento = () => ejecutar(pedirPregunta);

    const guardarRespuestaIa = () => ejecutar(async () => {
        if (!preguntaIa || !respuestaIa.trim()) return;
        await api(`/api/admin/chatbot/conocimiento/${preguntaIa.id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta: respuestaIa })
        });
        setConocimiento(await api('/api/admin/chatbot/conocimiento?estado=respondida'));
        await pedirPregunta(); // sigue con la siguiente para que el entrenamiento fluya
    });

    const noAplicaIa = () => ejecutar(async () => {
        if (!preguntaIa) return;
        await api(`/api/admin/chatbot/conocimiento/${preguntaIa.id}/descartar`, { method: 'POST' });
        await pedirPregunta();
    });

    // --- Dudas de usuarios ---
    const responderDuda = (id) => ejecutar(async () => {
        const respuesta = (borradores[id] || '').trim();
        if (!respuesta) return;
        const data = await api(`/api/admin/chatbot/conocimiento/${id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta })
        });
        setBorradores(prev => { const copia = { ...prev }; delete copia[id]; return copia; });
        await cargarTodo();
        if (data.avisados > 0) alert(`Respuesta guardada. Se le avisó a ${data.avisados} usuario(s).`);
    });

    const descartarDuda = (id) => ejecutar(async () => {
        if (!window.confirm('¿Descartar esta duda? No se le responderá al usuario.')) return;
        await api(`/api/admin/chatbot/conocimiento/${id}/descartar`, { method: 'POST' });
        await cargarTodo();
    });

    // --- Base de conocimiento ---
    const guardarEdicion = () => ejecutar(async () => {
        if (!edicion || !edicion.texto.trim()) return;
        await api(`/api/admin/chatbot/conocimiento/${edicion.id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta: edicion.texto })
        });
        setEdicion(null);
        setConocimiento(await api('/api/admin/chatbot/conocimiento?estado=respondida'));
    });

    const eliminar = (id) => ejecutar(async () => {
        if (!window.confirm('¿Borrar esta entrada? El bot dejará de saberla.')) return;
        await api(`/api/admin/chatbot/conocimiento/${id}`, { method: 'DELETE' });
        await cargarTodo();
    });

    const agregarManual = () => ejecutar(async () => {
        if (!nueva.pregunta.trim() || !nueva.respuesta.trim()) return;
        await api('/api/admin/chatbot/conocimiento', { method: 'POST', body: JSON.stringify(nueva) });
        setNueva({ pregunta: '', respuesta: '' });
        setConocimiento(await api('/api/admin/chatbot/conocimiento?estado=respondida'));
    });

    return (
        <div className="admin-view achat">
            {error && <div className="achat-error">⚠️ {error}</div>}

            {/* Estado y tono por defecto */}
            <div className="admin-section">
                <div className="section-header">
                    <h3>⚙️ Ajustes del asistente</h3>
                    <button className="refresh-btn" onClick={cargarTodo}>🔄 Actualizar</button>
                </div>
                {config && (
                    <>
                        <p className="achat-nota">
                            IA: {config.iaDisponible
                                ? <strong style={{ color: '#43b581' }}>conectada</strong>
                                : <strong style={{ color: '#ed4245' }}>sin configurar</strong>}
                            {!config.iaDisponible && ' — define CHATBOT_API_KEY en el servidor. Mientras tanto el entrenamiento usa preguntas base.'}
                        </p>
                        <p className="achat-nota">Tono por defecto (cada usuario puede elegir el suyo desde su chat):</p>
                        <div className="achat-tonos">
                            {TONOS_ADMIN.map(t => (
                                <button
                                    key={t.id}
                                    className={`admin-tab ${config.tonoDefecto === t.id ? 'active' : ''}`}
                                    title={t.ayuda}
                                    disabled={ocupado}
                                    onClick={() => cambiarTonoDefecto(t.id)}
                                >
                                    {t.etiqueta}
                                </button>
                            ))}
                        </div>
                    </>
                )}
            </div>

            {/* Entrenamiento */}
            <div className="admin-section" style={{ marginTop: 30 }}>
                <div className="section-header">
                    <h3>🎓 Entrenar: la IA te pregunta</h3>
                </div>
                {!preguntaIa ? (
                    <>
                        <p className="achat-nota">
                            En vez de darle un documento, responde las preguntas que te haga sobre la página. Lo que respondas
                            queda como conocimiento del bot.
                        </p>
                        {avisoEntrenamiento && <p className="achat-nota">{avisoEntrenamiento}</p>}
                        <button className="btn-resolve" disabled={ocupado} onClick={iniciarEntrenamiento}>
                            {ocupado ? 'Pensando...' : '🤖 Que la IA me pregunte'}
                        </button>
                    </>
                ) : (
                    <div className="achat-card">
                        <div className="achat-pregunta">🤖 {preguntaIa.pregunta}</div>
                        <textarea
                            rows={4}
                            maxLength={1500}
                            placeholder="Tu respuesta (el bot la usará tal cual como fuente)"
                            value={respuestaIa}
                            onChange={e => setRespuestaIa(e.target.value)}
                        />
                        <div className="achat-acciones">
                            <button className="btn-approve" disabled={ocupado || !respuestaIa.trim()} onClick={guardarRespuestaIa}>
                                ✅ Guardar y seguir
                            </button>
                            <button className="btn-reject" disabled={ocupado} onClick={noAplicaIa}>
                                🚫 No aplica
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Dudas de usuarios */}
            <div className="admin-section" style={{ marginTop: 30 }}>
                <div className="section-header">
                    <h3>📬 Dudas de usuarios sin respuesta ({dudasUsuarios.length})</h3>
                </div>
                <div className="admin-list">
                    {dudasUsuarios.length === 0 ? <p className="empty">Ninguna pendiente.</p> : dudasUsuarios.map(d => (
                        <div key={d.id} className="achat-card">
                            <div className="achat-pregunta">
                                ❓ {d.pregunta}
                                {d.veces > 1 && <span className="achat-veces"> · preguntada {d.veces} veces</span>}
                            </div>
                            <textarea
                                rows={3}
                                maxLength={1500}
                                placeholder="Tu respuesta (se guarda en el bot y se le entrega al usuario en su chat)"
                                value={borradores[d.id] || ''}
                                onChange={e => setBorradores(prev => ({ ...prev, [d.id]: e.target.value }))}
                            />
                            <div className="achat-acciones">
                                <button className="btn-approve" disabled={ocupado || !(borradores[d.id] || '').trim()} onClick={() => responderDuda(d.id)}>
                                    ✅ Responder
                                </button>
                                <button className="btn-reject" disabled={ocupado} onClick={() => descartarDuda(d.id)}>
                                    🚫 Descartar
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            </div>

            {/* Base de conocimiento */}
            <div className="admin-section" style={{ marginTop: 30 }}>
                <div className="section-header">
                    <h3>📚 Lo que sabe el bot ({conocimiento.length})</h3>
                </div>

                <div className="achat-card">
                    <div className="achat-pregunta">➕ Agregar conocimiento a mano</div>
                    <input
                        type="text"
                        maxLength={300}
                        placeholder="Pregunta (ej: ¿Cuánto tarda un retiro?)"
                        value={nueva.pregunta}
                        onChange={e => setNueva(prev => ({ ...prev, pregunta: e.target.value }))}
                    />
                    <textarea
                        rows={3}
                        maxLength={1500}
                        placeholder="Respuesta"
                        value={nueva.respuesta}
                        onChange={e => setNueva(prev => ({ ...prev, respuesta: e.target.value }))}
                    />
                    <div className="achat-acciones">
                        <button className="btn-approve" disabled={ocupado || !nueva.pregunta.trim() || !nueva.respuesta.trim()} onClick={agregarManual}>
                            💾 Guardar
                        </button>
                    </div>
                </div>

                <div className="admin-list">
                    {conocimiento.length === 0 ? <p className="empty">Todavía no sabe nada: entrénalo arriba.</p> : conocimiento.map(c => (
                        <div key={c.id} className="achat-card">
                            <div className="achat-pregunta">❓ {c.pregunta}</div>
                            {edicion && edicion.id === c.id ? (
                                <>
                                    <textarea
                                        rows={4}
                                        maxLength={1500}
                                        value={edicion.texto}
                                        onChange={e => setEdicion({ id: c.id, texto: e.target.value })}
                                    />
                                    <div className="achat-acciones">
                                        <button className="btn-approve" disabled={ocupado || !edicion.texto.trim()} onClick={guardarEdicion}>💾 Guardar</button>
                                        <button className="btn-reject" disabled={ocupado} onClick={() => setEdicion(null)}>Cancelar</button>
                                    </div>
                                </>
                            ) : (
                                <>
                                    <div className="achat-respuesta">{c.respuesta}</div>
                                    <div className="achat-acciones">
                                        <button className="refresh-btn" disabled={ocupado} onClick={() => setEdicion({ id: c.id, texto: c.respuesta || '' })}>✏️ Editar</button>
                                        <button className="btn-reject" disabled={ocupado} onClick={() => eliminar(c.id)}>🗑️ Borrar</button>
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

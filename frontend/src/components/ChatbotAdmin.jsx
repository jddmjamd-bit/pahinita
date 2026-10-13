import React, { useState, useEffect, useCallback } from 'react';
import { llamarApi } from '../store/llamarApi';
import './ChatbotAdmin.css';

// Panel admin del chatbot IA (roadmap parte 20). Se usa dentro de la página Admin (pestaña «🤖 Chatbot»).
// El servidor exige tipo_suscripcion = 'admin' en cada endpoint: ocultar la pestaña aquí es solo comodidad.

const SUBPESTANAS = [
    ['dudas', '❓ Dudas de usuarios'],
    ['entrenamiento', '🎓 Entrenamiento'],
    ['conocimiento', '📚 Conocimiento'],
    ['ajustes', '⚙️ Ajustes']
];

const ETIQUETA_TONO = {
    formal: 'Formal (trata de «usted»)',
    informal: 'Informal (tutea, algún emoji)',
    adaptativo: 'Adaptativo (imita cómo escribe cada usuario)'
};

const ETIQUETA_ORIGEN = { ia: 'Pregunta de la IA', usuario: 'Duda de un usuario', admin: 'Agregada por el admin' };

const fechaCorta = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d) ? '' : d.toLocaleString([], { dateStyle: 'short', timeStyle: 'short' });
};

export default function ChatbotAdmin() {
    const [sub, setSub] = useState('dudas');
    const [config, setConfig] = useState(null); // { tonoDefecto, tonos, iaDisponible, dudasPendientes }
    const [aviso, setAviso] = useState(null); // { texto, error }

    const avisar = useCallback((texto, error = false) => {
        setAviso({ texto, error });
        setTimeout(() => setAviso(null), 6000);
    }, []);

    const cargarConfig = useCallback(async () => {
        const r = await llamarApi('/api/admin/chatbot/config');
        if (r.ok) {
            setConfig(r.data);
        } else {
            avisar(r.data?.error || 'No se pudo cargar la configuración del chatbot', true);
        }
    }, [avisar]);

    useEffect(() => {
        cargarConfig();
    }, [cargarConfig]);

    return (
        <div className="cb-admin">
            <div className="section-header">
                <h3>🤖 Chatbot IA</h3>
                <button className="refresh-btn" onClick={cargarConfig}>🔄 Actualizar</button>
            </div>

            {config && (
                <p className="cb-estado">
                    IA: <strong className={config.iaDisponible ? 'cb-ok' : 'cb-mal'}>
                        {config.iaDisponible ? 'conectada' : 'desactivada (falta CHATBOT_API_KEY)'}
                    </strong>
                    {' · '}Dudas de usuarios pendientes: <strong>{config.dudasPendientes}</strong>
                </p>
            )}

            {aviso && <div className={`cb-aviso ${aviso.error ? 'error' : 'ok'}`}>{aviso.texto}</div>}

            <div className="admin-tabs cb-subtabs">
                {SUBPESTANAS.map(([clave, etiqueta]) => (
                    <button key={clave} className={`admin-tab ${sub === clave ? 'active' : ''}`} onClick={() => setSub(clave)}>
                        {etiqueta}
                    </button>
                ))}
            </div>

            {sub === 'dudas' && <Dudas avisar={avisar} alCambiar={cargarConfig} />}
            {sub === 'entrenamiento' && <Entrenamiento avisar={avisar} iaDisponible={config?.iaDisponible} alCambiar={cargarConfig} />}
            {sub === 'conocimiento' && <Conocimiento avisar={avisar} alCambiar={cargarConfig} />}
            {sub === 'ajustes' && <Ajustes avisar={avisar} config={config} alCambiar={cargarConfig} />}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Dudas que la IA no supo resolver y los usuarios dejaron pendientes
// ---------------------------------------------------------------------------
function Dudas({ avisar, alCambiar }) {
    const [dudas, setDudas] = useState(null);
    const [respuestas, setRespuestas] = useState({});

    const cargar = useCallback(async () => {
        const r = await llamarApi('/api/admin/chatbot/conocimiento?estado=pendiente');
        if (!r.ok) {
            avisar(r.data?.error || 'No se pudieron cargar las dudas', true);
            setDudas([]);
            return;
        }
        // Las preguntas pendientes de la IA (origen 'ia') se contestan en «Entrenamiento»
        setDudas((Array.isArray(r.data) ? r.data : []).filter(d => d.origen === 'usuario'));
    }, [avisar]);

    useEffect(() => {
        cargar();
    }, [cargar]);

    const responder = async (id) => {
        const respuesta = (respuestas[id] || '').trim();
        if (!respuesta) return avisar('Escribe la respuesta', true);
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta })
        });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo guardar la respuesta', true);
        avisar(`✅ Respondida. Se le avisó a ${r.data.avisados} usuario(s) y el bot ya lo sabe.`);
        cargar();
        alCambiar();
    };

    const descartar = async (id) => {
        if (!window.confirm('¿Descartar esta duda? No se le responderá a quien la hizo.')) return;
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${id}/descartar`, { method: 'POST' });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo descartar', true);
        cargar();
        alCambiar();
    };

    if (dudas === null) return <p>Cargando...</p>;
    if (dudas.length === 0) return <p className="empty">No hay dudas de usuarios pendientes 🎉</p>;

    return (
        <div className="admin-list">
            {dudas.map(d => (
                <div key={d.id} className="cb-item">
                    <div className="cb-pregunta">{d.pregunta}</div>
                    <div className="cb-meta">
                        Preguntada {d.veces} {d.veces === 1 ? 'vez' : 'veces'} · {fechaCorta(d.fecha_creacion)}
                    </div>
                    <textarea
                        className="cb-textarea"
                        placeholder="Escribe la respuesta (se guarda para entrenar al bot y se envía al usuario)..."
                        maxLength={1500}
                        value={respuestas[d.id] || ''}
                        onChange={(e) => setRespuestas(prev => ({ ...prev, [d.id]: e.target.value }))}
                    />
                    <div className="cb-acciones">
                        <button className="btn-approve" onClick={() => responder(d.id)}>✅ Responder</button>
                        <button className="btn-reject" onClick={() => descartar(d.id)}>🚫 Descartar</button>
                    </div>
                </div>
            ))}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Modo entrenamiento: la IA le hace preguntas al admin
// ---------------------------------------------------------------------------
function Entrenamiento({ avisar, iaDisponible, alCambiar }) {
    const [pregunta, setPregunta] = useState(null);
    const [vacio, setVacio] = useState('');
    const [respuesta, setRespuesta] = useState('');
    const [cargando, setCargando] = useState(false);

    // No se pide sola al abrir la pestaña: cada pregunta nueva puede costar una llamada a la IA
    const siguiente = async () => {
        setCargando(true);
        setVacio('');
        const r = await llamarApi('/api/admin/chatbot/entrenamiento/siguiente', { method: 'POST' });
        setCargando(false);
        if (!r.ok) return avisar(r.data?.error || 'No se pudo obtener la pregunta', true);
        setPregunta(r.data.pregunta || null);
        setVacio(r.data.pregunta ? '' : (r.data.mensaje || 'No hay más preguntas por ahora.'));
        setRespuesta('');
    };

    const responder = async () => {
        if (!respuesta.trim()) return avisar('Escribe la respuesta', true);
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${pregunta.id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta })
        });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo guardar la respuesta', true);
        avisar('✅ Guardado. El asistente ya lo sabe.');
        alCambiar();
        siguiente();
    };

    const noAplica = async () => {
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${pregunta.id}/descartar`, { method: 'POST' });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo descartar', true);
        alCambiar();
        siguiente();
    };

    return (
        <div className="admin-list">
            <p className="cb-ayuda">
                El asistente no se entrena con un documento: aquí te hace una pregunta a la vez sobre la plataforma y
                tus respuestas pasan a su base de conocimiento.
                {iaDisponible === false && ' (Sin IA conectada usará preguntas base predefinidas.)'}
            </p>

            {!pregunta && (
                <>
                    {vacio && <p className="empty">{vacio}</p>}
                    <button onClick={siguiente} disabled={cargando}>
                        {cargando ? '⏳ Pensando...' : '▶️ Obtener pregunta'}
                    </button>
                </>
            )}

            {pregunta && (
                <div className="cb-item">
                    <div className="cb-meta">🤖 El asistente pregunta:</div>
                    <div className="cb-pregunta">{pregunta.pregunta}</div>
                    <textarea
                        className="cb-textarea"
                        placeholder="Tu respuesta..."
                        maxLength={1500}
                        value={respuesta}
                        onChange={(e) => setRespuesta(e.target.value)}
                    />
                    <div className="cb-acciones">
                        <button className="btn-approve" onClick={responder} disabled={cargando}>✅ Guardar y siguiente</button>
                        <button className="btn-reject" onClick={noAplica} disabled={cargando}>🚫 No aplica</button>
                    </div>
                </div>
            )}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Base de conocimiento: ver, corregir, borrar y agregar a mano
// ---------------------------------------------------------------------------
function Conocimiento({ avisar, alCambiar }) {
    const [estado, setEstado] = useState('respondida');
    const [lista, setLista] = useState(null);
    const [editando, setEditando] = useState(null); // { id, texto }
    const [nuevaPregunta, setNuevaPregunta] = useState('');
    const [nuevaRespuesta, setNuevaRespuesta] = useState('');

    const cargar = useCallback(async () => {
        setLista(null);
        const r = await llamarApi(`/api/admin/chatbot/conocimiento?estado=${estado}`);
        if (!r.ok) {
            avisar(r.data?.error || 'No se pudo cargar el conocimiento', true);
            setLista([]);
            return;
        }
        setLista(Array.isArray(r.data) ? r.data : []);
    }, [estado, avisar]);

    useEffect(() => {
        cargar();
    }, [cargar]);

    const guardarEdicion = async () => {
        if (!editando.texto.trim()) return avisar('Escribe la respuesta', true);
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${editando.id}/responder`, {
            method: 'POST', body: JSON.stringify({ respuesta: editando.texto })
        });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo guardar', true);
        setEditando(null);
        avisar('✅ Respuesta actualizada.');
        cargar();
        alCambiar();
    };

    const eliminar = async (id) => {
        if (!window.confirm('¿Borrar esta entrada? El asistente dejará de saberla.')) return;
        const r = await llamarApi(`/api/admin/chatbot/conocimiento/${id}`, { method: 'DELETE' });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo borrar', true);
        cargar();
        alCambiar();
    };

    const agregar = async (e) => {
        e.preventDefault();
        if (!nuevaPregunta.trim() || !nuevaRespuesta.trim()) return avisar('Escribe la pregunta y la respuesta', true);
        const r = await llamarApi('/api/admin/chatbot/conocimiento', {
            method: 'POST', body: JSON.stringify({ pregunta: nuevaPregunta, respuesta: nuevaRespuesta })
        });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo agregar', true);
        setNuevaPregunta('');
        setNuevaRespuesta('');
        avisar('✅ Agregado a la base de conocimiento.');
        if (estado === 'respondida') cargar();
        alCambiar();
    };

    return (
        <div>
            <form className="cb-item" onSubmit={agregar}>
                <div className="cb-pregunta">➕ Agregar conocimiento a mano</div>
                <input
                    type="text"
                    className="cb-input"
                    placeholder="Pregunta (ej: ¿Cuánto tarda un retiro?)"
                    maxLength={300}
                    value={nuevaPregunta}
                    onChange={(e) => setNuevaPregunta(e.target.value)}
                />
                <textarea
                    className="cb-textarea"
                    placeholder="Respuesta"
                    maxLength={1500}
                    value={nuevaRespuesta}
                    onChange={(e) => setNuevaRespuesta(e.target.value)}
                />
                <div className="cb-acciones"><button type="submit">Agregar</button></div>
            </form>

            <div className="cb-filtro">
                <label htmlFor="cb-estado">Ver:</label>
                <select id="cb-estado" value={estado} onChange={(e) => setEstado(e.target.value)}>
                    <option value="respondida">Respondidas (lo que sabe el bot)</option>
                    <option value="descartada">Descartadas</option>
                </select>
            </div>

            {lista === null ? <p>Cargando...</p> : lista.length === 0 ? <p className="empty">No hay entradas.</p> : (
                <div className="admin-list">
                    {lista.map(item => (
                        <div key={item.id} className="cb-item">
                            <div className="cb-pregunta">{item.pregunta}</div>
                            <div className="cb-meta">
                                {ETIQUETA_ORIGEN[item.origen] || item.origen} · {fechaCorta(item.fecha_respuesta || item.fecha_creacion)}
                            </div>

                            {editando?.id === item.id ? (
                                <>
                                    <textarea
                                        className="cb-textarea"
                                        maxLength={1500}
                                        value={editando.texto}
                                        onChange={(e) => setEditando({ id: item.id, texto: e.target.value })}
                                    />
                                    <div className="cb-acciones">
                                        <button className="btn-approve" onClick={guardarEdicion}>💾 Guardar</button>
                                        <button className="btn-reject" onClick={() => setEditando(null)}>Cancelar</button>
                                    </div>
                                </>
                            ) : (
                                <>
                                    {item.respuesta && <div className="cb-respuesta">{item.respuesta}</div>}
                                    <div className="cb-acciones">
                                        <button onClick={() => setEditando({ id: item.id, texto: item.respuesta || '' })}>
                                            {item.respuesta ? '✏️ Editar' : '💬 Responder'}
                                        </button>
                                        <button className="btn-reject" onClick={() => eliminar(item.id)}>🗑️ Borrar</button>
                                    </div>
                                </>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Ajustes: tono por defecto
// ---------------------------------------------------------------------------
function Ajustes({ avisar, config, alCambiar }) {
    const cambiarTono = async (e) => {
        const r = await llamarApi('/api/admin/chatbot/config', { method: 'PUT', body: JSON.stringify({ tono: e.target.value }) });
        if (!r.ok) return avisar(r.data?.error || 'No se pudo guardar el tono', true);
        avisar('✅ Tono por defecto actualizado.');
        alCambiar();
    };

    if (!config) return <p>Cargando...</p>;

    return (
        <div className="admin-list">
            <label className="cb-meta" htmlFor="cb-tono-defecto">Tono por defecto del asistente</label>
            <select id="cb-tono-defecto" className="cb-input" value={config.tonoDefecto} onChange={cambiarTono}>
                {(config.tonos || []).map(t => <option key={t} value={t}>{ETIQUETA_TONO[t] || t}</option>)}
            </select>
            <p className="cb-ayuda">
                Es el tono que usan los usuarios que no eligieron uno propio. Cada usuario puede cambiarlo desde
                su chat con el asistente.
            </p>
        </div>
    );
}

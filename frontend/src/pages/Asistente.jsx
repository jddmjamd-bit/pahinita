import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import './Asistente.css';

// Asistente virtual (chatbot IA, roadmap parte 20). El usuario sale SIEMPRE de la sesión (cookie JWT):
// nunca se envía userId. Todo el texto se pinta como texto (React lo escapa); no hay dangerouslySetInnerHTML.

const MAX_MENSAJE = 500; // igual que ChatSanitizer.MAX_TEXTO en el servidor

const ETIQUETA_TONO = {
    defecto: 'El que configuró el administrador',
    formal: 'Formal',
    informal: 'Informal',
    adaptativo: 'Adaptativo (imita tu estilo)'
};

const formatearHora = (iso) => {
    const d = new Date(iso);
    return isNaN(d) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
};

export default function Asistente() {
    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);

    const [estado, setEstado] = useState(null); // { disponible, tono, tonoPropio, tonoDefecto, tonos }
    const [mensajes, setMensajes] = useState([]); // { rol: 'usuario' | 'bot' | 'error', texto, fecha }
    const [texto, setTexto] = useState('');
    const [cargando, setCargando] = useState(true);
    const [enviando, setEnviando] = useState(false);
    const [errorCarga, setErrorCarga] = useState('');
    const finRef = useRef(null);

    const cargar = useCallback(async () => {
        const r = await llamarApi('/api/chatbot/estado');
        if (!r.ok) {
            setErrorCarga(r.status === 401
                ? 'Tu sesión expiró. Vuelve a iniciar sesión para usar el asistente.'
                : (r.data?.error || 'No se pudo cargar el asistente.'));
            setCargando(false);
            return;
        }
        setErrorCarga('');
        setEstado(r.data);
        setMensajes(Array.isArray(r.data.historial) ? r.data.historial : []);
        setCargando(false);
    }, []);

    useEffect(() => {
        cargar();
    }, [cargar]);

    // Cuando el administrador responde una duda de este usuario, el servidor la guarda en su historial y además
    // la emite por socket (chatbot_respuesta): así aparece en vivo si está conectado, y al recargar sale del historial.
    useEffect(() => {
        if (!socket) return undefined;
        const alRecibir = (data) => {
            if (!data || typeof data.texto !== 'string') return;
            setMensajes(prev => [...prev, { rol: 'bot', texto: data.texto, fecha: new Date().toISOString() }]);
        };
        socket.on('chatbot_respuesta', alRecibir);
        return () => socket.off('chatbot_respuesta', alRecibir);
    }, [socket]);

    useEffect(() => {
        finRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [mensajes, enviando]);

    const agregarError = (mensaje) => {
        setMensajes(prev => [...prev, { rol: 'error', texto: mensaje, fecha: new Date().toISOString() }]);
    };

    const enviar = async (e) => {
        e.preventDefault();
        const limpio = texto.trim();
        if (!limpio || enviando) return;

        setTexto('');
        setEnviando(true);
        setMensajes(prev => [...prev, { rol: 'usuario', texto: limpio, fecha: new Date().toISOString() }]);

        const r = await llamarApi('/api/chatbot/mensaje', { method: 'POST', body: JSON.stringify({ texto: limpio }) });
        if (r.ok && r.data?.success) {
            setMensajes(prev => [...prev, { rol: 'bot', texto: r.data.respuesta, fecha: new Date().toISOString() }]);
        } else {
            agregarError(r.data?.error || 'No se pudo enviar el mensaje. Inténtalo de nuevo.');
        }
        setEnviando(false);
    };

    const cambiarTono = async (e) => {
        const r = await llamarApi('/api/chatbot/tono', { method: 'PUT', body: JSON.stringify({ tono: e.target.value }) });
        if (r.ok && r.data?.success) {
            setEstado(prev => ({ ...prev, tonoPropio: r.data.tonoPropio, tono: r.data.tono }));
        } else {
            agregarError(r.data?.error || 'No se pudo cambiar el tono.');
        }
    };

    const borrarHistorial = async () => {
        if (!window.confirm('¿Borrar toda tu conversación con el asistente?')) return;
        const r = await llamarApi('/api/chatbot/historial', { method: 'DELETE' });
        if (r.ok && r.data?.success) {
            setMensajes([]);
        } else {
            agregarError(r.data?.error || 'No se pudo borrar el historial.');
        }
    };

    if (!currentUser) {
        return <div className="asistente-vacio" style={{ padding: 20 }}>Inicia sesión para usar el asistente.</div>;
    }
    if (cargando) {
        return <div className="asistente-vacio" style={{ padding: 20 }}>Cargando asistente...</div>;
    }
    if (errorCarga) {
        return <div className="asistente-vacio" style={{ padding: 20 }}>{errorCarga}</div>;
    }

    const disponible = estado?.disponible !== false;

    return (
        <div className="asistente-container">
            <header className="asistente-header">
                <h3>🤖 Asistente virtual</h3>
                <div className="asistente-controles">
                    <label htmlFor="asistente-tono">Tono:</label>
                    <select id="asistente-tono" value={estado?.tonoPropio || 'defecto'} onChange={cambiarTono}>
                        <option value="defecto">{ETIQUETA_TONO.defecto}</option>
                        {(estado?.tonos || []).map(t => (
                            <option key={t} value={t}>{ETIQUETA_TONO[t] || t}</option>
                        ))}
                    </select>
                    <button type="button" className="asistente-btn-borrar" onClick={borrarHistorial} disabled={mensajes.length === 0}>
                        🗑️ Borrar chat
                    </button>
                </div>
            </header>

            {!disponible && (
                <p className="asistente-aviso">El asistente no está disponible en este momento. Inténtalo más tarde.</p>
            )}

            <div className="asistente-mensajes">
                {mensajes.length === 0 && (
                    <div className="asistente-vacio">
                        <span className="emoji">🤖</span>
                        Hola, soy el asistente de Torneos Flash. Pregúntame cómo recargar, retirar, jugar un torneo,
                        cómo funcionan los sorteos o el ranking. Si no sé la respuesta, le aviso al administrador y te
                        la envío por aquí apenas la tenga.
                    </div>
                )}

                {mensajes.map((m, i) => (
                    <div key={i} className={`asistente-msg ${m.rol}`}>
                        {m.rol !== 'error' && (
                            <span className="asistente-msg-quien">{m.rol === 'bot' ? '🤖 Asistente' : 'Tú'}</span>
                        )}
                        <span className="asistente-msg-texto">{m.texto}</span>
                        {m.rol !== 'error' && formatearHora(m.fecha) && (
                            <span className="asistente-msg-hora">{formatearHora(m.fecha)}</span>
                        )}
                    </div>
                ))}

                {enviando && <div className="asistente-escribiendo">🤖 Escribiendo...</div>}
                <div ref={finRef} />
            </div>

            <form className="asistente-form" onSubmit={enviar}>
                <input
                    type="text"
                    placeholder={disponible ? 'Escribe tu pregunta...' : 'Asistente no disponible'}
                    value={texto}
                    maxLength={MAX_MENSAJE}
                    onChange={(e) => setTexto(e.target.value)}
                    disabled={!disponible}
                    autoComplete="off"
                />
                <button type="submit" disabled={!disponible || enviando || !texto.trim()}>
                    {enviando ? '⏳' : 'Enviar'}
                </button>
            </form>
        </div>
    );
}

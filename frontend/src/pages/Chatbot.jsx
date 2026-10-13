import React, { useState, useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Chat.css';
import './Chatbot.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

// Tonos que el usuario puede elegir. "defecto" = el que fijó el administrador.
const TONOS = [
    { id: 'defecto', etiqueta: 'Por defecto', ayuda: 'Usa el tono que definió el administrador' },
    { id: 'formal', etiqueta: 'Formal', ayuda: 'Te trata de usted, sin jerga' },
    { id: 'informal', etiqueta: 'Informal', ayuda: 'Cercano, te tutea' },
    { id: 'adaptativo', etiqueta: 'Adaptativo', ayuda: 'Responde como tú le escribes' }
];

const BIENVENIDA = '¡Hola! Soy el asistente de la plataforma. Pregúntame cómo funcionan las recargas, los retiros, los torneos, los sorteos... Si no sé algo, le aviso al administrador y su respuesta te llega aquí mismo.';

let contadorLocal = 0;
const nuevoId = () => `local-${Date.now()}-${contadorLocal++}`;

export default function Chatbot() {
    const socket = useAppStore(state => state.socket);

    const [mensajes, setMensajes] = useState([]);
    const [texto, setTexto] = useState('');
    const [enviando, setEnviando] = useState(false);
    const [cargando, setCargando] = useState(true);
    const [disponible, setDisponible] = useState(true);
    const [tonoPropio, setTonoPropio] = useState(null); // null = usa el del admin
    const [tonoDefecto, setTonoDefecto] = useState('adaptativo');
    const finRef = useRef(null);

    const agregar = (rol, contenido, extra = {}) => {
        setMensajes(prev => [...prev, { id: nuevoId(), rol, texto: contenido, ...extra }]);
    };

    // Carga inicial: disponibilidad, tono e historial (la sesión viaja en la cookie httpOnly)
    useEffect(() => {
        let activo = true;
        (async () => {
            try {
                const res = await fetch(`${API_BASE_URL}/api/chatbot/estado`, { credentials: 'include' });
                const data = await res.json();
                if (!activo) return;
                if (!res.ok) {
                    agregar('error', data.error || 'No se pudo cargar el asistente');
                    return;
                }
                setDisponible(!!data.disponible);
                setTonoPropio(data.tonoPropio || null);
                setTonoDefecto(data.tonoDefecto || 'adaptativo');
                setMensajes((data.historial || []).map((m, i) => ({
                    id: `hist-${i}`,
                    rol: m.rol === 'usuario' ? 'usuario' : 'bot',
                    texto: m.texto,
                    fecha: m.fecha
                })));
            } catch (e) {
                if (activo) agregar('error', 'Error de conexión con el servidor');
            } finally {
                if (activo) setCargando(false);
            }
        })();
        return () => { activo = false; };
    }, []);

    // Cuando el admin responde una duda que hice, llega por socket (y ya quedó guardada en el historial)
    useEffect(() => {
        if (!socket) return;
        const alRecibirRespuesta = (data) => {
            if (data && typeof data.texto === 'string') agregar('bot', data.texto);
        };
        socket.on('chatbot_respuesta', alRecibirRespuesta);
        return () => socket.off('chatbot_respuesta', alRecibirRespuesta);
    }, [socket]);

    useEffect(() => {
        finRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [mensajes, enviando]);

    const enviar = async (e) => {
        e.preventDefault();
        const pregunta = texto.trim();
        if (!pregunta || enviando) return;

        agregar('usuario', pregunta);
        setTexto('');
        setEnviando(true);
        try {
            const res = await fetch(`${API_BASE_URL}/api/chatbot/mensaje`, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ texto: pregunta })
            });
            const data = await res.json();
            if (!res.ok || data.error) {
                agregar('error', data.error || 'No pude responder. Intenta de nuevo.');
            } else {
                agregar('bot', data.respuesta, { sinRespuesta: data.resuelta === false });
            }
        } catch (err) {
            agregar('error', 'Error de conexión con el servidor');
        } finally {
            setEnviando(false);
        }
    };

    const cambiarTono = async (tono) => {
        try {
            const res = await fetch(`${API_BASE_URL}/api/chatbot/tono`, {
                method: 'PUT',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ tono })
            });
            const data = await res.json();
            if (!res.ok || data.error) return agregar('error', data.error || 'No se pudo cambiar el tono');
            setTonoPropio(data.tonoPropio || null);
        } catch (e) {
            agregar('error', 'Error de conexión con el servidor');
        }
    };

    const borrarHistorial = async () => {
        if (!window.confirm('¿Borrar toda tu conversación con el asistente?')) return;
        try {
            const res = await fetch(`${API_BASE_URL}/api/chatbot/historial`, { method: 'DELETE', credentials: 'include' });
            const data = await res.json();
            if (!res.ok || data.error) return agregar('error', data.error || 'No se pudo borrar el historial');
            setMensajes([]);
        } catch (e) {
            agregar('error', 'Error de conexión con el servidor');
        }
    };

    const tonoActivo = tonoPropio || 'defecto';

    return (
        <div className="chat-container">
            <div className="chat-tabs chatbot-header">
                <strong>🤖 Asistente</strong>
                <div className="chatbot-tonos" role="group" aria-label="Tono de respuesta">
                    {TONOS.map(t => (
                        <button
                            key={t.id}
                            className={`chat-tab ${tonoActivo === t.id ? 'active' : ''}`}
                            title={t.id === 'defecto' ? `${t.ayuda} (ahora: ${tonoDefecto})` : t.ayuda}
                            onClick={() => cambiarTono(t.id)}
                        >
                            {t.etiqueta}
                        </button>
                    ))}
                </div>
                <button className="chat-tab" onClick={borrarHistorial} title="Borrar mi conversación">🗑️</button>
            </div>

            <div className="chat-messages">
                {!cargando && mensajes.length === 0 && (
                    <div className="msg other"><div className="chatbot-texto">{BIENVENIDA}</div></div>
                )}
                {mensajes.map(m => (
                    <div
                        key={m.id}
                        className={`msg ${m.rol === 'usuario' ? 'own' : 'other'} ${m.rol === 'error' ? 'chatbot-error' : ''} ${m.sinRespuesta ? 'chatbot-pendiente' : ''}`}
                    >
                        <div className="chatbot-texto">{m.texto}</div>
                    </div>
                ))}
                {enviando && (
                    <div className="msg other"><div className="chatbot-texto chatbot-escribiendo">Escribiendo…</div></div>
                )}
                <div ref={finRef} />
            </div>

            {disponible ? (
                <form className="chat-input-area" onSubmit={enviar}>
                    <input
                        type="text"
                        placeholder="Escribe tu pregunta..."
                        value={texto}
                        maxLength={500}
                        onChange={e => setTexto(e.target.value)}
                        disabled={enviando}
                    />
                    <button type="submit" disabled={enviando || !texto.trim()}>Enviar</button>
                </form>
            ) : (
                <div className="chat-input-area">
                    <p className="chat-read-only">El asistente no está disponible en este momento.</p>
                </div>
            )}
        </div>
    );
}

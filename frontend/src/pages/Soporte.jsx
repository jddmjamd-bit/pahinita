import React, { useState, useEffect, useCallback } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import './Soporte.css';
import { CATEGORIAS_SOPORTE, etiquetaCategoria, formatearFecha } from './soporteComun';

const MAX_ASUNTO = 100;
const MAX_MENSAJE = 2000;
const FORM_VACIO = { categoria: 'pregunta', asunto: '', mensaje: '' };

export default function Soporte() {
    const socket = useAppStore(state => state.socket);

    const [tickets, setTickets] = useState([]);
    const [cargando, setCargando] = useState(true);
    const [error, setError] = useState('');
    const [aviso, setAviso] = useState('');
    const [formAbierto, setFormAbierto] = useState(false);
    const [form, setForm] = useState(FORM_VACIO);
    const [enviando, setEnviando] = useState(false);
    const [errorForm, setErrorForm] = useState('');

    const cargar = useCallback(async () => {
        const { ok, data } = await llamarApi('/api/soporte/tickets');
        if (!ok || !Array.isArray(data)) {
            setError(data?.error || 'No se pudieron cargar tus consultas');
        } else {
            setTickets(data);
            setError('');
        }
        setCargando(false);
    }, []);

    useEffect(() => { cargar(); }, [cargar]);

    // Cuando el admin responde llega un aviso por socket: se recarga la lista para mostrar la respuesta
    useEffect(() => {
        if (!socket) return;
        const alResponder = () => {
            setAviso('✅ El administrador respondió una de tus consultas.');
            cargar();
        };
        socket.on('soporte_respuesta', alResponder);
        return () => socket.off('soporte_respuesta', alResponder);
    }, [socket, cargar]);

    const abrirForm = () => {
        setForm(FORM_VACIO);
        setErrorForm('');
        setFormAbierto(true);
    };

    const cerrarForm = () => {
        if (!enviando) setFormAbierto(false);
    };

    const enviar = async (e) => {
        e.preventDefault();
        if (enviando) return;
        if (!form.asunto.trim() || !form.mensaje.trim()) {
            setErrorForm('Escribe el asunto y tu mensaje');
            return;
        }
        setEnviando(true);
        setErrorForm('');
        const { ok, data } = await llamarApi('/api/soporte/tickets', {
            method: 'POST',
            body: JSON.stringify(form)
        });
        setEnviando(false);
        if (!ok || data?.error) {
            setErrorForm(data?.error || 'No se pudo enviar tu mensaje. Inténtalo de nuevo.');
            return;
        }
        setFormAbierto(false);
        setAviso('📨 Mensaje enviado. Te avisaremos cuando el administrador responda.');
        cargar();
    };

    return (
        <div className="sop-container">
            <header className="sop-header">
                <div>
                    <h2>✉️ Hablar con el admin</h2>
                    <p className="sop-nota">
                        Preguntas, sugerencias, bugs o problemas con tu cuenta. Te respondemos aquí y a tu correo.
                    </p>
                </div>
                <button className="sop-btn-primario" onClick={abrirForm}>➕ Nueva consulta</button>
            </header>

            {aviso && <div className="sop-aviso" onClick={() => setAviso('')}>{aviso}</div>}
            {error && <div className="sop-error">⚠️ {error}</div>}

            <h3 className="sop-subtitulo">Mis consultas</h3>
            {cargando ? (
                <p className="sop-nota">Cargando...</p>
            ) : tickets.length === 0 ? (
                <p className="sop-nota">Todavía no has enviado ninguna consulta.</p>
            ) : (
                <div className="sop-lista">
                    {tickets.map(t => (
                        <article key={t.id} className="sop-ticket">
                            <div className="sop-ticket-cabecera">
                                <strong className="sop-asunto">{t.asunto}</strong>
                                <span className={`sop-estado ${t.estado === 'resuelto' ? 'resuelto' : 'abierto'}`}>
                                    {t.estado === 'resuelto' ? '✅ Resuelto' : '⏳ En espera'}
                                </span>
                            </div>
                            <div className="sop-meta">
                                {etiquetaCategoria(t.categoria)} · {formatearFecha(t.fecha_creacion)}
                            </div>
                            <div className="sop-texto">{t.mensaje}</div>
                            {t.respuesta && (
                                <div className="sop-respuesta">
                                    <div className="sop-meta">👑 Respuesta del administrador · {formatearFecha(t.fecha_respuesta)}</div>
                                    <div className="sop-texto">{t.respuesta}</div>
                                </div>
                            )}
                        </article>
                    ))}
                </div>
            )}

            {formAbierto && (
                <div className="sop-overlay" onClick={cerrarForm}>
                    <form className="sop-modal" onClick={e => e.stopPropagation()} onSubmit={enviar}>
                        <div className="sop-modal-cabecera">
                            <h3>Nueva consulta</h3>
                            <button type="button" className="sop-cerrar" onClick={cerrarForm} aria-label="Cerrar">✕</button>
                        </div>

                        <label>
                            Tipo de consulta
                            <select
                                value={form.categoria}
                                onChange={e => setForm(prev => ({ ...prev, categoria: e.target.value }))}
                                disabled={enviando}
                            >
                                {CATEGORIAS_SOPORTE.map(c => (
                                    <option key={c.id} value={c.id}>{c.etiqueta}</option>
                                ))}
                            </select>
                        </label>

                        <label>
                            Asunto
                            <input
                                type="text"
                                value={form.asunto}
                                maxLength={MAX_ASUNTO}
                                placeholder="Resumen corto de lo que necesitas"
                                onChange={e => setForm(prev => ({ ...prev, asunto: e.target.value }))}
                                disabled={enviando}
                            />
                        </label>

                        <label>
                            Mensaje
                            <textarea
                                rows={6}
                                value={form.mensaje}
                                maxLength={MAX_MENSAJE}
                                placeholder="Cuéntanos con el mayor detalle posible. Nunca escribas tu contraseña."
                                onChange={e => setForm(prev => ({ ...prev, mensaje: e.target.value }))}
                                disabled={enviando}
                            />
                            <small className="sop-contador">{form.mensaje.length}/{MAX_MENSAJE}</small>
                        </label>

                        {errorForm && <div className="sop-error">⚠️ {errorForm}</div>}

                        <div className="sop-acciones">
                            <button type="button" className="sop-btn-secundario" onClick={cerrarForm} disabled={enviando}>
                                Cancelar
                            </button>
                            <button
                                type="submit"
                                className="sop-btn-primario"
                                disabled={enviando || !form.asunto.trim() || !form.mensaje.trim()}
                            >
                                {enviando ? 'Enviando...' : '📨 Enviar'}
                            </button>
                        </div>
                    </form>
                </div>
            )}
        </div>
    );
}

import React, { useState, useEffect, useRef, useMemo } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import './Chat.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

// S5 (XSS): un src multimedia solo es válido si es un archivo propio (/api/media/ID) o, para imágenes,
// un data URI base64 de formato raster (nunca SVG ni javascript:). Devuelve la URL final o null.
const resolverMedia = (src, tipo) => {
    if (typeof src !== 'string') return null;
    if (/^\/api\/media\/\d+$/.test(src)) return `${API_BASE_URL}${src}`;
    const base = `${API_BASE_URL}/api/media/`;
    if (src.startsWith(base) && /^\d+$/.test(src.slice(base.length))) return src;
    if (tipo === 'imagen' && /^data:image\/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=]+$/.test(src)) return src;
    return null;
};

// Evidencias de pago (roadmap 7): la imagen viaja en el JSON de un POST, así que se reduce antes de enviarla
// (máx. 1280 px, JPEG). El servidor acepta hasta PAGOS_MAX_IMAGEN_CHARS (800000 por defecto) y vuelve a validarla.
const MAX_EVIDENCIA_CHARS = 700000;

const comprimirImagen = (file) => new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
        URL.revokeObjectURL(url);
        let escala = Math.min(1, 1280 / Math.max(img.width, img.height));
        for (let intento = 0; intento < 6; intento++) {
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(img.width * escala));
            canvas.height = Math.max(1, Math.round(img.height * escala));
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#ffffff'; // los PNG con transparencia no deben quedar negros
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
            for (const calidad of [0.85, 0.7, 0.55]) {
                const dataUri = canvas.toDataURL('image/jpeg', calidad);
                if (dataUri.length <= MAX_EVIDENCIA_CHARS) {
                    resolve(dataUri);
                    return;
                }
            }
            escala *= 0.75;
        }
        reject(new Error('La imagen es demasiado pesada. Usa una captura más pequeña.'));
    };
    img.onerror = () => {
        URL.revokeObjectURL(url);
        reject(new Error('No se pudo leer la imagen. Usa png, jpg, gif o webp.'));
    };
    img.src = url;
});

const formatoMonto = (monto) => `$${Number(monto || 0).toLocaleString('es-CO')}`;

export default function Chat() {
    const [canalActivo, setCanalActivo] = useState('general'); // 'general', 'anuncios', 'clash', 'clash_logs'
    const [inputValues, setInputValues] = useState({
        general: '',
        anuncios: '',
        clash: ''
    });
    
    const [isUploading, setIsUploading] = useState(false);

    // Evidencias de pago (solo admin): retiros sin comprobante + formulario
    const [retirosSinEvidencia, setRetirosSinEvidencia] = useState([]);
    const [retiroSel, setRetiroSel] = useState('');
    const [imagenPago, setImagenPago] = useState('');
    const [notaPago, setNotaPago] = useState('');
    const [enviandoPago, setEnviandoPago] = useState(false);
    const [errorPago, setErrorPago] = useState('');
    const chatEndRef = useRef(null);

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const connectSocket = useAppStore(state => state.connectSocket);
    const chatMessages = useAppStore(state => state.chatMessages);
    const sendChatMessage = useAppStore(state => state.sendChatMessage);
    const setChatHistory = useAppStore(state => state.setChatHistory);
    const esAdmin = currentUser?.tipo_suscripcion === 'admin';

    useEffect(() => {
        if (currentUser) {
            connectSocket();
        }
    }, [currentUser, connectSocket]);

    const cargarPagos = async () => {
        const { ok, data } = await llamarApi('/api/pagos/evidencias');
        if (ok && Array.isArray(data)) setChatHistory('pagos', data);
    };

    const cargarRetirosSinEvidencia = async () => {
        const { ok, data } = await llamarApi('/api/admin/pagos/retiros');
        if (ok && Array.isArray(data)) setRetirosSinEvidencia(data);
    };

    // El historial del canal Pagos pesa (son imágenes): se pide solo al abrirlo, no al conectar el socket
    useEffect(() => {
        if (canalActivo === 'pagos' && currentUser) {
            cargarPagos();
            if (esAdmin) cargarRetirosSinEvidencia();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [canalActivo, currentUser?.id, esAdmin]);

    useEffect(() => {
        // Auto-scroll al recibir mensajes o cambiar de canal
        if (chatEndRef.current) {
            chatEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [chatMessages, canalActivo]);

    const convertirLinks = (texto) => {
        const urlRegex = /(https?:\/\/[^\s]+)/g;
        const parts = String(texto ?? '').split(urlRegex);
        return parts.map((part, i) => {
            if (part.match(urlRegex)) {
                return <a key={i} href={part} target="_blank" rel="noopener noreferrer" className="chat-link">{part}</a>;
            }
            return part;
        });
    };

    const handleSendMessage = (e, canal) => {
        e.preventDefault();
        const texto = inputValues[canal]?.trim();
        if (texto && currentUser) {
            sendChatMessage({ canal, texto, tipo: 'texto' });
            setInputValues(prev => ({ ...prev, [canal]: '' }));
        }
    };

    const handleUploadMedia = async (e) => {
        const file = e.target.files[0];
        if (!file || !currentUser || !socket) return;
        
        setIsUploading(true);
        if (file.type.startsWith('video')) {
            try {
                const formData = new FormData();
                formData.append('file', file);
                const res = await fetch(`${API_BASE_URL}/api/upload`, {
                    method: 'POST', body: formData, credentials: 'include'
                });
                if (!res.ok) throw new Error('Error al subir video');
                const data = await res.json();
                
                socket.emit('mensaje_chat', {
                    canal: 'anuncios',
                    usuario: currentUser.username,
                    texto: data.url,
                    tipo: 'video'
                });
            } catch (err) {
                alert('Error subiendo video: ' + err.message);
            } finally {
                setIsUploading(false);
            }
        } else {
            const r = new FileReader();
            r.onload = (ev) => {
                socket.emit('mensaje_chat', {
                    canal: 'anuncios',
                    usuario: currentUser.username,
                    texto: ev.target.result,
                    tipo: 'imagen'
                });
                setIsUploading(false);
            };
            r.readAsDataURL(file);
        }
        e.target.value = '';
    };

    const handleElegirImagenPago = async (e) => {
        const file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        setErrorPago('');
        if (!file.type.startsWith('image/')) {
            setErrorPago('El comprobante debe ser una imagen.');
            return;
        }
        try {
            setImagenPago(await comprimirImagen(file));
        } catch (err) {
            setImagenPago('');
            setErrorPago(err.message);
        }
    };

    const handlePublicarEvidencia = async (e) => {
        e.preventDefault();
        if (!retiroSel || !imagenPago || enviandoPago) return;
        setEnviandoPago(true);
        setErrorPago('');
        const { ok, data } = await llamarApi('/api/admin/pagos/evidencias', {
            method: 'POST',
            body: JSON.stringify({ transaccionId: Number(retiroSel), imagen: imagenPago, nota: notaPago })
        });
        setEnviandoPago(false);
        if (!ok) {
            setErrorPago(data?.error || 'No se pudo publicar la evidencia.');
            return;
        }
        setRetiroSel('');
        setImagenPago('');
        setNotaPago('');
        cargarPagos();
        cargarRetirosSinEvidencia();
    };

    const renderBurbuja = (msg, index) => {
        const isOwn = currentUser && msg.usuario === currentUser.username;
        const hora = msg.fecha ? new Date(msg.fecha).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';

        if (msg.canal === 'pagos') {
            const ev = msg.evidencia || {};
            const urlImagen = resolverMedia(msg.texto, 'imagen');
            return (
                <div key={ev.id ?? index} className="msg other msg-pago">
                    <span className="msg-user" style={{ color: '#4ade80', fontWeight: 'bold' }}>
                        🧾 Retiro pagado a {ev.para} · {formatoMonto(ev.monto)}{ev.metodo ? ` · ${ev.metodo}` : ''}
                    </span>
                    {urlImagen ? (
                        <img src={urlImagen} className="chat-image" alt="Comprobante de pago" onClick={() => window.open(urlImagen, '_blank', 'noopener,noreferrer')} />
                    ) : (
                        <span className="msg-text">[contenido no permitido]</span>
                    )}
                    {ev.nota && <span className="msg-text msg-pago-nota">{ev.nota}</span>}
                    <span className="msg-time">Publicado por {msg.usuario} · {hora}</span>
                </div>
            );
        }
        if (msg.canal === 'clash_logs') {
            return (
                <div key={index} className="log-msg">
                    <span className="log-icon">⚔️</span>
                    <span className="log-text">{msg.texto}</span>
                    <span className="log-time">{hora}</span>
                </div>
            );
        }

        let content = <span className="msg-text">{convertirLinks(msg.texto)}</span>;
        if (msg.tipo === 'imagen' || msg.tipo === 'video') {
            const urlMedia = resolverMedia(msg.texto, msg.tipo);
            if (!urlMedia) {
                content = <span className="msg-text">[contenido no permitido]</span>;
            } else if (msg.tipo === 'imagen') {
                content = <img src={urlMedia} className="chat-image" alt="media" onClick={() => window.open(urlMedia, '_blank', 'noopener,noreferrer')} />;
            } else {
                content = <video src={urlMedia} className="chat-video" controls />;
            }
        }

        const userStyle = msg.canal === 'anuncios' ? { color: '#e94560', fontWeight: 'bold' } : {};
        const userDisplay = msg.canal === 'anuncios' ? `📢 ${msg.usuario}` : msg.usuario;

        return (
            <div key={index} className={`msg ${isOwn ? 'own' : 'other'}`}>
                <span className="msg-user" style={userStyle}>{userDisplay}</span>
                {content}
                <span className="msg-time">{hora}</span>
            </div>
        );
    };

    // En pagos se quita lo repetido por id (puede llegar en vivo y también en el historial recargado)
    const mensajesActuales = useMemo(() => {
        const lista = chatMessages[canalActivo] || [];
        if (canalActivo !== 'pagos') return lista;
        const vistos = new Set();
        return lista.filter(m => {
            const id = m?.evidencia?.id;
            if (id === undefined) return true;
            if (vistos.has(id)) return false;
            vistos.add(id);
            return true;
        });
    }, [chatMessages, canalActivo]);

    return (
        <div className="chat-container">
            <div className="chat-tabs">
                <button className={`chat-tab ${canalActivo === 'general' ? 'active' : ''}`} onClick={() => setCanalActivo('general')}>💬 General</button>
                <button className={`chat-tab ${canalActivo === 'anuncios' ? 'active' : ''}`} onClick={() => setCanalActivo('anuncios')}>📢 Anuncios</button>
                <button className={`chat-tab ${canalActivo === 'clash' ? 'active' : ''}`} onClick={() => setCanalActivo('clash')}>⚔️ Clash</button>
                <button className={`chat-tab ${canalActivo === 'clash_logs' ? 'active' : ''}`} onClick={() => setCanalActivo('clash_logs')}>📜 Registro</button>
                <button className={`chat-tab ${canalActivo === 'pagos' ? 'active' : ''}`} onClick={() => setCanalActivo('pagos')}>🧾 Pagos</button>
            </div>

            <div className="chat-messages">
                {mensajesActuales.length === 0 ? (
                    <div className="chat-empty">
                        {canalActivo === 'clash_logs'
                            ? 'No hay registros de partidas o eventos aún.'
                            : canalActivo === 'pagos'
                                ? 'Todavía no hay comprobantes de pago publicados.'
                                : 'No hay mensajes en este canal. ¡Sé el primero en escribir!'}
                    </div>
                ) : (
                    mensajesActuales.map((msg, idx) => renderBurbuja(msg, idx))
                )}
                <div ref={chatEndRef} />
            </div>

            <div className="chat-bottom-bar">
                {canalActivo === 'clash_logs' ? (
                    <div className="chat-read-only">
                        <span>📜 Canal de registro de eventos y partidas del sistema (solo lectura).</span>
                    </div>
                ) : canalActivo === 'pagos' && !esAdmin ? (
                    <div className="chat-read-only">
                        <span>🧾 Aquí el administrador publica los comprobantes de los retiros pagados (solo lectura).</span>
                    </div>
                ) : canalActivo === 'pagos' ? (
                    <form className="pagos-form" onSubmit={handlePublicarEvidencia}>
                        <p className="pagos-aviso">
                            Este canal lo ve todo el mundo. Antes de subir el comprobante, tapa el número de cuenta o celular completo y cualquier documento.
                        </p>
                        <select value={retiroSel} onChange={(e) => setRetiroSel(e.target.value)}>
                            <option value="">
                                {retirosSinEvidencia.length === 0 ? 'No hay retiros aprobados sin comprobante' : 'Elige el retiro pagado...'}
                            </option>
                            {retirosSinEvidencia.map(r => (
                                <option key={r.id} value={r.id}>
                                    #{r.id} · {r.username} · {formatoMonto(r.monto)}{r.metodo ? ` · ${r.metodo}` : ''}
                                </option>
                            ))}
                        </select>
                        <div className="pagos-fila">
                            <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" onChange={handleElegirImagenPago} />
                            {imagenPago && <img src={imagenPago} className="pagos-preview" alt="Vista previa del comprobante" />}
                        </div>
                        <input
                            type="text"
                            maxLength={200}
                            placeholder="Nota opcional (ej. enviado a las 3:40 pm)"
                            value={notaPago}
                            onChange={(e) => setNotaPago(e.target.value)}
                        />
                        {errorPago && <p className="pagos-error">{errorPago}</p>}
                        <button type="submit" disabled={!retiroSel || !imagenPago || enviandoPago}>
                            {enviandoPago ? '⏳ Publicando...' : 'Publicar comprobante'}
                        </button>
                    </form>
                ) : canalActivo === 'anuncios' && !esAdmin ? (
                    <div className="chat-read-only">
                        <span>📢 Solo los administradores pueden enviar anuncios.</span>
                    </div>
                ) : (
                    <form className="chat-input-area" onSubmit={(e) => handleSendMessage(e, canalActivo)}>
                        {canalActivo === 'anuncios' && esAdmin && (
                            <div className="upload-btn-wrapper">
                                <button type="button" className="btn-upload" title="Subir imagen o video">📎</button>
                                <input type="file" accept="image/*,video/mp4" onChange={handleUploadMedia} />
                            </div>
                        )}
                        <input 
                            type="text" 
                            placeholder="Escribe un mensaje..." 
                            value={inputValues[canalActivo] || ''}
                            onChange={(e) => setInputValues(prev => ({...prev, [canalActivo]: e.target.value}))}
                        />
                        <button type="submit" disabled={isUploading}>{isUploading ? '⏳' : 'Enviar'}</button>
                    </form>
                )}
            </div>
        </div>
    );
}

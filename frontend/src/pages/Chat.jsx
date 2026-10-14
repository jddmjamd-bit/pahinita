import React, { useState, useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';
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

export default function Chat() {
    const [canalActivo, setCanalActivo] = useState('general'); // 'general', 'anuncios', 'clash', 'clash_logs'
    const [inputValues, setInputValues] = useState({
        general: '',
        anuncios: '',
        clash: ''
    });
    
    const [isUploading, setIsUploading] = useState(false);
    const chatEndRef = useRef(null);

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const connectSocket = useAppStore(state => state.connectSocket);
    const chatMessages = useAppStore(state => state.chatMessages);
    const sendChatMessage = useAppStore(state => state.sendChatMessage);
    const esAdmin = currentUser?.tipo_suscripcion === 'admin';

    useEffect(() => {
        if (currentUser) {
            connectSocket();
        }
    }, [currentUser, connectSocket]);

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

    const renderBurbuja = (msg, index) => {
        const isOwn = currentUser && msg.usuario === currentUser.username;
        const hora = msg.fecha ? new Date(msg.fecha).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';

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

    const mensajesActuales = chatMessages[canalActivo] || [];

    return (
        <div className="chat-container">
            <div className="chat-tabs">
                <button className={`chat-tab ${canalActivo === 'general' ? 'active' : ''}`} onClick={() => setCanalActivo('general')}>💬 General</button>
                <button className={`chat-tab ${canalActivo === 'anuncios' ? 'active' : ''}`} onClick={() => setCanalActivo('anuncios')}>📢 Anuncios</button>
                <button className={`chat-tab ${canalActivo === 'clash' ? 'active' : ''}`} onClick={() => setCanalActivo('clash')}>⚔️ Clash</button>
                <button className={`chat-tab ${canalActivo === 'clash_logs' ? 'active' : ''}`} onClick={() => setCanalActivo('clash_logs')}>📜 Registro</button>
            </div>

            <div className="chat-messages">
                {mensajesActuales.length === 0 ? (
                    <div className="chat-empty">
                        {canalActivo === 'clash_logs' 
                            ? 'No hay registros de partidas o eventos aún.' 
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

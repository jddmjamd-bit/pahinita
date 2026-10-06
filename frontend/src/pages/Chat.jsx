import React, { useState, useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Chat.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Chat() {
    const [canalActivo, setCanalActivo] = useState('general'); // 'general', 'anuncios', 'clash'
    const [mensajes, setMensajes] = useState({
        general: [],
        anuncios: [],
        clash: [],
        clash_logs: []
    });
    const [inputValues, setInputValues] = useState({
        general: '',
        anuncios: '',
        clash: ''
    });
    
    const [isUploading, setIsUploading] = useState(false);
    const chatEndRef = useRef(null);

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const esAdmin = currentUser?.tipo_suscripcion === 'admin';

    useEffect(() => {
        // TODO: En F3 conectar Socket.IO y suscribirse a historial_chat y mensaje_chat
        // Ejemplo:
        // socket.emit('join_chat', canalActivo);
        // socket.on('historial_chat', (data) => setMensajes(prev => ({...prev, [data.canal]: data.mensajes})));
        // socket.on('mensaje_chat', (data) => setMensajes(prev => ({...prev, [data.canal]: [...prev[data.canal], data]})));
    }, [canalActivo]);

    useEffect(() => {
        // Auto-scroll
        if (chatEndRef.current) {
            chatEndRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [mensajes, canalActivo]);

    const convertirLinks = (texto) => {
        const urlRegex = /(https?:\/\/[^\s]+)/g;
        const parts = texto.split(urlRegex);
        return parts.map((part, i) => {
            if (part.match(urlRegex)) {
                return <a key={i} href={part} target="_blank" rel="noopener noreferrer" className="chat-link">{part}</a>;
            }
            return part;
        });
    };

    const handleSendMessage = (e, canal) => {
        e.preventDefault();
        const texto = inputValues[canal];
        if (texto && currentUser) {
            // TODO: En F3 usar Socket.IO
            // socket.emit('mensaje_chat', { canal, usuario: currentUser.username, texto, tipo: 'texto' });
            
            // Simular mensaje (remover en F3)
            const nuevoMsg = { canal, usuario: currentUser.username, texto, tipo: 'texto', fecha: new Date().toISOString() };
            setMensajes(prev => ({ ...prev, [canal]: [...prev[canal], nuevoMsg] }));
            setInputValues(prev => ({ ...prev, [canal]: '' }));
        }
    };

    const handleUploadMedia = async (e) => {
        const file = e.target.files[0];
        if (!file || !currentUser) return;
        
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
                
                // TODO: F3 socket emit video
                const nuevoMsg = { canal: 'anuncios', usuario: currentUser.username, texto: data.url, tipo: 'video', fecha: new Date().toISOString() };
                setMensajes(prev => ({ ...prev, anuncios: [...prev.anuncios, nuevoMsg] }));
            } catch (err) {
                alert('Error subiendo video: ' + err.message);
            } finally {
                setIsUploading(false);
            }
        } else {
            const r = new FileReader();
            r.onload = (ev) => {
                // TODO: F3 socket emit imagen
                const nuevoMsg = { canal: 'anuncios', usuario: currentUser.username, texto: ev.target.result, tipo: 'imagen', fecha: new Date().toISOString() };
                setMensajes(prev => ({ ...prev, anuncios: [...prev.anuncios, nuevoMsg] }));
                setIsUploading(false);
            };
            r.readAsDataURL(file);
        }
        e.target.value = '';
    };

    const renderBurbuja = (msg, index) => {
        const isOwn = currentUser && msg.usuario === currentUser.username;
        const hora = new Date(msg.fecha).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        if (msg.canal === 'clash_logs') {
            return (
                <div key={index} className="log-msg">
                    <span>{msg.texto}</span><span className="log-time">{hora}</span>
                </div>
            );
        }

        let content = <span className="msg-text">{convertirLinks(msg.texto)}</span>;
        if (msg.tipo === 'imagen') {
            content = <img src={msg.texto} className="chat-image" alt="media" onClick={() => window.open(msg.texto, '_blank')} />;
        } else if (msg.tipo === 'video') {
            content = <video src={msg.texto} className="chat-video" controls />;
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

    return (
        <div className="chat-container">
            <div className="chat-tabs">
                <button className={`chat-tab ${canalActivo === 'general' ? 'active' : ''}`} onClick={() => setCanalActivo('general')}>💬 General</button>
                <button className={`chat-tab ${canalActivo === 'anuncios' ? 'active' : ''}`} onClick={() => setCanalActivo('anuncios')}>📢 Anuncios</button>
                <button className={`chat-tab ${canalActivo === 'clash' ? 'active' : ''}`} onClick={() => setCanalActivo('clash')}>⚔️ Clash</button>
            </div>

            <div className="chat-messages">
                {mensajes[canalActivo]?.map((msg, idx) => renderBurbuja(msg, idx))}
                <div ref={chatEndRef} />
            </div>

            <form className="chat-input-area" onSubmit={(e) => handleSendMessage(e, canalActivo)}>
                {canalActivo === 'anuncios' && esAdmin && (
                    <div className="upload-btn-wrapper">
                        <button type="button" className="btn-upload">📎</button>
                        <input type="file" accept="image/*,video/mp4" onChange={handleUploadMedia} />
                    </div>
                )}
                
                {((canalActivo === 'anuncios' && esAdmin) || canalActivo !== 'anuncios') ? (
                    <>
                        <input 
                            type="text" 
                            placeholder="Escribe un mensaje..." 
                            value={inputValues[canalActivo]}
                            onChange={(e) => setInputValues(prev => ({...prev, [canalActivo]: e.target.value}))}
                        />
                        <button type="submit" disabled={isUploading}>{isUploading ? '⏳' : 'Enviar'}</button>
                    </>
                ) : (
                    <p className="chat-read-only">Solo los administradores pueden enviar anuncios.</p>
                )}
            </form>
        </div>
    );
}

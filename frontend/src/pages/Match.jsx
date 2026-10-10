import React, { useState, useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Match.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Match() {
    const [view, setView] = useState('private'); // 'private' or 'game_result'
    const [rivalData, setRivalData] = useState(null);
    const [maxMontoPermitido, setMaxMontoPermitido] = useState(10000);
    const [chatMessages, setChatMessages] = useState([]);
    const [chatInput, setChatInput] = useState('');
    
    // Configuración
    const [gameMode, setGameMode] = useState('');
    const [monto, setMonto] = useState('');
    const [btnState, setBtnState] = useState({ text: '🎮 COMENZAR', disabled: true, cssClass: '' });
    const [winText, setWinText] = useState('Ganancia: $0');
    const [validationMsg, setValidationMsg] = useState('');

    // Modal
    const [showConfirmModal, setShowConfirmModal] = useState(false);
    const [confirmData, setConfirmData] = useState({ modo: '', monto: '' });
    const [confirmBtnText, setConfirmBtnText] = useState('Aceptar');

    // API Result view
    const [apiStatusText, setApiStatusText] = useState('Buscando resultado en Clash Royale...');
    const [apiResult, setApiResult] = useState(null); // { esGanador, premio, ganador, crowns, mensaje }

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const chatEndRef = useRef(null);
    const simulacionRef = useRef(0); // id de la última consulta de ganancia (descarta respuestas viejas)
    const gananciaTimerRef = useRef(null); // S8: debounce de la consulta de ganancia (antes: una petición por cada tecla/flecha del input)

    useEffect(() => () => clearTimeout(gananciaTimerRef.current), []);

    useEffect(() => {
        // Auto-scroll chat
        if (chatEndRef.current) chatEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }, [chatMessages]);

    useEffect(() => {
        // TODO: F3 conectar a socket.io global
        // Escuchar 'partida_encontrada', 'juego_iniciado', 'resultado_api', etc.
    }, []);

    // D4: la comisión la calcula el servidor (ComisionService). El frontend ya no replica la fórmula.
    const actualizarGanancia = async (dinero) => {
        const id = ++simulacionRef.current;
        try {
            const res = await fetch(`${API_BASE_URL}/api/comisiones/simular?monto=${dinero}`);
            const data = await res.json();
            if (id !== simulacionRef.current) return; // llegó una consulta más nueva
            setWinText(data.success ? `Si ganas recibes: $${data.premio}` : "Ganancia: $0");
        } catch (e) {
            if (id === simulacionRef.current) setWinText("Ganancia: $0");
        }
    };

    const validarNegociacion = (modo, dineroRaw) => {
        const dinero = parseInt(dineroRaw);
        let error = "";
        
        if (modo.length < 3) {}
        else if (!dineroRaw) {}
        else if (isNaN(dinero)) {}
        else if (dinero < 1000) error = "Mínimo $1.000";
        else if (dinero > 10000) error = "Máximo $10.000";
        else if (dinero > maxMontoPermitido) error = `Tope saldos: $${maxMontoPermitido}`;

        setValidationMsg(error);

        if (!isNaN(dinero) && dinero >= 1000) {
            clearTimeout(gananciaTimerRef.current);
            gananciaTimerRef.current = setTimeout(() => actualizarGanancia(dinero), 400);
        } else {
            simulacionRef.current++;
            clearTimeout(gananciaTimerRef.current);
            setWinText("Ganancia: $0");
        }

        if (error === "" && modo.length >= 3 && !isNaN(dinero)) {
            setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
        } else {
            setBtnState({ text: '🎮 COMENZAR', disabled: true, cssClass: '' });
        }
    };

    const handleModeChange = (e) => {
        const val = e.target.value;
        setGameMode(val);
        validarNegociacion(val, monto);
        // TODO: socket.emit('negociacion_live', {...})
    };

    const handleMontoChange = (e) => {
        const val = e.target.value;
        setMonto(val);
        validarNegociacion(gameMode, val);
        // TODO: socket.emit('negociacion_live', {...})
    };

    const handleStartClick = () => {
        if (!currentUser) return;
        if (btnState.text.includes('ESPERANDO')) {
            // Cancelar
            // socket.emit('cancelar_inicio');
            setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
            return;
        }
        
        setBtnState({ text: '⏳ ESPERANDO AL RIVAL... (Click cancelar)', disabled: false, cssClass: 'waiting-cancel' });
        // socket.emit('iniciar_juego', { dinero: monto, modo: gameMode });
    };

    const handleChatSubmit = (e) => {
        e.preventDefault();
        if (chatInput && currentUser) {
            // socket.emit('mensaje_privado', { ... })
            setChatMessages(prev => [...prev, { usuario: currentUser.username, texto: chatInput, fecha: new Date().toISOString() }]);
            setChatInput('');
        }
    };

    const handleAcceptMatch = () => {
        // socket.emit('confirmar_partida_resp', { acepta: true });
        setConfirmBtnText("Esperando...");
    };

    const handleRejectMatch = () => {
        // socket.emit('confirmar_partida_resp', { acepta: false });
        setShowConfirmModal(false);
    };

    return (
        <div className="match-container">
            {view === 'private' ? (
                <div className="view-private">
                    <header className="match-header">
                        <h3>🔒 Sala Privada</h3>
                        <button className="icon-btn-danger">❌</button>
                    </header>

                    <div className="private-room-content">
                        <div className="match-info">
                            <h2 style={{margin:0}}>VS {rivalData?.username || 'Rival'}</h2>
                            <div className="rival-stats-box">
                                <span title="Win Rate" style={{color: '#43b581'}}>🏆 50%</span>
                                <span title="Culpable Disputas" style={{color: '#bbb'}}>💀 0</span>
                                <span title="Huidas">🏃 0</span>
                            </div>
                            <span style={{fontSize: '0.8rem', color: '#faa61a', display: 'block', marginTop: 5}}>Tope: ${maxMontoPermitido.toLocaleString()}</span>
                        </div>

                        <div className="mini-chat">
                            {chatMessages.map((m, i) => (
                                <div key={i} className={`msg ${m.usuario === currentUser?.username ? 'own' : 'other'}`}>
                                    <span className="msg-user">{m.usuario}</span>
                                    <span className="msg-text">{m.texto}</span>
                                </div>
                            ))}
                            <div ref={chatEndRef} />
                        </div>

                        <form className="chat-input-area mini" onSubmit={handleChatSubmit}>
                            <input type="text" value={chatInput} onChange={e => setChatInput(e.target.value)} placeholder="Chatea..." style={{margin:0}} />
                            <button type="submit">Enviar</button>
                        </form>

                        <div className="negotiation-box">
                            <h4>⚙️ Configuración</h4>
                            <div className="inputs-row">
                                <div className="input-group">
                                    <label>Modo:</label>
                                    <input type="text" placeholder="Ej: Elección" value={gameMode} onChange={handleModeChange} />
                                </div>
                                <div className="input-group">
                                    <label>Monto:</label>
                                    <input type="number" placeholder="Mín: 1000" value={monto} onChange={handleMontoChange} />
                                </div>
                            </div>
                            <p style={{fontSize:'0.9rem', fontWeight:'bold', marginTop:5, color: winText.includes('$0') ? '#bbb' : '#4ecca3'}}>
                                {winText}
                            </p>
                            <p className="validation-error">{validationMsg}</p>
                            <button 
                                className={`start-game-btn ${btnState.cssClass}`} 
                                disabled={btnState.disabled}
                                onClick={handleStartClick}
                            >
                                {btnState.text}
                            </button>
                        </div>
                    </div>
                </div>
            ) : (
                <div className="view-game-result">
                    <header className="match-header">
                        <h3>⚔️ Partida en Curso</h3>
                    </header>
                    <div className="game-lobby-content">
                        {!apiResult ? (
                            <>
                                <div className="spinner-container">
                                    <div className="pulse-emoji">⚔️</div>
                                </div>
                                <h2>{apiStatusText}</h2>
                                <p style={{color: '#bbb', marginTop: 10}}>La API detectará automáticamente quién ganó.</p>
                                <p style={{color: '#faa61a', fontSize: '0.9rem'}}>⏱️ Tiempo límite: 10 minutos</p>
                            </>
                        ) : (
                            <div className="api-result-display">
                                <h1 style={{color: apiResult.esGanador ? '#43b581' : '#ed4245'}}>
                                    {apiResult.esGanador ? `🏆 ¡GANASTE! +$${apiResult.premio?.toLocaleString()}` : `💀 Perdiste. ${apiResult.ganador} ganó.`}
                                </h1>
                                <p>Coronas: {apiResult.crowns}</p>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {showConfirmModal && (
                <div className="modal-overlay">
                    <div className="modal-content text-center">
                        <h2>⚠️ ¿Aceptas la partida?</h2>
                        <p>Modo: <strong style={{color:'#7289da'}}>{confirmData.modo}</strong></p>
                        <p>Monto: <strong style={{color:'#43b581'}}>${confirmData.monto}</strong></p>
                        <div style={{marginTop: 20, display: 'flex', gap: 10, justifyContent: 'center'}}>
                            <button style={{background:'#43b581', padding:'10px 20px', border:'none', color:'white', borderRadius:4}} onClick={handleAcceptMatch}>{confirmBtnText}</button>
                            <button style={{background:'#ed4245', padding:'10px 20px', border:'none', color:'white', borderRadius:4}} onClick={handleRejectMatch}>Rechazar</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import './Match.css';
import VozDiscord from '../components/VozDiscord';

const URL_SEGURA_REGEX = /(https?:\/\/[^\s]+)/g;

const formatearRelojIndividual = (ms) => {
    const totalSeg = Math.max(0, Math.ceil((Number(ms) || 0) / 1000));
    const min = Math.floor(totalSeg / 60);
    const seg = totalSeg % 60;
    return `${min}:${String(seg).padStart(2, '0')}`;
};

const leerMsReloj = (valor, respaldo) => {
    const n = Number(valor);
    return Number.isFinite(n) ? n : respaldo;
};

const claseUrgenciaReloj = (ms) => {
    if (ms <= 15000) return 'reloj-peligro';
    if (ms <= 35000) return 'reloj-alerta';
    return '';
};

const renderTextoPrivado = (texto) => {
    const partes = String(texto ?? '').split(URL_SEGURA_REGEX);
    return partes.map((parte, idx) => (
        /^https?:\/\/[^\s]+$/.test(parte)
            ? <a key={idx} href={parte} target="_blank" rel="noopener noreferrer" className="chat-link">{parte}</a>
            : parte
    ));
};

const formatearHoraPrivada = (fecha) => {
    if (!fecha) return '';
    const d = new Date(fecha);
    return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
};

export default function Match() {
    // Si el usuario sale de esta pantalla y vuelve a mitad de partida, el estado sale del store (no se pierde la sala)
    const [view, setView] = useState(() => {
        const estado = useAppStore.getState().matchState.status;
        return (estado === 'playing' || estado === 'finished') ? 'game_result' : 'private'; // 'private' or 'game_result'
    });
    const [rivalData, setRivalData] = useState(() => useAppStore.getState().matchState.rival || null);
    const [maxMontoPermitido, setMaxMontoPermitido] = useState(() => useAppStore.getState().matchState.maxMonto || 10000);
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
    const [apiResult, setApiResult] = useState(() => useAppStore.getState().matchState.resultado || null); // { esGanador, premio, ganador, mensaje } o { disputa: true, mensaje }
    const [flujoCompletado, setFlujoCompletado] = useState(() => useAppStore.getState().matchState.status === 'finished');
    const [ahoraMs, setAhoraMs] = useState(() => Date.now());

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const connectSocket = useAppStore(state => state.connectSocket);
    const matchState = useAppStore(state => state.matchState);
    const setMatchState = useAppStore(state => state.setMatchState);
    const clearMatchState = useAppStore(state => state.clearMatchState);

    // Deja la pantalla lista para una partida nueva (partida cancelada o el usuario cierra el resultado)
    const resetMatch = useCallback(() => {
        clearMatchState();
        setView('private');
        setChatMessages([]);
        setRivalData(null);
        setApiResult(null);
        setFlujoCompletado(false);
        setGameMode('');
        setMonto('');
        setValidationMsg('');
        setWinText('Ganancia: $0');
        setBtnState({ text: '🎮 COMENZAR', disabled: true, cssClass: '' });
        setShowConfirmModal(false);
    }, [clearMatchState]);

    const chatEndRef = useRef(null);
    const simulacionRef = useRef(0); // id de la última consulta de ganancia (descarta respuestas viejas)
    const gananciaTimerRef = useRef(null); // S8: debounce de la consulta de ganancia (antes: una petición por cada tecla/flecha del input)

    useEffect(() => () => clearTimeout(gananciaTimerRef.current), []);

    useEffect(() => {
        if (currentUser) connectSocket();
    }, [currentUser, connectSocket]);

    // Mantiene la vista local sincronizada con el estado persistente que el socket restaura al reconectar.
    useEffect(() => {
        if (!matchState.inMatch) return;
        setView(matchState.status === 'playing' || matchState.status === 'finished' ? 'game_result' : 'private');
        if (matchState.rival) setRivalData(matchState.rival);
        if (matchState.maxMonto) setMaxMontoPermitido(matchState.maxMonto);
        if (matchState.gameMode !== undefined) setGameMode(matchState.gameMode || '');
        if (matchState.monto !== undefined) setMonto(matchState.monto || '');
        if (matchState.resultado !== undefined) setApiResult(matchState.resultado || null);
        if (matchState.status === 'playing') setFlujoCompletado(false);
    }, [matchState.inMatch, matchState.status, matchState.rival, matchState.maxMonto,
    matchState.gameMode, matchState.monto, matchState.resultado]);

    useEffect(() => {
        const hayRelojEnVivo = Boolean(
            matchState.inMatch && view === 'private' && (
                matchState.timerNegociacion?.activo ||
                (matchState.rivalDesconectado && matchState.rivalDesconectado.tiempo !== 'indefinido')
            )
        );
        if (!hayRelojEnVivo) return undefined;
        const tick = setInterval(() => setAhoraMs(Date.now()), 250);
        return () => clearInterval(tick);
    }, [matchState.inMatch, view, matchState.timerNegociacion?.activo, matchState.rivalDesconectado]);

    useEffect(() => {
        // Auto-scroll chat
        if (chatEndRef.current) chatEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }, [chatMessages]);

    useEffect(() => {
        if (!socket) return;

        const onPartidaEncontrada = (data) => {
            const rival = data.p1?.id === currentUser?.id ? data.p2 : data.p1;
            // Partida nueva: se limpia cualquier rastro de la anterior
            setChatMessages([]);
            setGameMode('');
            setMonto('');
            setValidationMsg('');
            setWinText('Ganancia: $0');
            setBtnState({ text: '🎮 COMENZAR', disabled: true, cssClass: '' });
            setApiResult(null);
            setFlujoCompletado(false);
            setShowConfirmModal(false);
            setRivalData(rival);
            setMaxMontoPermitido(data.maxMonto || 10000);
            setView('private');
            setMatchState({
                inMatch: true,
                salaId: data.salaId,
                rival,
                maxMonto: data.maxMonto || 10000,
                status: 'negotiating',
                resultado: null,
                gameMode: '',
                monto: '',
                rivalDesconectado: null,
                timerNegociacion: null
            });
        };

        const onActualizarNegociacion = (data) => {
            if (data.modo !== undefined) setGameMode(data.modo);
            if (data.dinero !== undefined) setMonto(data.dinero);
            validarNegociacion(data.modo !== undefined ? data.modo : gameMode, data.dinero !== undefined ? data.dinero : monto);
        };

        const onEsperandoInicioRival = () => {
            setBtnState({ text: '⏳ ESPERANDO AL RIVAL... (Click cancelar)', disabled: false, cssClass: 'waiting-cancel' });
        };

        const onRivalListoInicio = () => {
            setBtnState({ text: '⚡ RIVAL LISTO - ¡DALE COMENZAR!', disabled: false, cssClass: 'rival-ready' });
        };

        const onConfirmarPartida = (data) => {
            // El servidor manda { modo, monto } (no "dinero")
            setConfirmData({ modo: data.modo, monto: data.monto ?? data.dinero });
            setConfirmBtnText("Aceptar");
            setShowConfirmModal(true);
        };

        const onConfirmacionRechazada = () => {
            setShowConfirmModal(false);
            setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
            alert('La confirmación de la partida fue cancelada');
        };

        const onRivalCanceloInicio = () => {
            setShowConfirmModal(false);
            setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
        };

        const onErrorNegociacion = (msg) => {
            const errorMsg = typeof msg === 'string' ? msg : msg?.value || 'Error en la negociación';
            alert(errorMsg);
            setShowConfirmModal(false);
            setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
        };

        const onJuegoIniciado = () => {
            setShowConfirmModal(false);
            setView('game_result');
            setApiStatusText('Buscando resultado en Clash Royale...');
            setMatchState({ inMatch: true, status: 'playing', rivalDesconectado: null, timerNegociacion: null });
        };

        const onResultadoApi = (data) => {
            // El servidor manda { ganador, premio, esGanador, mensaje } (ResultadoPartidaListener); no envía coronas
            const resultado = {
                esGanador: data.esGanador === true,
                premio: data.premio,
                ganador: data.ganador,
                mensaje: data.mensaje
            };
            setView('game_result');
            setApiResult(resultado);
            setMatchState({ status: 'finished', resultado });
        };

        const onMensajePrivado = (data) => {
            setChatMessages(prev => [...prev, data]);
        };

        const onRestaurarPartida = (data) => {
            if (data.rival) setRivalData(data.rival);
            if (data.maxMonto) setMaxMontoPermitido(data.maxMonto);
            if (data.lastModo) setGameMode(data.lastModo);
            if (data.lastDinero) setMonto(data.lastDinero);
            if (data.historial && Array.isArray(data.historial)) setChatMessages(data.historial);
            const desconexionPrev = data.rivalDesconectado ? useAppStore.getState().matchState.rivalDesconectado : null;
            if (data.iniciado) {
                setView('game_result');
                setApiResult(null);
                setFlujoCompletado(false);
                setMatchState({
                    inMatch: true,
                    salaId: data.salaId,
                    rival: data.rival || null,
                    maxMonto: data.maxMonto || 10000,
                    status: 'playing',
                    resultado: null,
                    rivalDesconectado: desconexionPrev,
                    timerNegociacion: null
                });
            } else {
                setView('private');
                setMatchState({
                    inMatch: true,
                    salaId: data.salaId,
                    rival: data.rival || null,
                    maxMonto: data.maxMonto || 10000,
                    status: 'negotiating',
                    gameMode: data.lastModo || '',
                    monto: data.lastDinero || '',
                    rivalDesconectado: desconexionPrev,
                    timerNegociacion: data.timerNegociacion
                        ? { ...data.timerNegociacion, recibidoEn: Date.now() }
                        : null
                });
            }
        };

        const onMatchCancelado = (data) => {
            alert("⚠️ " + (data?.motivo || 'Partida cancelada'));
            resetMatch();
        };

        const onErrorSocket = (data) => {
            if (!data || !['cancelar_busqueda', 'cancelar_inicio', 'cancelar_match', 'activar_timer_negociacion'].includes(data.evento)) return;
            alert(data.mensaje || 'No se pudo completar la operación. Inténtalo de nuevo.');
            if (data.evento === 'cancelar_busqueda') setMatchState({ status: 'searching' });
        };
        const onCancelacionCompletada = (data) => {
            if (data?.evento === 'cancelar_busqueda') setMatchState({ status: 'idle' });
            if (data?.evento === 'cancelar_inicio') {
                setBtnState({ text: '🎮 COMENZAR', disabled: false, cssClass: 'enabled' });
            }
        };

        // Búsqueda de partida
        const onBuscandoActivo = () => setMatchState({ status: 'searching' }); // reconexión con la búsqueda aún activa
        const onErrorBusqueda = (msg) => {
            alert(typeof msg === 'string' ? msg : msg?.mensaje || 'No se pudo iniciar la búsqueda');
            setMatchState({ status: 'idle' });
        };
        const onBusquedaTimeout = (data) => {
            alert(data?.mensaje || 'Tu búsqueda fue cancelada porque nadie respondió.');
            setMatchState({ status: 'idle' });
        };

        // Cierre del flujo: el servidor ya liberó a los jugadores (tras el resultado, una disputa o un timeout)
        const onFlujoCompletado = () => {
            if (!useAppStore.getState().matchState.inMatch) return;
            setShowConfirmModal(false);
            setFlujoCompletado(true);
            setMatchState({ status: 'finished' });
        };
        const onDisputa = (data) => {
            const resultado = { disputa: true, mensaje: data?.mensaje || 'Resultado no claro, disputa creada.' };
            setView('game_result');
            setApiResult(resultado);
            setMatchState({ status: 'finished', resultado });
        };
        const onRivalDesconectado = (data) => {
            setMatchState({
                rivalDesconectado: { ...(data || { tiempo: 90 }), recibidoEn: Date.now() }
            });
        };
        const onRivalReconectado = () => {
            setMatchState({ rivalDesconectado: null });
        };

        socket.on('partida_encontrada', onPartidaEncontrada);
        socket.on('actualizar_negociacion', onActualizarNegociacion);
        socket.on('esperando_inicio_rival', onEsperandoInicioRival);
        socket.on('rival_listo_inicio', onRivalListoInicio);
        socket.on('confirmar_partida', onConfirmarPartida);
        socket.on('confirmacion_rechazada', onConfirmacionRechazada);
        socket.on('rival_cancelo_inicio', onRivalCanceloInicio);
        socket.on('error_negociacion', onErrorNegociacion);
        socket.on('juego_iniciado', onJuegoIniciado);
        socket.on('resultado_api', onResultadoApi);
        socket.on('mensaje_privado', onMensajePrivado);
        socket.on('restaurar_partida', onRestaurarPartida);
        socket.on('match_cancelado', onMatchCancelado);
        socket.on('error_socket', onErrorSocket);
        socket.on('cancelacion_completada', onCancelacionCompletada);
        socket.on('buscando_activo', onBuscandoActivo);
        socket.on('error_busqueda', onErrorBusqueda);
        socket.on('busqueda_timeout', onBusquedaTimeout);
        socket.on('flujo_completado', onFlujoCompletado);
        socket.on('disputa_creada', onDisputa);
        socket.on('disputa_timeout', onDisputa);
        socket.on('rival_desconectado', onRivalDesconectado);
        socket.on('rival_reconectado', onRivalReconectado);

        return () => {
            socket.off('partida_encontrada', onPartidaEncontrada);
            socket.off('actualizar_negociacion', onActualizarNegociacion);
            socket.off('esperando_inicio_rival', onEsperandoInicioRival);
            socket.off('rival_listo_inicio', onRivalListoInicio);
            socket.off('confirmar_partida', onConfirmarPartida);
            socket.off('confirmacion_rechazada', onConfirmacionRechazada);
            socket.off('rival_cancelo_inicio', onRivalCanceloInicio);
            socket.off('error_negociacion', onErrorNegociacion);
            socket.off('juego_iniciado', onJuegoIniciado);
            socket.off('resultado_api', onResultadoApi);
            socket.off('mensaje_privado', onMensajePrivado);
            socket.off('restaurar_partida', onRestaurarPartida);
            socket.off('match_cancelado', onMatchCancelado);
            socket.off('error_socket', onErrorSocket);
            socket.off('cancelacion_completada', onCancelacionCompletada);
            socket.off('buscando_activo', onBuscandoActivo);
            socket.off('error_busqueda', onErrorBusqueda);
            socket.off('busqueda_timeout', onBusquedaTimeout);
            socket.off('flujo_completado', onFlujoCompletado);
            socket.off('disputa_creada', onDisputa);
            socket.off('disputa_timeout', onDisputa);
            socket.off('rival_desconectado', onRivalDesconectado);
            socket.off('rival_reconectado', onRivalReconectado);
        };
    }, [socket, currentUser, gameMode, monto, maxMontoPermitido, setMatchState, resetMatch]);

    // D4: la comisión la calcula el servidor (ComisionService). El frontend ya no replica la fórmula.
    const actualizarGanancia = async (dinero) => {
        const id = ++simulacionRef.current;
        const { ok, data } = await llamarApi(`/api/comisiones/simular?monto=${encodeURIComponent(dinero)}`);
        if (id !== simulacionRef.current) return; // llegó una consulta más nueva
        setWinText(ok && data?.success ? `Si ganas recibes: $${data.premio}` : "Ganancia: $0");
    };

    const validarNegociacion = (modo, dineroRaw) => {
        const dinero = parseInt(dineroRaw);
        let error = "";

        if (modo.length < 3) { }
        else if (!dineroRaw) { }
        else if (isNaN(dinero)) { }
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
        if (socket && matchState?.salaId) {
            socket.emit('negociacion_live', { salaId: matchState.salaId, modo: val, dinero: monto });
        }
    };

    const handleMontoChange = (e) => {
        const val = e.target.value;
        setMonto(val);
        validarNegociacion(gameMode, val);
        if (socket && matchState?.salaId) {
            socket.emit('negociacion_live', { salaId: matchState.salaId, modo: gameMode, dinero: val });
        }
    };

    const handleStartClick = () => {
        if (!currentUser || !socket) return;
        if (btnState.text.includes('ESPERANDO')) {
            socket.emit('cancelar_inicio');
            return;
        }

        setBtnState({ text: '⏳ ESPERANDO AL RIVAL... (Click cancelar)', disabled: false, cssClass: 'waiting-cancel' });
        socket.emit('iniciar_juego', { dinero: monto, modo: gameMode });
    };

    const handleChatSubmit = (e) => {
        e.preventDefault();
        const texto = chatInput.trim();
        if (texto && currentUser && socket && matchState.salaId) {
            // El servidor exige salaId (sala_<número>) y reenvía el mensaje a TODA la sala, remitente incluido:
            // por eso no se agrega localmente (saldría duplicado).
            socket.emit('mensaje_privado', {
                salaId: matchState.salaId,
                texto
            });
            setChatInput('');
        }
    };

    const handleAcceptMatch = () => {
        if (socket) socket.emit('confirmar_partida_resp', { acepta: true });
        setConfirmBtnText("Esperando...");
    };

    const handleRejectMatch = () => {
        if (socket) socket.emit('confirmar_partida_resp', { acepta: false });
        setShowConfirmModal(false);
    };

    const handleBuscar = () => {
        if (!currentUser || !socket) return;
        socket.emit('buscar_partida'); // S2: el servidor identifica al jugador por la sesión JWT del socket
        setMatchState({ status: 'searching' });
    };

    const handleCancelarBusqueda = () => {
        if (socket) socket.emit('cancelar_busqueda');
    };

    // Botón ❌ de la sala: el servidor cancela la partida para ambos (match_cancelado) y cuenta como salida
    const handleSalir = () => {
        if (!socket) return;
        if (!window.confirm('¿Salir de la sala? La partida se cancela para los dos y cuenta como una salida tuya.')) return;
        socket.emit('cancelar_match', { motivo: 'Oprimió X' });
    };

    const timerNeg = matchState.timerNegociacion;
    const timerActivo = Boolean(timerNeg?.activo);
    const duracionBaseTimerMs = (Number(timerNeg?.duracionSegundos) || 150) * 1000;
    const transcurridoTimerMs = timerActivo && timerNeg?.recibidoEn ? Math.max(0, ahoraMs - timerNeg.recibidoEn) : 0;
    const soyJugador1Timer = Number(timerNeg?.p1Id) === Number(currentUser?.id);
    const miRestanteMs = timerActivo
        ? Math.max(0, leerMsReloj(soyJugador1Timer ? timerNeg?.p1RestanteMs : timerNeg?.p2RestanteMs, duracionBaseTimerMs) - transcurridoTimerMs)
        : duracionBaseTimerMs;
    const rivalRestanteMs = timerActivo
        ? Math.max(0, leerMsReloj(soyJugador1Timer ? timerNeg?.p2RestanteMs : timerNeg?.p1RestanteMs, duracionBaseTimerMs) - transcurridoTimerMs)
        : duracionBaseTimerMs;

    const handleActivarTimer = () => {
        if (!socket || !currentUser || !matchState.salaId || timerActivo) return;
        socket.emit('activar_timer_negociacion');
    };

    const desconexionActiva = matchState.rivalDesconectado;
    const rivalDesconectadoMsg = !desconexionActiva
        ? ''
        : desconexionActiva.tiempo === 'indefinido'
            ? '⚠️ Rival desconectado. Esperando resultado de la API...'
            : (() => {
                const baseMs = (Number(desconexionActiva.tiempo) || 90) * 1000;
                const transcurrido = desconexionActiva.recibidoEn ? Math.max(0, ahoraMs - desconexionActiva.recibidoEn) : 0;
                const segsRest = Math.max(1, Math.ceil((baseMs - transcurrido) / 1000));
                return `⚠️ Rival desconectado. Tiene ${segsRest}s para reconectarse.`;
            })();

    // Sin partida en curso: pantalla para buscar rival
    if (!matchState.inMatch) {
        const buscando = matchState.status === 'searching';
        return (
            <div className="match-container">
                <header className="match-header">
                    <h3>⚔️ Buscar partida</h3>
                </header>
                <div className="search-view-content">
                    {buscando ? (
                        <>
                            <div className="spinner-container">
                                <div className="pulse-emoji">🔍</div>
                            </div>
                            <h2>Buscando rival...</h2>
                            <p>Te llevamos a la sala en cuanto alguien acepte.</p>
                            <button className="search-btn cancel" onClick={handleCancelarBusqueda}>Cancelar búsqueda</button>
                        </>
                    ) : (
                        <>
                            <div className="pulse-emoji">⚔️</div>
                            <h2>¿Listo para jugar?</h2>
                            <p>Te emparejamos con otro jugador y acuerdan modo y monto en una sala privada.</p>
                            <button className="search-btn" onClick={handleBuscar} disabled={!socket || !currentUser}>🔍 Buscar partida</button>
                            <VozDiscord enPartida={false} />
                        </>
                    )}
                </div>
            </div>
        );
    }

    return (
        <div className="match-container">
            {view === 'private' ? (
                <div className="view-private">
                    <header className="match-header">
                        <h3>🔒 Sala Privada</h3>
                        <button className="icon-btn-danger" onClick={handleSalir} title="Salir de la sala">❌</button>
                    </header>

                    <div className="private-room-content">
                        <div className="match-info">
                            <h2 style={{ margin: 0 }}>VS {rivalData?.username || 'Rival'}</h2>
                            <div className="rival-stats-box">
                                <span title="Porcentaje de victorias" style={{ color: '#43b581' }}>
                                    🏆 {rivalData?.total_partidas > 0 ? Math.round(((rivalData.total_victorias || 0) / rivalData.total_partidas) * 100) : 0}%
                                </span>
                                <span title="Culpable en disputas" style={{ color: '#bbb' }}>
                                    💀 {rivalData?.faltas ?? 0}
                                </span>
                                <span title="Salidas de sala">
                                    🏃 {rivalData?.salidas_chat ?? 0}
                                </span>
                            </div>
                            <span style={{ fontSize: '0.8rem', color: '#faa61a', display: 'block', marginTop: 5 }}>Tope: ${maxMontoPermitido.toLocaleString()}</span>
                            {rivalDesconectadoMsg && (
                                <span style={{ fontSize: '0.82rem', color: '#ed4245', display: 'block', marginTop: 6, fontWeight: 'bold' }}>
                                    {rivalDesconectadoMsg}
                                </span>
                            )}
                        </div>

                        {!timerActivo ? (
                            <div className="negotiation-timer-bar">
                                <span className="negotiation-timer-hint">
                                    ⏱️ Reloj individual opcional (2:30 por jugador)
                                </span>
                                <button type="button" className="timer-toggle-btn" onClick={handleActivarTimer}>
                                    ⏱️ Activar temporizador (2:30)
                                </button>
                            </div>
                        ) : (
                            <div className="negotiation-timer-panel">
                                <div className="timer-panel-caption">
                                    ⏱️ Temporizador individual activo{timerNeg?.activadoPor ? ` · Activado por ${timerNeg.activadoPor}` : ''} · Tus acciones solo reinician tu reloj
                                </div>
                                <div className="timer-clocks-row">
                                    <div className={`timer-clock-card own ${claseUrgenciaReloj(miRestanteMs)}`}>
                                        <span className="timer-clock-label">Tu tiempo</span>
                                        <span className="timer-clock-value">{formatearRelojIndividual(miRestanteMs)}</span>
                                    </div>
                                    <div className={`timer-clock-card rival ${claseUrgenciaReloj(rivalRestanteMs)}`}>
                                        <span className="timer-clock-label">Tiempo de {rivalData?.username || 'Rival'}</span>
                                        <span className="timer-clock-value">{formatearRelojIndividual(rivalRestanteMs)}</span>
                                    </div>
                                </div>
                            </div>
                        )}

                        <div className="mini-chat">
                            {chatMessages.length === 0 ? (
                                <div className="mini-chat-empty">
                                    Saluda a tu rival y acuerden aquí el modo y el monto.
                                </div>
                            ) : (
                                chatMessages.map((m, i) => {
                                    const esPropio = m.usuario === currentUser?.username;
                                    const hora = formatearHoraPrivada(m.fecha);
                                    return (
                                        <div key={i} className={`msg ${esPropio ? 'own' : 'other'}`}>
                                            <span className="msg-user">{m.usuario}</span>
                                            <span className="msg-text">{renderTextoPrivado(m.texto)}</span>
                                            {hora && <span className="msg-time">{hora}</span>}
                                        </div>
                                    );
                                })
                            )}
                            <div ref={chatEndRef} />
                        </div>

                        <form className="chat-input-area mini" onSubmit={handleChatSubmit}>
                            <input type="text" value={chatInput} onChange={e => setChatInput(e.target.value)} placeholder="Escribe un mensaje..." style={{ margin: 0 }} />
                            <button type="submit">Enviar</button>
                        </form>

                        <VozDiscord enPartida={true} />

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
                            <p style={{ fontSize: '0.9rem', fontWeight: 'bold', marginTop: 5, color: winText.includes('$0') ? '#bbb' : '#4ecca3' }}>
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
                        {!apiResult && !flujoCompletado ? (
                            <>
                                <div className="spinner-container">
                                    <div className="pulse-emoji">⚔️</div>
                                </div>
                                <h2>{apiStatusText}</h2>
                                <p style={{ color: '#bbb', marginTop: 10 }}>La API detectará automáticamente quién ganó.</p>
                                <VozDiscord enPartida={true} />
                                <p style={{ color: '#faa61a', fontSize: '0.9rem' }}>⏱️ Tiempo límite: 10 minutos</p>
                            </>
                        ) : (
                            <div className="api-result-display">
                                {!apiResult ? (
                                    <h2>La partida terminó.</h2>
                                ) : apiResult.disputa ? (
                                    <>
                                        <h2 style={{ color: '#faa61a' }}>⚖️ Disputa creada</h2>
                                        <p>{apiResult.mensaje}</p>
                                        <p style={{ color: '#bbb' }}>Un administrador revisará la partida.</p>
                                    </>
                                ) : (
                                    <>
                                        <h1 style={{ color: apiResult.esGanador ? '#43b581' : '#ed4245' }}>
                                            {apiResult.esGanador ? `🏆 ¡GANASTE! +$${apiResult.premio?.toLocaleString()}` : `💀 Perdiste. ${apiResult.ganador} ganó.`}
                                        </h1>
                                        {apiResult.mensaje && <p>{apiResult.mensaje}</p>}
                                    </>
                                )}
                                <button className="result-back-btn" onClick={resetMatch}>Volver</button>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {showConfirmModal && (
                <div className="modal-overlay">
                    <div className="modal-content text-center">
                        <h2>⚠️ ¿Aceptas la partida?</h2>
                        <p>Modo: <strong style={{ color: '#7289da' }}>{confirmData.modo}</strong></p>
                        <p>Monto: <strong style={{ color: '#43b581' }}>${Number(confirmData.monto).toLocaleString()}</strong></p>
                        <div style={{ marginTop: 20, display: 'flex', gap: 10, justifyContent: 'center' }}>
                            <button style={{ background: '#43b581', padding: '10px 20px', border: 'none', color: 'white', borderRadius: 4 }} onClick={handleAcceptMatch}>{confirmBtnText}</button>
                            <button style={{ background: '#ed4245', padding: '10px 20px', border: 'none', color: 'white', borderRadius: 4 }} onClick={handleRejectMatch}>Rechazar</button>
                        </div>
                        <div className="modal-timer-strip">
                            {!timerActivo ? (
                                <button type="button" className="timer-toggle-btn" onClick={handleActivarTimer}>
                                    ⏱️ Activar temporizador (2:30)
                                </button>
                            ) : (
                                <div className="timer-clocks-row">
                                    <div className={`timer-clock-card own ${claseUrgenciaReloj(miRestanteMs)}`}>
                                        <span className="timer-clock-label">Tu tiempo</span>
                                        <span className="timer-clock-value">{formatearRelojIndividual(miRestanteMs)}</span>
                                    </div>
                                    <div className={`timer-clock-card rival ${claseUrgenciaReloj(rivalRestanteMs)}`}>
                                        <span className="timer-clock-label">{rivalData?.username || 'Rival'}</span>
                                        <span className="timer-clock-value">{formatearRelojIndividual(rivalRestanteMs)}</span>
                                    </div>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

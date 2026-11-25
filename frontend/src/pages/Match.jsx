import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import { calcularRestanteSegundos, formatearTiempoReloj } from '../utils/tiempoMatchmaking';
import { DEMO_BUSQUEDAS_MATCHMAKING, DEMO_MATCHMAKING_CONFIG, DEMO_SALA_PRIVADA } from '../data/vistaPreviaDemo';
import './Match.css';
import VozDiscord from '../components/VozDiscord';

const URL_SEGURA_REGEX = /(https?:\/\/[^\s]+)/g;
const ENLACE_CLASH_ROYALE = 'https://link.clashroyale.com';

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
    const [chatAbiertoEnJuego, setChatAbiertoEnJuego] = useState(false);
    const [mensajesPrivadosSinLeer, setMensajesPrivadosSinLeer] = useState(0);
    const [ahoraMs, setAhoraMs] = useState(() => Date.now());
    const [mostrarVariables, setMostrarVariables] = useState(false);
    const [demoVistaMatch, setDemoVistaMatch] = useState('buscar'); // 'buscar' | 'sala' | 'en_curso'

    const currentUser = useAppStore(state => state.user);
    const modoVistaPrevia = useAppStore(state => state.modoVistaPrevia);
    const solicitarAutenticacionVistaPrevia = useAppStore(state => state.solicitarAutenticacionVistaPrevia);
    const esVistaPrevia = !currentUser && modoVistaPrevia;
    const socket = useAppStore(state => state.socket);
    const connectSocket = useAppStore(state => state.connectSocket);
    const matchState = useAppStore(state => state.matchState);
    const setMatchState = useAppStore(state => state.setMatchState);
    const clearMatchState = useAppStore(state => state.clearMatchState);
    const busquedasVisibles = useAppStore(state => state.busquedasVisibles);
    const retoEnviado = useAppStore(state => state.retoEnviado);
    const avisoMatchmaking = useAppStore(state => state.avisoMatchmaking);
    const setAvisoMatchmaking = useAppStore(state => state.setAvisoMatchmaking);
    const matchmakingConfig = useAppStore(state => state.matchmakingConfig);
    const cargarEstadoMatchmaking = useAppStore(state => state.cargarEstadoMatchmaking);

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
        setChatAbiertoEnJuego(false);
        setMensajesPrivadosSinLeer(0);
    }, [clearMatchState]);

    const chatEndRef = useRef(null);
    const chatAbiertoEnJuegoRef = useRef(chatAbiertoEnJuego);
    const viewRef = useRef(view);
    const simulacionRef = useRef(0); // id de la última consulta de ganancia (descarta respuestas viejas)
    const gananciaTimerRef = useRef(null); // S8: debounce de la consulta de ganancia (antes: una petición por cada tecla/flecha del input)
    const ultimaSyncBusquedasRef = useRef(0);

    useEffect(() => {
        chatAbiertoEnJuegoRef.current = chatAbiertoEnJuego;
    }, [chatAbiertoEnJuego]);

    useEffect(() => {
        viewRef.current = view;
    }, [view]);

    useEffect(() => () => clearTimeout(gananciaTimerRef.current), []);

    useEffect(() => {
        if (currentUser) connectSocket();
    }, [currentUser, connectSocket]);

    // Mantiene la vista local sincronizada con el estado persistente que el socket restaura al reconectar.
    useEffect(() => {
        if (!currentUser?.id) return;
        void cargarEstadoMatchmaking();
    }, [currentUser?.id, cargarEstadoMatchmaking]);

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
            (!matchState.inMatch && busquedasVisibles.length > 0) || (
                matchState.inMatch && view === 'private' && (
                    matchState.timerNegociacion?.activo ||
                    (matchState.rivalDesconectado && matchState.rivalDesconectado.tiempo !== 'indefinido')
                )
            ) || (
                matchState.inMatch && view === 'game_result'
                && Boolean(matchState.redireccionClashHastaMs)
                && !matchState.redireccionClashEjecutada
                && !apiResult
                && !flujoCompletado
            )
        );
        if (!hayRelojEnVivo) return undefined;
        const intervalo = !matchState.inMatch ? 1000 : 250;
        const tick = setInterval(() => {
            const ahora = Date.now();
            setAhoraMs(ahora);
            const algunaListaPorHabilitar = !matchState.inMatch && busquedasVisibles.some(item =>
                item?.habilitada === false
                && calcularRestanteSegundos(item.segundosParaHabilitar, item.recibidoEn, ahora) <= 0
            );
            if (algunaListaPorHabilitar && (ahora - ultimaSyncBusquedasRef.current) > 3000) {
                ultimaSyncBusquedasRef.current = ahora;
                void cargarEstadoMatchmaking();
            }
        }, intervalo);
        return () => clearInterval(tick);
    }, [matchState.inMatch, view, matchState.timerNegociacion?.activo, matchState.rivalDesconectado,
        matchState.redireccionClashHastaMs, matchState.redireccionClashEjecutada,
        apiResult, flujoCompletado, busquedasVisibles, cargarEstadoMatchmaking]);

    const abrirClashRoyale = useCallback(() => {
        if (typeof window === 'undefined') return;
        const nuevaVentana = window.open(ENLACE_CLASH_ROYALE, '_blank', 'noopener,noreferrer');
        if (!nuevaVentana && window.location && typeof window.location.assign === 'function') {
            window.location.assign(ENLACE_CLASH_ROYALE);
        }
    }, []);

    const cancelarRedireccionClash = useCallback(() => {
        setMatchState({ redireccionClashHastaMs: null, redireccionClashEjecutada: false });
    }, [setMatchState]);

    useEffect(() => {
        if (esVistaPrevia || apiResult || flujoCompletado) return undefined;
        const hastaMs = Number(matchState.redireccionClashHastaMs);
        if (!Number.isFinite(hastaMs) || hastaMs <= 0 || matchState.redireccionClashEjecutada) {
            return undefined;
        }
        const restanteMs = Math.max(0, hastaMs - Date.now());
        const timer = setTimeout(() => {
            setMatchState({ redireccionClashHastaMs: null, redireccionClashEjecutada: true });
            abrirClashRoyale();
        }, restanteMs);
        return () => clearTimeout(timer);
    }, [
        esVistaPrevia,
        apiResult,
        flujoCompletado,
        matchState.redireccionClashHastaMs,
        matchState.redireccionClashEjecutada,
        setMatchState,
        abrirClashRoyale
    ]);

    useEffect(() => {
        // Auto-scroll chat
        if (chatEndRef.current) chatEndRef.current.scrollIntoView({ behavior: 'smooth' });
    }, [chatMessages, chatAbiertoEnJuego]);

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
            setChatAbiertoEnJuego(false);
            setMensajesPrivadosSinLeer(0);
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

        const onJuegoIniciado = (data) => {
            setShowConfirmModal(false);
            setChatAbiertoEnJuego(false);
            setMensajesPrivadosSinLeer(0);
            setView('game_result');
            setApiStatusText('Buscando resultado en Clash Royale...');
            const actual = useAppStore.getState().matchState;
            const matchIdNuevo = Number(data?.matchId) || actual?.matchId || null;
            const yaTieneRedireccion = Boolean(actual?.redireccionClashHastaMs || actual?.redireccionClashEjecutada);
            setMatchState({
                inMatch: true,
                status: 'playing',
                matchId: matchIdNuevo,
                rivalDesconectado: null,
                timerNegociacion: null,
                ...(yaTieneRedireccion ? {} : { redireccionClashHastaMs: Date.now() + 5000, redireccionClashEjecutada: false })
            });
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
            setMatchState({ status: 'finished', resultado, redireccionClashHastaMs: null });
        };

        const onMensajePrivado = (data) => {
            setChatMessages(prev => [...prev, data]);
            const esDeOtro = Boolean(data?.usuario) && data.usuario !== currentUser?.username;
            const estadoMatch = useAppStore.getState().matchState.status;
            const enVistaApi = viewRef.current === 'game_result'
                || estadoMatch === 'playing'
                || estadoMatch === 'finished';
            if (esDeOtro && enVistaApi && !chatAbiertoEnJuegoRef.current) {
                setMensajesPrivadosSinLeer(prev => prev + 1);
            }
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
                    matchId: data.matchId || null,
                    rival: data.rival || null,
                    maxMonto: data.maxMonto || 10000,
                    status: 'playing',
                    resultado: null,
                    rivalDesconectado: desconexionPrev,
                    timerNegociacion: null,
                    redireccionClashHastaMs: null
                });
            } else {
                setView('private');
                setMatchState({
                    inMatch: true,
                    salaId: data.salaId,
                    matchId: null,
                    rival: data.rival || null,
                    maxMonto: data.maxMonto || 10000,
                    status: 'negotiating',
                    gameMode: data.lastModo || '',
                    monto: data.lastDinero || '',
                    rivalDesconectado: desconexionPrev,
                    timerNegociacion: data.timerNegociacion
                        ? { ...data.timerNegociacion, recibidoEn: Date.now() }
                        : null,
                    redireccionClashHastaMs: null
                });
            }
        };

        const onMatchCancelado = (data) => {
            alert("⚠️ " + (data?.motivo || 'Partida cancelada'));
            resetMatch();
        };

        const onErrorSocket = (data) => {
            if (!data) return;
            if (['desafiar_busqueda', 'responder_reto_matchmaking'].includes(data.evento)) {
                setAvisoMatchmaking(data.mensaje || 'No se pudo completar la acción.');
                return;
            }
            if (!['cancelar_busqueda', 'cancelar_inicio', 'cancelar_match', 'activar_timer_negociacion'].includes(data.evento)) return;
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
            setMatchState({ status: 'finished', redireccionClashHastaMs: null });
        };
        const onDisputa = (data) => {
            const resultado = { disputa: true, mensaje: data?.mensaje || 'Resultado no claro, disputa creada.' };
            setView('game_result');
            setApiResult(resultado);
            setMatchState({ status: 'finished', resultado, redireccionClashHastaMs: null });
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
        if (!currentUser) return;
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
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('iniciar una partida');
            return;
        }
        if (!socket) return;
        if (btnState.text.includes('ESPERANDO')) {
            socket.emit('cancelar_inicio');
            return;
        }

        setBtnState({ text: '⏳ ESPERANDO AL RIVAL... (Click cancelar)', disabled: false, cssClass: 'waiting-cancel' });
        socket.emit('iniciar_juego', { dinero: monto, modo: gameMode });
    };

    const handleChatSubmit = (e) => {
        e.preventDefault();
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('enviar mensajes en la sala privada');
            return;
        }
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
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('buscar partida');
            return;
        }
        if (!socket) return;
        socket.emit('buscar_partida'); // S2: el servidor identifica al jugador por la sesión JWT del socket
        setMatchState({ status: 'searching' });
    };

    const handleCancelarBusqueda = () => {
        if (socket) socket.emit('cancelar_busqueda');
    };

    // Botón ❌ de la sala: el servidor cancela la partida para ambos (match_cancelado) y cuenta como salida
    const handleSalir = () => {
        if (esVistaPrevia) {
            setDemoVistaMatch('buscar');
            return;
        }
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
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('activar el temporizador de negociación');
            return;
        }
        if (!socket || !matchState.salaId || timerActivo) return;
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

    const selectorVistaPreviaMatch = esVistaPrevia ? (
        <div className="match-preview-switcher" role="group" aria-label="Vistas de demostración">
            <button
                type="button"
                className={`match-preview-tab ${demoVistaMatch === 'buscar' ? 'active' : ''}`}
                onClick={() => setDemoVistaMatch('buscar')}
            >
                🔍 Vista previa: Buscar partida
            </button>
            <button
                type="button"
                className={`match-preview-tab ${demoVistaMatch === 'sala' ? 'active' : ''}`}
                onClick={() => setDemoVistaMatch('sala')}
            >
                🔒 Vista previa: Sala privada
            </button>
            <button
                type="button"
                className={`match-preview-tab ${demoVistaMatch === 'en_curso' ? 'active' : ''}`}
                onClick={() => setDemoVistaMatch('en_curso')}
            >
                ⚔️ Vista previa: Partida en curso
            </button>
        </div>
    ) : null;

    // Sin partida en curso: pantalla para buscar rival
    if (!matchState.inMatch && (!esVistaPrevia || demoVistaMatch === 'buscar')) {
        const buscando = matchState.status === 'searching';
        const configActual = esVistaPrevia ? DEMO_MATCHMAKING_CONFIG : matchmakingConfig;
        const variables = configActual?.variables || {};
        const listaBusquedas = esVistaPrevia ? DEMO_BUSQUEDAS_MATCHMAKING : busquedasVisibles;
        const handleDesafiarBusqueda = (busqueda) => {
            if (!currentUser) {
                solicitarAutenticacionVistaPrevia('desafiar a un rival');
                return;
            }
            const targetId = busqueda.buscadorId ?? busqueda.userId;
            if (!socket || !targetId || busqueda.habilitada === false) return;
            if (busqueda.esBuscadorMejor) {
                const ok = window.confirm(
                    `${busqueda.username} está por encima de tu nivel (${busqueda.etiquetaNivel}). ¿Confirmas que quieres jugar contra él?`
                );
                if (!ok) return;
            }
            setAvisoMatchmaking(null);
            socket.emit('desafiar_busqueda', { buscadorId: targetId });
        };

        return (
            <div className="match-container">
                {selectorVistaPreviaMatch}
                <header className="match-header">
                    <h3>⚔️ Buscar partida</h3>
                    <button
                        type="button"
                        className="mm-header-level"
                        aria-expanded={mostrarVariables}
                        onClick={() => setMostrarVariables(prev => !prev)}
                    >
                        Nivel: {configActual?.perfilCualitativo || 'Calibrando'}
                    </button>
                </header>
                {mostrarVariables && (
                    <div className="mm-header-vars-panel">
                        <div className="mm-pills-row">
                            <span className={`mm-pill ${variables.winRate ? 'ok' : ''}`}>
                                📊 Win rate: {variables.winRate ? 'Activo' : 'Sin partidas aún (no afecta)'}
                            </span>
                            <span className={`mm-pill ${variables.clash ? 'ok' : ''}`}>
                                🏆 Clash Royale: {variables.clash ? 'Copas / PoL activos' : 'Pendiente (no afecta)'}
                            </span>
                            <span className={`mm-pill ${variables.liga ? 'ok' : ''}`}>
                                🛡 Liga plataforma: {variables.liga ? 'Activa' : 'Próximamente (no afecta)'}
                            </span>
                        </div>
                        <p className="mm-sub">Tu puntaje numérico exacto es privado.</p>
                    </div>
                )}
                <div className="search-view-content">
                    {avisoMatchmaking && (
                        <div className="mm-notice-banner" role="status">
                            <span>{avisoMatchmaking}</span>
                            <button type="button" className="mm-notice-close" onClick={() => setAvisoMatchmaking(null)}>✕</button>
                        </div>
                    )}

                    {retoEnviado && (
                        <div className="mm-pending-banner" role="status">
                            ⏳ Esperando confirmación de <strong>{retoEnviado.buscadorUsername}</strong> (como eres superior, le preguntamos si aun así quiere jugar contra ti).
                        </div>
                    )}

                    {buscando ? (
                        <>
                            <div className="spinner-container">
                                <div className="pulse-emoji">🔍</div>
                            </div>
                            <h2>Buscando rival justo...</h2>
                            <p>
                                Primero avisamos a jugadores de tu mismo nivel o inferior; los niveles superiores verán tu búsqueda
                                y quedarán habilitados por oleadas (con confirmación previa tuya) hasta 2 minutos antes del cierre.
                            </p>
                            <button className="search-btn cancel" onClick={handleCancelarBusqueda}>Cancelar búsqueda</button>
                        </>
                    ) : (
                        <>
                            <div className="pulse-emoji">⚔️</div>
                            <h2>¿Listo para jugar?</h2>
                            <p>Te emparejamos por nivel justo y acuerdan modo y monto en una sala privada.</p>
                            <button className="search-btn" onClick={handleBuscar} disabled={!esVistaPrevia && (!socket || !currentUser)}>🔍 Buscar partida</button>
                            <VozDiscord enPartida={false} />
                        </>
                    )}

                    <section className="fair-matchmaking-panel" aria-label="Matchmaking justo">
                        <div className="mm-active-searches">
                            <h5>🎮 Rivales buscando ahora ({listaBusquedas.length})</h5>
                            {listaBusquedas.length === 0 ? (
                                <p className="mm-empty">No hay búsquedas visibles en este momento.</p>
                            ) : (
                                <ul className="mm-search-list">
                                    {listaBusquedas.map(b => {
                                        const id = b.buscadorId ?? b.userId;
                                        const habilitadaAhora = b.habilitada !== false;
                                        const restanteHabilitar = habilitadaAhora
                                            ? 0
                                            : calcularRestanteSegundos(b.segundosParaHabilitar, b.recibidoEn, ahoraMs);
                                        const restanteAceptar = calcularRestanteSegundos(
                                            b.segundosRestantesAceptar, b.recibidoEn, ahoraMs, b.expiraEnEpochMs
                                        );
                                        const bloqueado = !habilitadaAhora
                                            || b.retoEstado === 'pendiente'
                                            || b.retoEstado === 'rechazado';
                                        return (
                                            <li key={id} className="mm-search-item">
                                                <div className="mm-search-info">
                                                    <strong>{b.username}</strong>
                                                    <span className={`mm-level-tag ${b.eresMejor ? 'mejor' : (b.esBuscadorMejor ? 'rival-mejor' : 'similar')}`}>
                                                        {b.etiquetaNivel || 'Nivel competitivo disponible'}
                                                    </span>
                                                    {!habilitadaAhora ? (
                                                        <span className="mm-lock-tag">
                                                            {restanteHabilitar > 0
                                                                ? `🔒 Se habilitará en ${formatearTiempoReloj(restanteHabilitar)}`
                                                                : '🔒 Habilitándose...'}
                                                        </span>
                                                    ) : (b.segundosRestantesAceptar > 0 || b.expiraEnEpochMs) ? (
                                                        <span className="mm-accept-time-tag">
                                                            ⏱ Tiempo para aceptar: {formatearTiempoReloj(restanteAceptar)}
                                                        </span>
                                                    ) : null}
                                                </div>
                                                <button
                                                    type="button"
                                                    className="mm-challenge-btn"
                                                    disabled={(!esVistaPrevia && !socket) || bloqueado}
                                                    onClick={() => handleDesafiarBusqueda(b)}
                                                >
                                                    {!habilitadaAhora
                                                        ? (restanteHabilitar > 0
                                                            ? `⏳ En ${formatearTiempoReloj(restanteHabilitar)}`
                                                            : '⏳ Habilitándose...')
                                                        : b.retoEstado === 'pendiente'
                                                            ? 'Esperando confirmación'
                                                            : (b.retoEstado === 'rechazado' ? 'Prefirió otro rival' : '⚔️ Jugar')}
                                                </button>
                                            </li>
                                        );
                                    })}
                                </ul>
                            )}
                        </div>
                    </section>
                </div>
            </div>
        );
    }

    const rivalMostrado = esVistaPrevia ? DEMO_SALA_PRIVADA.rival : rivalData;
    const topeMostrado = esVistaPrevia ? DEMO_SALA_PRIVADA.maxMonto : maxMontoPermitido;
    const mensajesMostrados = esVistaPrevia ? DEMO_SALA_PRIVADA.mensajes : chatMessages;
    const modoMostrado = esVistaPrevia && !gameMode ? DEMO_SALA_PRIVADA.modoEjemplo : gameMode;
    const montoMostrado = esVistaPrevia && !monto ? DEMO_SALA_PRIVADA.montoEjemplo : monto;
    const winTextMostrado = esVistaPrevia ? DEMO_SALA_PRIVADA.gananciaEjemplo : winText;
    const mostrarRecordatorioInicio = btnState.cssClass === 'waiting-cancel' || btnState.cssClass === 'rival-ready';

    const toggleChatEnJuego = () => {
        setChatAbiertoEnJuego(prev => {
            const proximo = !prev;
            if (proximo) setMensajesPrivadosSinLeer(0);
            return proximo;
        });
    };

    const renderChatPrivado = (textoVacio) => (
        <>
            <div className="friend-link-reminder" role="note">
                <span>🤝</span>
                <span>
                    <strong>Recordatorio:</strong> Envíense por este chat privado el <strong>link de amistad de Clash Royale</strong> para agregarse antes de jugar.
                </span>
            </div>

            <div className="mini-chat">
                {mensajesMostrados.length === 0 ? (
                    <div className="mini-chat-empty">
                        {textoVacio}
                    </div>
                ) : (
                    mensajesMostrados.map((m, i) => {
                        const esPropio = m.usuario === (currentUser?.username || 'Visitante');
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
                <input type="text" value={chatInput} onChange={e => setChatInput(e.target.value)} placeholder="Escribe un mensaje o pega tu link de amistad..." style={{ margin: 0 }} />
                <button type="submit">Enviar</button>
            </form>
        </>
    );

    const vistaEfectiva = esVistaPrevia ? (demoVistaMatch === 'en_curso' ? 'game_result' : 'private') : view;
    const redireccionPendiente = esVistaPrevia
        ? demoVistaMatch === 'en_curso'
        : Boolean(matchState.redireccionClashHastaMs && !matchState.redireccionClashEjecutada && !apiResult && !flujoCompletado);
    const segundosRedireccion = esVistaPrevia
        ? 5
        : redireccionPendiente
            ? Math.max(1, Math.min(5, Math.ceil((Number(matchState.redireccionClashHastaMs) - ahoraMs) / 1000)))
            : 0;

    return (
        <div className="match-container">
            {selectorVistaPreviaMatch}
            {vistaEfectiva === 'private' ? (
                <div className="view-private">
                    <header className="match-header">
                        <h3>🔒 Sala Privada</h3>
                        <button className="icon-btn-danger" onClick={handleSalir} title="Salir de la sala">❌</button>
                    </header>

                    <div className="private-room-content">
                        <div className="match-info">
                            <h2 style={{ margin: 0 }}>VS {rivalMostrado?.username || 'Rival'}</h2>
                            <div className="rival-stats-box">
                                <span title="Porcentaje de victorias" style={{ color: '#43b581' }}>
                                    🏆 {rivalMostrado?.total_partidas > 0 ? Math.round(((rivalMostrado.total_victorias || 0) / rivalMostrado.total_partidas) * 100) : 0}%
                                </span>
                                <span title="Culpable en disputas" style={{ color: '#bbb' }}>
                                    💀 {rivalMostrado?.faltas ?? 0}
                                </span>
                                <span title="Salidas de sala">
                                    🏃 {rivalMostrado?.salidas_chat ?? 0}
                                </span>
                            </div>
                            <span style={{ fontSize: '0.8rem', color: '#faa61a', display: 'block', marginTop: 5 }}>Tope: ${topeMostrado.toLocaleString()}</span>
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
                                        <span className="timer-clock-label">Tiempo de {rivalMostrado?.username || 'Rival'}</span>
                                        <span className="timer-clock-value">{formatearRelojIndividual(rivalRestanteMs)}</span>
                                    </div>
                                </div>
                            </div>
                        )}

                        {renderChatPrivado('Saluda a tu rival, envíense el link de amistad de Clash Royale y acuerden aquí el modo y el monto.')}

                        <VozDiscord enPartida={true} />

                        <div className="negotiation-box">
                            <h4>⚙️ Configuración</h4>
                            <div className="inputs-row">
                                <div className="input-group">
                                    <label>Modo:</label>
                                    <input type="text" placeholder="Ej: Elección" value={modoMostrado} onChange={handleModeChange} />
                                </div>
                                <div className="input-group">
                                    <label>Monto:</label>
                                    <input type="number" placeholder="Mín: 1000" value={montoMostrado} onChange={handleMontoChange} />
                                </div>
                            </div>
                            <p style={{ fontSize: '0.9rem', fontWeight: 'bold', marginTop: 5, color: winTextMostrado.includes('$0') ? '#bbb' : '#4ecca3' }}>
                                {winTextMostrado}
                            </p>
                            <p className="validation-error">{validationMsg}</p>
                            {mostrarRecordatorioInicio && (
                                <div className="start-friend-link-warning" role="status">
                                    🤝 ¿Ya se enviaron el <strong>link de amistad de Clash Royale</strong> por el chat privado? Asegúrate de haber agregado a tu rival antes de iniciar.
                                </div>
                            )}
                            <button
                                className={`start-game-btn ${esVistaPrevia ? 'enabled' : btnState.cssClass}`}
                                disabled={!esVistaPrevia && btnState.disabled}
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
                                <div className="clash-redirect-banner" role="status">
                                    {redireccionPendiente ? (
                                        <>
                                            <p className="clash-redirect-text">
                                                🚀 Ya estamos buscando tu batalla en la API de Clash Royale. Serás redireccionado automáticamente a Clash Royale en <strong>{segundosRedireccion}s</strong>...
                                            </p>
                                            <div className="clash-redirect-actions">
                                                <a
                                                    href={ENLACE_CLASH_ROYALE}
                                                    target="_blank"
                                                    rel="noopener noreferrer"
                                                    className="clash-open-link-btn"
                                                    onClick={(e) => {
                                                        if (esVistaPrevia) {
                                                            e.preventDefault();
                                                            solicitarAutenticacionVistaPrevia('abrir Clash Royale en una partida en curso');
                                                        } else {
                                                            setMatchState({ redireccionClashHastaMs: null, redireccionClashEjecutada: true });
                                                        }
                                                    }}
                                                >
                                                    ⚔️ Abrir Clash Royale ahora
                                                </a>
                                                <button
                                                    type="button"
                                                    className="clash-stay-btn"
                                                    onClick={() => {
                                                        if (esVistaPrevia) {
                                                            solicitarAutenticacionVistaPrevia('gestionar la redirección de partida');
                                                        } else {
                                                            cancelarRedireccionClash();
                                                        }
                                                    }}
                                                >
                                                    Quedarme en esta página
                                                </button>
                                            </div>
                                        </>
                                    ) : (
                                        <>
                                            <p className="clash-redirect-text">
                                                📲 El sondeo automático de la API de Clash Royale sigue activo en segundo plano.
                                            </p>
                                            <div className="clash-redirect-actions">
                                                <a
                                                    href={ENLACE_CLASH_ROYALE}
                                                    target="_blank"
                                                    rel="noopener noreferrer"
                                                    className="clash-open-link-btn"
                                                >
                                                    📲 Abrir Clash Royale
                                                </a>
                                            </div>
                                        </>
                                    )}
                                </div>
                                <div className="game-private-chat-controls">
                                    <button
                                        type="button"
                                        className={`private-chat-toggle-btn ${chatAbiertoEnJuego ? 'open' : ''}`}
                                        aria-expanded={chatAbiertoEnJuego}
                                        onClick={toggleChatEnJuego}
                                    >
                                        <span>💬 {chatAbiertoEnJuego ? 'Ocultar chat privado' : 'Abrir chat privado'}</span>
                                        {mensajesPrivadosSinLeer > 0 && (
                                            <span
                                                className="notification-dot private-chat-unread-dot"
                                                aria-label={`${mensajesPrivadosSinLeer} mensajes nuevos`}
                                                title={`${mensajesPrivadosSinLeer} mensaje(s) nuevo(s)`}
                                            />
                                        )}
                                    </button>
                                </div>
                                {chatAbiertoEnJuego && (
                                    <div className="game-private-chat-panel">
                                        {renderChatPrivado('Aún no hay mensajes. Puedes escribirle a tu rival o enviarle tu link de amistad aquí.')}
                                    </div>
                                )}
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
                        <p className="modal-friend-link-note">
                            🤝 Recuerda haber enviado o aceptado el <strong>link de amistad</strong> en el chat privado antes de aceptar.
                        </p>
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

import { create } from 'zustand';
import { io } from 'socket.io-client';
import { API_BASE_URL, llamarApi } from './llamarApi';
import { cerrarSesionConPush } from '../services/pushTokens';

const eliminarUsuarioPersistido = () => {
    try {
        // Limpia datos personales y financieros guardados por versiones anteriores.
        localStorage.removeItem('currentUser');
    } catch {
        // El almacenamiento puede estar deshabilitado; la sesión vive solo en memoria.
    }
};

eliminarUsuarioPersistido();

export const useAppStore = create((set, get) => ({
    // Modo Claro/Oscuro
    theme: localStorage.getItem('appTheme') || 'dark',
    toggleTheme: () => {
        const newTheme = get().theme === 'dark' ? 'light' : 'dark';
        localStorage.setItem('appTheme', newTheme);
        set({ theme: newTheme });
    },

    // Estado del usuario
    user: null,
    sessionReady: false,
    authRevision: 0,
    // Token nativo FCM solo en memoria; no se persiste junto con los datos del usuario.
    pushToken: null,
    pushTokenAnterior: null,
    setPushToken: (token) => {
        const limpio = typeof token === 'string' ? token.trim() : '';
        if (!limpio) return;
        set(state => {
            if (state.pushToken === limpio) return state;
            return { pushToken: limpio, pushTokenAnterior: state.pushToken };
        });
    },
    limpiarPushTokenAnterior: (tokenActual, tokenAnterior) => set(state => {
        if (state.pushToken !== tokenActual || state.pushTokenAnterior !== tokenAnterior) return state;
        return { pushTokenAnterior: null };
    }),
    setUser: (userData) => {
        eliminarUsuarioPersistido();
        const anterior = get().user;
        const authRevisionDelta = userData == null || anterior?.id !== userData.id ? 1 : 0;
        if (userData) {
            const socket = get().socket;
            // registrar_socket solo cuando cambia de usuario (login). Los cambios de saldo NO deben repetirlo:
            // el servidor rehace todo el registro (incluido reenviar restaurar_partida) en cada emisión.
            // Al conectar el socket ya se registra solo (ver 'connect' en connectSocket).
            if (socket && socket.connected && anterior?.id !== userData.id) {
                socket.emit('registrar_socket');
            }
        }
        set(state => ({
            user: userData,
            sessionReady: true,
            authRevision: state.authRevision + authRevisionDelta
        }));
    },
    // La cookie httpOnly es la única persistencia de sesión; los datos del usuario quedan en memoria.
    sincronizarSesion: async () => {
        const revisionInicial = get().authRevision;
        const { ok, status, data } = await llamarApi('/api/session');
        // Ignora respuestas atrasadas si el usuario inició o cerró sesión mientras la petición estaba en vuelo.
        if (get().authRevision !== revisionInicial) return { ok, status };
        if (ok && data?.user) {
            get().setUser(data.user);
        } else if (status === 401) {
            get().logout();
        } else {
            set({ sessionReady: true });
        }
        return { ok, status };
    },
    logout: () => {
        const { pushToken, pushTokenAnterior } = get();
        eliminarUsuarioPersistido();
        get().disconnectSocket();
        // El servidor desasocia los tokens de este dispositivo antes de borrar la cookie httpOnly.
        const tokens = [pushToken, pushTokenAnterior].filter(Boolean);
        void cerrarSesionConPush(tokens);
        set({ 
            user: null,
            sessionReady: true,
            authRevision: get().authRevision + 1,
            pushTokenAnterior: null,
            chatMessages: { general: [], anuncios: [], clash: [], clash_logs: [], pagos: [] },
            matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle', rivalDesconectado: null, timerNegociacion: null }
        });
    },

    // Estado del Chat centralizado (F3)
    chatMessages: {
        general: [],
        anuncios: [],
        clash: [],
        clash_logs: [],
        pagos: [] // evidencias de pago (roadmap 7): el historial se pide por HTTP al abrir el canal; en vivo llega por mensaje_chat
    },
    setChatHistory: (canal, mensajes) => set(state => ({
        chatMessages: {
            ...state.chatMessages,
            [canal]: Array.isArray(mensajes) ? mensajes : []
        }
    })),
    addChatMessage: (msg) => set(state => {
        const canal = msg.canal || 'general';
        const currentList = state.chatMessages[canal] || [];
        return {
            chatMessages: {
                ...state.chatMessages,
                [canal]: [...currentList, msg]
            }
        };
    }),
    sendChatMessage: ({ canal, texto, tipo = 'texto' }) => {
        const { socket, user } = get();
        if (!socket || !user || !texto) return;
        socket.emit('mensaje_chat', {
            canal,
            texto,
            tipo
        });
    },

    // Estado del Socket
    socket: null,
    connectSocket: () => {
        if (get().socket) return; // Ya está conectado

        const newSocket = io(API_BASE_URL, {
            transports: ['websocket'],
            withCredentials: true
        });

        let yaConectado = false;
        newSocket.on('connect', () => {
            console.log('Socket conectado:', newSocket.id);
            const currentUser = get().user;
            if (currentUser) {
                newSocket.emit('registrar_socket');
            }
            // En una reconexión pudo haberse aprobado una recarga mientras no había socket: se resincroniza el saldo.
            // (La primera conexión ya la cubre la sincronización al cargar la app.)
            if (yaConectado) get().sincronizarSesion();
            yaConectado = true;
        });

        newSocket.on('disconnect', () => {
            console.log('Socket desconectado');
        });

        // Chat: Historial inicial emitido por el servidor en la conexión
        newSocket.on('historial_chat', (data) => {
            if (data && data.canal && Array.isArray(data.mensajes)) {
                get().setChatHistory(data.canal, data.mensajes);
            }
        });

        // Chat: Mensajes públicos en tiempo real y logs del sistema (clash_logs)
        newSocket.on('mensaje_chat', (data) => {
            if (data && data.canal) {
                get().addChatMessage(data);
            }
        });

        // Escuchar actualización de saldos (global)
        newSocket.on('saldo_actualizado', (data) => {
            const currentUser = get().user;
            if (currentUser && data) {
                const nuevoSaldo = data.nuevoSaldo !== undefined ? data.nuevoSaldo : data;
                get().setUser({ ...currentUser, saldo: nuevoSaldo });
            }
        });

        newSocket.on('actualizar_saldo', (nuevoSaldo) => {
            const currentUser = get().user;
            if (currentUser && nuevoSaldo !== undefined) {
                get().setUser({ ...currentUser, saldo: nuevoSaldo });
            }
        });

        // Partida encontrada: se guarda en el store (no solo en Match.jsx) para que, si el usuario está en otra
        // pantalla mientras busca, al volver a Match ya encuentre la sala con su rival.
        newSocket.on('partida_encontrada', (data) => {
            if (!data) return;
            const yo = get().user;
            const rival = data.p1?.id === yo?.id ? data.p2 : data.p1;
            get().setMatchState({
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
        });

        // La sala se restaura en el handshake y este evento puede llegar antes de que Match.jsx monte.
        // El store global conserva el estado y permite volver a /match tras recargar o cambiar de ruta.
        newSocket.on('restaurar_partida', (data) => {
            if (!data?.salaId) return;
            get().setMatchState({
                inMatch: true,
                salaId: data.salaId,
                rival: data.rival || null,
                maxMonto: data.maxMonto || 10000,
                status: data.iniciado ? 'playing' : 'negotiating',
                gameMode: data.lastModo || '',
                monto: data.lastDinero || '',
                resultado: data.iniciado ? get().matchState.resultado : null,
                rivalDesconectado: data.rivalDesconectado ? get().matchState.rivalDesconectado : null,
                timerNegociacion: !data.iniciado && data.timerNegociacion
                    ? { ...data.timerNegociacion, recibidoEn: Date.now() }
                    : null
            });
        });

        newSocket.on('timer_negociacion', (data) => {
            if (!data) return;
            get().setMatchState({
                timerNegociacion: { ...data, recibidoEn: Date.now() }
            });
        });

        newSocket.on('juego_iniciado', () => {
            get().setMatchState({
                inMatch: true,
                status: 'playing',
                rivalDesconectado: null,
                timerNegociacion: null
            });
        });

        // Resultado de la partida: también en el store, para que no se pierda si el usuario está en otra pantalla
        // cuando llega (Match.jsx lo lee al montarse).
        newSocket.on('resultado_api', (data) => {
            if (!data) return;
            get().setMatchState({
                status: 'finished',
                resultado: { esGanador: data.esGanador === true, premio: data.premio, ganador: data.ganador, mensaje: data.mensaje }
            });
        });
        const alDisputa = (data) => {
            get().setMatchState({
                status: 'finished',
                resultado: { disputa: true, mensaje: data?.mensaje || 'Resultado no claro, disputa creada.' }
            });
        };
        newSocket.on('disputa_creada', alDisputa);
        newSocket.on('disputa_timeout', alDisputa);
        newSocket.on('flujo_completado', () => {
            if (get().matchState.inMatch) get().setMatchState({ status: 'finished' });
        });

        // Estado de la búsqueda (silencioso; Match.jsx muestra los avisos cuando está abierto)
        newSocket.on('buscando_activo', () => {
            if (!get().matchState.inMatch) get().setMatchState({ status: 'searching' });
        });
        newSocket.on('error_busqueda', () => get().setMatchState({ status: 'idle' }));
        newSocket.on('busqueda_timeout', () => get().setMatchState({ status: 'idle' }));
        newSocket.on('busqueda_cancelada', (data) => {
            const userId = get().user?.id;
            if (userId && Number(data?.oderId) === Number(userId) && !get().matchState.inMatch) {
                get().setMatchState({ inMatch: false, salaId: null, rival: null, status: 'idle' });
            }
        });
        newSocket.on('cancelacion_completada', (data) => {
            if (data?.evento === 'cancelar_busqueda') {
                get().setMatchState({ inMatch: false, salaId: null, rival: null, status: 'idle' });
            }
        });
        newSocket.on('rival_desconectado', (data) => get().setMatchState({
            rivalDesconectado: { ...(data || { tiempo: 90 }), recibidoEn: Date.now() }
        }));
        newSocket.on('rival_reconectado', () => get().setMatchState({ rivalDesconectado: null }));
        newSocket.on('match_cancelado', () => get().clearMatchState());

        // Desconexión por duplicidad de sesión
        newSocket.on('sesion_duplicada', (data) => {
            alert(data?.mensaje || 'Tu cuenta se conectó desde otro dispositivo');
            get().logout();
        });

        set({ socket: newSocket });
    },
    disconnectSocket: () => {
        const { socket } = get();
        if (socket) {
            socket.disconnect();
            set({ socket: null });
        }
    },

    // Estado de la partida actual
    matchState: {
        inMatch: false,
        salaId: null,
        rival: null,
        gameMode: '',
        monto: 0,
        maxMonto: 10000,
        status: 'idle', // idle, searching, negotiating, waiting, playing, finished
        rivalDesconectado: null,
        timerNegociacion: null
    },
    setMatchState: (newState) => set((state) => ({ matchState: { ...state.matchState, ...newState } })),
    clearMatchState: () => set({ 
        matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle', rivalDesconectado: null, timerNegociacion: null }
    }),
}));

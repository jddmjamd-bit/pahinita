import { create } from 'zustand';
import { io } from 'socket.io-client';
import { API_BASE_URL, llamarApi } from './llamarApi';

const leerUsuarioGuardado = () => {
    try {
        const raw = localStorage.getItem('currentUser');
        return raw ? JSON.parse(raw) : null;
    } catch {
        localStorage.removeItem('currentUser');
        return null;
    }
};

export const useAppStore = create((set, get) => ({
    // Modo Claro/Oscuro
    theme: localStorage.getItem('appTheme') || 'dark',
    toggleTheme: () => {
        const newTheme = get().theme === 'dark' ? 'light' : 'dark';
        localStorage.setItem('appTheme', newTheme);
        set({ theme: newTheme });
    },

    // Estado del usuario
    user: leerUsuarioGuardado(),
    setUser: (userData) => {
        if (userData) {
            const anterior = get().user;
            localStorage.setItem('currentUser', JSON.stringify(userData));
            const socket = get().socket;
            // registrar_socket solo cuando cambia de usuario (login). Los cambios de saldo NO deben repetirlo:
            // el servidor rehace todo el registro (incluido reenviar restaurar_partida) en cada emisión.
            // Al conectar el socket ya se registra solo (ver 'connect' en connectSocket).
            if (socket && socket.connected && anterior?.id !== userData.id) {
                socket.emit('registrar_socket');
            }
        } else {
            localStorage.removeItem('currentUser');
        }
        set({ user: userData });
    },
    // Trae el usuario real del servidor (saldo, estado...) para no depender del localStorage viejo.
    // 401 = sesión vencida o cuenta inexistente -> se cierra la sesión. Cualquier otro fallo (sin red, 5xx, 429)
    // conserva el usuario guardado.
    sincronizarSesion: async () => {
        if (!get().user) return { ok: false, status: 0 };
        const { ok, status, data } = await llamarApi('/api/session');
        if (ok && data?.user) {
            const actual = get().user;
            if (actual && actual.id === data.user.id) get().setUser(data.user); // si cerró sesión mientras tanto, se ignora
        } else if (status === 401) {
            get().logout();
        }
        return { ok, status };
    },
    logout: () => {
        localStorage.removeItem('currentUser');
        get().disconnectSocket();
        // S1 / F3: pedir al servidor que borre la cookie httpOnly "session"
        llamarApi('/api/logout', { method: 'POST' });
        set({ 
            user: null,
            chatMessages: { general: [], anuncios: [], clash: [], clash_logs: [], pagos: [] },
            matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle' }
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
                resultado: null
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
        status: 'idle' // idle, searching, negotiating, waiting, playing, finished
    },
    setMatchState: (newState) => set((state) => ({ matchState: { ...state.matchState, ...newState } })),
    clearMatchState: () => set({ 
        matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle' } 
    }),
}));

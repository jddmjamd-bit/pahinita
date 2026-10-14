import { create } from 'zustand';
import { io } from 'socket.io-client';

const API_BASE_URL = 'https://torneos-beta.onrender.com'; // TODO: use env variables

export const useAppStore = create((set, get) => ({
    // Modo Claro/Oscuro
    theme: localStorage.getItem('appTheme') || 'dark',
    toggleTheme: () => {
        const newTheme = get().theme === 'dark' ? 'light' : 'dark';
        localStorage.setItem('appTheme', newTheme);
        set({ theme: newTheme });
    },

    // Estado del usuario
    user: JSON.parse(localStorage.getItem('currentUser')) || null,
    setUser: (userData) => {
        if (userData) {
            localStorage.setItem('currentUser', JSON.stringify(userData));
            const socket = get().socket;
            if (socket && socket.connected) {
                socket.emit('registrar_socket', userData);
            }
        } else {
            localStorage.removeItem('currentUser');
        }
        set({ user: userData });
    },
    logout: () => {
        localStorage.removeItem('currentUser');
        get().disconnectSocket();
        set({ 
            user: null,
            chatMessages: { general: [], anuncios: [], clash: [], clash_logs: [] },
            matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle' }
        });
    },

    // Estado del Chat centralizado (F3)
    chatMessages: {
        general: [],
        anuncios: [],
        clash: [],
        clash_logs: []
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
            usuario: user.username,
            texto,
            tipo
        });
    },

    // Estado del Socket
    socket: null,
    connectSocket: () => {
        if (get().socket) return; // Ya está conectado
        
        const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
        const url = isLocal ? 'http://localhost:7070' : API_BASE_URL;

        const newSocket = io(url, {
            transports: ['websocket'],
            withCredentials: true
        });

        newSocket.on('connect', () => {
            console.log('Socket conectado:', newSocket.id);
            const currentUser = get().user;
            if (currentUser) {
                newSocket.emit('registrar_socket', currentUser);
            }
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

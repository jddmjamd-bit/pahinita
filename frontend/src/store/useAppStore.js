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
        } else {
            localStorage.removeItem('currentUser');
        }
        set({ user: userData });
    },
    logout: () => {
        localStorage.removeItem('currentUser');
        set({ user: null });
        get().disconnectSocket();
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
        });

        newSocket.on('disconnect', () => {
            console.log('Socket desconectado');
        });

        // Escuchar actualización de saldos (global)
        newSocket.on('saldo_actualizado', (data) => {
            const currentUser = get().user;
            if (currentUser) {
                const updatedUser = { ...currentUser, saldo: data.nuevoSaldo };
                get().setUser(updatedUser);
            }
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
        rival: null,
        gameMode: '',
        monto: 0,
        status: 'idle' // idle, negotiating, waiting, playing, finished
    },
    setMatchState: (newState) => set((state) => ({ matchState: { ...state.matchState, ...newState } })),
    clearMatchState: () => set({ 
        matchState: { inMatch: false, rival: null, gameMode: '', monto: 0, status: 'idle' } 
    }),
}));

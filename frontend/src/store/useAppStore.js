import { create } from 'zustand';
import { io } from 'socket.io-client';
import { API_BASE_URL, llamarApi } from './llamarApi';
import { cerrarSesionConPush } from '../services/pushTokens';
import { cerrarPushBusqueda } from '../services/browserPush';
import { despacharAvisoCanales } from '../services/notificacionesCanales';
import { formatearTiempoTexto, parsearDatosAviso } from '../utils/tiempoMatchmaking';

const eliminarUsuarioPersistido = () => {
    try {
        // Limpia datos personales y financieros guardados por versiones anteriores.
        localStorage.removeItem('currentUser');
    } catch {
        // El almacenamiento puede estar deshabilitado; la sesión vive solo en memoria.
    }
};

eliminarUsuarioPersistido();

const CANALES_CHAT_VALIDOS = new Set(['general', 'anuncios', 'clash', 'clash_logs', 'pagos']);

const extraerCanalDeAviso = (aviso) => {
    if (!aviso) return '';
    const datos = parsearDatosAviso(aviso.datos);
    const canalDatos = typeof datos?.canal === 'string' ? datos.canal.trim().toLowerCase() : '';
    if (CANALES_CHAT_VALIDOS.has(canalDatos)) return canalDatos;
    const ruta = typeof aviso.ruta === 'string' ? aviso.ruta : '';
    const match = ruta.match(/[?&]canal=([a-z0-9_]+)/i);
    if (match && CANALES_CHAT_VALIDOS.has(match[1].toLowerCase())) return match[1].toLowerCase();
    if (aviso.categoria === 'chat') return 'general';
    return '';
};

const avisoPerteneceACanal = (aviso, canal) => {
    if (!aviso || !canal) return false;
    const canalAviso = extraerCanalDeAviso(aviso);
    if (canalAviso !== canal) return false;
    return aviso.categoria === 'chat' || (canal === 'pagos' && aviso.categoria === 'finanzas');
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
    recargarZonaActiva: async () => {
        set({
            chatMessages: { general: [], anuncios: [], clash: [], clash_logs: [], pagos: [] },
            notificaciones: [],
            notificacionesSinLeer: {},
            chatSinLeer: {},
            busquedasVisibles: [],
            retoRecibido: null,
            retoEnviado: null,
            avisoMatchmaking: null,
            matchmakingConfig: null
        });
        get().clearMatchState();
        await get().sincronizarSesion();
        await get().cargarNotificaciones();
        await get().cargarEstadoMatchmaking();
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
            pushToken: null,
            pushTokenAnterior: null,
            chatMessages: { general: [], anuncios: [], clash: [], clash_logs: [], pagos: [] },
            notificaciones: [],
            notificacionesSinLeer: {},
            chatSinLeer: {},
            usuariosOnline: 0,
            busquedasVisibles: [],
            retoRecibido: null,
            retoEnviado: null,
            avisoMatchmaking: null,
            matchmakingConfig: null,
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

    // Centro de notificaciones persistentes.
    notificaciones: [],
    notificacionesSinLeer: {},
    chatSinLeer: {},
    usuariosOnline: 0,
    cargarNotificaciones: async () => {
        const { ok, data } = await llamarApi('/api/notificaciones');
        if (!ok || !data) return;
        const ahora = Date.now();
        set({
            notificaciones: Array.isArray(data.items)
                ? data.items.map(item => ({ ...item, recibidoEn: item.creada_en ? Date.parse(item.creada_en) || ahora : ahora }))
                : [],
            notificacionesSinLeer: data.sinLeer || {},
            chatSinLeer: data.chatSinLeer || {}
        });
    },
    marcarNotificacionLeida: async (id) => {
        const aviso = get().notificaciones.find(item => Number(item.id) === Number(id));
        if (aviso?.leida_en) return;
        const { ok } = await llamarApi(`/api/notificaciones/leer/${id}`, { method: 'POST' });
        if (!ok) return;
        if (!aviso) {
            await get().cargarNotificaciones();
            return;
        }
        set(state => {
            const sinLeerItem = state.notificaciones.find(item => Number(item.id) === Number(id) && !item.leida_en);
            const canalAviso = sinLeerItem ? extraerCanalDeAviso(sinLeerItem) : '';
            const chatSinLeer = canalAviso
                ? { ...state.chatSinLeer, [canalAviso]: Math.max(0, (state.chatSinLeer[canalAviso] || 0) - 1) }
                : state.chatSinLeer;
            return {
                notificaciones: state.notificaciones.map(item => Number(item.id) === Number(id) && !item.leida_en
                    ? { ...item, leida_en: new Date().toISOString() } : item),
                notificacionesSinLeer: {
                    ...state.notificacionesSinLeer,
                    [aviso.categoria]: Math.max(0, (state.notificacionesSinLeer[aviso.categoria] || 0) -
                        (sinLeerItem ? 1 : 0))
                },
                chatSinLeer
            };
        });
    },
    marcarCategoriaLeida: async (categoria, canal = '') => {
        if (!categoria) return;
        const canalNorm = categoria === 'chat' && CANALES_CHAT_VALIDOS.has(String(canal || '').trim().toLowerCase())
            ? String(canal).trim().toLowerCase()
            : '';
        const { ok, data } = await llamarApi('/api/notificaciones/leer/categoria', {
            method: 'POST',
            body: JSON.stringify(canalNorm ? { categoria, canal: canalNorm } : { categoria })
        });
        if (!ok) return;
        const ahora = new Date().toISOString();
        set(state => {
            if (canalNorm) {
                const leidasChat = state.notificaciones.filter(item =>
                    !item.leida_en && item.categoria === 'chat' && extraerCanalDeAviso(item) === canalNorm).length;
                const leidasFinanzas = canalNorm === 'pagos'
                    ? state.notificaciones.filter(item =>
                        !item.leida_en && item.categoria === 'finanzas' && extraerCanalDeAviso(item) === 'pagos').length
                    : 0;
                const sinLeer = data?.sinLeer ? data.sinLeer : { ...state.notificacionesSinLeer };
                if (!data?.sinLeer) {
                    sinLeer.chat = Math.max(0, (sinLeer.chat || 0) - leidasChat);
                    if (canalNorm === 'pagos') {
                        sinLeer.finanzas = Math.max(0, (sinLeer.finanzas || 0) - leidasFinanzas);
                    }
                }
                return {
                    notificaciones: state.notificaciones.map(item =>
                        !item.leida_en && avisoPerteneceACanal(item, canalNorm)
                            ? { ...item, leida_en: ahora }
                            : item),
                    notificacionesSinLeer: sinLeer,
                    chatSinLeer: data?.chatSinLeer || { ...state.chatSinLeer, [canalNorm]: 0 }
                };
            }
            const chatSinLeer = { ...state.chatSinLeer };
            if (categoria === 'chat') {
                delete chatSinLeer.general;
                delete chatSinLeer.anuncios;
                delete chatSinLeer.clash;
                delete chatSinLeer.clash_logs;
            } else if (categoria === 'finanzas') {
                delete chatSinLeer.pagos;
            }
            return {
                notificaciones: state.notificaciones.map(item => item.categoria === categoria && !item.leida_en
                    ? { ...item, leida_en: ahora } : item),
                notificacionesSinLeer: data?.sinLeer || { ...state.notificacionesSinLeer, [categoria]: 0 },
                chatSinLeer: data?.chatSinLeer || chatSinLeer
            };
        });
    },
    descartarNotificacion: async (id) => {
        const aviso = get().notificaciones.find(item => Number(item.id) === Number(id));
        if (!aviso) return;
        const { ok } = await llamarApi(`/api/notificaciones/${id}`, { method: 'DELETE' });
        if (!ok) return;
        set(state => {
            const actual = state.notificaciones.find(item => Number(item.id) === Number(id));
            const nuevas = state.notificaciones.filter(item => Number(item.id) !== Number(id));
            const sinLeer = { ...state.notificacionesSinLeer };
            const chatSinLeer = { ...state.chatSinLeer };
            if (actual && !actual.leida_en) {
                sinLeer[aviso.categoria] = Math.max(0, (sinLeer[aviso.categoria] || 0) - 1);
                const canalAviso = extraerCanalDeAviso(actual);
                if (canalAviso) chatSinLeer[canalAviso] = Math.max(0, (chatSinLeer[canalAviso] || 0) - 1);
            }
            return { notificaciones: nuevas, notificacionesSinLeer: sinLeer, chatSinLeer };
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
        let tuvoDesconexion = false;
        let recargandoPorReconexion = false;
        newSocket.on('connect', () => {
            console.log('Socket conectado:', newSocket.id);
            // Al volver a conectarse el socket tras desconexión (ej. móvil retomando después de un tiempo),
            // forzamos una recarga única para despejar vistas congeladas o bundles viejos.
            if (yaConectado && tuvoDesconexion && get().socket === newSocket && !recargandoPorReconexion) {
                recargandoPorReconexion = true;
                if (typeof window !== 'undefined' && typeof window.location?.reload === 'function') {
                    newSocket.disconnect();
                    window.location.reload();
                    return;
                }
            }
            const currentUser = get().user;
            if (currentUser) {
                newSocket.emit('registrar_socket');
                void get().cargarNotificaciones();
            }
            // En una reconexión pudo haberse aprobado una recarga mientras no había socket: se resincroniza el saldo.
            // (La primera conexión ya la cubre la sincronización al cargar la app.)
            if (yaConectado) get().sincronizarSesion();
            yaConectado = true;
        });

        newSocket.on('disconnect', () => {
            tuvoDesconexion = true;
            console.log('Socket desconectado');
        });

        newSocket.on('usuarios_online', (data) => {
            const total = typeof data === 'number' ? data : Number(data?.total);
            if (Number.isFinite(total)) set({ usuariosOnline: Math.max(0, total) });
        });
        newSocket.on('notificacion_nueva', (aviso) => {
            if (!aviso?.id) return;
            const avisoConMarca = { ...aviso, recibidoEn: Date.now() };
            set(state => {
                if (state.notificaciones.some(item => Number(item.id) === Number(aviso.id))) return state;
                const canalAviso = extraerCanalDeAviso(aviso);
                const chatSinLeer = canalAviso
                    ? { ...state.chatSinLeer, [canalAviso]: (state.chatSinLeer[canalAviso] || 0) + 1 }
                    : state.chatSinLeer;
                return {
                    notificaciones: [avisoConMarca, ...state.notificaciones].slice(0, 100),
                    notificacionesSinLeer: {
                        ...state.notificacionesSinLeer,
                        [aviso.categoria]: (state.notificacionesSinLeer[aviso.categoria] || 0) + 1
                    },
                    chatSinLeer
                };
            });
            if (aviso.categoria === 'partida') {
                const datosAviso = parsearDatosAviso(aviso.datos);
                const tagAviso = datosAviso.tipoMatchmaking === 'match_confirmado'
                    ? `mm-confirmado-${datosAviso.challengeId || aviso.id}`
                    : datosAviso.challengeId
                        ? `reto-${datosAviso.challengeId}`
                        : (datosAviso.buscadorId ? `busqueda-${datosAviso.buscadorId}` : `notif-${aviso.id}`);
                void despacharAvisoCanales({
                    titulo: aviso.titulo || 'Notificación de partida',
                    mensaje: aviso.mensaje || '',
                    tag: tagAviso,
                    link: aviso.ruta || '/match',
                    urgente: datosAviso.tipoMatchmaking === 'reto_superior' || datosAviso.tipoMatchmaking === 'match_confirmado'
                });
            }
        });
        newSocket.on('notificacion_leida', (data) => {
            set(state => {
                const aviso = state.notificaciones.find(item => Number(item.id) === Number(data?.id));
                if (!aviso || aviso.leida_en) return state;
                const canalAviso = extraerCanalDeAviso(aviso);
                const chatSinLeer = canalAviso
                    ? { ...state.chatSinLeer, [canalAviso]: Math.max(0, (state.chatSinLeer[canalAviso] || 0) - 1) }
                    : state.chatSinLeer;
                return {
                    notificaciones: state.notificaciones.map(item => Number(item.id) === Number(data.id)
                        ? { ...item, leida_en: new Date().toISOString() } : item),
                    notificacionesSinLeer: {
                        ...state.notificacionesSinLeer,
                        [aviso.categoria]: Math.max(0, (state.notificacionesSinLeer[aviso.categoria] || 0) - 1)
                    },
                    chatSinLeer
                };
            });
        });
        newSocket.on('notificaciones_leidas', (data) => {
            if (!data?.categoria) return;
            const ahora = new Date().toISOString();
            const canalNorm = data.categoria === 'chat' && CANALES_CHAT_VALIDOS.has(String(data.canal || '').trim().toLowerCase())
                ? String(data.canal).trim().toLowerCase()
                : '';
            set(state => {
                if (canalNorm) {
                    const leidasChat = state.notificaciones.filter(item =>
                        !item.leida_en && item.categoria === 'chat' && extraerCanalDeAviso(item) === canalNorm).length;
                    const leidasFinanzas = canalNorm === 'pagos'
                        ? state.notificaciones.filter(item =>
                            !item.leida_en && item.categoria === 'finanzas' && extraerCanalDeAviso(item) === 'pagos').length
                        : 0;
                    const sinLeer = {
                        ...state.notificacionesSinLeer,
                        chat: typeof data.sinLeerCategoria === 'number'
                            ? data.sinLeerCategoria
                            : Math.max(0, (state.notificacionesSinLeer.chat || 0) - leidasChat)
                    };
                    if (canalNorm === 'pagos') {
                        sinLeer.finanzas = typeof data.sinLeerFinanzas === 'number'
                            ? data.sinLeerFinanzas
                            : Math.max(0, (state.notificacionesSinLeer.finanzas || 0) - leidasFinanzas);
                    }
                    return {
                        notificaciones: state.notificaciones.map(item =>
                            !item.leida_en && avisoPerteneceACanal(item, canalNorm)
                                ? { ...item, leida_en: ahora }
                                : item),
                        notificacionesSinLeer: sinLeer,
                        chatSinLeer: { ...state.chatSinLeer, [canalNorm]: 0 }
                    };
                }
                const chatSinLeer = { ...state.chatSinLeer };
                if (data.categoria === 'chat') {
                    delete chatSinLeer.general;
                    delete chatSinLeer.anuncios;
                    delete chatSinLeer.clash;
                    delete chatSinLeer.clash_logs;
                } else if (data.categoria === 'finanzas') {
                    delete chatSinLeer.pagos;
                }
                return {
                    notificaciones: state.notificaciones.map(item => item.categoria === data.categoria && !item.leida_en
                        ? { ...item, leida_en: ahora } : item),
                    notificacionesSinLeer: { ...state.notificacionesSinLeer, [data.categoria]: 0 },
                    chatSinLeer
                };
            });
        });
        newSocket.on('notificacion_eliminada', (data) => {
            if (data?.id) {
                set(state => {
                    const aviso = state.notificaciones.find(item => Number(item.id) === Number(data.id));
                    if (!aviso) return state;
                    const sinLeer = { ...state.notificacionesSinLeer };
                    const chatSinLeer = { ...state.chatSinLeer };
                    if (!aviso.leida_en) {
                        sinLeer[aviso.categoria] = Math.max(0, (sinLeer[aviso.categoria] || 0) - 1);
                        const canalAviso = extraerCanalDeAviso(aviso);
                        if (canalAviso) chatSinLeer[canalAviso] = Math.max(0, (chatSinLeer[canalAviso] || 0) - 1);
                    }
                    return {
                        notificaciones: state.notificaciones.filter(item => Number(item.id) !== Number(data.id)),
                        notificacionesSinLeer: sinLeer,
                        chatSinLeer
                    };
                });
                return;
            }
            if (data?.eventoClave) {
                set(state => {
                    const coincide = (clave) => clave === data.eventoClave || clave?.startsWith(`${data.eventoClave}:`);
                    const removidas = state.notificaciones.filter(item => coincide(item.evento_clave));
                    const nuevas = state.notificaciones.filter(item => !coincide(item.evento_clave));
                    const sinLeer = { ...state.notificacionesSinLeer };
                    const chatSinLeer = { ...state.chatSinLeer };
                    for (const aviso of removidas) {
                        if (!aviso.leida_en) {
                            sinLeer[aviso.categoria] = Math.max(0, (sinLeer[aviso.categoria] || 0) - 1);
                            const canalAviso = extraerCanalDeAviso(aviso);
                            if (canalAviso) chatSinLeer[canalAviso] = Math.max(0, (chatSinLeer[canalAviso] || 0) - 1);
                        }
                    }
                    return { notificaciones: nuevas, notificacionesSinLeer: sinLeer, chatSinLeer };
                });
            }
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
            set({ retoRecibido: null, retoEnviado: null, avisoMatchmaking: null });
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
        newSocket.on('busqueda_timeout', () => set(state => ({
            matchState: { ...state.matchState, status: 'idle' },
            retoRecibido: null
        })));
        newSocket.on('alguien_buscando', (data) => {
            const buscadorId = Number(data?.buscadorId ?? data?.oderId);
            const yoId = Number(get().user?.id);
            if (!buscadorId || (yoId && buscadorId === yoId)) return;
            const ahora = Date.now();
            set(state => {
                const resto = state.busquedasVisibles.filter(item => Number(item.buscadorId ?? item.oderId) !== buscadorId);
                return { busquedasVisibles: [{ ...data, buscadorId, oderId: buscadorId, recibidoEn: ahora }, ...resto] };
            });
            if (data?.habilitada !== false && data?.notificarAviso === true) {
                const tiempoTxt = Number(data?.segundosRestantesAceptar) > 0
                    ? ` · Te quedan ${formatearTiempoTexto(data.segundosRestantesAceptar)} para aceptar`
                    : '';
                void despacharAvisoCanales({
                    titulo: data?.eresMejor ? 'Rival buscando partida (oleada)' : 'Alguien busca partida',
                    mensaje: `${data?.username || 'Un jugador'} busca rival · ${data?.etiquetaNivel || 'Disponible para ti'}${tiempoTxt}`,
                    tag: `busqueda-${buscadorId}`,
                    link: '/match'
                });
            }
        });
        newSocket.on('busqueda_cancelada', (data) => {
            const userId = get().user?.id;
            const canceledId = Number(data?.oderId);
            if (canceledId) {
                void cerrarPushBusqueda(`busqueda:${canceledId}`);
                set(state => ({
                    busquedasVisibles: state.busquedasVisibles.filter(item => Number(item.buscadorId ?? item.oderId) !== canceledId),
                    retoEnviado: state.retoEnviado && Number(state.retoEnviado.buscadorId) === canceledId ? null : state.retoEnviado
                }));
            }
            if (userId && canceledId === Number(userId) && !get().matchState.inMatch) {
                get().setMatchState({ inMatch: false, salaId: null, rival: null, status: 'idle' });
            }
        });
        newSocket.on('matchmaking_reto_recibido', (data) => {
            if (!data?.challengeId) return;
            const conMarca = { ...data, recibidoEn: Date.now() };
            set({ retoRecibido: conMarca });
            const tiempoTxt = Number(data.expiraEnSegundos) > 0 && !String(data.mensaje || '').includes('para aceptar')
                ? ` Te quedan ${formatearTiempoTexto(data.expiraEnSegundos)} para aceptar.`
                : '';
            void despacharAvisoCanales({
                titulo: data.esAutomatico ? 'Emparejamiento disponible con rival superior' : 'Un jugador superior quiere enfrentarte',
                mensaje: (data.mensaje || `${data.retadorUsername || 'Un rival'} es mejor que tú. ¿Aun así quieres jugar contra él?`) + tiempoTxt,
                tag: `reto-${data.challengeId}`,
                link: '/match',
                urgente: true
            });
        });
        newSocket.on('matchmaking_reto_cerrado', (data) => {
            set(state => ({
                retoRecibido: state.retoRecibido && (!data?.challengeId || Number(state.retoRecibido.challengeId) === Number(data.challengeId))
                    ? null : state.retoRecibido
            }));
        });
        newSocket.on('matchmaking_reto_expirado', (data) => {
            set(state => ({
                retoRecibido: state.retoRecibido && (!data?.challengeId || Number(state.retoRecibido.challengeId) === Number(data.challengeId))
                    ? null : state.retoRecibido
            }));
        });
        newSocket.on('matchmaking_reto_respuesta', (data) => {
            if (!data) return;
            if (data.pendiente) {
                set(state => ({
                    retoEnviado: data,
                    avisoMatchmaking: data.mensaje || null,
                    busquedasVisibles: state.busquedasVisibles.map(item =>
                        Number(item.buscadorId ?? item.oderId) === Number(data.buscadorId)
                            ? { ...item, retoEstado: 'pendiente', retoId: data.challengeId } : item)
                }));
                return;
            }
            if (data.aceptado === true) {
                const tiempoTxt = Number(data.segundosRestantesAceptar) > 0
                    ? ` (tienes ${formatearTiempoTexto(data.segundosRestantesAceptar)} para responder)`
                    : '';
                void despacharAvisoCanales({
                    titulo: '¡Partida confirmada!',
                    mensaje: (data.mensaje || `${data.buscadorUsername || 'Tu rival'} aceptó jugar contra ti.`) + tiempoTxt,
                    tag: `mm-confirmado-${data.challengeId || data.buscadorId}`,
                    link: '/match',
                    urgente: true
                });
            }
            set(state => ({
                retoEnviado: null,
                avisoMatchmaking: data.mensaje || null,
                busquedasVisibles: state.busquedasVisibles.map(item =>
                    Number(item.buscadorId ?? item.oderId) === Number(data.buscadorId) && data.aceptado === false
                        ? { ...item, retoEstado: 'rechazado' } : item)
            }));
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
        newSocket.on('zona_cambiada', () => {
            void get().recargarZonaActiva();
        });
        newSocket.on('zona_pruebas_reiniciada', () => {
            void get().recargarZonaActiva();
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
        status: 'idle', // idle, searching, negotiating, waiting, playing, finished
        rivalDesconectado: null,
        timerNegociacion: null
    },
    busquedasVisibles: [],
    retoRecibido: null,
    retoEnviado: null,
    avisoMatchmaking: null,
    matchmakingConfig: null,
    cargarEstadoMatchmaking: async () => {
        const { ok, data } = await llamarApi('/api/matchmaking/estado');
        if (!ok || !data?.success) return;
        const ahora = Date.now();
        set({
            matchmakingConfig: data,
            busquedasVisibles: Array.isArray(data.busquedasVisibles)
                ? data.busquedasVisibles.map(b => ({ ...b, recibidoEn: ahora }))
                : [],
            retoRecibido: data.retoRecibidoPendiente ? { ...data.retoRecibidoPendiente, recibidoEn: ahora } : null,
            retoEnviado: data.retoEnviadoPendiente || null
        });
    },
    setAvisoMatchmaking: (aviso) => set({ avisoMatchmaking: aviso }),
    setRetoRecibido: (reto) => set({ retoRecibido: reto }),
    setMatchState: (newState) => set((state) => ({ matchState: { ...state.matchState, ...newState } })),
    clearMatchState: () => set({ 
        retoRecibido: null,
        retoEnviado: null,
        matchState: { inMatch: false, salaId: null, rival: null, gameMode: '', monto: 0, maxMonto: 10000, status: 'idle', rivalDesconectado: null, timerNegociacion: null }
    }),
}));

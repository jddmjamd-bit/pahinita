import { activarRegistroPush, registrarTokenPush } from './pushTokens';
import { llamarApi } from '../store/llamarApi';

const VERSION_FIREBASE = '13.1.0';
let configuracionPushCache = null;
let tokenRegistradoNavegador = '';
let listenerMensajeForeground = false;

const importarFirebase = async (biblioteca) => import(
    /* @vite-ignore */ `https://www.gstatic.com/firebasejs/${VERSION_FIREBASE}/${biblioteca}.js`
);

/** Pide permiso solo cuando el usuario lo solicita y guarda el token en su sesión actual. */
export async function activarPushNavegador(userId, pedirPermiso = false) {
    if (!userId || typeof window === 'undefined' || !('Notification' in window)
            || !('serviceWorker' in navigator)) return { estado: 'no-disponible' };

    let permiso = Notification.permission;
    if (permiso === 'default' && pedirPermiso) permiso = await Notification.requestPermission();
    if (permiso !== 'granted') return { estado: permiso === 'denied' ? 'rechazado' : 'pendiente' };

    if (!configuracionPushCache) {
        const respuesta = await llamarApi('/api/push/config');
        configuracionPushCache = respuesta.ok ? respuesta.data : null;
    }
    const config = configuracionPushCache?.firebaseConfig;
    const vapidKey = configuracionPushCache?.vapidKey;
    if (!configuracionPushCache?.habilitado) return { estado: 'sin-configuracion' };
    if (!config?.projectId || !config?.messagingSenderId || !config?.appId || !vapidKey) {
        return { estado: 'sin-configuracion' };
    }

    try {
        const [appSdk, messagingSdk] = await Promise.all([
            importarFirebase('firebase-app'),
            importarFirebase('firebase-messaging')
        ]);
        if (!(await messagingSdk.isSupported())) return { estado: 'no-disponible' };

        const nombreApp = 'torneos-flash-web-push';
        const app = appSdk.getApps().find(instancia => instancia.name === nombreApp)
            || appSdk.initializeApp(config, nombreApp);
        const messaging = messagingSdk.getMessaging(app);
        if (!listenerMensajeForeground) {
            messagingSdk.onMessage(messaging, async payload => {
                const datos = payload?.data || {};
                const registroActivo = await navigator.serviceWorker.getRegistration('/');
                if (!registroActivo) return;
                if (datos.accion === 'cerrar' && datos.tag) {
                    const visibles = await registroActivo.getNotifications({ tag: datos.tag });
                    visibles.forEach(aviso => aviso.close());
                    return;
                }
                const titulo = payload?.notification?.title;
                if (!titulo) return;
                await registroActivo.showNotification(titulo, {
                    body: payload.notification.body || '',
                    icon: '/icon-192.png',
                    badge: '/icon-192.png',
                    tag: datos.tag || undefined,
                    data: { link: datos.link || '/', notificationId: datos.notificationId || '' }
                });
            });
            listenerMensajeForeground = true;
        }
        const swUrl = `/firebase-messaging-sw.js?config=${encodeURIComponent(JSON.stringify(config))}`;
        const registro = await navigator.serviceWorker.register(swUrl, { scope: '/' });
        const token = await messagingSdk.getToken(messaging, {
            vapidKey,
            serviceWorkerRegistration: registro
        });
        if (!token) return { estado: 'error' };

        activarRegistroPush(userId);
        const anterior = tokenRegistradoNavegador;
        const respuesta = await registrarTokenPush(userId, token, anterior);
        if (!respuesta.ok) return { estado: 'error' };
        tokenRegistradoNavegador = token;
        return { estado: 'activo', token };
    } catch (error) {
        console.warn('No se pudo activar el push del navegador');
        return { estado: 'error', causa: error };
    }
}

/** Cierra avisos del sistema cuando el usuario vuelve a abrir el sitio. */
export async function limpiarPushVisibles() {
    if (!('serviceWorker' in navigator)) return;
    try {
        const registro = await navigator.serviceWorker.getRegistration('/');
        registro?.active?.postMessage({ tipo: 'limpiar-notificaciones' });
    } catch {
        // Si el navegador bloquea el acceso, no se interrumpe la carga de la aplicación.
    }
}

/** Retira la push de búsqueda visible cuando llega su cancelación por socket. */
export async function cerrarPushBusqueda(eventoClave) {
    if (!eventoClave?.startsWith('busqueda:') || !('serviceWorker' in navigator)) return;
    const finalizador = eventoClave.indexOf(':', 'busqueda:'.length);
    const clave = finalizador > 0 ? eventoClave.slice(0, finalizador) : eventoClave;
    const tag = clave.replace(/[^A-Za-z0-9_-]/g, '-');
    try {
        const registro = await navigator.serviceWorker.getRegistration('/');
        const notificaciones = await registro?.getNotifications({ tag });
        notificaciones?.forEach(notificacion => notificacion.close());
    } catch {
        // Cerrar una alerta no debe bloquear la sesión del jugador.
    }
}

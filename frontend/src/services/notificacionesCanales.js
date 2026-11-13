import { Capacitor } from '@capacitor/core';

export const CANALES_MATCHMAKING = ['web', 'app', 'pc'];

const avisosRecientesPorTag = new Map();
const VENTANA_DEDUP_MS = 5000;

function registrarTagReciente(tag) {
    if (!tag) return true;
    const ahora = Date.now();
    for (const [clave, ts] of avisosRecientesPorTag.entries()) {
        if (ahora - ts > VENTANA_DEDUP_MS) avisosRecientesPorTag.delete(clave);
    }
    if (avisosRecientesPorTag.has(tag)) return false;
    avisosRecientesPorTag.set(tag, ahora);
    return true;
}

function navegarEnApp(ruta) {
    if (typeof window === 'undefined') return;
    window.dispatchEvent(new CustomEvent('torneos:navegar', { detail: { ruta: ruta || '/match' } }));
}

/** Solicita permisos nativos de escritorio (PC) al usuario tras una interacción explícita. */
export async function solicitarPermisoNotificacionesPc() {
    if (Capacitor.isNativePlatform()) return 'nativo';
    if (typeof window === 'undefined' || !('Notification' in window)) return 'no-disponible';
    try {
        return await window.Notification.requestPermission();
    } catch {
        return window.Notification.permission || 'default';
    }
}

export function permisoNotificacionesPc() {
    if (Capacitor.isNativePlatform()) return 'nativo';
    if (typeof window === 'undefined' || !('Notification' in window)) return 'no-disponible';
    return window.Notification.permission;
}

/**
 * Despacha notificaciones de matchmaking por los canales activos del dispositivo:
 * - Web: buzón persistente + tarjeta en vivo gestionados por el store.
 * - App (Capacitor nativo): canal Capacitor Push / LocalNotifications + vibración de alerta cuando esté disponible.
 * - PC (escritorio web): Web Notification API / Service Worker cuando el navegador tiene permiso concedido.
 */
export async function despacharAvisoCanales({
    titulo = 'Notificación de partida',
    mensaje = '',
    tag = '',
    link = '/match',
    urgente = false
} = {}) {
    const tagSeguro = tag || `mm-${Date.now()}`;
    if (!registrarTagReciente(tagSeguro)) {
        return { web: true, app: false, pc: false, deduplicado: true };
    }

    const resultado = { web: true, app: false, pc: false };

    if (Capacitor.isNativePlatform()) {
        try {
            const localPlugins = window?.Capacitor?.Plugins?.LocalNotifications;
            if (localPlugins && typeof localPlugins.schedule === 'function') {
                await localPlugins.schedule({
                    notifications: [{
                        id: Math.abs(Array.from(tagSeguro).reduce((acc, ch) => ((acc * 31) + ch.charCodeAt(0)) | 0, 7)) || 1,
                        title: titulo,
                        body: mensaje,
                        extra: { link }
                    }]
                });
            }
            if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
                navigator.vibrate(urgente ? [180, 70, 180] : [120]);
            }
            resultado.app = true;
        } catch {
            // Si el shell nativo todavía no compila LocalNotifications, el buzón y Push FCM siguen activos.
        }
        return resultado;
    }

    if (typeof window !== 'undefined' && 'Notification' in window && window.Notification.permission === 'granted') {
        try {
            if ('serviceWorker' in navigator) {
                const registro = await navigator.serviceWorker.getRegistration('/');
                if (registro && typeof registro.showNotification === 'function') {
                    await registro.showNotification(titulo, {
                        body: mensaje,
                        icon: '/icon-192.png',
                        badge: '/icon-192.png',
                        tag: tagSeguro,
                        renotify: Boolean(urgente),
                        data: { link }
                    });
                    resultado.pc = true;
                    return resultado;
                }
            }
            const avisoPc = new window.Notification(titulo, {
                body: mensaje,
                icon: '/icon-192.png',
                tag: tagSeguro
            });
            avisoPc.onclick = () => {
                try { window.focus(); } catch {}
                navegarEnApp(link);
                avisoPc.close();
            };
            resultado.pc = true;
        } catch {
            // Si el navegador bloquea notificaciones directas en primer plano, el aviso web permanece visible.
        }
    }

    return resultado;
}

/** Resume el estado visual de los canales Web, App y PC para la vista de Match. */
export function resumenCanalesNotificacion({ esNativo, pushToken, permisoPc } = {}) {
    return [
        {
            id: 'web',
            nombre: 'Web',
            estado: 'Activo',
            activo: true
        },
        {
            id: 'pc',
            nombre: 'PC',
            estado: permisoPc === 'granted'
                ? 'Activo'
                : (permisoPc === 'denied' ? 'Bloqueado en navegador' : (permisoPc === 'nativo' ? 'Solo escritorio web' : 'Pendiente de permiso')),
            activo: permisoPc === 'granted'
        },
        {
            id: 'app',
            nombre: 'App',
            estado: esNativo && pushToken ? 'Push nativo activo' : 'Canal Capacitor/FCM preparado',
            activo: Boolean(esNativo && pushToken),
            preparado: true
        }
    ];
}

import { useEffect } from 'react';
import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { PushNotifications } from '@capacitor/push-notifications';
import { Camera } from '@capacitor/camera';
import { useAppStore } from '../store/useAppStore';
import { activarRegistroPush, registrarTokenPush } from '../services/pushTokens';

export function useCapacitor() {
  const userId = useAppStore(state => state.user?.id ?? null);
  const pushToken = useAppStore(state => state.pushToken);
  const setPushToken = useAppStore(state => state.setPushToken);
  const limpiarPushTokenAnterior = useAppStore(state => state.limpiarPushTokenAnterior);

  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return undefined;
    let activo = true;
    const listeners = [];

    const agregarListener = async (evento, callback) => {
      const handle = await PushNotifications.addListener(evento, callback);
      if (!activo) {
        await handle.remove();
        return;
      }
      listeners.push(handle);
    };

    const initCapacitor = async () => {
      try {
        await SplashScreen.hide();
      } catch {
        console.warn('No se pudo ocultar el splash screen');
      }

      if (!activo) return;

      try {
        await Camera.requestPermissions();
      } catch {
        console.warn('No se pudieron solicitar permisos de cámara/micrófono');
      }

      if (!activo) return;

      try {
        let permStatus = await PushNotifications.checkPermissions();
        if (permStatus.receive === 'prompt') {
          permStatus = await PushNotifications.requestPermissions();
        }
        if (!activo || permStatus.receive !== 'granted') return;

        await agregarListener('registration', token => {
          // El token FCM identifica este dispositivo: nunca escribir su valor en consola.
          const valor = typeof token?.value === 'string' ? token.value.trim() : '';
          if (activo && valor) setPushToken(valor);
        });
        await agregarListener('registrationError', () => {
          if (activo) console.warn('Falló el registro de notificaciones push');
        });
        if (!activo) return;
        await PushNotifications.register();
      } catch {
        if (activo) console.warn('No se pudo iniciar el registro de notificaciones push');
      }
    };

    void initCapacitor();
    return () => {
      activo = false;
      listeners.forEach(handle => { void handle.remove().catch(() => {}); });
    };
  }, [setPushToken]);

  useEffect(() => {
    if (!Capacitor.isNativePlatform() || !userId || !pushToken) return undefined;
    activarRegistroPush(userId);
    let activo = true;
    let temporizador = null;
    let reintentos = 0;

    const enviarToken = async () => {
      const tokenAnterior = useAppStore.getState().pushTokenAnterior;
      const res = await registrarTokenPush(userId, pushToken, tokenAnterior);
      if (!activo) return;
      if (res.ok) {
        if (tokenAnterior) limpiarPushTokenAnterior(pushToken, tokenAnterior);
        return;
      }

      const reintentable = res.status === 0 || res.status === 429 || res.status >= 500;
      const esperaMs = [1000, 5000, 15000][reintentos];
      if (reintentable && esperaMs) {
        reintentos += 1;
        temporizador = setTimeout(enviarToken, esperaMs);
      } else {
        console.warn(`No se pudo registrar el token push (HTTP ${res.status})`);
      }
    };

    void enviarToken();
    return () => {
      activo = false;
      if (temporizador) clearTimeout(temporizador);
    };
  }, [pushToken, userId, limpiarPushTokenAnterior]);
}

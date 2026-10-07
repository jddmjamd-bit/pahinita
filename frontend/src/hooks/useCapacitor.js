import { useEffect } from 'react';
import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { PushNotifications } from '@capacitor/push-notifications';
import { Camera } from '@capacitor/camera';

export function useCapacitor() {
  useEffect(() => {
    const initCapacitor = async () => {
      if (Capacitor.isNativePlatform()) {
        try {
          await SplashScreen.hide();
          
          // Solicitar permisos en orden (Camara, Notificaciones)
          await requestAppPermissions();
        } catch (e) {
          console.error("Capacitor init error:", e);
        }
      }
    };

    initCapacitor();
  }, []);
}

const requestAppPermissions = async () => {
  // 1. Solicitar permisos de Cámara y Micrófono
  try {
    await Camera.requestPermissions();
  } catch (e) {
    console.warn("No se pudieron solicitar permisos de cámara/micrófono", e);
  }

  // 2. Solicitar permisos de Notificaciones Push
  await registerPushNotifications();
};

const registerPushNotifications = async () => {
  let permStatus = await PushNotifications.checkPermissions();

  if (permStatus.receive === 'prompt') {
    permStatus = await PushNotifications.requestPermissions();
  }

  if (permStatus.receive !== 'granted') {
    console.warn('User denied push notification permissions');
    return;
  }

  await PushNotifications.register();

  PushNotifications.addListener('registration', (token) => {
    console.log('Push registration success, token: ' + token.value);
    // TODO: Send this token to the backend via socket or API
  });

  PushNotifications.addListener('registrationError', (error) => {
    console.error('Error on registration: ' + JSON.stringify(error));
  });

  PushNotifications.addListener('pushNotificationReceived', (notification) => {
    console.log('Push received: ' + JSON.stringify(notification));
  });

  PushNotifications.addListener('pushNotificationActionPerformed', (notification) => {
    console.log('Push action performed: ' + JSON.stringify(notification));
  });
};

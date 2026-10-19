import { llamarApi } from '../store/llamarApi';

let colaPush = Promise.resolve();
let usuarioPushActivo = null;
let generacionSesionPush = 0;

const encolarSolicitud = (solicitud) => {
  const resultado = colaPush.then(solicitud, solicitud);
  colaPush = resultado.then(() => undefined, () => undefined);
  return resultado;
};

export function activarRegistroPush(userId) {
  if (!userId) return;
  if (usuarioPushActivo !== userId) {
    usuarioPushActivo = userId;
    generacionSesionPush += 1;
  }
}

export function registrarTokenPush(userId, token, previousToken) {
  const generacion = generacionSesionPush;
  return encolarSolicitud(() => {
    // Descarta registros pendientes de una sesión que ya cerró o cambió de usuario.
    if (usuarioPushActivo !== userId || generacion !== generacionSesionPush) {
      return { ok: false, status: 401, data: null };
    }
    return llamarApi('/api/register-token', {
      method: 'POST',
      body: JSON.stringify({ token, previousToken: previousToken || '' })
    });
  });
}

export function cerrarSesionConPush(tokens) {
  usuarioPushActivo = null;
  generacionSesionPush += 1;
  const pushTokens = [...new Set((tokens || []).filter(Boolean))].slice(0, 10);
  return encolarSolicitud(() => llamarApi('/api/logout', {
    method: 'POST',
    ...(pushTokens.length ? { body: JSON.stringify({ pushTokens }) } : {})
  }));
}

export function esperarSolicitudesPush() {
  return colaPush;
}

import { useEffect, useState, useCallback } from 'react';
import { llamarApi } from '../store/llamarApi';
import './VozDiscord.css';

// Mensajes cuando Discord devuelve al usuario a /match?discord=<resultado> (ver VozDiscordService.completarVinculacion)
const MENSAJES_VINCULACION = {
    ok: { tipo: 'ok', texto: '✅ Cuenta de Discord vinculada.' },
    cancelado: { tipo: 'info', texto: 'Cancelaste la vinculación con Discord.' },
    discord_en_uso: { tipo: 'error', texto: 'Esa cuenta de Discord ya está vinculada a otro usuario.' },
    estado_invalido: { tipo: 'error', texto: 'El enlace de vinculación venció. Inténtalo de nuevo.' },
    no_disponible: { tipo: 'error', texto: 'El chat de voz no está disponible en este momento.' },
    error: { tipo: 'error', texto: 'No se pudo vincular con Discord. Inténtalo de nuevo.' }
};

// Solo se abren enlaces que apunten a un canal de Discord (nunca una dirección cualquiera que llegue del servidor)
const PREFIJO_CANAL = 'https://discord.com/channels/';

/**
 * Chat de voz opcional con Discord (roadmap 84).
 * - Si el servidor no tiene Discord configurado, no se muestra nada.
 * - Sin cuenta vinculada: botón para vincularla (Discord es gratis; quien no quiera, juega sin voz).
 * - Con cuenta vinculada y partida en curso (`enPartida`): botón que abre el canal de voz de la partida.
 * Todo el texto se pinta con React (nada de innerHTML).
 */
export default function VozDiscord({ enPartida }) {
    const [estado, setEstado] = useState(null);   // respuesta de GET /api/voz/estado
    const [aviso, setAviso] = useState(null);     // { tipo: 'ok' | 'info' | 'error', texto, enlace? }
    const [ocupado, setOcupado] = useState(false);

    const cargarEstado = useCallback(async () => {
        const res = await llamarApi('/api/voz/estado');
        if (res.ok && res.data) setEstado(res.data);
    }, []);

    useEffect(() => {
        cargarEstado();
        // Resultado de la vinculación (el servidor nos devuelve aquí con ?discord=...)
        const params = new URLSearchParams(window.location.search);
        const resultado = params.get('discord');
        if (resultado) {
            setAviso(MENSAJES_VINCULACION[resultado] || MENSAJES_VINCULACION.error);
            params.delete('discord');
            const resto = params.toString();
            window.history.replaceState(null, '', window.location.pathname + (resto ? `?${resto}` : ''));
        }
    }, [cargarEstado]);

    const vincular = async () => {
        setOcupado(true);
        setAviso(null);
        const res = await llamarApi('/api/voz/discord/vincular', { method: 'POST' });
        if (res.ok && res.data?.url) {
            window.location.href = res.data.url; // Discord pide autorización y nos devuelve a /match?discord=...
            return;
        }
        setAviso({ tipo: 'error', texto: res.data?.error || 'No se pudo iniciar la vinculación con Discord.' });
        setOcupado(false);
    };

    const desvincular = async () => {
        if (!window.confirm('¿Desvincular tu cuenta de Discord? Podrás vincularla de nuevo cuando quieras.')) return;
        setOcupado(true);
        const res = await llamarApi('/api/voz/discord', { method: 'DELETE' });
        setOcupado(false);
        if (res.ok) {
            setAviso({ tipo: 'info', texto: 'Cuenta de Discord desvinculada.' });
            cargarEstado();
        } else {
            setAviso({ tipo: 'error', texto: res.data?.error || 'No se pudo desvincular.' });
        }
    };

    const abrirCanal = async () => {
        setOcupado(true);
        setAviso(null);
        const res = await llamarApi('/api/voz/mi-canal');
        setOcupado(false);
        const data = res.data;
        if (!res.ok || !data) {
            setAviso({ tipo: 'error', texto: data?.error || 'No se pudo abrir el chat de voz.' });
            return;
        }
        if (data.estado === 'listo' && typeof data.url === 'string' && data.url.startsWith(PREFIJO_CANAL)) {
            window.open(data.url, '_blank', 'noopener,noreferrer');
            setAviso({ tipo: 'ok', texto: '🎧 Se abrió tu canal en Discord. Entra al canal de voz para hablar.' });
        } else if (data.estado === 'falta_servidor') {
            const enlace = typeof data.servidorUrl === 'string' && data.servidorUrl.startsWith('https://') ? data.servidorUrl : null;
            setAviso({ tipo: 'info', texto: data.mensaje, enlace });
        } else {
            setAviso({ tipo: 'info', texto: data.mensaje || 'El chat de voz no está disponible ahora.' });
        }
    };

    // Servidor sin Discord configurado (o todavía cargando): no se muestra nada
    if (!estado || !estado.disponible) return null;

    return (
        <div className="voz-box">
            <h4>🎙️ Chat de voz (opcional)</h4>

            {!estado.vinculado ? (
                <>
                    <p className="voz-texto">
                        Para hablar por voz con tu rival necesitas una cuenta de Discord (es gratis). Si no tienes, puedes jugar igual sin voz.
                    </p>
                    <button className="voz-btn" onClick={vincular} disabled={ocupado}>
                        🔗 Vincular Discord
                    </button>
                </>
            ) : (
                <>
                    <p className="voz-texto">
                        Discord vinculado{estado.discordUsername ? <>: <strong>{estado.discordUsername}</strong></> : ''}.
                    </p>
                    {enPartida ? (
                        <button className="voz-btn" onClick={abrirCanal} disabled={ocupado}>
                            🎧 Abrir chat de voz
                        </button>
                    ) : (
                        <p className="voz-texto">El canal de voz se crea cuando empieza la partida.</p>
                    )}
                    <button className="voz-link" onClick={desvincular} disabled={ocupado}>
                        Desvincular Discord
                    </button>
                </>
            )}

            {aviso && (
                <p className={`voz-aviso voz-aviso-${aviso.tipo}`}>
                    {aviso.texto}
                    {aviso.enlace && (
                        <> <a href={aviso.enlace} target="_blank" rel="noopener noreferrer">Unirme al servidor</a></>
                    )}
                </p>
            )}
        </div>
    );
}

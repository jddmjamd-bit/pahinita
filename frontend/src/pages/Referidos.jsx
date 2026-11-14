import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import { DEMO_REFERIDOS } from '../data/vistaPreviaDemo';
import './Referidos.css';

const PRESETS_PORCENTAJE = [0, 10, 25, 50, 75, 100];

const formatearMonto = (valor) => Number(valor ?? 0).toLocaleString('es-CO', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
});

const formatearPorcentaje = (valor) => Number(valor ?? 0).toLocaleString('es-CO', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2
});

const formatearFecha = (iso) => {
    if (!iso) return '—';
    try {
        return new Date(iso).toLocaleString('es-CO', { dateStyle: 'short', timeStyle: 'short' });
    } catch {
        return '—';
    }
};

const copiarAlPortapapeles = async (texto) => {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(texto);
        return;
    }
    if (typeof document !== 'undefined') {
        const area = document.createElement('textarea');
        area.value = texto;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        const copiado = document.execCommand('copy');
        document.body.removeChild(area);
        if (!copiado) throw new Error('copy-failed');
        return;
    }
    throw new Error('clipboard-unavailable');
};

const limitarPorcentaje = (valor) => {
    const num = Number(String(valor).replace(',', '.'));
    if (!Number.isFinite(num)) return null;
    if (num < 0 || num > 100) return null;
    return Math.round(num * 100) / 100;
};

export default function Referidos() {
    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);
    const solicitarAutenticacionVistaPrevia = useAppStore(state => state.solicitarAutenticacionVistaPrevia);

    const [datos, setDatos] = useState(null);
    const [loading, setLoading] = useState(true);
    const [savingConfig, setSavingConfig] = useState(false);
    const [savingUserId, setSavingUserId] = useState(null);
    const [feedback, setFeedback] = useState(null);

    const [porcentajeInput, setPorcentajeInput] = useState('0');
    const [mensajeCortoInput, setMensajeCortoInput] = useState('');
    const [aplicarATodos, setAplicarATodos] = useState(false);
    const [porcentajesPorReferido, setPorcentajesPorReferido] = useState({});

    const sincronizarFormularios = useCallback((payload) => {
        if (!payload) return;
        setPorcentajeInput(String(Number(payload.porcentajeCompartidoGeneral ?? 0)));
        setMensajeCortoInput(payload.mensajeCorto || payload.mensajeCortoDefecto || '');
        const mapa = {};
        for (const r of payload.referidos || []) {
            mapa[r.referredUserId] = String(Number(r.porcentajeCompartido ?? 0));
        }
        setPorcentajesPorReferido(mapa);
    }, []);

    const cargarResumen = useCallback(async () => {
        if (!currentUser) {
            setDatos(DEMO_REFERIDOS);
            sincronizarFormularios(DEMO_REFERIDOS);
            setLoading(false);
            return;
        }
        setLoading(true);
        const { ok, data } = await llamarApi('/api/referidos/mio');
        if (!ok || !data?.success) {
            setFeedback({ tipo: 'err', texto: data?.error || '❌ No se pudo cargar tu panel de referidos.' });
            setLoading(false);
            return;
        }
        setDatos(data);
        sincronizarFormularios(data);
        setLoading(false);
    }, [currentUser, sincronizarFormularios]);

    useEffect(() => {
        void cargarResumen();
    }, [cargarResumen]);

    useEffect(() => {
        if (!socket || !currentUser) return undefined;
        const refrescar = () => { void cargarResumen(); };
        socket.on('wallet_awarded', refrescar);
        socket.on('resultado_api', refrescar);
        return () => {
            socket.off('wallet_awarded', refrescar);
            socket.off('resultado_api', refrescar);
        };
    }, [socket, currentUser, cargarResumen]);

    const codigoReferido = datos?.codigoReferido || '';
    const originWeb = typeof window !== 'undefined' && window.location?.protocol?.startsWith('http')
        ? window.location.origin
        : '';
    const linkReferido = codigoReferido ? `${originWeb}/auth?ref=${encodeURIComponent(codigoReferido)}` : '';

    const textoConMensaje = useMemo(() => {
        const msg = mensajeCortoInput.trim();
        if (!linkReferido) return msg;
        return msg ? `${msg}\n${linkReferido}` : linkReferido;
    }, [mensajeCortoInput, linkReferido]);

    const porcentajeNumerico = useMemo(() => {
        const parsed = limitarPorcentaje(porcentajeInput);
        return parsed === null ? 0 : parsed;
    }, [porcentajeInput]);

    const devueltoEjemplo100 = Math.floor(100 * porcentajeNumerico) / 100;
    const referidoEjemplo100 = Math.round((100 - devueltoEjemplo100) * 100) / 100;

    const manejarCopia = async (texto, etiqueta) => {
        if (!texto) return;
        try {
            await copiarAlPortapapeles(texto);
            setFeedback({ tipo: 'ok', texto: `✅ ${etiqueta}` });
        } catch {
            setFeedback({ tipo: 'err', texto: '⚠️ No pudimos copiar automáticamente; selecciona y copia el texto.' });
        }
    };

    const guardarConfiguracion = async (e) => {
        e.preventDefault();
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('guardar tu configuración de comisión compartida y mensaje de invitación');
            return;
        }
        const pctValido = limitarPorcentaje(porcentajeInput);
        if (pctValido === null) {
            setFeedback({ tipo: 'err', texto: '⚠️ Escribe un porcentaje válido entre 0% y 100%.' });
            return;
        }
        setSavingConfig(true);
        setFeedback(null);
        const { ok, data } = await llamarApi('/api/referidos/configurar', {
            method: 'POST',
            body: JSON.stringify({
                porcentajeCompartido: pctValido,
                mensajeCorto: mensajeCortoInput,
                aplicarATodos
            })
        });
        setSavingConfig(false);
        if (!ok || !data?.success) {
            setFeedback({ tipo: 'err', texto: data?.error || '❌ No se pudo guardar la configuración.' });
            return;
        }
        setDatos(data);
        sincronizarFormularios(data);
        setFeedback({ tipo: 'ok', texto: '✅ Configuración de referidos guardada correctamente.' });
    };

    const guardarPorcentajeReferido = async (referredUserId) => {
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('personalizar el porcentaje compartido con un referido');
            return;
        }
        const pctValido = limitarPorcentaje(porcentajesPorReferido[referredUserId]);
        if (pctValido === null) {
            setFeedback({ tipo: 'err', texto: '⚠️ El porcentaje para ese referido debe estar entre 0% y 100%.' });
            return;
        }
        setSavingUserId(referredUserId);
        setFeedback(null);
        const { ok, data } = await llamarApi('/api/referidos/referido-porcentaje', {
            method: 'POST',
            body: JSON.stringify({
                referredUserId,
                porcentajeCompartido: pctValido
            })
        });
        setSavingUserId(null);
        if (!ok || !data?.success) {
            setFeedback({ tipo: 'err', texto: data?.error || '❌ No se pudo actualizar ese referido.' });
            return;
        }
        setDatos(data);
        sincronizarFormularios(data);
        setFeedback({ tipo: 'ok', texto: '✅ Porcentaje personalizado guardado para tu referido.' });
    };

    const stats = datos?.estadisticas || {};
    const referidos = datos?.referidos || [];
    const historialPagos = datos?.historialPagos || [];
    const miReferidor = datos?.miReferidor || null;

    return (
        <div className="referidos-container">
            <div className="referidos-header">
                <div className="referidos-title-group">
                    <h2>👥 Sistema de Referidos</h2>
                    <p className="referidos-subtitle">
                        Comparte tu link o código de invitación. Cuando tus referidos realizan torneos, la parte de
                        referidos de la comisión se envía directamente a tu cuenta (o la repartes con ellos si activas
                        comisión compartida).
                    </p>
                </div>
                <button
                    type="button"
                    className="referidos-refresh-btn"
                    onClick={cargarResumen}
                    disabled={loading}
                >
                    🔄 Actualizar
                </button>
            </div>

            {feedback && (
                <div className={`referidos-feedback ${feedback.tipo}`} role="status">
                    {feedback.texto}
                </div>
            )}

            {loading && !datos ? (
                <p>Cargando panel de referidos...</p>
            ) : (
                <>
                    <div className="referidos-stats-grid">
                        <div className="referidos-stat-card is-count">
                            <span className="referidos-stat-label">Personas referidas</span>
                            <strong className="referidos-stat-value">{stats.totalReferidos ?? 0}</strong>
                            <span className="referidos-stat-hint">
                                {stats.referidosActivos ?? 0} con partidas jugadas · {stats.totalPartidasReferidos ?? 0} partidas
                            </span>
                        </div>
                        <div className="referidos-stat-card is-earnings">
                            <span className="referidos-stat-label">Total ganado por ti</span>
                            <strong className="referidos-stat-value">${formatearMonto(stats.totalGanadoReferidor)}</strong>
                            <span className="referidos-stat-hint">
                                Acreditado directo a tu saldo disponible
                            </span>
                        </div>
                        <div className="referidos-stat-card is-shared">
                            <span className="referidos-stat-label">Devuelto a tus referidos</span>
                            <strong className="referidos-stat-value">${formatearMonto(stats.totalDevueltoReferidos)}</strong>
                            <span className="referidos-stat-hint">
                                Comisión compartida entregada a tus invitados
                            </span>
                        </div>
                        <div className="referidos-stat-card">
                            <span className="referidos-stat-label">Comisión total generada</span>
                            <strong className="referidos-stat-value">${formatearMonto(stats.totalComisionGenerada)}</strong>
                            <span className="referidos-stat-hint">
                                Suma total salida de la categoría Referidos
                            </span>
                        </div>
                    </div>

                    {miReferidor && (
                        <div className="referidos-my-referrer">
                            <div>
                                🎁 Te refirió <strong>@{miReferidor.username}</strong> con el código{' '}
                                <code>{miReferidor.codigoUsado}</code>
                            </div>
                            <div className="referidos-my-referrer-meta">
                                <span>
                                    Comisión que comparte contigo:{' '}
                                    <strong>{formatearPorcentaje(miReferidor.porcentajeCompartido)}%</strong>
                                </span>
                                <span>
                                    Devuelto a tu saldo:{' '}
                                    <strong>+${formatearMonto(miReferidor.totalRecibidoDevolucion)}</strong>
                                </span>
                            </div>
                        </div>
                    )}

                    <div className="referidos-main-grid">
                        <section className="referidos-card" aria-label="Tu link y mensaje corto">
                            <h3>🔗 Tu link y mensaje de invitación</h3>
                            <p className="referidos-card-desc">
                                Quien entre con tu link verá tu código prellenado automáticamente al registrarse (también
                                puede escribirlo manualmente o dejarlo vacío).
                            </p>

                            <div className="referidos-code-row">
                                <span className="referidos-code-label">Tu código de referido</span>
                                <span className="referidos-code-value">{codigoReferido || '—'}</span>
                            </div>

                            <div className="referidos-link-box" aria-label="Link de referidos">
                                {linkReferido || 'Cargando enlace...'}
                            </div>

                            <div className="referidos-copy-actions">
                                <button
                                    type="button"
                                    className="referidos-btn-secondary"
                                    onClick={() => manejarCopia(linkReferido, '¡Link de referidos copiado!')}
                                    disabled={!linkReferido}
                                >
                                    🔗 Copiar solo link
                                </button>
                                <button
                                    type="button"
                                    className="referidos-btn-primary"
                                    onClick={() => manejarCopia(textoConMensaje, '¡Link y mensajito corto copiados!')}
                                    disabled={!textoConMensaje}
                                >
                                    💬 Copiar link con mensaje
                                </button>
                            </div>

                            <div className="referidos-field">
                                <label htmlFor="ref-short-msg">
                                    Mensajito corto editable ({mensajeCortoInput.length}/240)
                                </label>
                                <textarea
                                    id="ref-short-msg"
                                    className="referidos-textarea"
                                    maxLength={240}
                                    value={mensajeCortoInput}
                                    onChange={(e) => setMensajeCortoInput(e.target.value)}
                                    placeholder="Escribe una frase corta para acompañar tu link..."
                                />
                            </div>

                            <div className="referidos-msg-preview">
                                <strong>Vista previa al copiar con mensaje</strong>
                                {textoConMensaje || '—'}
                            </div>
                        </section>

                        <section className="referidos-card" aria-label="Compartir comisión con referidos">
                            <h3>🤝 Compartir la comisión (Opcional)</h3>
                            <p className="referidos-card-desc">
                                Decide cuánto porcentaje de tu ganancia de referido quieres devolverle automáticamente a
                                cada invitado. Es 100% opcional y no tiene mínimo ni máximo (0% a 100%).
                            </p>

                            <form onSubmit={guardarConfiguracion}>
                                <div className="referidos-share-controls">
                                    <input
                                        type="range"
                                        className="referidos-range"
                                        min="0"
                                        max="100"
                                        step="0.5"
                                        aria-label="Porcentaje compartido por defecto"
                                        value={porcentajeNumerico}
                                        onChange={(e) => setPorcentajeInput(e.target.value)}
                                    />
                                    <div className="referidos-pct-input-wrap">
                                        <input
                                            id="ref-pct-general"
                                            type="number"
                                            className="referidos-pct-input"
                                            min="0"
                                            max="100"
                                            step="0.01"
                                            aria-label="Porcentaje numérico compartido"
                                            value={porcentajeInput}
                                            onChange={(e) => setPorcentajeInput(e.target.value)}
                                        />
                                        <span>%</span>
                                    </div>
                                </div>

                                <div className="referidos-presets">
                                    {PRESETS_PORCENTAJE.map((pct) => (
                                        <button
                                            key={pct}
                                            type="button"
                                            className={`referidos-preset-chip ${Math.abs(porcentajeNumerico - pct) < 0.005 ? 'activo' : ''}`}
                                            onClick={() => setPorcentajeInput(String(pct))}
                                        >
                                            {pct}%{pct === 0 ? ' (Todo para ti)' : pct === 100 ? ' (Todo para invitado)' : ''}
                                        </button>
                                    ))}
                                </div>

                                <div className="referidos-split-preview">
                                    <div className="referidos-split-box">
                                        <span>Por cada $100 generados, tú recibes</span>
                                        <strong className="val-green">${formatearMonto(referidoEjemplo100)}</strong>
                                    </div>
                                    <div className="referidos-split-box">
                                        <span>Tu referido recibe devuelto</span>
                                        <strong>${formatearMonto(devueltoEjemplo100)}</strong>
                                    </div>
                                </div>

                                <label className="referidos-checkbox-row">
                                    <input
                                        type="checkbox"
                                        checked={aplicarATodos}
                                        onChange={(e) => setAplicarATodos(e.target.checked)}
                                    />
                                    <span>
                                        Aplicar este nuevo porcentaje también a quienes ya referí con porcentaje
                                        personalizado
                                    </span>
                                </label>

                                <button
                                    type="submit"
                                    className="referidos-btn-primary"
                                    disabled={savingConfig}
                                >
                                    {savingConfig ? 'Guardando...' : '💾 Guardar configuración'}
                                </button>
                            </form>
                        </section>
                    </div>

                    <section className="referidos-card referidos-section" aria-label="Tus jugadores referidos">
                        <div className="referidos-section-head">
                            <h3>📋 Personas que has referido</h3>
                            <span className="referidos-badge-count">{referidos.length} referidos</span>
                        </div>

                        {referidos.length === 0 ? (
                            <div className="referidos-empty">
                                Aún no tienes referidos registrados. Copia tu link o mensajito arriba para invitar a tus
                                amigos.
                            </div>
                        ) : (
                            <div className="referidos-list">
                                {referidos.map((item) => (
                                    <div key={item.referredUserId} className="referidos-item-card">
                                        <div className="referidos-item-top">
                                            <div className="referidos-item-user">
                                                <strong>@{item.username}</strong>
                                                <span className={`referidos-item-badge ${item.usaPorcentajePersonalizado ? 'custom' : ''}`}>
                                                    {item.usaPorcentajePersonalizado
                                                        ? `% personalizado (${formatearPorcentaje(item.porcentajeCompartido)}%)`
                                                        : `Según tu % general (${formatearPorcentaje(item.porcentajeCompartido)}%)`}
                                                </span>
                                            </div>
                                            <span className="referidos-payout-sub">
                                                Registrado: {formatearFecha(item.creadoEn)}
                                            </span>
                                        </div>

                                        <div className="referidos-item-metrics">
                                            <div className="referidos-mini-metric">
                                                <span>Partidas jugadas</span>
                                                <strong>{item.partidasJugadas}</strong>
                                            </div>
                                            <div className="referidos-mini-metric">
                                                <span>Comisión generada</span>
                                                <strong>${formatearMonto(item.totalComisionReferido)}</strong>
                                            </div>
                                            <div className="referidos-mini-metric">
                                                <span>Tu ganancia</span>
                                                <strong className="val-green">+${formatearMonto(item.totalGanadoReferidor)}</strong>
                                            </div>
                                            <div className="referidos-mini-metric">
                                                <span>Devuelto al referido</span>
                                                <strong>${formatearMonto(item.totalDevueltoReferido)}</strong>
                                            </div>
                                        </div>

                                        <div className="referidos-item-share-edit">
                                            <span className="referidos-payout-sub">
                                                Ajustar cuánto compartes solo con @{item.username} (0% a 100%):
                                            </span>
                                            <div className="referidos-item-share-form">
                                                <input
                                                    type="number"
                                                    min="0"
                                                    max="100"
                                                    step="0.01"
                                                    className="referidos-pct-input"
                                                    aria-label={`Porcentaje compartido para ${item.username}`}
                                                    value={porcentajesPorReferido[item.referredUserId] ?? '0'}
                                                    onChange={(e) => setPorcentajesPorReferido(prev => ({
                                                        ...prev,
                                                        [item.referredUserId]: e.target.value
                                                    }))}
                                                />
                                                <span>%</span>
                                                <button
                                                    type="button"
                                                    className="referidos-btn-secondary"
                                                    disabled={savingUserId === item.referredUserId}
                                                    onClick={() => guardarPorcentajeReferido(item.referredUserId)}
                                                >
                                                    {savingUserId === item.referredUserId ? 'Guardando...' : 'Aplicar'}
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </section>

                    <section className="referidos-card referidos-section" aria-label="Historial de pagos de referidos">
                        <div className="referidos-section-head">
                            <h3>💸 Movimientos recientes de referidos</h3>
                            <span className="referidos-badge-count">{historialPagos.length} movimientos</span>
                        </div>

                        {historialPagos.length === 0 ? (
                            <div className="referidos-empty">
                                Aquí aparecerá cada pago acreditado cuando tus referidos completen partidas (o cuando
                                recibas devolución de quien te invitó).
                            </div>
                        ) : (
                            <div className="referidos-list">
                                {historialPagos.map((pago) => {
                                    const esReferidor = pago.rolUsuario === 'referidor';
                                    return (
                                        <div key={pago.id} className="referidos-payout-row">
                                            <div className="referidos-payout-info">
                                                <strong>
                                                    {esReferidor
                                                        ? `Comisión por partida #${pago.matchId} de @${pago.contraparte}`
                                                        : `Devolución de @${pago.contraparte} en tu partida #${pago.matchId}`}
                                                </strong>
                                                <span className="referidos-payout-sub">
                                                    Base Referidos: ${formatearMonto(pago.montoBase)} · Compartido:{' '}
                                                    {formatearPorcentaje(pago.porcentajeCompartido)}% · {formatearFecha(pago.creadoEn)}
                                                </span>
                                            </div>
                                            <span className={`referidos-payout-amount ${esReferidor ? '' : 'cashback'}`}>
                                                +${formatearMonto(pago.montoParaUsuario)}
                                            </span>
                                        </div>
                                    );
                                })}
                            </div>
                        )}
                    </section>
                </>
            )}
        </div>
    );
}


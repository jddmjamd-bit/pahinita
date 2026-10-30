import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { llamarApi } from '../store/llamarApi';
import './Leaderboard.css';

function IndicadorCambio({ cambio }) {
    const cantidad = Number(cambio || 0);
    if (!cantidad) return null;
    const subio = cantidad > 0;
    return (
        <span className={`lb-movement ${subio ? 'subio' : 'bajo'}`} title={subio ? 'Subiste puestos' : 'Bajaste puestos'}>
            {subio ? '▲' : '▼'} {Math.abs(cantidad)}
        </span>
    );
}

export default function Leaderboard() {
    const [period, setPeriod] = useState('dia');
    const [rankingData, setRankingData] = useState([]);
    const [prizes, setPrizes] = useState([]);
    const [miPosicion, setMiPosicion] = useState(null);
    const [ganadores, setGanadores] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);

    const currentUser = useAppStore(state => state.user);
    const socket = useAppStore(state => state.socket);

    const cargarDatos = useCallback(async () => {
        setLoading(true);
        setError(null);
        const [ranking, posicion, historial] = await Promise.all([
            llamarApi(`/api/leaderboard/${period}`),
            llamarApi(`/api/leaderboard/mi-posicion/${period}`),
            llamarApi('/api/leaderboard/ganadores')
        ]);
        if (!ranking.ok) {
            setError('❌ Error cargando rankings');
        } else {
            setRankingData(ranking.data?.ranking || []);
            setPrizes(ranking.data?.premios || []);
        }
        setMiPosicion(posicion.ok ? posicion.data : null);
        setGanadores(historial.ok && Array.isArray(historial.data) ? historial.data : []);
        setLoading(false);
    }, [period]);

    useEffect(() => { void cargarDatos(); }, [cargarDatos]);

    useEffect(() => {
        if (!socket) return undefined;
        const actualizar = () => { void cargarDatos(); };
        const avisoRanking = aviso => {
            if (aviso?.categoria === 'ranking') actualizar();
        };
        socket.on('leaderboard_reset', actualizar);
        socket.on('resultado_api', actualizar);
        socket.on('notificacion_nueva', avisoRanking);
        return () => {
            socket.off('leaderboard_reset', actualizar);
            socket.off('resultado_api', actualizar);
            socket.off('notificacion_nueva', avisoRanking);
        };
    }, [socket, cargarDatos]);

    const medalIcons = ['🥇', '🥈', '🥉', '4️⃣', '5️⃣'];
    const periodLabels = {
        dia: 'Hoy', semana: 'Esta semana', mes: 'Este mes', ano: 'Este año', global: 'Histórico',
        monto_torneos: 'Más Monto en Torneos (Total)', ganado: 'Más Ganado (Total)'
    };
    const tabLabels = {
        dia: 'Día', semana: 'Semana', mes: 'Mes', ano: 'Año', global: 'Global',
        monto_torneos: 'Monto en torneos', ganado: 'Ganado'
    };
    const isMoneyTab = period === 'monto_torneos' || period === 'ganado';
    const cortes = useMemo(() => {
        const agrupados = new Map();
        for (const ganador of ganadores) {
            if (!agrupados.has(ganador.corte_id)) agrupados.set(ganador.corte_id, []);
            agrupados.get(ganador.corte_id).push(ganador);
        }
        return [...agrupados.entries()].map(([id, jugadores]) => ({ id, jugadores }));
    }, [ganadores]);

    return (
        <div className="leaderboard-container">
            <h2>🏆 Rankings y Premios</h2>

            <div className="lb-tabs">
                {Object.keys(periodLabels).map(p => (
                    <button key={p} className={`lb-tab ${period === p ? 'active' : ''}`} onClick={() => setPeriod(p)}>
                        {tabLabels[p]}
                    </button>
                ))}
            </div>

            {miPosicion && (
                <div className="lb-my-position">
                    <div>
                        <strong>Tu puesto: {miPosicion.posicion ? `#${miPosicion.posicion}` : '—'}</strong>
                        <span>{miPosicion.totalUsuarios} jugadores con puntaje en {periodLabels[period].toLowerCase()}</span>
                    </div>
                    <IndicadorCambio cambio={miPosicion.cambioPosicion} />
                    {!miPosicion.enTop50 && miPosicion.posicion && <span className="lb-outside">Fuera del top 50</span>}
                    {!miPosicion.posicion && <span className="lb-outside">Aún sin puntaje</span>}
                </div>
            )}

            <div className="leaderboard-prizes">
                {prizes.length > 0 && (
                    <div className="prizes-row">
                        {prizes.map((premio, i) => (
                            <span key={premio.posicion} className="prize-badge">
                                {medalIcons[i] || '🏅'} #{premio.posicion}: ${Number(premio.premio).toLocaleString()}
                            </span>
                        ))}
                    </div>
                )}
            </div>

            <div className="leaderboard-content">
                {loading ? (
                    <p className="lb-message">⏳ Cargando...</p>
                ) : error ? (
                    <p className="lb-error">{error}</p>
                ) : rankingData.length === 0 ? (
                    <p className="lb-message">🏜️ Nadie ha ganado partidas en este periodo aún</p>
                ) : (
                    <>
                        <div className="lb-period-label">{periodLabels[period]}</div>
                        <div className="lb-list">
                            {rankingData.map((player, idx) => {
                                const pos = Number(player.posicion) || idx + 1;
                                let medalClass = '';
                                let medal = <span className="lb-pos">{pos}</span>;
                                if (pos === 1) { medalClass = 'lb-gold'; medal = <span className="lb-medal">🥇</span>; }
                                else if (pos === 2) { medalClass = 'lb-silver'; medal = <span className="lb-medal">🥈</span>; }
                                else if (pos === 3) { medalClass = 'lb-bronze'; medal = <span className="lb-medal">🥉</span>; }

                                const isMe = currentUser && Number(player.id) === Number(currentUser.id);
                                const displayValue = isMoneyTab ? player.monto : player.victorias;
                                const displayLabel = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue} victorias`;
                                const badgeText = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue}W`;
                                const badgeClass = isMoneyTab
                                    ? (period === 'monto_torneos' ? 'lb-money-torneos' : 'lb-money-ganado') : 'lb-wins';

                                return (
                                    <div key={player.id || idx} className={`lb-row ${medalClass} ${isMe ? 'lb-me' : ''}`}>
                                        {medal}
                                        <div className="lb-info">
                                            <span className="lb-name">{isMe ? '⭐ ' : ''}{player.username}</span>
                                            <span className="lb-stats">{displayLabel} · {player.total_partidas} partidas</span>
                                        </div>
                                        <IndicadorCambio cambio={player.cambio_posicion} />
                                        <span className={badgeClass}>{badgeText}</span>
                                    </div>
                                );
                            })}
                        </div>
                    </>
                )}
            </div>

            <section className="lb-history">
                <h3>Ganadores de los últimos cortes</h3>
                {cortes.length === 0 ? <p>Aún no hay cortes completados.</p> : cortes.map(corte => {
                    const primero = corte.jugadores[0];
                    const fecha = primero?.completado_en
                        ? new Date(primero.completado_en).toLocaleString('es-CO', { dateStyle: 'medium', timeStyle: 'short' })
                        : '';
                    const nombrePeriodo = { dia: 'Diario', semana: 'Semanal', mes: 'Mensual', ano: 'Anual' }[primero?.periodo] || 'Ranking';
                    return (
                        <details className="lb-cut" key={corte.id}>
                            <summary>{nombrePeriodo} · Corte #{corte.id} {fecha && `· ${fecha}`}</summary>
                            <ol>
                                {corte.jugadores.map(ganador => (
                                    <li key={`${corte.id}-${ganador.user_id}`}>
                                        <span>#{ganador.posicion} {ganador.username}</span>
                                        <span>{ganador.victorias} victorias · premio ${Number(ganador.premio).toLocaleString()}</span>
                                    </li>
                                ))}
                            </ol>
                        </details>
                    );
                })}
            </section>
        </div>
    );
}

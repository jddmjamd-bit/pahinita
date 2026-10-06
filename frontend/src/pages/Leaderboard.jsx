import React, { useState, useEffect } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Leaderboard.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Leaderboard() {
    const [period, setPeriod] = useState('dia');
    const [rankingData, setRankingData] = useState([]);
    const [prizes, setPrizes] = useState([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(null);

    const currentUser = useAppStore(state => state.user);

    useEffect(() => {
        const fetchLeaderboard = async () => {
            setLoading(true);
            setError(null);
            try {
                const res = await fetch(`${API_BASE_URL}/api/leaderboard/${period}`);
                if (!res.ok) throw new Error('Error al obtener datos');
                const data = await res.json();
                setRankingData(data.ranking || []);
                setPrizes(data.premios || []);
            } catch (err) {
                console.error('Error cargando leaderboard:', err);
                setError('❌ Error cargando rankings');
            } finally {
                setLoading(false);
            }
        };

        fetchLeaderboard();
    }, [period]);

    const medalIcons = ['🥇', '🥈', '🥉', '4️⃣', '5️⃣'];
    const periodLabels = { 
        dia: 'Hoy', 
        semana: 'Esta semana', 
        mes: 'Este mes', 
        ano: 'Este año', 
        global: 'Histórico', 
        apostado: 'Más Apostado (Total)', 
        ganado: 'Más Ganado (Total)' 
    };

    const isMoneyTab = period === 'apostado' || period === 'ganado';

    return (
        <div className="leaderboard-container">
            <h2>🏆 Rankings y Premios</h2>
            
            <div className="lb-tabs">
                {Object.keys(periodLabels).map((p) => (
                    <button 
                        key={p} 
                        className={`lb-tab ${period === p ? 'active' : ''}`}
                        onClick={() => setPeriod(p)}
                    >
                        {p.charAt(0).toUpperCase() + p.slice(1)}
                    </button>
                ))}
            </div>

            <div className="leaderboard-prizes">
                {prizes.length > 0 && (
                    <div className="prizes-row">
                        {prizes.map((p, i) => (
                            <span key={p.posicion} className="prize-badge">
                                {medalIcons[i] || '🏅'} #{p.posicion}: ${p.premio.toLocaleString()}
                            </span>
                        ))}
                    </div>
                )}
            </div>

            <div className="leaderboard-content">
                {loading ? (
                    <p style={{ textAlign: 'center', color: '#bbb', padding: '20px' }}>⏳ Cargando...</p>
                ) : error ? (
                    <p style={{ textAlign: 'center', color: '#ed4245', padding: '20px' }}>{error}</p>
                ) : rankingData.length === 0 ? (
                    <p style={{ textAlign: 'center', color: '#888', padding: '40px' }}>🏜️ Nadie ha ganado partidas en este periodo aún</p>
                ) : (
                    <>
                        <div className="lb-period-label">{periodLabels[period]}</div>
                        <div className="lb-list">
                            {rankingData.map((player, idx) => {
                                const pos = idx + 1;
                                let medalClass = '';
                                let medal = <span className="lb-pos">{pos}</span>;

                                if (pos === 1) { medalClass = 'lb-gold'; medal = <span className="lb-medal">🥇</span>; }
                                else if (pos === 2) { medalClass = 'lb-silver'; medal = <span className="lb-medal">🥈</span>; }
                                else if (pos === 3) { medalClass = 'lb-bronze'; medal = <span className="lb-medal">🥉</span>; }

                                const isMe = currentUser && player.id === currentUser.id;
                                const displayValue = isMoneyTab ? player.monto : player.victorias;
                                const displayLabel = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue} victorias`;
                                const badgeText = isMoneyTab ? `$${Number(displayValue).toLocaleString()}` : `${displayValue}W`;
                                const badgeClass = isMoneyTab ? (period === 'apostado' ? 'lb-money-apostado' : 'lb-money-ganado') : 'lb-wins';

                                return (
                                    <div key={player.id || idx} className={`lb-row ${medalClass} ${isMe ? 'lb-me' : ''}`}>
                                        {medal}
                                        <div className="lb-info">
                                            <span className="lb-name">{isMe ? '⭐ ' : ''}{player.username}</span>
                                            <span className="lb-stats">{displayLabel} · {player.total_partidas} partidas</span>
                                        </div>
                                        <span className={badgeClass}>{badgeText}</span>
                                    </div>
                                );
                            })}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { API_BASE_URL, llamarApi } from '../store/llamarApi';
import { DEMO_MOVIMIENTOS_FINANZAS } from '../data/vistaPreviaDemo';
import './Finance.css';

const FILTROS_MOVIMIENTOS = [
    { id: 'todos', label: 'Todos' },
    { id: 'pendiente', label: '⏳ Pendientes / En curso' },
    { id: 'positivo', label: '🟢 Ingresos' },
    { id: 'negativo', label: '🔴 Salidas' },
    { id: 'referido', label: '🤝 Referidos' },
    { id: 'partida', label: '⚔️ Partidas' },
    { id: 'recargas_retiros', label: '💳 Recargas y Retiros' }
];

const formatearCop = (monto) => `$${Number(monto || 0).toLocaleString('es-CO')}`;

const formatearFechaHora = (fechaIso) => {
    if (!fechaIso) return '—';
    const d = new Date(fechaIso);
    if (Number.isNaN(d.getTime())) return '—';
    return d.toLocaleString('es-CO', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
};

export default function Finance() {
    const [activeTab, setActiveTab] = useState('deposit'); // 'deposit' | 'withdraw' | 'movements'
    const [depositMethod, setDepositMethod] = useState('manual'); // 'manual' or 'auto'
    const [filtroMovimiento, setFiltroMovimiento] = useState('todos');
    const [movimientosData, setMovimientosData] = useState({ movimientos: [], resumen: null });
    const [cargandoMovimientos, setCargandoMovimientos] = useState(false);
    
    // Deposit states
    const [manualAmount, setManualAmount] = useState('');
    const [manualRef, setManualRef] = useState('');
    const [autoAmount, setAutoAmount] = useState('');
    const [isProcessing, setIsProcessing] = useState(false);
    const [wompiPendientes, setWompiPendientes] = useState([]);
    const [nequiInfo, setNequiInfo] = useState({
        cargado: false,
        esDeModerador: false,
        nequis: [{ id: 0, numero: '3126655995', titular: '' }]
    });
    const [nequiCopiado, setNequiCopiado] = useState('');

    // Withdraw states
    const [withdrawAmount, setWithdrawAmount] = useState('');
    const [withdrawAccount, setWithdrawAccount] = useState('');
    const [withdrawName, setWithdrawName] = useState('');

    const currentUser = useAppStore(state => state.user);
    const zonaActiva = useAppStore(state => state.zonaActiva);
    const movimientoRevision = useAppStore(state => state.movimientoRevision);
    const solicitarAutenticacionVistaPrevia = useAppStore(state => state.solicitarAutenticacionVistaPrevia);
    const setUser = useAppStore(state => state.setUser);
    const sincronizarSesion = useAppStore(state => state.sincronizarSesion);
    const socket = useAppStore(state => state.socket);
    const location = useLocation();
    const navigate = useNavigate();

    const cargarMovimientos = useCallback(async () => {
        if (!currentUser) {
            setMovimientosData(DEMO_MOVIMIENTOS_FINANZAS);
            return;
        }
        setCargandoMovimientos(true);
        try {
            const res = await fetch(`${API_BASE_URL}/api/finance/movements`, { credentials: 'include' });
            if (res.ok) {
                const data = await res.json();
                setMovimientosData({
                    movimientos: Array.isArray(data?.movimientos) ? data.movimientos : [],
                    resumen: data?.resumen || null
                });
            }
        } catch (error) {
            console.error('No se pudieron cargar los movimientos financieros', error);
        } finally {
            setCargandoMovimientos(false);
        }
    }, [currentUser]);

    const cargarWompiPendientes = async () => {
        if (!currentUser) return setWompiPendientes([]);
        try {
            const res = await fetch(`${API_BASE_URL}/api/wompi/pending`, { credentials: 'include' });
            if (res.ok) {
                const data = await res.json();
                setWompiPendientes(Array.isArray(data) ? data : []);
            }
        } catch (error) {
            console.error('No se pudieron cargar los pagos pendientes de Wompi', error);
        }
    };

    const cargarNequiInfo = useCallback(async () => {
        if (!currentUser) {
            setNequiInfo({
                cargado: true,
                esDeModerador: false,
                nequis: [{ id: 0, numero: '3126655995', titular: '' }]
            });
            return;
        }
        const { ok, data } = await llamarApi('/api/finance/nequi-info');
        if (ok && data) {
            setNequiInfo({
                cargado: true,
                esDeModerador: Boolean(data.esDeModerador),
                nequis: Array.isArray(data.nequis) ? data.nequis : []
            });
        }
    }, [currentUser]);

    const copiarNequi = async (numero) => {
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(numero);
                setNequiCopiado(numero);
                setTimeout(() => setNequiCopiado(prev => (prev === numero ? '' : prev)), 2000);
            }
        } catch {
            // Sin permiso de portapapeles no bloquea el flujo manual
        }
    };

    useEffect(() => {
        cargarWompiPendientes();
    }, [currentUser?.id]);

    useEffect(() => {
        void cargarNequiInfo();
    }, [cargarNequiInfo, zonaActiva]);

    useEffect(() => {
        void cargarMovimientos();
    }, [cargarMovimientos, movimientoRevision, currentUser?.saldo]);

    useEffect(() => {
        if (!socket) return undefined;
        const actualizarTodo = () => {
            void cargarWompiPendientes();
            void cargarMovimientos();
        };
        socket.on('wompi_pago_actualizado', actualizarTodo);
        socket.on('zona_cambiada', actualizarTodo);
        socket.on('zona_pruebas_reiniciada', actualizarTodo);
        return () => {
            socket.off('wompi_pago_actualizado', actualizarTodo);
            socket.off('zona_cambiada', actualizarTodo);
            socket.off('zona_pruebas_reiniciada', actualizarTodo);
        };
    }, [socket, currentUser?.id, cargarMovimientos]);

    const actualizarEstadoVerificacion = async () => {
        const resultado = await sincronizarSesion();
        if (!resultado.ok && resultado.status !== 401) {
            alert(resultado.data?.error || 'No se pudo actualizar el estado. Inténtalo de nuevo.');
        }
    };

    const sincronizarPagoWompi = async (transactionId) => {
        if (!transactionId) return null;
        const res = await fetch(`${API_BASE_URL}/api/wompi/sync`, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ transactionId })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'No se pudo consultar el pago Wompi.');
        if (data.status === 'APPROVED') await sincronizarSesion();
        await cargarWompiPendientes();
        return data;
    };

    useEffect(() => {
        const transactionId = new URLSearchParams(location.search).get('id');
        if (!transactionId) return;
        sincronizarPagoWompi(transactionId)
            .catch(error => alert(error.message || 'No se pudo verificar el pago con Wompi.'))
            .finally(() => navigate({ pathname: location.pathname, hash: location.hash }, { replace: true }));
    }, [location.search, location.pathname, location.hash, navigate, currentUser?.id]);

    // Calculos de comisión Wompi
    const calculateTotalAuto = (val) => {
        if (!val || val < 1000) return { totalPagar: 0, comisionTotal: 0 };
        const baseConMargen = val + 840;
        const totalPagar = Math.ceil(baseConMargen / 0.964);
        const comisionTotal = totalPagar - val;
        return { totalPagar, comisionTotal };
    };

    const autoCalc = calculateTotalAuto(Number(autoAmount));

    const handleManualDeposit = async () => {
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('notificar una recarga de saldo');
            return;
        }
        if (!manualAmount || !manualRef) return alert("Por favor llena los datos.");

        try {
            const res = await fetch(API_BASE_URL + '/api/transaction/create', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    tipo: 'deposito',
                    metodo: 'manual_nequi',
                    monto: manualAmount,
                    referencia: manualRef
                })
            });
            const d = await res.json();
            // D5: si el servidor rechaza el monto, se muestra su mensaje y se conserva lo escrito
            if (!res.ok || d.error) {
                alert(d.error || "No se pudo crear la solicitud.");
                return;
            }
            alert(d.message);
            setManualAmount('');
            setManualRef('');
            void cargarMovimientos();
        } catch (e) {
            console.error(e);
            alert("Error procesando solicitud.");
        }
    };

    const abrirCheckoutWompi = (datos) => {
        if (typeof window.WidgetCheckout !== 'function') {
            throw new Error("El widget de pagos de Wompi aún no ha terminado de cargar o fue bloqueado por el navegador. Recarga la página e inténtalo de nuevo.");
        }
        const checkout = new window.WidgetCheckout({
            currency: "COP",
            amountInCents: datos.amountInCents,
            reference: datos.reference,
            publicKey: datos.publicKey,
            signature: { integrity: datos.signature },
            redirectUrl: window.location.href,
        });
        checkout.open((result) => {
            const transaction = result?.transaction;
            const status = transaction?.status;
            if (transaction?.id) {
                sincronizarPagoWompi(transaction.id)
                    .then(data => {
                        if (data.status === 'APPROVED') alert("Wompi aprobó el pago y el saldo fue actualizado.");
                        else if (['DECLINED', 'ERROR', 'VOIDED'].includes(data.status)) alert("Wompi no aprobó el pago. Puedes iniciar otro desde esta pantalla.");
                        else alert("El pago sigue en proceso con Wompi. Su estado se actualizará automáticamente.");
                    })
                    .catch(error => alert(error.message || 'Pago enviado. Estamos esperando la confirmación de Wompi.'));
            } else {
                alert("Pago recibido por Wompi. El saldo se actualizará cuando llegue la confirmación.");
                cargarWompiPendientes();
            }
            setIsProcessing(false);
        });
        // Al cerrar el cuadro de Wompi se permite volver a abrir el mismo pago pendiente.
        setIsProcessing(false);
    };

    const reanudarPagoWompi = (datos) => {
        if (isProcessing) return;
        setIsProcessing(true);
        try {
            abrirCheckoutWompi(datos);
        } catch (error) {
            alert(error.message || "Error iniciando Wompi");
            setIsProcessing(false);
        }
    };

    const handleAutoDeposit = async () => {
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('recargar saldo con tarjeta o PSE');
            return;
        }
        const monto = autoAmount;
        if (!monto || monto < 1000 || isProcessing) return;
        setIsProcessing(true);
        try {
            const res = await fetch(API_BASE_URL + '/api/wompi/init', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    monto: Number(monto)
                })
            });

            if (!res.ok) {
                const errData = await res.json();
                throw new Error(errData.error || "Error al iniciar pago");
            }

            const datos = await res.json();
            abrirCheckoutWompi(datos);

        } catch (error) {
            console.error(error);
            alert(error.message || "Error iniciando Wompi");
            setIsProcessing(false);
        }
    };

    const handleWithdraw = async () => {
        if (!currentUser) {
            solicitarAutenticacionVistaPrevia('solicitar un retiro de saldo');
            return;
        }
        if (!withdrawAmount || !withdrawAccount || !withdrawName) return alert("Por favor completa todos los datos.");
        if (Number(withdrawAmount) > currentUser.saldo) return alert("Saldo insuficiente.");

        const datosCuenta = `${withdrawAccount} - ${withdrawName}`;

        try {
            const res = await fetch(API_BASE_URL + '/api/transaction/withdraw', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    monto: withdrawAmount,
                    datosCuenta: datosCuenta
                })
            });

            const data = await res.json();
            if (data.success) {
                alert(data.message);
                setUser({ ...currentUser, saldo: data.newBalance });
                setWithdrawAmount('');
                setWithdrawAccount('');
                setWithdrawName('');
                // TODO: Notificar al componente superior/header para actualizar saldo
            } else {
                alert("Error: " + data.error);
            }
        } catch (e) {
            console.error(e);
            alert("Error al procesar retiro.");
        }
    };

    const movimientos = useMemo(
        () => (Array.isArray(movimientosData?.movimientos) ? movimientosData.movimientos : []),
        [movimientosData]
    );
    const resumenMovimientos = movimientosData?.resumen || {
        pendientesCount: 0,
        enCursoCount: 0,
        totalIngresos: 0,
        totalEgresos: 0,
        totalReferidos: 0,
        totalPremios: 0
    };
    const pendientesActivosCount = Number(resumenMovimientos.pendientesCount || 0)
        + Number(resumenMovimientos.enCursoCount || 0);

    const movimientosFiltrados = useMemo(() => {
        if (filtroMovimiento === 'todos') return movimientos;
        if (filtroMovimiento === 'pendiente' || filtroMovimiento === 'positivo' || filtroMovimiento === 'negativo') {
            return movimientos.filter(m => m.estadoVisual === filtroMovimiento);
        }
        if (filtroMovimiento === 'recargas_retiros') {
            return movimientos.filter(m => m.categoriaMovimiento === 'recarga' || m.categoriaMovimiento === 'retiro');
        }
        return movimientos.filter(m => m.categoriaMovimiento === filtroMovimiento);
    }, [movimientos, filtroMovimiento]);

    return (
        <div className="finance-container">
            <h2>💰 Finanzas</h2>

            <div className="finance-main-tabs">
                <button
                    className={`tab-btn ${activeTab === 'deposit' ? 'active' : ''}`}
                    onClick={() => setActiveTab('deposit')}
                >Recargar</button>
                <button
                    className={`tab-btn ${activeTab === 'withdraw' ? 'active' : ''}`}
                    onClick={() => setActiveTab('withdraw')}
                >Retirar</button>
                <button
                    className={`tab-btn ${activeTab === 'movements' ? 'active' : ''}`}
                    onClick={() => setActiveTab('movements')}
                >
                    📋 Movimientos
                    {pendientesActivosCount > 0 && (
                        <span className="finance-tab-badge">{pendientesActivosCount}</span>
                    )}
                </button>
            </div>

            {activeTab === 'deposit' && (
                <div className="deposit-section-container">
                    <div className="deposit-tabs">
                        <button 
                            className={`sub-tab-btn ${depositMethod === 'manual' ? 'active' : ''}`}
                            onClick={() => setDepositMethod('manual')}
                        >📱 Nequi Manual</button>
                        <button 
                            className={`sub-tab-btn ${depositMethod === 'auto' ? 'active' : ''}`}
                            onClick={() => setDepositMethod('auto')}
                        >💳 Tarjeta / PSE (Wompi)</button>
                    </div>

                    {depositMethod === 'manual' ? (
                        <div className="deposit-section">
                            {currentUser && !nequiInfo.cargado ? (
                                <p className="info-text">Cargando datos de Nequi...</p>
                            ) : nequiInfo.esDeModerador && nequiInfo.nequis.length === 0 ? (
                                <div className="nequi-empty-notice">
                                    ⚠️ Aún no hay un número de Nequi configurado para recargas manuales en tu cuenta. Usa Tarjeta / PSE (Wompi) o solicita que agreguen el número en administración.
                                </div>
                            ) : (
                                <div className="nequi-targets-box">
                                    <p className="info-text" style={{ marginBottom: nequiInfo.nequis.length > 1 ? 10 : 0 }}>
                                        {nequiInfo.nequis.length > 1
                                            ? 'Envía a cualquiera de estas cuentas Nequi:'
                                            : 'Envía a Nequi:'}
                                    </p>
                                    <div className="nequi-targets-list">
                                        {nequiInfo.nequis.map((item) => (
                                            <div key={item.id ?? item.numero} className="nequi-target-row">
                                                <div className="nequi-target-data">
                                                    <strong>{item.numero}</strong>
                                                    {item.titular && <span className="nequi-target-holder">{item.titular}</span>}
                                                </div>
                                                <button
                                                    type="button"
                                                    className="nequi-copy-btn"
                                                    onClick={() => copiarNequi(item.numero)}
                                                >
                                                    {nequiCopiado === item.numero ? '✓ Copiado' : 'Copiar'}
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                                    <p className="info-text" style={{ marginTop: 10, marginBottom: 16 }}>
                                        Espera aprobación (1-5 min).
                                    </p>
                                </div>
                            )}
                            <input type="number" placeholder="Monto enviado" min="1000"
                                value={manualAmount} onChange={e => setManualAmount(e.target.value)} />
                            <input type="text" placeholder="Número de Referencia"
                                value={manualRef} onChange={e => setManualRef(e.target.value)} />
                            <button className="finance-action-btn" onClick={handleManualDeposit}>Notificar Pago</button>
                        </div>
                    ) : (
                        <div className="deposit-section">
                            <p className="info-text">Recarga inmediata automatizada.</p>
                                {wompiPendientes.length > 0 && (
                                    <div className="wompi-pending-box">
                                        <strong>Tienes pagos Wompi pendientes</strong>
                                        <p>Continúa el mismo pago o espera su confirmación. No necesitas crear otra solicitud.</p>
                                        {wompiPendientes.map(pago => (
                                            pago.transactionId ? (
                                                <button key={pago.reference} type="button" className="wompi-resume-btn"
                                                    disabled={isProcessing} onClick={() => sincronizarPagoWompi(pago.transactionId)
                                                        .catch(error => alert(error.message || 'No se pudo consultar el pago.'))}>
                                                    Consultar estado del pago de ${Number(pago.montoReal || 0).toLocaleString('es-CO')}
                                                </button>
                                            ) : (
                                                <button key={pago.reference} type="button" className="wompi-resume-btn"
                                                    disabled={isProcessing} onClick={() => reanudarPagoWompi(pago)}>
                                                    {isProcessing ? 'Abriendo Wompi…' : `Continuar pago de $${Number(pago.montoReal || 0).toLocaleString('es-CO')}`}
                                                </button>
                                            )
                                        ))}
                                    </div>
                                )}
                            <div className="calc-box">
                                <label>Recibir en cuenta:</label>
                                <input type="number" placeholder="10000" min="1000"
                                    value={autoAmount} onChange={e => setAutoAmount(e.target.value)} />
                                
                                {autoAmount >= 1000 && (
                                    <div className="cost-breakdown">
                                        <div className="cost-row"><span>Comisión pasarela:</span> <span className="fee-amount">+ ${autoCalc.comisionTotal.toLocaleString()}</span></div>
                                        <hr/>
                                        <div className="cost-row total"><span>Total a pagar:</span> <span className="total-amount">${autoCalc.totalPagar.toLocaleString()}</span></div>
                                    </div>
                                )}
                            </div>
                            <button 
                                className="finance-action-btn" 
                                disabled={isProcessing || (Boolean(currentUser) && autoAmount < 1000)}
                                onClick={handleAutoDeposit}
                            >
                                {isProcessing ? 'Cargando Wompi...' : autoAmount >= 1000 ? `Pagar $${autoCalc.totalPagar.toLocaleString()}` : 'Pagar con Wompi'}
                            </button>
                        </div>
                    )}
                </div>
            )}

            {activeTab === 'withdraw' && (
                <div className="withdraw-section">
                    <p className="info-text">Retiros a Nequi o Daviplata. Tiempo estimado: 24h.</p>
                    <div className="withdraw-balance">
                        Saldo disponible: <strong>${currentUser ? currentUser.saldo.toLocaleString() : 0}</strong>
                    </div>
                    {currentUser?.player_tag_verificado === false ? (
                        <p className="info-text verification-pending">
                            Tu Player Tag está pendiente de verificación manual. Puedes usar la plataforma; los retiros se habilitarán cuando el administrador confirme tu cuenta.
                            <button type="button" className="verification-refresh-btn" onClick={actualizarEstadoVerificacion}>Actualizar estado</button>
                        </p>
                    ) : (
                        <>
                            <input type="number" placeholder="Monto a retirar" min="10000"
                                value={withdrawAmount} onChange={e => setWithdrawAmount(e.target.value)} />
                            <input type="text" placeholder="Número de cuenta (Nequi/Daviplata)"
                                value={withdrawAccount} onChange={e => setWithdrawAccount(e.target.value)} />
                            <input type="text" placeholder="Nombre completo del titular"
                                value={withdrawName} onChange={e => setWithdrawName(e.target.value)} />
                            <button className="finance-action-btn withdraw-btn" onClick={handleWithdraw}>Solicitar Retiro</button>
                        </>
                    )}
                </div>
            )}

            {activeTab === 'movements' && (
                <div className="movimientos-section">
                    <div className="movimientos-header-bar">
                        <h3>📋 Historial y movimientos en vivo</h3>
                        <button type="button" className="movimientos-refresh-btn" onClick={cargarMovimientos} disabled={cargandoMovimientos}>
                            {cargandoMovimientos ? 'Actualizando…' : '🔄 Actualizar'}
                        </button>
                    </div>

                    <div className="movimientos-resumen-grid">
                        <div className="mov-resumen-card">
                            <span className="mov-resumen-label">Pendientes / En curso</span>
                            <strong className="mov-resumen-valor is-pendiente">{pendientesActivosCount}</strong>
                        </div>
                        <div className="mov-resumen-card">
                            <span className="mov-resumen-label">Ingresos acreditados</span>
                            <strong className="mov-resumen-valor is-positivo">{formatearCop(resumenMovimientos.totalIngresos)}</strong>
                        </div>
                        <div className="mov-resumen-card">
                            <span className="mov-resumen-label">Salidas / Entradas</span>
                            <strong className="mov-resumen-valor is-negativo">-{formatearCop(resumenMovimientos.totalEgresos)}</strong>
                        </div>
                        <div className="mov-resumen-card">
                            <span className="mov-resumen-label">Por referidos</span>
                            <strong className="mov-resumen-valor is-positivo">{formatearCop(resumenMovimientos.totalReferidos)}</strong>
                        </div>
                        <div className="mov-resumen-card">
                            <span className="mov-resumen-label">Premios ganados</span>
                            <strong className="mov-resumen-valor is-positivo">{formatearCop(resumenMovimientos.totalPremios)}</strong>
                        </div>
                    </div>

                    <div className="movimientos-filtros" role="tablist" aria-label="Filtrar movimientos">
                        {FILTROS_MOVIMIENTOS.map(f => (
                            <button
                                key={f.id}
                                type="button"
                                className={`mov-filtro-btn ${filtroMovimiento === f.id ? 'active' : ''}`}
                                onClick={() => setFiltroMovimiento(f.id)}
                            >
                                {f.label}
                            </button>
                        ))}
                    </div>

                    {cargandoMovimientos && movimientos.length === 0 ? (
                        <div className="movimientos-empty">Cargando movimientos…</div>
                    ) : movimientosFiltrados.length === 0 ? (
                        <div className="movimientos-empty">
                            No hay movimientos en esta categoría todavía.
                        </div>
                    ) : (
                        <div className="movimientos-list">
                            {movimientosFiltrados.map(m => {
                                const estadoVisual = m.estadoVisual || 'pendiente';
                                const signo = m.signo || '';
                                return (
                                    <article key={m.id} className={`movimiento-card is-${estadoVisual}`}>
                                        <div className="movimiento-top">
                                            <div className="movimiento-title-wrap">
                                                <span className="movimiento-title">{m.titulo}</span>
                                                <span className={`movimiento-estado is-${estadoVisual}`}>
                                                    {m.estadoTexto || 'Pendiente'}
                                                </span>
                                            </div>
                                            <span className={`movimiento-monto is-${estadoVisual}`}>
                                                {signo}{formatearCop(m.monto)}
                                            </span>
                                        </div>
                                        <div className="movimiento-meta">
                                            <span className="movimiento-origen">📌 Origen: {m.origen || 'Sistema'}</span>
                                            <span className="movimiento-fecha">🕒 {formatearFechaHora(m.fecha)}</span>
                                        </div>
                                        {m.detalle && <p className="movimiento-detalle">{m.detalle}</p>}
                                    </article>
                                );
                            })}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

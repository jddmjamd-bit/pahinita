import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../store/useAppStore';
import { API_BASE_URL } from '../store/llamarApi';
import './Finance.css';

export default function Finance() {
    const [activeTab, setActiveTab] = useState('deposit'); // 'deposit' or 'withdraw'
    const [depositMethod, setDepositMethod] = useState('manual'); // 'manual' or 'auto'
    
    // Deposit states
    const [manualAmount, setManualAmount] = useState('');
    const [manualRef, setManualRef] = useState('');
    const [autoAmount, setAutoAmount] = useState('');
    const [isProcessing, setIsProcessing] = useState(false);
    const [wompiPendientes, setWompiPendientes] = useState([]);

    // Withdraw states
    const [withdrawAmount, setWithdrawAmount] = useState('');
    const [withdrawAccount, setWithdrawAccount] = useState('');
    const [withdrawName, setWithdrawName] = useState('');

    const currentUser = useAppStore(state => state.user);
    const setUser = useAppStore(state => state.setUser);
    const sincronizarSesion = useAppStore(state => state.sincronizarSesion);
    const socket = useAppStore(state => state.socket);
    const location = useLocation();
    const navigate = useNavigate();

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

    useEffect(() => {
        cargarWompiPendientes();
    }, [currentUser?.id]);

    useEffect(() => {
        if (!socket) return undefined;
        const actualizarPago = () => cargarWompiPendientes();
        socket.on('wompi_pago_actualizado', actualizarPago);
        return () => socket.off('wompi_pago_actualizado', actualizarPago);
    }, [socket, currentUser?.id]);

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
        if (!manualAmount || !manualRef) return alert("Por favor llena los datos.");
        if (!currentUser) return alert("Debes iniciar sesión.");

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
        const monto = autoAmount;
        if (!monto || monto < 1000 || !currentUser || isProcessing) return;
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
        if (!withdrawAmount || !withdrawAccount || !withdrawName) return alert("Por favor completa todos los datos.");
        if (!currentUser) return alert("Debes iniciar sesión.");
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

    return (
        <div className="finance-container center-screen">
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
                            <p className="info-text">Envía a Nequi <strong>3126655995</strong>.<br/>Espera aprobación (1-5 min).</p>
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
                                disabled={isProcessing || autoAmount < 1000}
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
        </div>
    );
}

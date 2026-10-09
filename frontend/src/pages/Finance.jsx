import React, { useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import './Finance.css';

const API_BASE_URL = 'https://torneos-beta.onrender.com';

export default function Finance() {
    const [activeTab, setActiveTab] = useState('deposit'); // 'deposit' or 'withdraw'
    const [depositMethod, setDepositMethod] = useState('manual'); // 'manual' or 'auto'
    
    // Deposit states
    const [manualAmount, setManualAmount] = useState('');
    const [manualRef, setManualRef] = useState('');
    const [autoAmount, setAutoAmount] = useState('');
    const [isProcessing, setIsProcessing] = useState(false);

    // Withdraw states
    const [withdrawAmount, setWithdrawAmount] = useState('');
    const [withdrawAccount, setWithdrawAccount] = useState('');
    const [withdrawName, setWithdrawName] = useState('');

    const currentUser = useAppStore(state => state.user);
    const setUser = useAppStore(state => state.setUser);

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
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUser.id,
                    username: currentUser.username,
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

    const handleAutoDeposit = async () => {
        const monto = autoAmount;
        if (!monto || monto < 1000 || !currentUser) return;
        
        setIsProcessing(true);
        try {
            const res = await fetch(API_BASE_URL + '/api/wompi/init', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUser.id,
                    username: currentUser.username,
                    monto: Number(monto)
                })
            });

            if (!res.ok) {
                const errData = await res.json();
                throw new Error(errData.error || "Error al iniciar pago");
            }

            const datos = await res.json();

            // Configurar Widget
            const checkout = new window.WidgetCheckout({
                currency: "COP",
                amountInCents: datos.amountInCents,
                reference: datos.reference,
                publicKey: datos.publicKey,
                signature: { integrity: datos.signature },
                redirectUrl: window.location.href,
            });

            checkout.open(function (result) {
                const transaction = result.transaction;
                console.log('Transaction ID: ', transaction.id);
                // Confirmación real llega por Socket desde Webhook
                alert("Pago en proceso con Wompi. Recibirás una notificación cuando se confirme.");
                setIsProcessing(false);
            });

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
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUser.id,
                    username: currentUser.username,
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
                    <input type="number" placeholder="Monto a retirar" min="10000"
                        value={withdrawAmount} onChange={e => setWithdrawAmount(e.target.value)} />
                    <input type="text" placeholder="Número de cuenta (Nequi/Daviplata)"
                        value={withdrawAccount} onChange={e => setWithdrawAccount(e.target.value)} />
                    <input type="text" placeholder="Nombre completo del titular"
                        value={withdrawName} onChange={e => setWithdrawName(e.target.value)} />
                    <button className="finance-action-btn withdraw-btn" onClick={handleWithdraw}>Solicitar Retiro</button>
                </div>
            )}
        </div>
    );
}

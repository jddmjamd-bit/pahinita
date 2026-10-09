    // --- PAGOS ---
    if (btnOpenDeposit) btnOpenDeposit.addEventListener('click', () => depositModal.classList.remove('hidden'));
    if (closeDepositModal) closeDepositModal.addEventListener('click', () => depositModal.classList.add('hidden'));
    if (autoInput) {
        autoInput.addEventListener('input', () => {
            const val = parseInt(autoInput.value);

            // Validación mínima
            if (!val || val < 1000) {
                costBreakdown.classList.add('hidden');
                btnAutoDeposit.disabled = true;
                btnAutoDeposit.textContent = "Pagar con Tarjeta";
                return;
            }

            // --- FÓRMULA DE COMISIÓN (TARIFA CARA) ---
            // Fórmula: ((Valor * 1.3) + 700) * 1.2
            const baseConMargen = val + 840;
            const totalPagar = Math.ceil(baseConMargen / 0.964);

            const comisionTotal = totalPagar - val;

            // Mostrar resultados con separadores de miles (ej: 10.000)
            feeDisplay.textContent = `+ $${comisionTotal.toLocaleString()}`;
            totalDisplay.textContent = `$${totalPagar.toLocaleString()}`;

            costBreakdown.classList.remove('hidden');

            btnAutoDeposit.disabled = false;
            btnAutoDeposit.textContent = `Pagar $${totalPagar.toLocaleString()}`;
        });
    }
    if (btnManualDeposit) btnManualDeposit.addEventListener('click', async () => { const m = document.getElementById('manual-amount').value; const r = document.getElementById('manual-ref').value; if (!m || !r) return alert("Datos?"); const res = await fetch(API_BASE_URL + '/api/transaction/create', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: currentUser.id, username: currentUser.username, tipo: 'deposito', metodo: 'manual_nequi', monto: m, referencia: r }) }); const d = await res.json(); alert(d.error || d.message); if (!res.ok) return; depositModal.classList.add('hidden'); });
    if (btnAutoDeposit) {
        btnAutoDeposit.addEventListener('click', async () => {
            const monto = autoInput.value;
            if (!monto || !currentUser) return;

            btnAutoDeposit.disabled = true;
            btnAutoDeposit.textContent = "Cargando Wompi...";

            try {
                // 1. Pedir datos de transacción al servidor
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

                // 2. Configurar Widget
                const checkout = new WidgetCheckout({
                    currency: "COP",
                    amountInCents: datos.amountInCents,
                    reference: datos.reference,
                    publicKey: datos.publicKey,
                    signature: { integrity: datos.signature }, // ¡Seguridad!
                    redirectUrl: window.location.href, // Opcional: A dónde vuelve al terminar
                });

                // 3. Abrir Widget
                checkout.open(function (result) {
                    const transaction = result.transaction;
                    console.log('Transaction ID: ', transaction.id);
                    console.log('Transaction object: ', transaction);
                    // Aquí solo cerramos el modal, la confirmación real llega por Socket desde el Webhook
                    depositModal.classList.add('hidden');
                    btnAutoDeposit.disabled = false;
                    btnAutoDeposit.textContent = "Pagar con Wompi";
                });

            } catch (error) {
                console.error(error);
                alert((error && error.message) || "Error iniciando Wompi");
                btnAutoDeposit.disabled = false;
            }
        });
    }

    // --- LÓGICA DE RETIROS ---
    if (btnOpenWithdraw) btnOpenWithdraw.addEventListener('click', () => withdrawModal.classList.remove('hidden'));
    if (closeWithdrawModal) closeWithdrawModal.addEventListener('click', () => withdrawModal.classList.add('hidden'));

    if (btnSubmitWithdraw) {
        btnSubmitWithdraw.addEventListener('click', async () => {
            const monto = document.getElementById('withdraw-amount').value;
            const cuenta = document.getElementById('withdraw-account').value;
            const nombre = document.getElementById('withdraw-name').value;

            if (!monto || !cuenta || !nombre) return alert("Por favor completa todos los datos.");
            if (parseInt(monto) > currentUser.saldo) return alert("Saldo insuficiente.");

            const datosCuenta = `${cuenta} - ${nombre}`;

            const res = await fetch(API_BASE_URL + '/api/transaction/withdraw', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    userId: currentUser.id,
                    username: currentUser.username,
                    monto: monto,
                    datosCuenta: datosCuenta
                })
            });

            const data = await res.json();
            if (data.success) {
                alert(data.message);
                userBalanceDisplay.textContent = '$' + data.newBalance;
                currentUser.saldo = data.newBalance;
                withdrawModal.classList.add('hidden');
            } else {
                alert("Error: " + data.error);
            }
        });
    }

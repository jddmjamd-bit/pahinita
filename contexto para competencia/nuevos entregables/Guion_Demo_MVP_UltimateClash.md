# 🎬 Guion de Presentación y Demo del MVP — UltimateClash (Máx. 3:00 min)
**Entregable 01 · Fase 3: MVP · Producto Funcional · Execute the Idea 2026**  
**URL en vivo:** [https://torneos-beta.onrender.com](https://torneos-beta.onrender.com)  

> **💡 Regla de oro del jurado en Fase 3:** *"Lo importante es que se vea el producto funcionando de principio a fin y que se puede cobrar, no que se explique en diapositivas."*  
> **Preparación antes de grabar (2 minutos antes):**
> 1. Ten abiertas **2 ventanas del navegador** (una normal con tu cuenta principal y otra en modo incógnito con una segunda cuenta de prueba) para que el emparejamiento y la negociación en vivo se vean instantáneos sin cortes.
> 2. Ten abierto *Clash Royale* (o una partida recién jugada entre las dos cuentas) para mostrar cómo la página detecta al ganador sola.

---

## ⏱️ Estructura minuto a minuto (Duración total: ~2:45 min)

### 1. Gancho + Entrada y Verificación Real (0:00 – 0:30)
- **🖥️ Qué muestras en pantalla:**
  - Abres **[torneos-beta.onrender.com](https://torneos-beta.onrender.com)** en la pantalla de **Registro / Iniciar Sesión** (`/auth`).
  - Señalas el campo del **Player Tag (`#TAG`) + Token de API de Clash Royale** y el checkbox de **Términos y Privacidad**, y luego entras al **Lobby principal**.
- **🗣️ Qué dices:**
  > *"Hoy en Colombia más de 3,500 jugadores de Clash Royale compiten por dinero real en grupos de WhatsApp donde les cobran 20% de comisión fija, verifican ganadores a mano con capturas de pantalla y el administrador puede cerrar el grupo cuando quiera.  
  > Esto es **UltimateClash**, nuestra plataforma web ya desplegada en vivo en **torneos-beta.onrender.com**.  
  > Al registrarse, el jugador vincula su etiqueta oficial y su token de Clash Royale: nuestro servidor valida directamente con la API oficial de Supercell que esa cuenta existe y que realmente le pertenece."*

---

### 2. Mecanismo Real de Cobro y Transparencia (0:30 – 1:05) *(Criterio 03 del jurado: "Se puede cobrar")*
- **🖥️ Qué muestras en pantalla:**
  - En el menú izquierdo, haces clic en **`+ Recargar`** (`/finance`).
  - Muestras las dos pestañas de recarga: **Automática con Wompi** (escribes `$5,000` o `$10,000 COP` y abres el widget real de **Wompi** para que se vea la pasarela oficial en pantalla) y **Manual con Nequi** (desde `$1,000 COP`).
  - Pasas rápido a **`# 💬 Chat / Anuncios`** → pestaña **`🧾 Pagos`** para mostrar los comprobantes reales de retiros pagados.
- **🗣️ Qué dices:**
  > *"El producto ya está listo para facturar y recibir dinero real en pesos colombianos.  
  > En la sección de Finanzas, cualquier jugador puede recargar desde **$1,000 pesos**. Si elige pago automático, se abre directamente la pasarela oficial de **Wompi** con firma criptográfica para pagar con tarjeta o PSE y el saldo se acredita solo; o puede transferir por **Nequi**.  
  > Además, para eliminar la desconfianza de los grupos de WhatsApp, cada retiro pagado se publica en el canal **Pagos** con su comprobante verificado."*

---

### 3. Flujo Principal: Matchmaking, Negociación y Partida Automática (1:05 – 2:05) *(Criterios 01 y 02: "Funciona de principio a fin")*
- **🖥️ Qué muestras en pantalla:**
  - Abres **`⚔️ CLASH ROYALE` → `⚔️ Jugar / Privada`** (`/match`).
  - Haces clic en **Buscar Partida** (si un jugador está fuera de la página, le llega una **Notificación Push** al celular).
  - En cuanto empareja con la segunda cuenta, muestras la **Sala Privada de Negociación**:
    - El botón de **Voz en Discord** (que crea un canal de voz privado temporal solo para los dos rivales).
    - El **Temporizador individual de 2:30 min** (estilo *Pokémon Showdown* para evitar que el rival deje esperando).
    - Ambos eligen el modo y escriben el monto (por ejemplo, **`$5,000 COP`**; en promedio en nuestro modelo financiero la comisión cobrada es de **`$1,763 COP / 17.58%`**, menor que el 20% fijo de WhatsApp). Señalas el texto automático que calcula la ganancia (**"Si ganas recibes: $8,167 COP"**).
  - Ambos presionan **`🎮 COMENZAR`** y confirman en el modal.
  - La pantalla cambia a **"Buscando resultado en Clash Royale..."** y, al terminar la batalla 1v1 en el juego, muestras cómo aparece el **resultado automático**, sube el saldo del ganador y se entregan los **tickets de sorteo**.
- **🗣️ Qué dices:**
  > *"Veamos el flujo principal de principio a fin. Entro a **Jugar**, busco rival —si otros jugadores tienen el navegador cerrado, les llega una notificación Push al celular para entrar al instante— y se abre nuestra **Sala Privada**.  
  > Aquí podemos hablar por chat, abrir un canal de **voz privado en Discord** con un clic y activar el **reloj individual de 2 minutos y medio** para que nadie haga perder tiempo.  
  > Acordamos un reto de **$5,000 pesos por jugador**: el sistema calcula en tiempo real la comisión escalonada —que baja del 25% hasta el 10% según el pozo— y muestra exactamente cuánto recibirá el ganador.  
  > Ambos confirmamos, se descuenta el saldo de forma segura y jugamos en Clash Royale. **Nadie tiene que subir capturas ni esperar a un administrador:** nuestro servidor consulta la API oficial de Clash Royale cada 5 segundos, detecta quién ganó por coronas, liquida el premio al instante en el saldo del ganador y les entrega tickets de sorteo a ambos."*

---

### 4. Retención (75% Devuelto) y Soporte con IA 24/7 (2:05 – 2:45)
- **🖥️ Qué muestras en pantalla:**
  - Haces clic en **`🎁 Sorteos`** (`/sorteos`) donde se usan los tickets ganados en cada partida.
  - Haces clic en **`🏆 Rankings`** (`/leaderboard`) mostrando los pozos acumulados de **Día, Semana, Mes y Año** y el historial de cortes.
  - Haces clic en **`🤖 Asistente`** (`/chatbot`) y haces una pregunta rápida al **Chatbot IA**, más la pestaña **`✉️ Hablar con el admin`** (`/soporte`).
- **🗣️ Qué dices:**
  > *"En nuestro piloto real de 15 días con 35 usuarios movimos $300,000 pesos y generamos $60,000 en comisiones, pero aprendimos que sin comunidad ni guía inicial la retención caía.  
  > Por eso construimos dos motores que ya están funcionando hoy:  
  > Primero, **solo el 25% de la comisión es ganancia neta de la plataforma** y el **75% se destina a los jugadores**: hoy ya está activo el **35%** alimentando automáticamente los **Sorteos con tickets (20%)** y los **4 pozos de Rankings (15%: diario, semanal, mensual y anual)** que se premian solos, mientras el otro **40%** se acumula en reserva para misiones, logros, devolución y referidos. Incluso si pierdes una partida, ganas tickets para recuperar tu dinero.  
  > Y segundo, creamos un **Asistente con Inteligencia Artificial 24/7** entrenado por nosotros: resuelve cualquier duda al instante y, si algo nuevo no lo sabe, nos envía un correo automático a los administradores y le avisa al jugador por notificación Push apenas le respondemos.  
  > **UltimateClash ya funciona, ya cobra y está listo para escalar en Colombia y Latinoamérica.**"*

---

## ✅ Checklist rápido para que el video / demo quede perfecto
- [ ] **Duración:** Menos de **3:00 minutos** (este guion hablado a ritmo natural toma **2:35 – 2:45 min**).
- [ ] **Sin diapositivas:** 100% grabación de pantalla mostrando `torneos-beta.onrender.com` en acción (con tu cámara pequeña en una esquina si quieres sumar cercanía).
- [ ] **Que se vea el cobro:** Abrir el modal de **Wompi** y la pestaña de **Nequi / Evidencias de pago** demuestra de inmediato el Criterio 03 (*"Se puede cobrar"*).
- [ ] **Que se vea la diferencia frente a WhatsApp:** Resaltar que **no hay capturas de pantalla** (todo es vía API de Clash Royale) y que **el 75% de la comisión regresa** en sorteos y rankings.


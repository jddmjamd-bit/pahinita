# UltimateClash — Estudio de Mercado (Entregable 03 · Fase 3)
**Execute the Idea · 2ª Edición · Colombia 2026**  
**MVP funcional en línea:** [https://torneos-beta.onrender.com](https://torneos-beta.onrender.com)  
**Equipo Ultimate Clash:** Juan David Delgado M. & Juan José Neira B.  
**Tasa de cambio de referencia del modelo (TRM):** $4,400 COP = $1 USD  

---

## 1. Problema y Cliente Objetivo

### El Problema: Informalidad, sobrecosto y riesgo en torneos de habilidad de Clash Royale
Miles de jugadores competitivos de *Clash Royale (CR)* en Colombia y Latinoamérica buscan competir por dinero real en partidas 1v1 para rentabilizar su habilidad, pero hoy dependen de grupos informales de WhatsApp que presentan tres fallas críticas:

1. **Concentración y abandono (Dato propio [F8]):** Solo **3 grupos activos de WhatsApp** concentran **más de 3,500 membresías** (2 grupos en Colombia con ~1,000 miembros cada uno y 1 grupo regional en 4 países LATAM con >1,500 miembros). Además, hemos documentado **15 grupos cerrados** unilateralmente cuando el administrador decide abandonar la operación.
2. **Gravedad económica (20% de pérdida fija sin retorno):** Estos grupos cobran una **comisión fija del 20% sobre el pozo** sin devolver nada al jugador. En partidas típicas de **$4,000 a $6,000 COP por jugador** (pozos de $8,000 a $12,000 COP) y ~40 partidas diarias por grupo, los jugadores pierden entre **$1,600 y $2,400 COP por partida** en comisiones puras sin recibir beneficios, premios de ranking ni reembolsos.
3. **Fricción manual y horario cerrado:** El jugador debe transferir al Nequi personal del administrador, enviar capturas de pantalla al terminar la partida para verificar manualmente al ganador (con esperas de 15 a 40 minutos y disputas por capturas editadas) y solo puede jugar en los horarios fijos en que el administrador abre el chat (el resto del día el chat permanece cerrado).

### Perfil Concreto del Cliente (Arquetipo / Buyer Persona — Usuario = Pagador)
- **Demografía [F1]:** Hombre de **18 a 24 años** (el **74.51%** de los jugadores globales de CR son hombres y el **42.93%** tiene entre 18 y 24 años según datos de AppMagic/Udonis [F1]), estudiante universitario/técnico o joven trabajador urbano en Colombia (Bogotá, Medellín, Cali, Barranquilla y ciudades intermedias).
- **Hábito en el juego:** Juega *Clash Royale* entre **1 y 2 horas diarias**, supera las 5,000 copas / Camino de Leyendas, sigue el *meta* competitivo en TikTok, YouTube y Discord, y busca competir en duelos 1v1 desde **$1,000 hasta $10,000 COP** por partida.
- **Medios de pago:** Cuenta con **Nequi** (>27 millones de usuarios en Colombia [F4]), **Daviplata**, **Llaves Bre-B** o tarjeta débito/crédito (procesada vía **Wompi**). **El jugador que usa la plataforma es exactamente la misma persona que paga y cobra.**
- **¿Por qué ahora? Momentum del sector:** *Clash Royale* generó **$646.8M USD en gasto bruto de jugadores en 2025 (+149% frente a 2024)** [F2] y sus jugadores nuevos crecieron casi **+500%** [F5]. En Colombia, el **62% de los gamers juega en celular** [F3] dentro de un universo de ~11 millones de jugadores móviles [F6].

---

## 2 y 3. Tamaño de Mercado: TAM y SAM (Cálculo Explícito: Clientes × Gasto Anual)

### Origen del Gasto Anual por Pagador (ARPPU Base = $9.28 USD/año ≈ $40,832 COP/año) [F7]
En nuestro **piloto comercial real de 15 días (diciembre 2025)** con **35 usuarios registrados** (20 activos/día), circularon **$300,000 COP** en pozos y la plataforma generó **$60,000 COP en comisiones brutas**:
- Comisión bruta quincenal por usuario = `\$60,000 COP ÷ 35 usuarios = \$1,714.29 COP / quincena`.
- Anualizado por 24 quincenas: `\$1,714.29 COP × 24 = \$41,143 COP/año`, equivalente a **$9.28 USD/año** (a la TRM de dic. 2025 de $4,433 COP/USD) o **$40,832 COP/año** (a la TRM estándar del modelo de **$4,400 COP/USD**).
- *Nota de transparencia:* Estos montos miden **ingresos brutos potenciales por comisiones de la plataforma**, sin inflar la cifra con el dinero total apostado en los pozos (GMV).

| Nivel | Clientes Potenciales | Fórmula Explícita (Paso a Paso) | Mercado Anual (USD) | Mercado Anual (COP a $4,400) |
| :--- | :--- | :--- | ---: | ---: |
| **2. TAM Global** | **72,200,000 MAU** [F1] | `72,200,000 jugadores activos [F1] × $9.28 USD/año` | **$670,016,000 USD** (~$670.0M) | **$2,948,070,400,000 COP** (~$2.95 billones) |
| **3A. SAM Global** | **2,166,000 jug./año** (~2.17M One Pager) | `72,200,000 × 15% competitivos × 20% dispuestos = 2,166,000` <br> `2,166,000 × $9.28 USD/año` | **$20,100,480 USD** (~$20.1M) | **$88,442,112,000 COP** (~$88.4 mil millones) |
| **3B. SAM Colombia** *(Geografía y canal hoy)* | **32,550 jug./año** *(Est. proxy; 32,490 sin redond.)* | `2,170,000 (SAM Global) × 1.5% cuota CO = 32,550 jugadores` <br> `32,550 × $9.28 USD/año` | **$302,064 USD** (~$302K) | **$1,329,081,600 COP** (~$1,329.1 millones) |
| **3C. SAM LATAM** *(CO + 2 países M6–12)* | **141,050 jug./año** *(Est.)* | `2,170,000 (SAM Global) × 6.5% (CO 1.5% + MX/PE/CL 5.0%)` <br> `141,050 × $9.28 USD/año` | **$1,308,944 USD** (~$1.31M) | **$5,759,353,600 COP** (~$5,759.4 millones) |

**Justificación de los 3 supuestos estimados del SAM:**
1. **15% competitivo (estimado):** Porción de jugadores activos que sigue el *meta*, mejora mazos, sube de liga y juega diariamente (`72.2M × 15% = 10.83M jugadores`).
2. **20% dispuestos a competir por dinero real (estimado):** Porción del segmento competitivo que participaría en retos pagados si cuenta con verificación automática y pasarelas locales (`10.83M × 20% = 2.166M jugadores`, equivalente al 3% del MAU global).
3. **1.5% cuota Colombia (estimado proxy):** Ponderación conservadora basada en los ~11M de gamers móviles en Colombia [F6]; en grupos regionales de WhatsApp de 4 países LATAM [F8], Colombia aporta >25% de los miembros activos.

---

## 4. SOM: Mercado Capturable en 1 a 3 Años (Coherente con One Pager y Plan Financiero)

La tabla siguiente conecta tanto la cifra base del **One Pager** (`500 pagadores × $9.28 USD = $4,640 USD` al ritmo del piloto sin mejoras) como la **rampa operativa del Plan Financiero** hacia el hito del One Pager (**~300 partidas/día en 6–12 meses** en Colombia + 2 países LATAM) y su proyección a **2 y 3 años**:

| Horizonte / Escenario | Registros × Conversión = Pagadores | Multiplicación Explícita (Clientes × Gasto / Actividad) | Ingresos Brutos Plataforma | Ingreso Neto Retenido en Caja (25% Comisiones + 100% VIP)* |
| :--- | :--- | :--- | ---: | ---: |
| **Año 1 · Base Piloto** *(Cifra One Pager)* | `5,000 reg. × 10%` = **500 pagadores** *(1.54% SAM CO)* | `500 pagadores × $9.28 USD ($40,832 COP)/año` *(manteniendo la frecuencia base del piloto sin retención ni VIP)* | **$4,640 USD** <br> **$20,416,000 COP** | **$1,160 USD** <br> **$5,104,000 COP** |
| **Año 1 · Meta Plan Financiero** *(Rampa a 300 part./día)* | `5,000 reg. × 15%` = **750 pagadores** *(2.30% SAM CO)* | Promedio **140 part./día** en Año 1 (cierra Mes 12 en hito de **300 part./día**): <br> `51,100 part./año × $1,300 COP comisión prom.` (pozo $6,000 COP) = `$66,430,000 COP` <br> `+ 75 suscriptores VIP × $13,200 COP × 12 meses = $11,880,000 COP` | **$17,798 USD** <br> **$78,310,000 COP** | **$6,479 USD** <br> **$28,507,500 COP** |
| **Año 2 · Consolidación** *(CO + 2 países LATAM)* | `20,000 reg. × 12.5%` = **2,500 pagadores** *(1.77% SAM LATAM)* | **320 part./día** sostenidas (`116,800 part./año × $1,300 COP`) = `$151,840,000 COP` <br> `+ 250 VIPs × $13,200 COP × 12 meses = $39,600,000 COP` <br> *(Al ARPPU base piloto: `2,500 × $9.28 USD = $23,200 USD / $102,080,000 COP`)* | **$43,509 USD** <br> **$191,440,000 COP** | **$17,627 USD** <br> **$77,560,000 COP** |
| **Año 3 · Escala LATAM** *(CO + MX, PE, CL, AR)* | `45,000 reg. × 13.3%` = **6,000 pagadores** *(0.28% SAM Global)* | **700 part./día** (`255,500 part./año × $1,300 COP`) = `$332,150,000 COP` <br> `+ 600 VIPs × $13,200 COP × 12 meses = $95,040,000 COP` <br> *(Al ARPPU base piloto: `6,000 × $9.28 USD = $55,680 USD / $244,992,000 COP`)* | **$97,089 USD** <br> **$427,190,000 COP** | **$40,472 USD** <br> **$178,077,500 COP** |

*\*Nota contable verificable en el código (`ComisionService.java`):* De cada peso cobrado en comisión por partida, el **75% se devuelve automáticamente a los jugadores** (sorteos, rankings, devolución, misiones, referidos y logros) y el **25% restante + el 100% de las membresías VIP** queda como ingreso neto disponible en caja para UltimateClash.

---

## 5. Validación con Usuarios Reales

### 1. Piloto Comercial en Vivo de 15 Días (Diciembre 2025) [F7]
- **Participantes:** **35 usuarios registrados reales**, captados orgánicamente de grupos de WhatsApp con **$0 COP de presupuesto en marketing** y operado por un solo fundador.
- **Tracción y Ventas Reales:** **20 usuarios activos diarios (57% DAU/MAU)**, **17 partidas pagadas por día** (~255 partidas en 15 días), **$300,000 COP circulados en pozos** y **$60,000 COP generados en comisiones reales**.
- **Qué indagamos y qué respondieron los 35 participantes durante el soporte y seguimiento post-partida:**
  1. *¿Pagan por usar una plataforma web automática?* **Sí:** el 57% jugó a diario depositando desde $1,000 COP porque eliminó la espera del administrador de WhatsApp.
  2. *¿Qué fricción encontraron al entrar?* Varios usuarios no sabían cómo encontrar el *API Token* dentro de los ajustes de Clash Royale para verificar su cuenta (`#TAG`) ni cómo usar la sala de negociación.
  3. *¿Por qué disminuyó la retención en la segunda semana?* Cuando entraban y no había un rival conectado en ese instante, abandonaban la sesión; además, pedían incentivos continuos (sorteos/rankings) y ver comprobantes reales de retiros pagados para confiar plenamente.

### 2. Qué construimos en el código a partir de esta validación (Hoy activo en `torneos-beta.onrender.com`)
- **Chatbot IA 24/7 (`ChatbotService` con Gemini/OpenAI) + Soporte (`SoporteService`):** Entrenado por el administrador; si no puede resolver una duda (`[SIN_RESPUESTA]`), envía alerta inmediata por correo al admin y notifica al jugador por WebSocket y *Push* apenas se responde.
- **Notificaciones Push Web/Móvil (`NotificacionesService` + Firebase FCM):** Cuando un jugador busca partida, el sistema envía una alerta Push a los celulares de los jugadores desconectados para emparejar en segundos, junto con un reloj individual de 2:30 min estilo *Pokémon Showdown* (`TemporizadorSalaService`).
- **Economía de Retención (75% devuelto) + Evidencias de Pago (`EvidenciaPagoService`):** Sorteos automáticos por tickets de partida (`SorteoService`), cortes automáticos de ranking diario/semanal/mensual/anual (`LeaderboardService`) y el canal público **«🧾 Pagos»** donde se publican los comprobantes verificados de cada retiro pagado.

---

## 6. Competencia y Alternativas Actuales

| Criterio | Grupos de WhatsApp en CO/LATAM *(Alternativa informal dominante · >3,500 miembros)* | Mundogames / Torneum *(Plataformas internacionales multijuego)* | UltimateClash (Nosotros) *(Especializada en CR · En línea hoy)* |
| :--- | :--- | :--- | :--- |
| **Verificación del ganador** | **Manual (capturas de pantalla):** tarda 15–40 min, propensa a fraude con imágenes editadas y discusiones. | **Semimanual o variable:** depende de reportes de usuarios y arbitraje externo según el juego. | **100% Automática vía API oficial de CR (`ClashApiServicio`):** sondeo cada 5s, valida `#TAG` y coronas enteras al instante. |
| **Comisión y Retorno** | **20% fijo sobre el pozo, 0% de devolución.** Todo se lo queda el administrador del grupo. | Inscripciones o membresías en **USD / EUR**; sin devolución de comisiones al jugador. | **10% a 25% variable escalonada** (menor % a mayor pozo) y **hasta 75% devuelto** a los jugadores. |
| **Horario y Liquidez** | **Horario fijo:** solo cuando el admin abre el chat; el resto del día permanece bloqueado. | Por torneos programados a horas fijas; baja liquidez para duelos rápidos en Colombia. | **24/7 en tiempo real:** matchmaking instantáneo + alertas Push FCM a jugadores desconectados. |
| **Pagos y Custodia** | Transferencia al Nequi personal del admin. **15 grupos cerrados** por abandono o riesgo de pérdida. | PayPal o tarjetas internacionales en dólares (excluye al joven bancarizado solo en Nequi). | **Nequi y Wompi en COP desde $1,000 COP:** libro contable inmutable (`wallet_ledger`) y canal público de evidencias. |
| **Comunidad y Soporte** | Chat silenciado entre torneos; sin rankings automatizados ni soporte 24/7. | Genéricas (abarcan muchos juegos sin comunidad local enfocada en *Clash Royale*). | **Chat 24/7, Voz en Discord privada por sala, Chatbot IA, Rankings (4 periodos) y Sorteos.** |

---

## 7. Precio y Modelo de Cobro

1. **Comisión escalonada por partida del 25% al 10% sobre el pozo (`ComisionService.java`):**
   - Los jugadores pactan partidas desde **$1,000 hasta $10,000 COP por jugador** (pozos de **$2,000 a $20,000 COP**).
   - En el pozo mínimo (**$2,000 COP**) se aplica **25%** ($500 COP comisión; $1,500 COP premio al ganador). El porcentaje **baja linealmente** a medida que crece el pozo hasta llegar al **10%** en pozos de **$20,000 COP** ($2,000 COP comisión; $18,000 COP premio).
   - **Comparación directa con lo que el cliente paga hoy:** En una partida típica de WhatsApp (**$5,000 COP por jugador = pozo $10,000 COP**), WhatsApp cobra **20% fijo ($2,000 COP)**; UltimateClash cobra **18.33% ($1,833 COP)** (o en un pozo de $6,000 COP cobra 21.67% = $1,300 COP).
2. **Devolución del 75% → Costo neto real de solo 2.5% a 6.25% del pozo:**
   - Cada comisión cobrada se divide automáticamente por código en 7 categorías: **20% Sorteos + 15% Leaderboard (día/semana/mes/año) + 15% Devolución + 10% Misiones + 10% Referidos + 5% Logros = 75% devuelto a la comunidad**, y **25% ganancia neta de UltimateClash**.
   - **Por qué ese precio:** En WhatsApp el jugador pierde el 20% para siempre; en UltimateClash incluso quien pierde recibe tickets de sorteo y puntos de ranking, haciendo que el costo neto real para la comunidad sea **hasta 4 veces menor**.
3. **Suscripción VIP ($13,200 COP / ~$3 USD al mes) y Pasarela (`FinanzasService.java`):**
   - Otorga multiplicadores de tickets/puntos y menores costos de recarga. En recargas con tarjeta vía Wompi el usuario cubre la tarifa de pasarela (`+$840 COP ÷ 0.964`); en Nequi manual recarga desde **$1,000 COP** sin recargo de la plataforma.

---

## 8. Canal de Distribución y Adquisición (Coherencia con el CAC)

### Cómo llega el producto al cliente
- **Web App Progresiva (PWA)** en [https://torneos-beta.onrender.com](https://torneos-beta.onrender.com) accesible al instante desde cualquier navegador móvil o PC sin instalación + **App móvil nativa Android/iOS (Capacitor)** con notificaciones Push (Firebase Cloud Messaging).

### Cómo se enteran de que existe y cuadre exacto con el CAC del One Pager y Plan Financiero
1. **Pauta en Meta Ads + Micro-creadores de contenido de CR en Colombia/LATAM ($9,000 USD / $39,600,000 COP):**
   - Piezas en Reels/TikTok mostrando duelos verificados automáticamente por la API de Clash Royale y retiros pagados en Nequi.
   - **Costo por Registro (CPR objetivo):** **$1.80 USD ($7,920 COP)**.
   - **Registros captados:** `\$9,000 USD ÷ \$1.80 USD = 5,000 usuarios registrados` en el Año 1.
   - **CAC en Canal Pago (escenario base One Pager al 10% de conversión = 500 pagadores):** `\$1.80 USD ÷ 10% = \$18.00 USD ($79,200 COP)` por cliente pagador.
2. **Torneos Gratuitos Iniciales + Alianzas con Administradores de WhatsApp ($500 USD / $2,200,000 COP):**
   - Torneos abiertos con premios semilla para migrar bloques enteros de los >3,500 miembros de los 3 grupos activos y los 15 grupos cerrados.
3. **Programa de Referidos autofinanciado por código (`10%` de cada comisión de partida):**
   - Cada jugador gana recompensas al invitar amigos sin consumir presupuesto de marketing. Al elevar la conversión a pagador del 10% al **15% (750 pagadores activos en la meta operativa del Año 1)** gracias al Chatbot IA, notificaciones Push y referidos, el **CAC Mezclado (Blended CAC) baja a $12.00 USD ($52,800 COP)**, permitiendo que el ingreso anual por pagador activo (~$23.73 USD brutos / ~$8.64 USD netos en Año 1, escalando en Año 2) recupere la inversión de adquisición.

---

## Fuentes y Referencias Verificables

- **[F1] Udonis / AppMagic (2 de julio de 2026):** *Clash Royale Player Count, Demographics & Revenue* — 72.2M MAU globales, ~25M DAU, 74.51% hombres, 42.93% entre 18 y 24 años. URL: https://blog.udonis.co/mobile-marketing/mobile-games/clash-royale-player-count
- **[F2] PocketGamer.biz (9 de marzo de 2026):** *Clash Royale celebrates 10 years and $4.9bn in player spending after resurgence* — $646.8M USD en gasto bruto de jugadores en 2025 (+149% YoY frente a $259.7M en 2024). URL: https://www.pocketgamer.biz/clash-royale-celebrates-10-years-and-49bn-in-player-spending-after-resurgence/
- **[F3] Forbes Colombia (29 de agosto de 2025):** *El crecimiento del mercado gamer en Colombia 2025* — El 62% de los gamers en Colombia juega en dispositivos móviles. URL: https://forbes.co/negocios/el-crecimiento-del-mercado-gamer-en-colombia-2025
- **[F4] Valora Analitik (10 de agosto de 2026):** *Recorrido de Nequi en Colombia* — Más de 27 millones de usuarios activos en Colombia. URL: https://www.valoraanalitik.com/recorrido-de-nequi-en-colombia/
- **[F5] Supercell Official News / Ilkka Paananen (10 de febrero de 2026):** *The best games haven't been made yet* — Crecimiento de casi +500% en nuevos jugadores de *Clash Royale*. URL: https://supercell.com/en/news/the-best-games-havent-been-made-yet/
- **[F6] El Colombiano / Adsmovil (2024–2025):** *Juegos de celular mueven $2,600 millones de dólares al año en América Latina y cifras de Colombia* — ~11 millones de gamers móviles en Colombia. URL: https://www.elcolombiano.com/negocios/JUEGOS-DE-CELULAR-MUEVEN-2600-MILLONES-DE-DOLARES-AL-ANO-EN-AMERICA-LATINA-Y-CIFRAS-DE-COLOMBIA-CG23526455
- **[F7] Piloto Propio UltimateClash (1 al 15 de diciembre de 2025):** Prueba comercial en vivo durante 15 días con **n = 35 usuarios registrados reales**, 20 usuarios activos diarios, 17 partidas pagadas/día (~255 partidas), $300,000 COP circulados en pozos y $60,000 COP en comisiones brutas cobradas, más levantamiento directo de feedback post-partida con los 35 participantes.
- **[F8] Observación Primaria de Competencia en Comunidades (2025–2026):** Relevamiento directo en **18 grupos de WhatsApp** de torneos de *Clash Royale* en Colombia y LATAM (3 grupos activos que reúnen >3,500 membresías con cobro fijo del 20% de comisión y 15 grupos cerrados por abandono del administrador) y análisis funcional de plataformas internacionales (https://mundogames.com/ y https://torneum.com/).

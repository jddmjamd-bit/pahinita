package com.torneosflash.servicio;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitización y validación de mensajes de chat (S5 - XSS).
 *
 * Defensa en profundidad: el frontend ya NO usa innerHTML con datos del usuario,
 * pero el servidor tampoco confía en lo que manda el cliente (un cliente
 * modificado, una app vieja o un script pueden saltarse el frontend).
 *
 * Reglas:
 *  - Texto: se eliminan caracteres de control, se quitan etiquetas HTML y se limita la longitud.
 *    NO se escapa a entidades (&amp;lt;) para no mostrar entidades dobles en el frontend,
 *    que ya renderiza con textContent / React.
 *  - Imagen: solo data URI base64 de png/jpeg/gif/webp (nada de SVG) o un archivo propio /api/media/{id}.
 *  - Video: solo archivos propios /api/media/{id} (los que devuelve POST /api/upload).
 *  - Canal: solo general / anuncios / clash. Los clientes nunca escriben en clash_logs ni en salas privadas.
 */
public final class ChatSanitizer {

    public static final int MAX_TEXTO = 500;
    public static final int MAX_USUARIO = 50;
    /** ~5 MB binarios codificados en base64. */
    public static final int MAX_IMAGEN_BASE64 = 7_000_000;
    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 30;

    private static final Set<String> CANALES_PUBLICOS = Set.of("general", "anuncios", "clash");
    private static final Set<String> TIPOS = Set.of("texto", "imagen", "video");
    private static final Set<String> FORMATOS_IMAGEN = Set.of("png", "jpeg", "jpg", "gif", "webp");

    /** Etiquetas HTML (también las que quedan sin cerrar: "<img src=x onerror=..."). */
    private static final Pattern ETIQUETA_HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!?][^>]*>?");
    /** Caracteres de control (menos \n y \t) y marcas bidireccionales que sirven para suplantar texto. */
    private static final Pattern CARACTERES_CONTROL =
            Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069&&[^\\n\\t]]");
    private static final Pattern MEDIA_INTERNA = Pattern.compile("^/api/media/\\d{1,10}$");
    private static final Pattern SALA = Pattern.compile("^sala_\\d{1,20}$");
    /** Caracteres que nunca deben ir en un nombre de usuario: < > " ' & ` */
    private static final Pattern USERNAME_PROHIBIDO = Pattern.compile("[<>\"'&`]");

    private ChatSanitizer() {}

    public record MensajePublico(String canal, String usuario, String texto, String tipo) {}

    public record MensajePrivado(String salaId, String usuario, String texto) {}

    /** Limpia un texto libre: sin controles, sin etiquetas HTML, recortado y con tope de longitud. */
    public static String limpiarTexto(String raw, int max) {
        if (raw == null) return "";
        // Acota el trabajo del regex si alguien manda un texto gigante
        if (raw.length() > max * 4) raw = raw.substring(0, max * 4);

        String s = CARACTERES_CONTROL.matcher(raw).replaceAll("");
        // Se repite hasta que no cambie: evita que "<<b>script>" reconstruya una etiqueta al quitar la interna
        String anterior;
        do {
            anterior = s;
            s = ETIQUETA_HTML.matcher(s).replaceAll("");
        } while (!s.equals(anterior));

        s = s.trim();
        if (s.length() > max) {
            int fin = max;
            if (Character.isHighSurrogate(s.charAt(fin - 1))) fin--; // no partir un emoji a la mitad
            s = s.substring(0, fin).trim();
        }
        return s;
    }

    /**
     * Valida un mensaje de los canales públicos.
     * @return el mensaje limpio, o null si es inválido y debe descartarse.
     */
    public static MensajePublico validarMensajePublico(String canal, String usuario, String texto, String tipo) {
        if (canal == null || !CANALES_PUBLICOS.contains(canal)) return null;
        String t = (tipo == null || tipo.isBlank()) ? "texto" : tipo;
        if (!TIPOS.contains(t)) return null;
        String u = limpiarTexto(usuario, MAX_USUARIO);
        if (u.isEmpty() || texto == null) return null;

        if (t.equals("texto")) {
            String limpio = limpiarTexto(texto, MAX_TEXTO);
            return limpio.isEmpty() ? null : new MensajePublico(canal, u, limpio, t);
        }

        // Multimedia: solo se permite en anuncios (el frontend solo lo ofrece ahí)
        if (!canal.equals("anuncios")) return null;
        if (t.equals("imagen")) {
            return esImagenValida(texto) ? new MensajePublico(canal, u, texto, t) : null;
        }
        return MEDIA_INTERNA.matcher(texto).matches() ? new MensajePublico(canal, u, texto, t) : null;
    }

    /** Valida un mensaje del chat privado de una sala (siempre texto). */
    public static MensajePrivado validarMensajePrivado(String salaId, String usuario, String texto) {
        if (salaId == null || !SALA.matcher(salaId).matches()) return null;
        String u = limpiarTexto(usuario, MAX_USUARIO);
        String limpio = limpiarTexto(texto, MAX_TEXTO);
        if (u.isEmpty() || limpio.isEmpty()) return null;
        return new MensajePrivado(salaId, u, limpio);
    }

    /** Imagen válida: archivo propio, o data URI base64 de un formato raster conocido. */
    static boolean esImagenValida(String s) {
        if (s == null) return false;
        if (MEDIA_INTERNA.matcher(s).matches()) return true;
        if (s.length() > MAX_IMAGEN_BASE64) return false;

        final String prefijo = "data:image/";
        final String sufijo = ";base64";
        int coma = s.indexOf(',');
        if (!s.startsWith(prefijo) || coma < 0 || coma > 40) return false;
        String cabecera = s.substring(prefijo.length(), coma); // p.ej. "png;base64"
        if (!cabecera.endsWith(sufijo)) return false;
        String formato = cabecera.substring(0, cabecera.length() - sufijo.length());
        if (!FORMATOS_IMAGEN.contains(formato)) return false;
        if (coma + 1 >= s.length()) return false;

        for (int i = coma + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean base64 = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
            if (!base64) return false;
        }
        return true;
    }

    /**
     * Nombre de usuario seguro para registrar: 3-30 caracteres, sin < > " ' & ` ni caracteres de control.
     * (Los usernames se muestran en chat, rankings y paneles de admin.)
     */
    public static boolean esNombreUsuarioSeguro(String username) {
        if (username == null) return false;
        int len = username.length();
        if (len < USERNAME_MIN || len > USERNAME_MAX) return false;
        if (USERNAME_PROHIBIDO.matcher(username).find()) return false;
        return !CARACTERES_CONTROL.matcher(username).find() && !username.contains("\n") && !username.contains("\t");
    }
}

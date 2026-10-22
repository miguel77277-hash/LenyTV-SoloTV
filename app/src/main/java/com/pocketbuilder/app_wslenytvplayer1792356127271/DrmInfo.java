package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.media.MediaDrm;

import java.util.Locale;
import java.util.UUID;

/**
 * Consulta al sistema DRM OFICIAL de Android y lo cuenta con claridad en el
 * diagnóstico.
 *
 * Qué hace y qué NO hace:
 *
 *  · SÍ pregunta al teléfono, con la API oficial (android.media.MediaDrm), si
 *    tiene Widevine instalado, qué formatos acepta, quién lo fabrica, qué
 *    versión tiene y qué nivel de seguridad declara.
 *  · NO pide ninguna licencia, NO descarga ni guarda claves, NO descifra
 *    contenido protegido y NO elude ninguna protección.
 *
 * Sirve para una sola cosa: poder explicar al usuario, con datos reales de su
 * aparato, por qué un canal con DRM no se puede ver con este motor, en lugar de
 * decir "error" sin motivo. El motor de LENY TV (motor DASH propio y el
 * reproductor de Android para HLS) no descifra contenido protegido.
 *
 * El resultado se calcula una vez y se guarda: la pantalla DIAGNÓSTICO puede
 * pedirlo varias veces sin volver a abrir el sistema DRM.
 */
final class DrmInfo {

    /** Identificador oficial de Widevine (el mismo que usa Android). */
    static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    private static String cachedReport;
    private static String cachedSummary;

    private DrmInfo() {
    }

    /**
     * Texto largo para la pantalla DIAGNÓSTICO: qué DRM tiene el teléfono y qué
     * significa para LENY TV. Nunca contiene claves ni datos del canal.
     */
    static synchronized String report() {
        if (cachedReport != null) return cachedReport;
        StringBuilder sb = new StringBuilder();
        sb.append("DRM DEL TELÉFONO (consulta oficial al sistema, sin pedir licencias)\n");
        boolean available = false;
        try {
            available = MediaDrm.isCryptoSchemeSupported(WIDEVINE);
        } catch (Throwable t) {
            sb.append("· no se pudo preguntar al sistema: ").append(Net.describe(t)).append('\n');
        }
        sb.append("· Widevine: ")
                .append(available ? "este teléfono lo declara instalado"
                        : "este teléfono no lo ofrece")
                .append('\n');
        if (available) {
            sb.append("· formatos que el sistema acepta: vídeo MP4 (CENC) ").append(supports("video/mp4"))
                    .append(" · vídeo WebM ").append(supports("video/webm"))
                    .append(" · audio MP4 ").append(supports("audio/mp4"))
                    .append('\n');
            MediaDrm drm = null;
            try {
                drm = new MediaDrm(WIDEVINE);
                sb.append("· fabricante del sistema DRM: ")
                        .append(value(drm, MediaDrm.PROPERTY_VENDOR, "(el teléfono no lo informa)"))
                        .append('\n');
                sb.append("· versión del sistema DRM: ")
                        .append(value(drm, MediaDrm.PROPERTY_VERSION, "(el teléfono no lo informa)"))
                        .append('\n');
                sb.append("· nivel de seguridad: ").append(securityLevel(drm)).append('\n');
            } catch (Throwable t) {
                sb.append("· el sistema DRM no se pudo abrir para leer sus datos: ")
                        .append(Net.describe(t)).append('\n');
            } finally {
                if (drm != null) {
                    try {
                        drm.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        sb.append("· QUÉ SIGNIFICA: LENY TV solo CONSULTA al sistema. Su motor de reproducción NO")
                .append(" descifra contenido protegido, no pide licencias y no guarda claves.")
                .append(" Un canal con DRM no se puede ver y la app lo explica con este aviso.\n");
        cachedReport = sb.toString();
        return cachedReport;
    }

    /**
     * Una sola frase, para los avisos de error de un canal protegido. Es el texto
     * que ve el usuario: nunca promete lo que el motor no hace.
     */
    static synchronized String summary() {
        if (cachedSummary != null) return cachedSummary;
        boolean available = false;
        try {
            available = MediaDrm.isCryptoSchemeSupported(WIDEVINE);
        } catch (Throwable ignored) {
            // Si el teléfono no responde, se usa el texto prudente de abajo.
        }
        if (available) {
            cachedSummary = "Este teléfono sí tiene Widevine instalado, pero el motor de LENY TV no"
                    + " descifra contenido protegido: sin una licencia autorizada de tu proveedor, este"
                    + " canal no se puede ver aquí.";
        } else {
            cachedSummary = "Este teléfono no ofrece Widevine y, además, el motor de LENY TV no descifra"
                    + " contenido protegido: este canal no se puede ver aquí.";
        }
        return cachedSummary;
    }

    /** UUID a partir del texto que escribe un manifiesto (con o sin llaves). */
    static UUID uuidFromText(String text) {
        if (text == null) return null;
        String clean = text.trim().replace("{", "").replace("}", "");
        if (clean.length() == 0) return null;
        try {
            return UUID.fromString(clean);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Qué dice el TELÉFONO sobre un sistema DRM concreto.
     *
     * Separa dos causas que antes se mezclaban en un solo aviso: que el aparato no
     * tenga ese DRM instalado (límite del DISPOSITIVO) o que lo tenga pero LENY TV
     * no descifre el contenido (límite del MOTOR). Nunca pide ninguna licencia.
     */
    static String deviceSupportFor(String uuidText, String systemName) {
        String name = systemName == null || systemName.length() == 0 ? "ese sistema DRM" : systemName;
        UUID uuid = uuidFromText(uuidText);
        boolean supported = false;
        if (uuid == null) {
            try {
                supported = MediaDrm.isCryptoSchemeSupported(WIDEVINE);
            } catch (Throwable ignored) {
                supported = false;
            }
            if (supported) {
                return "el manifiesto no escribe el identificador del sistema; este teléfono declara"
                        + " Widevine instalado";
            }
            return "el manifiesto no escribe el identificador del sistema y este teléfono no declara"
                    + " Widevine";
        }
        try {
            supported = MediaDrm.isCryptoSchemeSupported(uuid);
        } catch (Throwable ignored) {
            supported = false;
        }
        if (!supported) {
            return "este teléfono NO tiene instalado " + name + ", así que ese contenido protegido no se"
                    + " podría descifrar en este aparato ni con un reproductor compatible";
        }
        return "este teléfono sí tiene " + name + " instalado; lo que falta es un motor que descifre el"
                + " contenido protegido (CENC)";
    }

    /**
     * Busca en los datos descargados las entradas de muestra CIFRADAS (encv/enca),
     * que toda pista protegida con CENC está obligada a usar. Si no aparecen, el
     * manifiesto declara DRM pero los bytes llegan sin cifrar y el canal se puede
     * intentar reproducir. Aquí no se descifra nada: solo se leen marcadores.
     */
    static boolean hasEncryptedSampleEntry(byte[] data) {
        if (data == null || data.length < 8) return false;
        return indexOf(data, "encv") >= 0 || indexOf(data, "enca") >= 0;
    }

    /** Posición de un texto ASCII dentro de los datos (-1 si no está). */
    private static int indexOf(byte[] data, String text) {
        byte[] needle = text.getBytes();
        int last = data.length - needle.length;
        for (int i = 0; i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /** ¿El sistema acepta ese tipo de contenido con Widevine? "sí", "no" o sin poder saberlo. */
    private static String supports(String mimeType) {
        try {
            return MediaDrm.isCryptoSchemeSupported(WIDEVINE, mimeType) ? "sí" : "no";
        } catch (Throwable t) {
            return "(no se pudo comprobar)";
        }
    }

    /** Una propiedad del sistema DRM, sin dejar que un fallo rompa el informe. */
    private static String value(MediaDrm drm, String key, String fallback) {
        try {
            String text = drm.getPropertyString(key);
            return text == null || text.length() == 0 ? fallback : text;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Nivel de seguridad que declara el sistema DRM del aparato (L1 o L3 en
     * Widevine). Se consulta con una sesión temporal que se cierra enseguida: no
     * se pide ninguna licencia ni se abre ningún contenido.
     */
    private static String securityLevel(MediaDrm drm) {
        byte[] session = null;
        try {
            // Algunos teléfonos solo informan del nivel de seguridad con una
            // sesión abierta; se abre y se cierra enseguida, sin pedir licencia.
            session = drm.openSession();
            String text = drm.getPropertyString("securityLevel");
            if (text == null || text.length() == 0) return "(el teléfono no lo informa)";
            String upper = text.toUpperCase(Locale.US);
            if (upper.indexOf("L1") >= 0) return text + " (alto: descifrado en hardware)";
            if (upper.indexOf("L3") >= 0) return text + " (básico: descifrado en software)";
            return text;
        } catch (Throwable t) {
            return "(el teléfono no lo informa)";
        } finally {
            if (session != null) {
                try {
                    drm.closeSession(session);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

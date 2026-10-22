package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;

import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesDiag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Registro de diagnóstico de LENY TV.
 *
 * Guarda paso a paso lo que hace la app (comprobación de internet, cada intento
 * de descarga con su código de respuesta y su tamaño, resultado del análisis de
 * la lista, número de canales que llegan a la pantalla y el error real del
 * reproductor). Sirve para saber EXACTAMENTE en qué punto se interrumpe la
 * carga sin necesidad de conectar el teléfono a un ordenador:
 *
 *  - Se puede ver dentro de la app (botón DIAGNÓSTICO).
 *  - Se puede copiar al portapapeles y pegar en un mensaje.
 *  - Se guarda también en un archivo, así no se pierde si la app se cierra.
 */
final class Diag {

    /** Versión visible del registro: identifica la compilación que lo generó. */
    static final String REVISION = "revisión 55";

    private static final int MAX_LINES = 700;
    private static final int MAX_SAVED_LINES = 700;
    private static final String FILE_NAME = "diagnostico-leny-tv.txt";
    private static final Object LOCK = new Object();
    private static final ArrayList<String> LINES = new ArrayList<String>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static boolean loadedFromFile = false;
    /** Contexto de la app para leer la ficha de instalación (versión y firma). */
    private static Context appContext = null;

    private Diag() {
    }

    /**
     * Verdadero si este código corre en el PROCESO DEL MÓDULO DE PELÍCULAS
     * (":movies").
     *
     * Las pantallas de películas se declaran en el manifiesto con proceso propio,
     * así que un fallo suyo no cierra la televisión. Ese proceso tiene sus propias
     * variables estáticas y escribe en los mismos archivos, de modo que aquí se
     * desvía todo al registro del módulo: ni se mezclan los mensajes ni se
     * sobrescribe el archivo de diagnóstico de la televisión.
     */
    static boolean inMoviesProcess() {
        try {
            String name = android.app.Application.getProcessName();
            return name != null && name.endsWith(":movies");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Añade una línea al registro. Nunca lanza excepciones. */
    static void add(String message) {
        if (inMoviesProcess()) {
            MoviesDiag.add(message);
            return;
        }
        try {
            String clean = message == null ? "" : message.trim();
            if (clean.length() == 0) return;
            if (clean.length() > 600) clean = clean.substring(0, 600) + "…";
            String line = STAMP.format(new Date()) + "  " + clean;
            synchronized (LOCK) {
                LINES.add(line);
                while (LINES.size() > MAX_LINES) LINES.remove(0);
            }
            android.util.Log.i("LenyTV", line);
        } catch (Throwable ignored) {
            // Un registro nunca debe romper la app.
        }
    }

    static void clear() {
        if (inMoviesProcess()) {
            MoviesDiag.clear();
            return;
        }
        synchronized (LOCK) {
            LINES.clear();
        }
    }

    /** Texto completo del registro, listo para mostrar o copiar. */
    static String text() {
        if (inMoviesProcess()) return MoviesDiag.text();
        StringBuilder sb = new StringBuilder();
        sb.append("LENY TV · ").append(REVISION).append('\n');
        sb.append("----------------------------------------\n");
        // Primero el informe del motor: dice qué reproduce cada formato, por dónde
        // pasa el canal y el error técnico completo (códigos crudos, etapa del motor
        // DASH y respuesta real del servidor). Así el motivo se lee de un vistazo,
        // antes del registro paso a paso.
        sb.append(EngineInfo.report());
        sb.append("----------------------------------------\n");
        // Ficha de instalación: identificador, versión, Android mínimo, firma y
        // archivo del que se instaló. Sirve para entender un «App no instalada».
        sb.append(InstallInfo.report(appContext));
        sb.append("----------------------------------------\n");
        sb.append("REGISTRO PASO A PASO\n");
        synchronized (LOCK) {
            if (LINES.isEmpty()) {
                sb.append("(el registro está vacío todavía)");
            } else {
                for (int i = 0; i < LINES.size(); i++) sb.append(LINES.get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Dirección resumida, sin datos que puedan ser privados (usuario, clave o
     * cualquier valor largo de la dirección). Se guarda el servidor y el
     * principio del camino para poder reconocerla.
     */
    static String shortUrl(String url) {
        if (url == null || url.length() == 0) return "(sin enlace)";
        String clean = url.trim();
        try {
            java.net.URL parsed = new java.net.URL(clean);
            String host = parsed.getHost();
            if (host == null || host.length() == 0) return "(enlace local)";
            return parsed.getProtocol() + "://" + host + "/…";
        } catch (Throwable t) {
            if (clean.length() <= 24) return clean;
            return clean.substring(0, 24) + "…";
        }
    }

    /**
     * Dirección de un canal escrita para el registro SIN exponer datos privados.
     * Se conserva el servidor, el puerto, cuántos tramos tiene la ruta y la
     * extensión del enlace, y se añade una marca corta y estable calculada a
     * partir de la dirección completa: así se puede comprobar que la cuadrícula
     * y el reproductor hablan del MISMO enlace (misma marca) sin guardar el
     * usuario, la clave ni el resto de la dirección.
     */
    static String channelUrl(String url) {
        if (url == null || url.trim().length() == 0) return "(sin enlace)";
        String clean = url.trim();
        String mark = mark(clean);
        String extension = extensionOf(clean);
        try {
            java.net.URL parsed = new java.net.URL(clean);
            String host = parsed.getHost();
            if (host == null || host.length() == 0) return "(enlace local) · marca " + mark;
            StringBuilder sb = new StringBuilder();
            sb.append(parsed.getProtocol()).append("://").append(host);
            if (parsed.getPort() > 0) sb.append(':').append(parsed.getPort());
            int segments = 0;
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') segments++;
            }
            sb.append(" · ").append(segments).append(" tramos de ruta");
            sb.append(" · ").append(extension.length() == 0 ? "sin extensión" : extension);
            sb.append(" · marca ").append(mark);
            return sb.toString();
        } catch (Throwable t) {
            return extension + " · marca " + mark;
        }
    }

    /** Marca corta y estable de una dirección, calculada sin guardar la dirección. */
    static String mark(String url) {
        if (url == null || url.length() == 0) return "000000";
        int hash = 0x811c9dc5;
        for (int i = 0; i < url.length(); i++) {
            hash ^= url.charAt(i);
            hash *= 0x01000193;
        }
        String hex = Integer.toHexString(hash & 0xFFFFFF);
        while (hex.length() < 6) hex = "0" + hex;
        return hex;
    }

    /** Extensión del enlace (.m3u8, .ts, .mp4...) para saber qué tipo se pidió. */
    static String extensionOf(String url) {
        if (url == null) return "";
        String value = url.trim().toLowerCase(Locale.US);
        int cut = value.length();
        int query = value.indexOf('?');
        if (query >= 0 && query < cut) cut = query;
        int hash = value.indexOf('#');
        if (hash >= 0 && hash < cut) cut = hash;
        value = value.substring(0, cut);
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == value.length() - 1) return "";
        String extension = value.substring(dot);
        return extension.length() > 8 ? "" : extension;
    }

    static boolean isEmpty() {
        synchronized (LOCK) {
            return LINES.isEmpty();
        }
    }

    /** Carga la última sesión guardada para no perder el motivo de un cierre. */
    static void init(Context context) {
        if (inMoviesProcess()) {
            MoviesDiag.init(context);
            return;
        }
        if (context != null) {
            try {
                if (appContext == null) appContext = context.getApplicationContext();
            } catch (Throwable ignored) {
            }
        }
        if (context == null || loadedFromFile) return;
        loadedFromFile = true;
        try {
            File file = file(context);
            if (file == null || !file.exists()) return;
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            ArrayList<String> all = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) all.add(line);
            reader.close();
            int from = Math.max(0, all.size() - 120);
            synchronized (LOCK) {
                for (int i = from; i < all.size(); i++) LINES.add(all.get(i));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Guarda el registro en el archivo de la app. */
    static void save(Context context) {
        if (inMoviesProcess()) {
            MoviesDiag.save(context);
            return;
        }
        if (context == null) return;
        try {
            File file = file(context);
            if (file == null) return;
            String[] lines = text().split("\n");
            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, lines.length - MAX_SAVED_LINES);
            for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
            FileOutputStream out = new FileOutputStream(file, false);
            OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");
            writer.write(sb.toString());
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private static File file(Context context) {
        try {
            return new File(context.getFilesDir(), FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}

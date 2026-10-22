package com.pocketbuilder.app_wslenytvplayer1792356127271;

import java.util.ArrayList;

/**
 * Proveedores de películas y series que LENY TV trae ya creados.
 *
 * Esta lista se ha hecho a partir de los CUATRO complementos de CloudStream que
 * el usuario adjuntó con la versión 1.58:
 *
 *   AllCalidadProvider.cs3, PelisplusHDProvider.cs3, Latanime.cs3, Flixlatam.cs3
 *
 * De cada archivo se reconoce el proveedor por su nombre, y cada proveedor tiene
 * aquí su ficha ya creada dentro de la app (nombre, color, explicación y su hueco
 * de dirección). Así, al instalar el APK, los cuatro proveedores aparecen ya en
 * ADMINISTRAR REPOSITORIOS y en el resumen de PELÍCULAS sin tener que crearlos a
 * mano.
 *
 * Lo que NO se puede hacer es ejecutar el .cs3: es código compilado para el motor
 * de CloudStream (ver Cs3Audit). Por eso cada proveedor guarda su propia
 * dirección de catálogo en JSON: se pega una vez y sus títulos entran en la
 * cuadrícula, con ficha, temporadas, episodios y reproducción en el reproductor
 * del módulo.
 *
 * REVISIÓN 59: la clase es pública para que el paquete del módulo de películas
 * (…movies) pueda leer la lista de proveedores y preparar sus adaptadores sin
 * duplicar nombres ni identificadores.
 *
 * defaultUrl vacío = la app no conoce (ni inventa) la dirección de ese
 * proveedor; queda esperándola. Si algún día se conoce una dirección oficial,
 * basta con escribirla aquí y el proveedor aparecerá ya configurado en la primera
 * instalación.
 */
public final class Providers {

    public static final String ID_PREFIX = "preset:";

    public static final class Preset {
        public final String id;
        public final String name;
        public final String blurb;
        public final String hint;
        /** Color del distintivo del proveedor en la lista. */
        public final int accent;
        /** Archivo .cs3 adjunto del que procede este proveedor (solo informativo). */
        public final String pluginFile;
        /** Tamaño en bytes del complemento adjunto, para el informe. */
        public final int pluginBytes;
        public final String defaultUrl;

        Preset(String id, String name, String blurb, String hint, int accent,
               String pluginFile, int pluginBytes, String defaultUrl) {
            this.id = id;
            this.name = name;
            this.blurb = blurb;
            this.hint = hint;
            this.accent = accent;
            this.pluginFile = pluginFile == null ? "" : pluginFile;
            this.pluginBytes = pluginBytes;
            this.defaultUrl = defaultUrl == null ? "" : defaultUrl;
        }

        /** Identificador con el que este proveedor se guarda en el teléfono. */
        public String repoId() {
            return ID_PREFIX + id;
        }

        public String initial() {
            return name.length() == 0 ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.US);
        }

        /** Línea corta para la ficha: de qué complemento viene. */
        public String pluginLine() {
            if (pluginFile.length() == 0) return "";
            return "Complemento adjunto: " + pluginFile + " · no ejecutable aquí";
        }
    }

    private static final ArrayList<Preset> PRESETS = new ArrayList<Preset>();

    static {
        PRESETS.add(new Preset("allcalidad", "AllCalidad",
                "Películas y series en español",
                "Dirección del catálogo JSON de AllCalidad",
                0xFFE0413A, "AllCalidadProvider.cs3", 35828, ""));
        PRESETS.add(new Preset("pelisplushd", "Pelisplus HD",
                "Películas y series en HD",
                "Dirección del catálogo JSON de Pelisplus HD",
                0xFF2D63E8, "PelisplusHDProvider.cs3", 38519, ""));
        PRESETS.add(new Preset("latanime", "Latanime",
                "Anime doblado y subtitulado",
                "Dirección del catálogo JSON de Latanime",
                0xFFA855F7, "Latanime.cs3", 21666, ""));
        PRESETS.add(new Preset("flixlatam", "Flixlatam",
                "Películas y series latinas",
                "Dirección del catálogo JSON de Flixlatam",
                0xFF35C46A, "Flixlatam.cs3", 78003, ""));
    }

    private Providers() {
    }

    /** Copia de la lista, para que nadie la modifique por accidente. */
    public static ArrayList<Preset> all() {
        return new ArrayList<Preset>(PRESETS);
    }

    /** Proveedor por su id corto ("allcalidad"). */
    public static Preset find(String id) {
        if (id == null) return null;
        for (int i = 0; i < PRESETS.size(); i++) {
            if (PRESETS.get(i).id.equals(id)) return PRESETS.get(i);
        }
        return null;
    }

    /** Proveedor a partir del identificador guardado ("preset:allcalidad"). */
    public static Preset fromRepoId(String repoId) {
        if (repoId == null || !repoId.startsWith(ID_PREFIX)) return null;
        return find(repoId.substring(ID_PREFIX.length()));
    }

    /** Verdadero si ese identificador es el de un proveedor integrado. */
    public static boolean isPresetRepo(String repoId) {
        return repoId != null && repoId.startsWith(ID_PREFIX);
    }

    /** Verdadero si ese repo es un proveedor que ya no está en la lista actual. */
    public static boolean isOrphan(String repoId) {
        return isPresetRepo(repoId) && fromRepoId(repoId) == null;
    }
}

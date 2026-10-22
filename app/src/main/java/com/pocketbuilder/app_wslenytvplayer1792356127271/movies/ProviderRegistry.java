package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import com.pocketbuilder.app_wslenytvplayer1792356127271.Providers;

import java.util.ArrayList;

/**
 * LISTA DE ADAPTADORES DE PROVEEDOR del módulo de películas.
 *
 * Aquí se declara, proveedor por proveedor, QUÉ MOTOR lo lee en esta versión y
 * qué le falta para funcionar. Es el punto único donde se añadirá cada adaptador
 * individual (AllCalidad, Latanime, Flixlatam…) sin tocar la cuadrícula, las
 * fichas ni el reproductor.
 *
 * Estado real de la versión 1.59:
 *   - Los cuatro proveedores integrados usan EL MISMO motor: el lector de
 *     catálogos JSON del módulo (claves "items" y "seasons"). Está terminado y es
 *     el que llena la cuadrícula.
 *   - Ese motor necesita que el usuario pegue la dirección de un catálogo JSON.
 *     Los complementos .cs3 NO se ejecutan: no hay motor de CloudStream en esta
 *     app, y este proyecto no puede enlazar sus librerías.
 *   - Por tanto ningún adaptador promete leer la web de un proveedor todavía. Se
 *     añadirán de uno en uno, empezando por el más sencillo.
 */
public final class ProviderRegistry {

    /** Adaptador del lector de catálogos JSON que ya funciona en la app. */
    private static final String ENGINE_JSON = "Catálogo JSON (formato LENY)";

    private ProviderRegistry() {
    }

    /** Adaptador del motor JSON, aplicable a cualquier dirección de catálogo. */
    private static ProviderAdapter jsonAdapter(final String repoId, final String name,
                                               final String requirement) {
        return new ProviderAdapter() {
            @Override
            public String repoId() {
                return repoId;
            }

            @Override
            public String providerName() {
                return name;
            }

            @Override
            public String engineLabel() {
                return ENGINE_JSON;
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public String requirement() {
                return requirement;
            }

            @Override
            public String note() {
                return "Lee un archivo JSON con títulos (y temporadas si es una serie) y sus "
                        + "enlaces HLS, MP4, WebM o TS. No ejecuta el complemento .cs3.";
            }
        };
    }

    /** Todos los adaptadores del módulo: los cuatro proveedores y las direcciones libres. */
    public static ArrayList<ProviderAdapter> adapters() {
        ArrayList<ProviderAdapter> list = new ArrayList<ProviderAdapter>();
        ArrayList<Providers.Preset> presets = Providers.all();
        for (int i = 0; i < presets.size(); i++) {
            final Providers.Preset preset = presets.get(i);
            String requirement = "Falta su dirección de catálogo JSON. El complemento "
                    + (preset.pluginFile.length() == 0 ? "de CloudStream" : preset.pluginFile)
                    + " no se ejecuta en esta app.";
            list.add(jsonAdapter(preset.repoId(), preset.name, requirement));
        }
        list.add(jsonAdapter("", "Direcciones añadidas a mano",
                "Se pega la dirección en ENLACES."));
        return list;
    }

    /** Adaptador de un repositorio concreto (por su identificador guardado). */
    public static ProviderAdapter forRepo(String repoId) {
        ArrayList<ProviderAdapter> all = adapters();
        for (int i = 0; i < all.size(); i++) {
            ProviderAdapter adapter = all.get(i);
            if (adapter.repoId().equals(repoId == null ? "" : repoId)) return adapter;
        }
        return all.get(all.size() - 1);
    }

    /** Informe de texto con la tabla de adaptadores, para la pantalla de ajustes. */
    public static String report() {
        StringBuilder out = new StringBuilder();
        ArrayList<ProviderAdapter> all = adapters();
        for (int i = 0; i < all.size(); i++) {
            ProviderAdapter adapter = all.get(i);
            out.append(adapter.providerName()).append('\n');
            out.append("  Motor: ").append(adapter.engineLabel()).append('\n');
            out.append("  Estado: ").append(adapter.ready() ? "listo" : "pendiente").append('\n');
            out.append("  Necesita: ").append(adapter.requirement()).append('\n');
            out.append("  Nota: ").append(adapter.note()).append("\n\n");
        }
        out.append("El motor de extensiones de CloudStream no está integrado en esta app, así "
                + "que los .cs3 no se cargan. Cada proveedor tendrá su propio adaptador escrito "
                + "en Java cuando se aborde uno a uno.");
        return out.toString();
    }
}

package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import android.content.Context;
import android.os.Handler;

/**
 * RED DE SEGURIDAD DEL MÓDULO DE PELÍCULAS Y SERIES.
 *
 * Tres capas, de dentro hacia fuera:
 *
 *  1. {@link #run(Runnable)} envuelve el trabajo que se lanza a mano (hilos y
 *     publicaciones en el hilo principal) para que una excepción quede anotada en
 *     el registro del módulo y no suba.
 *  2. {@link #install(Context)} instala un capturador de errores no controlados:
 *     si algo del módulo falla igualmente, el error completo (tipo, mensaje y
 *     pila) queda escrito en el registro del módulo ANTES de cerrarse el proceso.
 *  3. El manifiesto declara las pantallas del módulo en su propio proceso
 *     (":movies"). Por eso, aunque el proceso del módulo se cierre, el proceso de
 *     la televisión sigue vivo y la TV se puede seguir usando.
 *
 * No se promete una recuperación imposible: si el proceso del módulo se cae, se
 * cierra la pantalla de películas, queda el motivo escrito y la televisión no se
 * ve afectada.
 */
public final class MoviesGuard {

    private static boolean installed = false;

    private MoviesGuard() {
    }

    /** Instala el capturador de errores del módulo (una sola vez por proceso). */
    public static synchronized void install(final Context context) {
        if (installed) return;
        installed = true;
        try {
            MoviesDiag.init(context);
        } catch (Throwable ignored) {
        }
        try {
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        MoviesDiag.add("ERROR NO CONTROLADO · hilo "
                                + (thread == null ? "(desconocido)" : thread.getName())
                                + " · " + describe(error));
                        MoviesDiag.add(stack(error));
                        MoviesDiag.save(context);
                    } catch (Throwable ignored) {
                    }
                    if (previous != null) {
                        previous.uncaughtException(thread, error);
                    } else {
                        try {
                            android.os.Process.killProcess(android.os.Process.myPid());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Ejecuta un trabajo del módulo sin dejar que una excepción se escape. */
    public static void run(String what, Runnable work) {
        if (work == null) return;
        try {
            work.run();
        } catch (Throwable error) {
            MoviesDiag.add("Fallo en " + (what == null ? "el módulo" : what) + ": "
                    + describe(error));
        }
    }

    /** Publica en el hilo principal un trabajo protegido por el registro. */
    public static void post(Handler handler, final String what, final Runnable work) {
        if (handler == null || work == null) return;
        try {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    MoviesGuard.run(what, work);
                }
            });
        } catch (Throwable error) {
            MoviesDiag.add("No se pudo publicar " + what + ": " + describe(error));
        }
    }

    /** Texto corto de un error, sin datos privados. */
    public static String describe(Throwable error) {
        if (error == null) return "(sin detalle)";
        String message = error.getMessage();
        String text = error.getClass().getSimpleName()
                + (message == null || message.length() == 0 ? "" : ": " + message);
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    /** Pila del error en texto (máximo 25 líneas). */
    public static String stack(Throwable error) {
        if (error == null) return "(sin pila)";
        try {
            StringBuilder out = new StringBuilder();
            StackTraceElement[] frames = error.getStackTrace();
            int limit = Math.min(frames == null ? 0 : frames.length, 25);
            for (int i = 0; i < limit; i++) {
                out.append("    en ").append(frames[i].toString()).append('\n');
            }
            Throwable cause = error.getCause();
            if (cause != null && cause != error) {
                out.append("  causa: ").append(describe(cause)).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "(no se pudo leer la pila)";
        }
    }
}

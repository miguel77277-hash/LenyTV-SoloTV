package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Pantalla completa de LENY TV.
 *
 * En los teléfonos modernos la app no llegaba hasta el borde superior porque el
 * sistema reserva el espacio del "notch" (orificio o muesca de la cámara) salvo
 * que se pida lo contrario. Aquí se hace todo lo necesario:
 *
 *  1. Se permite que la ventana use la zona del recorte (shortEdges).
 *  2. Se ocultan la barra de estado y la barra de navegación, y se vuelven a
 *     ocultar si el sistema las muestra por su cuenta.
 *  3. El fondo llega hasta el borde en TODAS las pantallas: los márgenes del
 *     recorte se reparten entre los contenedores que tienen su propio color
 *     (nunca al contenedor raíz, que era lo que dejaba una franja vacía).
 */
public final class Immersion {

    private static final int LEGACY_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private static final int CHECK_INTERVAL_MS = 2500;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable keeper;

    /** Recibe los márgenes de la zona de la cámara (notch). */
    public interface InsetCallback {
        void onInsets(int left, int top, int right, int bottom);
    }

    private Immersion() {
    }

    /** Oculta las barras del sistema y deja que la ventana use toda la pantalla. */
    public static void apply(Activity activity) {
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor == null) return;

            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams params = window.getAttributes();
                if (params != null
                        && params.layoutInDisplayCutoutMode
                        != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                    params.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    window.setAttributes(params);
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            } else {
                decor.setSystemUiVisibility(LEGACY_FLAGS);
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (Throwable ignored) {
            // Si el sistema no permite ocultarlas, la app sigue funcionando igual.
        }
    }

    /** Vuelve a mostrar las barras del sistema (botón "pantalla completa" apagado). */
    public static void showBars(Activity activity) {
        if (activity == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(true);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
            if (decor != null) decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Aplica la pantalla completa y la mantiene mientras esta actividad está en
     * primer plano (algunos teléfonos vuelven a mostrar las barras solas).
     */
    public static void keepHidden(final Activity activity) {
        apply(activity);
        if (activity == null) return;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor != null) {
                decor.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        int hiddenFlags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                        if ((visibility & hiddenFlags) != hiddenFlags) apply(activity);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        stopKeeper();
        keeper = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing()) return;
                apply(activity);
                HANDLER.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        HANDLER.postDelayed(keeper, CHECK_INTERVAL_MS);
    }

    /** Deja de vigilar (la actividad pasa a segundo plano o se cierra). */
    public static void stopKeeper() {
        if (keeper != null) {
            HANDLER.removeCallbacks(keeper);
            keeper = null;
        }
    }

    /**
     * Avisa de los márgenes que ocupa la cámara frontal (notch) para que cada
     * contenedor con su propio color los rellene por dentro. El resultado es que
     * el fondo llega hasta el borde de la pantalla y el texto no queda tapado.
     */
    public static void listenInsets(final View view, final InsetCallback callback) {
        if (view == null || callback == null) return;
        try {
            view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int left = 0;
                    int top = 0;
                    int right = 0;
                    int bottom = 0;
                    if (Build.VERSION.SDK_INT >= 28 && insets != null) {
                        DisplayCutout cutout = insets.getDisplayCutout();
                        if (cutout != null) {
                            left = cutout.getSafeInsetLeft();
                            top = cutout.getSafeInsetTop();
                            right = cutout.getSafeInsetRight();
                            bottom = cutout.getSafeInsetBottom();
                        }
                    }
                    callback.onInsets(left, top, right, bottom);
                    return insets;
                }
            });
            view.requestApplyInsets();
        } catch (Throwable ignored) {
        }
    }
}

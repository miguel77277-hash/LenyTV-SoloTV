package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;

/**
 * Colores y piezas visuales de la pantalla general (barra superior, columna de
 * categorías y cuadrícula), copiadas de la referencia enviada por el usuario.
 *
 * Todo lo de este archivo afecta SOLO a la pantalla de canales: el reproductor y
 * los ajustes conservan su aspecto anterior.
 */
final class RogStyle {

    private RogStyle() {
    }

    /** Fondo de la barra superior: negro con un tono rojizo muy oscuro. */
    static final int BAR_TOP = 0xF0081228;
    static final int BAR_BOTTOM = 0xE00A1830;
    /** Caja azul de la categoría elegida. */
    static final int BLUE_BOX = 0xFF0A4D8C;
    static final int BLUE_BOX_EDGE = 0xFF00E5FF;
    /** Fondo de la columna de categorías (deja ver el fondo de la pantalla). */
    static final int RAIL_PANEL = 0xCC0A1220;
    static final int RAIL_IDLE = 0x40152840;
    /** Textos. */
    static final int TEXT = 0xFFE8F4FF;
    static final int TEXT_DIM = 0xFF8AABB8;
    static final int DATE = 0xFFB8D4E8;
    /** Engranaje verde y botón de apagado de la referencia. */
    static final int GREEN = 0xFF00E5FF;
    static final int POWER = 0xFFFF4D6A;
    /** Recuadro blanco donde va el logo de cada canal. */
    static final int LOGO_BOX = 0xFFFFFFFF;

    /**
     * Fondo del menú de canales: Lenny en el sillón (assets/generated/cover.png)
     * con una capa oscura para que se lean categorías y tarjetas.
     */
    static void applyBackdrop(Context ctx, View view) {
        if (ctx == null || view == null) return;
        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeStream(ctx.getAssets().open("generated/cover.png"));
        } catch (Throwable ignored) {
        }
        if (bitmap == null) {
            try {
                bitmap = BitmapFactory.decodeStream(ctx.getAssets().open("generated/fondo_rog.png"));
            } catch (Throwable ignored) {
            }
        }
        if (bitmap != null) {
            BitmapDrawable photo = new BitmapDrawable(ctx.getResources(), bitmap);
            photo.setGravity(Gravity.FILL);
            // Capa semitransparente para legibilidad
            GradientDrawable scrim = new GradientDrawable();
            scrim.setColor(0xCC08080F);
            android.graphics.drawable.LayerDrawable layers =
                    new android.graphics.drawable.LayerDrawable(new Drawable[]{photo, scrim});
            view.setBackground(layers);
            return;
        }
        view.setBackgroundColor(0xFF0A0507);
    }

    /** Degradado de la barra superior. */
    static Drawable bar(Context ctx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BAR_TOP, BAR_BOTTOM});
        g.setCornerRadius(0f);
        return g;
    }

    /** Recuadro blanco del logo de un canal. */
    static Drawable logoBox(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(LOGO_BOX);
        g.setCornerRadius(Ui.dpi(ctx, 3));
        return g;
    }

    /** Fila de la columna de categorías: caja azul cuando está elegida. */
    static Drawable categoryRow(Context ctx, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        if (selected) {
            g.setColor(0xFF0A3A5C);
            g.setStroke(Math.max(1, Ui.dpi(ctx, 1)), 0xFF00E5FF);
            g.setCornerRadius(Ui.dpi(ctx, 18));
        } else {
            g.setColor(RAIL_IDLE);
            g.setCornerRadius(Ui.dpi(ctx, 12));
        }
        return g;
    }

    /** Tarjeta de canal estilo cristal / neón. */
    static Drawable channelCard(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0xE6152038);
        g.setStroke(Math.max(1, Ui.dpi(ctx, 1)), 0x6600D4FF);
        g.setCornerRadius(Ui.dpi(ctx, 14));
        return g;
    }

    /**
     * Tarjeta con FOCO del mando: borde cian brillante y relleno más claro
     * para distinguir en qué canal está el cursor.
     */
    static Drawable channelCardFocused(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0xF0204060);
        int stroke = Math.max(3, Ui.dpi(ctx, 3));
        g.setStroke(stroke, 0xFF00F0FF);
        g.setCornerRadius(Ui.dpi(ctx, 14));
        return g;
    }

    /** Categoría con foco del mando (además de la seleccionada). */
    static Drawable categoryRowFocused(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0xFF0A5080);
        g.setStroke(Math.max(2, Ui.dpi(ctx, 2)), 0xFF00F0FF);
        g.setCornerRadius(Ui.dpi(ctx, 18));
        return g;
    }
}

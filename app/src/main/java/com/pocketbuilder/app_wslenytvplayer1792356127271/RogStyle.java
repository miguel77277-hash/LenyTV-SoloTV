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
    static final int BAR_TOP = 0xF20C0709;
    static final int BAR_BOTTOM = 0xE6070405;
    /** Caja azul de la categoría elegida. */
    static final int BLUE_BOX = 0xFF0D47A1;
    static final int BLUE_BOX_EDGE = 0xFF00E5FF;
    /** Fondo de la columna de categorías (deja ver el fondo de la pantalla). */
    static final int RAIL_PANEL = 0x8C0A0608;
    static final int RAIL_IDLE = 0x59150E12;
    /** Textos. */
    static final int TEXT = 0xFFF3F1F1;
    static final int TEXT_DIM = 0xFFB4ADAF;
    static final int DATE = 0xFFE4DCDC;
    /** Engranaje verde y botón de apagado de la referencia. */
    static final int GREEN = 0xFF35C46A;
    static final int POWER = 0xFFE0413A;
    /** Recuadro blanco donde va el logo de cada canal. */
    static final int LOGO_BOX = 0xFFFFFFFF;

    /** Pone la imagen de fondo de la referencia; si faltara, un color oscuro. */
    static void applyBackdrop(Context ctx, View view) {
        if (ctx == null || view == null) return;
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(ctx.getAssets().open("generated/fondo_rog.png"));
            if (bitmap != null) {
                BitmapDrawable drawable = new BitmapDrawable(ctx.getResources(), bitmap);
                drawable.setGravity(Gravity.FILL);
                view.setBackground(drawable);
                return;
            }
        } catch (Throwable ignored) {
            // Si la imagen no se puede leer se usa el color de reserva.
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
        g.setColor(selected ? BLUE_BOX : RAIL_IDLE);
        g.setCornerRadius(Ui.dpi(ctx, 2));
        if (selected) g.setStroke(Math.max(1, Ui.dpi(ctx, 1)), BLUE_BOX_EDGE);
        return g;
    }
}

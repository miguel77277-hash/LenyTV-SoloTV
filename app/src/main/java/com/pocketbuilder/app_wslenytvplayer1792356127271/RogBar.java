package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Barra superior de la pantalla general, copiada de la referencia:
 *
 *   NOMBRE  [teclado]  fecha  [reloj] hora  ......  [lupa] [refrescar] [engranaje] [apagado]
 *
 * Cada botón hace lo mismo que el botón equivalente que ya existía en la app:
 * teclado = escribir un enlace a mano, lupa = buscar, refrescar = volver a cargar
 * la lista, engranaje = AJUSTES y apagado = cerrar la app.
 *
 * REVISIÓN 50: los iconos estaban un poco por encima de la línea del texto. La
 * causa era que se dibujaban como "icono de un TextView": un TextView vacío
 * reserva además la altura de una línea de texto y por eso el dibujo subía unos
 * píxeles. Ahora cada icono es una vista propia (IconView) que se dibuja
 * exactamente en el centro de su cuadro, así queda a la altura de la fecha y de
 * la hora que tiene al lado.
 */
final class RogBar {

    /** Lo que hace cada botón; lo decide la pantalla principal. */
    interface Host {
        void onKeyboard();

        void onSearch();

        void onRefresh();

        void onSettings();

        void onPower();
    }

    private RogBar() {
    }

    static View create(Context ctx, String name, String version, final Host host) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(RogStyle.bar(ctx));
        bar.setPadding(Ui.dpi(ctx, 10), Ui.dpi(ctx, 4), Ui.dpi(ctx, 8), Ui.dpi(ctx, 4));

        TextView brand = Ui.text(ctx, name, 15f, RogStyle.TEXT, true);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setSingleLine(true);
        brand.setLetterSpacing(0.02f);
        bar.addView(brand, wrap());

        TextView ver = Ui.text(ctx, version, 9.5f, RogStyle.TEXT_DIM, false);
        ver.setSingleLine(true);
        LinearLayout.LayoutParams verParams = wrap();
        verParams.leftMargin = Ui.dpi(ctx, 4);
        bar.addView(ver, verParams);

        LinearLayout.LayoutParams gap = wrap();
        gap.leftMargin = Ui.dpi(ctx, 10);
        bar.addView(iconButton(ctx, new KeyboardIcon(0xFFF3F1F1, Ui.dp(ctx, 24f)),
                "Escribir un enlace a mano", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onKeyboard();
                    }
                }), gap);

        TextView date = new ClockText(ctx, "dd-MM-yyyy", 10.5f, RogStyle.DATE, false);
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.leftMargin = Ui.dpi(ctx, 12);
        bar.addView(date, dateParams);

        LinearLayout.LayoutParams clockGap = wrap();
        clockGap.leftMargin = Ui.dpi(ctx, 10);
        bar.addView(icon(ctx, new ClockIcon(0xFFF3F1F1, Ui.dp(ctx, 24f)), "Reloj"), clockGap);

        TextView time = new ClockText(ctx, "HH:mm:ss", 12f, RogStyle.TEXT, true);
        LinearLayout.LayoutParams timeParams = wrap();
        timeParams.leftMargin = Ui.dpi(ctx, 6);
        bar.addView(time, timeParams);

        View spacer = new View(ctx);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(iconButton(ctx, Icons.search(ctx, 0xFFF3F1F1),
                "Buscar un canal", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onSearch();
                    }
                }), wrap());
        bar.addView(iconButton(ctx, Icons.refresh(ctx, 0xFFF3F1F1),
                "Volver a cargar la lista", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onRefresh();
                    }
                }), wrap());
        bar.addView(iconButton(ctx, Icons.gear(ctx, RogStyle.GREEN),
                "Ajustes", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onSettings();
                    }
                }), wrap());
        bar.addView(iconButton(ctx, Icons.power(ctx, RogStyle.POWER),
                "Cerrar LENY TV", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.onPower();
                    }
                }), wrap());

        return bar;
    }


    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static View iconButton(Context ctx, Drawable icon, String label,
                                   View.OnClickListener click) {
        View button = icon(ctx, icon, label);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        button.setBackground(Ui.buttonBg(ctx, 0x00000000, 0x33FFFFFF, 0x00000000, 4f));
        return button;
    }

    private static View icon(Context ctx, Drawable icon, String label) {
        IconView view = new IconView(ctx, icon, 38f);
        view.setContentDescription(label);
        return view;
    }

    /**
     * Cuadro cuadrado que dibuja su icono en el centro exacto. Sustituye al
     * "icono de TextView" que quedaba un poco alto respecto al texto de al lado.
     */
    private static final class IconView extends View {
        private final Drawable icon;
        private final float boxDp;

        IconView(Context ctx, Drawable icon, float boxDp) {
            super(ctx);
            this.icon = icon;
            this.boxDp = boxDp;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = Ui.dpi(getContext(), boxDp);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (icon == null) return;
            int width = getWidth();
            int height = getHeight();
            int iconSize = Ui.dpi(getContext(), boxDp * 0.52f);
            int left = (width - iconSize) / 2;
            int top = (height - iconSize) / 2;
            icon.setBounds(left, top, left + iconSize, top + iconSize);
            icon.draw(canvas);
        }
    }

    /** Texto que se actualiza solo cada segundo (fecha y hora de la barra). */
    private static final class ClockText extends TextView {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SimpleDateFormat format;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                try {
                    setText(format.format(new Date()));
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, 1000);
            }
        };

        ClockText(Context ctx, String pattern, float sizeSp, int color, boolean bold) {
            super(ctx);
            format = new SimpleDateFormat(pattern, Locale.US);
            setText(format.format(new Date()));
            setTextSize(sizeSp);
            setTextColor(color);
            if (bold) setTypeface(Typeface.DEFAULT_BOLD);
            setSingleLine(true);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.removeCallbacks(tick);
            handler.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(tick);
            super.onDetachedFromWindow();
        }
    }

    private static abstract class BaseIcon extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float size;
        private int alpha = 255;

        BaseIcon(int color, float size) {
            this.size = size;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void setAlpha(int value) {
            alpha = value;
            paint.setAlpha(value);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(size);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(size);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float w = bounds.width();
            float h = bounds.height();
            float s = Math.min(w, h);
            if (s <= 0) return;
            canvas.save();
            canvas.translate(bounds.left + (w - s) / 2f, bounds.top + (h - s) / 2f);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(Math.max(1f, s * 0.085f));
            drawIcon(canvas, s);
            canvas.restore();
        }

        abstract void drawIcon(Canvas canvas, float s);
    }

    /** Tecla: teclado con teclas y barra espaciadora. */
    private static final class KeyboardIcon extends BaseIcon {
        KeyboardIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(s * 0.06f, s * 0.26f, s * 0.94f, s * 0.74f),
                    s * 0.10f, s * 0.10f, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int row = 0; row < 2; row++) {
                for (int i = 0; i < 4; i++) {
                    canvas.drawCircle(s * (0.24f + i * 0.17f), s * (0.40f + row * 0.14f),
                            s * 0.033f, paint);
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(s * 0.30f, s * 0.66f, s * 0.70f, s * 0.66f, paint);
        }
    }

    /** Reloj: esfera con agujas. */
    private static final class ClockIcon extends BaseIcon {
        ClockIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.38f, paint);
            canvas.drawLine(s * 0.5f, s * 0.5f, s * 0.5f, s * 0.26f, paint);
            canvas.drawLine(s * 0.5f, s * 0.5f, s * 0.70f, s * 0.58f, paint);
        }
    }
}

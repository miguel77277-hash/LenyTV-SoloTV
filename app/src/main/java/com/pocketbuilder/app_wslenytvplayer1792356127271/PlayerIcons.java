package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Iconos del reproductor dibujados con Canvas, copiados de la captura de
 * referencia: transmitir, ventana flotante, altavoz, retroceder, avanzar,
 * reproducir, pausar y subtítulos.
 *
 * Se dibujan igual en cualquier teléfono y no necesitan imágenes externas.
 */
final class PlayerIcons {

    private PlayerIcons() {
    }

    /**
     * Botón cuadrado que dibuja su icono exactamente en el centro del cuadro.
     * Se usa en lugar de "icono dentro de un TextView" porque un TextView vacío
     * reserva además la altura de una línea de texto y el dibujo quedaba unos
     * píxeles alto respecto al texto de al lado.
     */
    static final class IconButton extends View {
        private Drawable icon;
        private final float boxDp;
        private final float scale;

        IconButton(Context ctx, Drawable icon, float boxDp, float scale) {
            super(ctx);
            this.icon = icon;
            this.boxDp = boxDp;
            this.scale = scale;
        }

        /** Cambia el dibujo del botón (por ejemplo el candado abierto/cerrado). */
        void setIcon(Drawable value) {
            icon = value;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = Ui.dpi(getContext(), boxDp);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (icon == null) return;
            int size = Ui.dpi(getContext(), boxDp * scale);
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.draw(canvas);
        }
    }

    static Drawable cast(Context c, int color) {
        return new CastIcon(color, Ui.dp(c, 24f));
    }

    static Drawable pip(Context c, int color) {
        return new PipIcon(color, Ui.dp(c, 24f));
    }

    static Drawable volume(Context c, int color) {
        return new VolumeIcon(color, Ui.dp(c, 24f));
    }

    static Drawable rewind(Context c, int color) {
        return new SkipIcon(color, Ui.dp(c, 24f), true);
    }

    static Drawable forward(Context c, int color) {
        return new SkipIcon(color, Ui.dp(c, 24f), false);
    }

    static Drawable play(Context c, int color) {
        return new PlayIcon(color, Ui.dp(c, 24f), false);
    }

    static Drawable pause(Context c, int color) {
        return new PlayIcon(color, Ui.dp(c, 24f), true);
    }

    static Drawable subtitles(Context c, int color) {
        return new SubtitlesIcon(color, Ui.dp(c, 24f));
    }

    private static abstract class Icon extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float size;
        private int alpha = 255;

        Icon(int color, float size) {
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
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h);
            if (s <= 0) return;
            canvas.save();
            canvas.translate(b.left + (w - s) / 2f, b.top + (h - s) / 2f);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(Math.max(1f, s * 0.09f));
            drawIcon(canvas, s);
            canvas.restore();
        }

        abstract void drawIcon(Canvas canvas, float s);
    }

    /** Transmitir a otro dispositivo: pantalla con ondas en la esquina. */
    private static final class CastIcon extends Icon {
        CastIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(s * 0.10f, s * 0.22f, s * 0.90f, s * 0.78f),
                    s * 0.08f, s * 0.08f, paint);
            canvas.drawArc(new RectF(s * 0.18f, s * 0.62f, s * 0.34f, s * 0.78f),
                    -90f, -70f, false, paint);
            canvas.drawArc(new RectF(s * 0.18f, s * 0.48f, s * 0.48f, s * 0.78f),
                    -90f, -70f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(s * 0.20f, s * 0.74f, s * 0.035f, paint);
        }
    }

    /** Ventana flotante: pantalla con un recuadro pequeño dentro. */
    private static final class PipIcon extends Icon {
        PipIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(s * 0.10f, s * 0.22f, s * 0.90f, s * 0.78f),
                    s * 0.06f, s * 0.06f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(s * 0.50f, s * 0.50f, s * 0.82f, s * 0.72f),
                    s * 0.04f, s * 0.04f, paint);
        }
    }

    /** Altavoz con ondas de sonido. */
    private static final class VolumeIcon extends Icon {
        VolumeIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.FILL);
            android.graphics.Path body = new android.graphics.Path();
            body.moveTo(s * 0.18f, s * 0.38f);
            body.lineTo(s * 0.32f, s * 0.38f);
            body.lineTo(s * 0.48f, s * 0.22f);
            body.lineTo(s * 0.48f, s * 0.78f);
            body.lineTo(s * 0.32f, s * 0.62f);
            body.lineTo(s * 0.18f, s * 0.62f);
            body.close();
            canvas.drawPath(body, paint);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawArc(new RectF(s * 0.46f, s * 0.30f, s * 0.70f, s * 0.70f),
                    -55f, 110f, false, paint);
            canvas.drawArc(new RectF(s * 0.46f, s * 0.18f, s * 0.90f, s * 0.82f),
                    -55f, 110f, false, paint);
        }
    }

    /** Doble triángulo para atrás o para delante. */
    private static final class SkipIcon extends Icon {
        private final boolean back;

        SkipIcon(int color, float size, boolean isBack) {
            super(color, size);
            back = isBack;
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.FILL);
            canvas.save();
            if (!back) {
                canvas.translate(s, 0f);
                canvas.scale(-1f, 1f);
            }
            triangle(canvas, s, s * 0.14f);
            triangle(canvas, s, s * 0.36f);
            canvas.restore();
        }

        private void triangle(Canvas canvas, float s, float x) {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x + s * 0.20f, s * 0.24f);
            p.lineTo(x, s * 0.50f);
            p.lineTo(x + s * 0.20f, s * 0.76f);
            p.close();
            canvas.drawPath(p, paint);
        }
    }

    /** Reproducir (triángulo) o pausar (dos barras). */
    private static final class PlayIcon extends Icon {
        private final boolean paused;

        PlayIcon(int color, float size, boolean isPause) {
            super(color, size);
            paused = isPause;
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.FILL);
            if (paused) {
                canvas.drawRoundRect(new RectF(s * 0.30f, s * 0.22f, s * 0.44f, s * 0.78f),
                        s * 0.03f, s * 0.03f, paint);
                canvas.drawRoundRect(new RectF(s * 0.56f, s * 0.22f, s * 0.70f, s * 0.78f),
                        s * 0.03f, s * 0.03f, paint);
            } else {
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(s * 0.32f, s * 0.20f);
                p.lineTo(s * 0.80f, s * 0.50f);
                p.lineTo(s * 0.32f, s * 0.80f);
                p.close();
                canvas.drawPath(p, paint);
            }
        }
    }

    /** Subtítulos: recuadro con dos líneas cortas y sus puntos. */
    private static final class SubtitlesIcon extends Icon {
        SubtitlesIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(new RectF(s * 0.10f, s * 0.24f, s * 0.90f, s * 0.76f),
                    s * 0.08f, s * 0.08f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(s * 0.24f, s * 0.52f, s * 0.46f, s * 0.60f),
                    s * 0.03f, s * 0.03f, paint);
            canvas.drawRoundRect(new RectF(s * 0.54f, s * 0.52f, s * 0.76f, s * 0.60f),
                    s * 0.03f, s * 0.03f, paint);
        }
    }
}

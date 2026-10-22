package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Iconos dibujados a mano con Canvas (no dependen de fuentes ni de imágenes),
 * así se ven igual en cualquier teléfono y la app no necesita recursos externos.
 */
final class Icons {

    private Icons() {
    }

    static Drawable gear(Context c, int color) {
        return new Gear(color, Ui.dp(c, 24f));
    }

    static Drawable list(Context c, int color) {
        return new ListIcon(color, Ui.dp(c, 24f));
    }

    static Drawable lock(Context c, int color, boolean closed) {
        return new LockIcon(color, Ui.dp(c, 24f), closed);
    }

    static Drawable expand(Context c, int color) {
        return new ExpandIcon(color, Ui.dp(c, 24f), false);
    }

    static Drawable contract(Context c, int color) {
        return new ExpandIcon(color, Ui.dp(c, 24f), true);
    }

    static Drawable search(Context c, int color) {
        return new SearchIcon(color, Ui.dp(c, 24f));
    }

    static Drawable refresh(Context c, int color) {
        return new RefreshIcon(color, Ui.dp(c, 24f));
    }

    static Drawable power(Context c, int color) {
        return new PowerIcon(color, Ui.dp(c, 24f));
    }

    static Drawable aspect(Context c, int color) {
        return new AspectIcon(color, Ui.dp(c, 24f));
    }

    static Drawable back(Context c, int color) {
        return new BackIcon(color, Ui.dp(c, 24f));
    }

    static Drawable close(Context c, int color) {
        return new CloseIcon(color, Ui.dp(c, 24f));
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

    private static final class Gear extends Icon {
        Gear(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            float cx = s / 2f;
            float cy = s / 2f;
            for (int i = 0; i < 8; i++) {
                canvas.save();
                canvas.rotate(i * 45f, cx, cy);
                canvas.drawLine(cx, cy - s * 0.30f, cx, cy - s * 0.46f, paint);
                canvas.restore();
            }
            canvas.drawCircle(cx, cy, s * 0.29f, paint);
            canvas.drawCircle(cx, cy, s * 0.10f, paint);
        }
    }

    private static final class ListIcon extends Icon {
        ListIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                float y = s * (0.28f + i * 0.22f);
                canvas.drawCircle(s * 0.20f, y, s * 0.055f, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 3; i++) {
                float y = s * (0.28f + i * 0.22f);
                canvas.drawLine(s * 0.36f, y, s * 0.82f, y, paint);
            }
        }
    }

    private static final class LockIcon extends Icon {
        private final boolean closed;

        LockIcon(int color, float size, boolean isClosed) {
            super(color, size);
            closed = isClosed;
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            RectF body = new RectF(s * 0.24f, s * 0.46f, s * 0.76f, s * 0.86f);
            canvas.drawRoundRect(body, s * 0.08f, s * 0.08f, paint);
            if (closed) {
                RectF arc = new RectF(s * 0.33f, s * 0.18f, s * 0.67f, s * 0.58f);
                canvas.drawArc(arc, 180f, 180f, false, paint);
            } else {
                RectF arc = new RectF(s * 0.45f, s * 0.18f, s * 0.79f, s * 0.58f);
                canvas.drawArc(arc, 180f, 140f, false, paint);
            }
        }
    }

    private static final class ExpandIcon extends Icon {
        private final boolean inward;

        ExpandIcon(int color, float size, boolean contract) {
            super(color, size);
            inward = contract;
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            float a = s * 0.18f;
            float b = s * 0.82f;
            float k = s * 0.20f;
            if (!inward) {
                corner(canvas, a, a, 1, 1, k, s);
                corner(canvas, b, a, -1, 1, k, s);
                corner(canvas, a, b, 1, -1, k, s);
                corner(canvas, b, b, -1, -1, k, s);
            } else {
                corner(canvas, a + k, a + k, -1, -1, k, s);
                corner(canvas, b - k, a + k, 1, -1, k, s);
                corner(canvas, a + k, b - k, -1, 1, k, s);
                corner(canvas, b - k, b - k, 1, 1, k, s);
            }
        }

        private void corner(Canvas canvas, float x, float y, int dx, int dy, float k, float s) {
            canvas.drawLine(x, y, x + dx * k, y, paint);
            canvas.drawLine(x, y, x, y + dy * k, paint);
        }
    }

    private static final class SearchIcon extends Icon {
        SearchIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(s * 0.42f, s * 0.42f, s * 0.24f, paint);
            canvas.drawLine(s * 0.60f, s * 0.60f, s * 0.82f, s * 0.82f, paint);
        }
    }

    private static final class RefreshIcon extends Icon {
        RefreshIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            RectF oval = new RectF(s * 0.20f, s * 0.20f, s * 0.80f, s * 0.80f);
            canvas.drawArc(oval, -55f, 290f, false, paint);
            Path head = new Path();
            head.moveTo(s * 0.66f, s * 0.16f);
            head.lineTo(s * 0.86f, s * 0.24f);
            head.lineTo(s * 0.62f, s * 0.36f);
            head.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(paint.getColor());
            fill.setAlpha(255);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawPath(head, fill);
        }
    }

    private static final class PowerIcon extends Icon {
        PowerIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            RectF oval = new RectF(s * 0.20f, s * 0.24f, s * 0.80f, s * 0.84f);
            canvas.drawArc(oval, -60f, 300f, false, paint);
            canvas.drawLine(s * 0.5f, s * 0.14f, s * 0.5f, s * 0.48f, paint);
        }
    }

    private static final class AspectIcon extends Icon {
        AspectIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(s * 0.12f, s * 0.22f, s * 0.88f, s * 0.78f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(s * 0.24f, s * 0.34f, s * 0.76f, s * 0.66f, paint);
        }
    }

    private static final class BackIcon extends Icon {
        BackIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(s * 0.20f, s * 0.5f, s * 0.76f, s * 0.5f, paint);
            canvas.drawLine(s * 0.20f, s * 0.5f, s * 0.44f, s * 0.26f, paint);
            canvas.drawLine(s * 0.20f, s * 0.5f, s * 0.44f, s * 0.74f, paint);
        }
    }

    private static final class CloseIcon extends Icon {
        CloseIcon(int color, float size) {
            super(color, size);
        }

        @Override
        void drawIcon(Canvas canvas, float s) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(s * 0.26f, s * 0.26f, s * 0.74f, s * 0.74f, paint);
            canvas.drawLine(s * 0.74f, s * 0.26f, s * 0.26f, s * 0.74f, paint);
        }
    }
}

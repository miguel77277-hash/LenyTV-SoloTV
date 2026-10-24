package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

/** Paleta retro TV y ayudas para construir la interfaz por código. */
final class Ui {

    static final int BG = 0xFF070B14;
    static final int HEADER = 0xFF12121C;
    static final int PANEL = 0xFF0F1729;
    static final int CARD = 0xFF152038;
    static final int CARD_SEL = 0xFF1A3A5C;
    static final int RED = 0xFF00D4FF;
    static final int RED_DEEP = 0xFF0088AA;
    static final int BLUE = 0xFF2F8CFF;
    static final int BLUE_DEEP = 0xFF00B8E6;
    static final int TEXT = 0xFFF4F4F8;
    static final int TEXT_DIM = 0xFF9E9EB2;
    static final int TEXT_MUTED = 0xFF6E6E85;
    static final int OK = 0xFF3EC46E;
    static final int WARN = 0xFFE8A33D;

    private Ui() {
    }

    static float dp(Context c, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics());
    }

    static int dpi(Context c, float value) {
        return Math.round(dp(c, value));
    }

    static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static GradientDrawable roundedStroke(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable g = rounded(c, fill, radiusDp);
        if (stroke != 0) g.setStroke(Math.max(1, dpi(c, strokeDp)), stroke);
        return g;
    }

    static Drawable buttonBg(Context c, int fill, int pressed, int stroke, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, roundedStroke(c, pressed, stroke, 1.5f, radiusDp));
        s.addState(new int[]{android.R.attr.state_focused}, roundedStroke(c, pressed, stroke, 2f, radiusDp));
        s.addState(new int[]{}, roundedStroke(c, fill, stroke, 1.5f, radiusDp));
        return s;
    }

    static TextView text(Context c, CharSequence value, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Botón de texto tipo "placa" del mando. */
    static TextView actionButton(Context c, CharSequence label) {
        return plate(c, label, CARD, Ui.TEXT, Ui.RED_DEEP);
    }

    static TextView primaryButton(Context c, CharSequence label) {
        return plate(c, label, RED, 0xFFFFFFFF, RED_DEEP);
    }

    static TextView plate(Context c, CharSequence label, int fill, int textColor, int stroke) {
        TextView b = text(c, label, 13f, textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setAllCaps(true);
        b.setLetterSpacing(0.06f);
        b.setPadding(dpi(c, 14), dpi(c, 10), dpi(c, 14), dpi(c, 10));
        b.setBackground(buttonBg(c, fill, CARD_SEL, stroke, 6f));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dpi(c, 44));
        b.setMinWidth(dpi(c, 44));
        return b;
    }

    /** Botón cuadrado con icono dibujado y etiqueta accesible. */
    static TextView iconButton(Context c, Drawable icon, String description, float sizeDp) {
        TextView b = new TextView(c);
        int size = dpi(c, sizeDp);
        b.setWidth(size);
        b.setHeight(size);
        icon.setBounds(0, 0, dpi(c, sizeDp * 0.52f), dpi(c, sizeDp * 0.52f));
        b.setCompoundDrawables(null, icon, null, null);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setBackground(buttonBg(c, 0x66000000, 0x99E13A2B, 0x66F4F4F8, 6f));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static View space(Context c, int widthDp, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dpi(c, widthDp), dpi(c, heightDp)));
        return v;
    }

    static View hLine(Context c, int color, float heightDp) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpi(c, heightDp))));
        return v;
    }

    /** Adaptador sencillo para los desplegables, con el estilo oscuro de la app. */
    static final class SpinnerAdapter extends BaseAdapter {
        private final Context ctx;
        private final String[] items;

        SpinnerAdapter(Context c, String[] items) {
            this.ctx = c;
            this.items = items == null ? new String[0] : items;
        }

        @Override
        public int getCount() {
            return items.length;
        }

        @Override
        public Object getItem(int position) {
            return items[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        String labelAt(int position) {
            if (position < 0 || position >= items.length) return "";
            return items[position];
        }

        int indexOf(String label) {
            for (int i = 0; i < items.length; i++) {
                if (items[i].equals(label)) return i;
            }
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return row(labelAt(position), false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return row(labelAt(position), true);
        }

        private View row(String value, boolean dropdown) {
            TextView t = text(ctx, value, 13f, TEXT, false);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dpi(ctx, 10), dpi(ctx, 9), dpi(ctx, 10), dpi(ctx, 9));
            if (dropdown) {
                t.setTextColor(TEXT_DIM);
                t.setBackgroundColor(0xFF101018);
            } else {
                t.setBackground(buttonBg(ctx, PANEL, CARD_SEL, BLUE_DEEP, 6f));
            }
            return t;
        }
    }
}

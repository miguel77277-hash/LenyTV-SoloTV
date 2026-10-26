package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Columna de categorías de la pantalla general (izquierda), copiada de la
 * referencia: cada fila muestra el nombre del apartado y la categoría elegida
 * aparece dentro de una caja azul.
 *
 * REVISIÓN 53: la fila ya NO lleva delante la cifra de canales (el usuario veía
 * «37 FOX SPORTS», «393 ESPN» y pidió los nombres limpios).
 */
final class RogCategoryAdapter extends BaseAdapter {

    /** Con qué categoría se compara cada fila para saber si está elegida. */
    interface Selected {
        String id();
    }

    private final android.content.Context ctx;
    private final ArrayList<String> names;
    private final java.util.HashMap<String, Integer> counts;
    private final Selected selected;

    RogCategoryAdapter(android.content.Context ctx, ArrayList<String> names,
                       java.util.HashMap<String, Integer> counts, Selected selected) {
        this.ctx = ctx;
        this.names = names;
        this.counts = counts;
        this.selected = selected;
    }

    @Override
    public int getCount() {
        return names == null ? 0 : names.size();
    }

    @Override
    public Object getItem(int position) {
        return names == null || position < 0 || position >= names.size() ? null : names.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView row = convertView instanceof TextView ? (TextView) convertView : new TextView(ctx);
        String category = names.get(position);
        String current = selected == null ? "" : selected.id();
        boolean chosen = category.equals(current);

        int total = 0;
        Integer count = counts == null ? null : counts.get(category);
        if (count != null) {
            total = count.intValue();
        } else if (counts != null) {
            // La fila «Todas» no tiene cifra propia: se suman todas.
            for (Integer value : counts.values()) {
                if (value != null) total += value.intValue();
            }
        }

        // Los apartados se muestran en MAYÚSCULAS (como en la lista del usuario);
        // el nombre guardado no cambia, así que la categoría elegida se sigue
        // reconociendo igual.
        //
        // REVISIÓN 53: el usuario pidió quitar los números delante de los nombres
        // («37 FOX SPORTS» → «FOX SPORTS», «393 ESPN» → «ESPN»). Esa cifra era el
        // número de canales del apartado, no parte del nombre. Los apartados que
        // llevan números de verdad (24/7) los conservan, porque van dentro del
        // propio nombre. La cifra sigue disponible para quien use el lector de
        // pantalla, en la descripción.
        String visible = Names.forSection(row.getPaint(), category);
        row.setText(visible);
        row.setTextSize(10.5f);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        row.setTextColor(chosen ? 0xFFFFFFFF : RogStyle.TEXT_DIM);
        row.setBackground(RogStyle.categoryRow(ctx, chosen));
        row.setPadding(Ui.dpi(ctx, 7), Ui.dpi(ctx, 7), Ui.dpi(ctx, 6), Ui.dpi(ctx, 7));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMaxLines(2);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setContentDescription(category + ": " + total + " canales");
        row.setFocusable(true);
        row.setClickable(false);
        final boolean rowChosen = chosen;
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackground(RogStyle.categoryRowFocused(ctx));
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                } else {
                    v.setBackground(RogStyle.categoryRow(ctx, rowChosen));
                    ((TextView) v).setTextColor(rowChosen ? 0xFFFFFFFF : RogStyle.TEXT_DIM);
                }
            }
        });
        if (row.isFocused()) {
            row.setBackground(RogStyle.categoryRowFocused(ctx));
            row.setTextColor(0xFFFFFFFF);
        }
        return row;
    }
}

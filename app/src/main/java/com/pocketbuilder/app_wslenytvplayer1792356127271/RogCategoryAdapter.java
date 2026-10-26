package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Columna de categorías. Foco del mando con brillo; clic táctil y OK abren el apartado.
 */
final class RogCategoryAdapter extends BaseAdapter {

    interface Selected {
        String id();
    }

    interface OnCategoryAction {
        void onSelect(String category);
    }

    private final android.content.Context ctx;
    private final ArrayList<String> names;
    private final java.util.HashMap<String, Integer> counts;
    private final Selected selected;
    private OnCategoryAction action;

    RogCategoryAdapter(android.content.Context ctx, ArrayList<String> names,
                       java.util.HashMap<String, Integer> counts, Selected selected) {
        this.ctx = ctx;
        this.names = names;
        this.counts = counts;
        this.selected = selected;
    }

    void setOnCategoryAction(OnCategoryAction action) {
        this.action = action;
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
        final String category = names.get(position);
        String current = selected == null ? "" : selected.id();
        final boolean chosen = category.equals(current);

        int total = 0;
        Integer count = counts == null ? null : counts.get(category);
        if (count != null) {
            total = count.intValue();
        } else if (counts != null) {
            for (Integer value : counts.values()) {
                if (value != null) total += value.intValue();
            }
        }

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
        row.setClickable(true);

        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackground(RogStyle.categoryRowFocused(ctx));
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                } else {
                    boolean stillChosen = selected != null && category.equals(selected.id());
                    v.setBackground(RogStyle.categoryRow(ctx, stillChosen));
                    ((TextView) v).setTextColor(stillChosen ? 0xFFFFFFFF : RogStyle.TEXT_DIM);
                }
            }
        });

        final int pos = position;
        View.OnClickListener open = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    action.onSelect(category);
                    return;
                }
                if (parent instanceof AdapterView) {
                    ((AdapterView<?>) parent).performItemClick(v, pos, getItemId(pos));
                }
            }
        };
        row.setOnClickListener(open);
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_UP) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    open.onClick(v);
                    return true;
                }
                return false;
            }
        });

        if (row.isFocused()) {
            row.setBackground(RogStyle.categoryRowFocused(ctx));
            row.setTextColor(0xFFFFFFFF);
        }
        return row;
    }
}

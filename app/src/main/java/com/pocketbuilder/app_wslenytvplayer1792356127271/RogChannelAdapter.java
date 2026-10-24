package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Cuadrícula de canales de la pantalla general, copiada de la referencia:
 * cada canal es un recuadro blanco con su logo y, debajo, el nombre centrado en
 * un máximo de dos líneas. No lleva marcos ni botones añadidos.
 *
 * El adaptador recibe la MISMA lista que la app ya usaba para dibujar la
 * cuadrícula, así que los filtros, la búsqueda y la apertura del reproductor
 * siguen funcionando igual que antes.
 */
final class RogChannelAdapter extends BaseAdapter {

    private final Context ctx;
    private final ArrayList<Channel> data;

    RogChannelAdapter(Context ctx, ArrayList<Channel> data) {
        this.ctx = ctx;
        this.data = data;
    }

    @Override
    public int getCount() {
        return data == null ? 0 : data.size();
    }

    @Override
    public Object getItem(int position) {
        return data == null || position < 0 || position >= data.size() ? null : data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout card;
        ImageView logo;
        TextView name;
        if (convertView instanceof LinearLayout && convertView.getTag() instanceof Object[]) {
            card = (LinearLayout) convertView;
            Object[] holder = (Object[]) card.getTag();
            logo = (ImageView) holder[0];
            name = (TextView) holder[1];
        } else {
            card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.dpi(ctx, 8), Ui.dpi(ctx, 10), Ui.dpi(ctx, 8), Ui.dpi(ctx, 10));
            card.setBackground(RogStyle.channelCard(ctx));

            logo = new ImageView(ctx);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setBackground(RogStyle.logoBox(ctx));
            logo.setPadding(Ui.dpi(ctx, 6), Ui.dpi(ctx, 6), Ui.dpi(ctx, 6), Ui.dpi(ctx, 6));
            card.addView(logo, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpi(ctx, 52)));

            name = new TextView(ctx);
            name.setTextSize(9.5f);
            name.setTextColor(0xFFB8E8FF);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLineSpacing(0f, 0.95f);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = Ui.dpi(ctx, 3);
            card.addView(name, nameParams);

            card.setTag(new Object[]{logo, name});
        }

        Channel channel = data.get(position);
        // Los nombres con banderas y bolas de color se arreglan antes de dibujarlos:
        // si el teléfono no puede dibujar el emoji, se muestra algo legible (AR, MX...).
        String visible = Names.forDisplay(name.getPaint(), channel.name);
        name.setText(visible);
        logo.setImageDrawable(null);
        LogoLoader.load(ctx, channel.logo, logo, visible);
        card.setContentDescription(visible);
        return card;
    }
}

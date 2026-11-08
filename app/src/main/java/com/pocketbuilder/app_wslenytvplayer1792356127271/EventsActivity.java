package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Módulo Eventos (deportes / en vivo). Independiente de TV y Películas.
 * Listas M3U agrupadas por servidor; ajustes para añadir o cambiar enlaces.
 */
public final class EventsActivity extends Activity {

    private static final int CYAN = 0xFF00D4FF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private LinearLayout rowsBox;
    private LinearLayout loadingBox;
    private TextView statusView;
    private LinearLayout serverChips;
    private String selectedServer = "";
    private boolean loading = false;

    /** Canal de evento: nombre, url, logo, servidor origen. */
    static final class EventItem {
        String name = "";
        String url = "";
        String logo = "";
        String server = "";
        String group = "";
        String listUrl = "";
    }

    private final ArrayList<EventItem> all = new ArrayList<EventItem>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
        EventLists.seedDefaults(this);
        buildUi();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
    }

    private int dpi(float v) {
        return Ui.dpi(this, v);
    }

    private GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dpi(this, r));
        return g;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D14);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dpi(14), dpi(10), dpi(12), dpi(8));
        top.addView(Ui.text(this, "Leny TV", 16f, CYAN, true));
        top.addView(Ui.text(this, "  |  ", 14f, 0xFF556070, false));
        top.addView(Ui.text(this, "Eventos", 15f, 0xFFFFFFFF, true));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView tv = Ui.text(this, "TV", 12f, 0xFFAABBCC, true);
        tv.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        tv.setBackground(rounded(0x22FFFFFF, 10f));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(tv);
        TextView gear = Ui.text(this, "⚙", 16f, 0xFFE8EEF8, true);
        gear.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        gear.setBackground(rounded(0x22182028, 10f));
        LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gearLp.leftMargin = dpi(8);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(gear, gearLp);
        root.addView(top);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(dpi(12), dpi(4), dpi(12), dpi(4));
        serverChips = new LinearLayout(this);
        serverChips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(serverChips);
        root.addView(chipScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = Ui.text(this, "", 11f, 0xFF8899AA, false);
        statusView.setPadding(dpi(16), dpi(4), dpi(16), dpi(6));
        root.addView(statusView);

        FrameLayout body = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setPadding(dpi(12), dpi(4), dpi(12), dpi(20));
        scroll.addView(rowsBox);
        body.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        loadingBox = new LinearLayout(this);
        loadingBox.setOrientation(LinearLayout.VERTICAL);
        loadingBox.setGravity(Gravity.CENTER);
        loadingBox.addView(new ProgressBar(this));
        loadingBox.addView(Ui.text(this, "Cargando eventos…", 12f, 0xFFCCD6E0, false));
        loadingBox.setVisibility(View.GONE);
        body.addView(loadingBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void reload() {
        final ArrayList<String> urls = EventLists.urls(this);
        if (urls.isEmpty()) {
            all.clear();
            rebuildRows();
            return;
        }
        // Solo la lista de la pestaña elegida (evita mezclar y reduce lag)
        if (selectedServer.length() == 0) {
            selectedServer = EventLists.displayName(urls.get(0));
        }
        String chosenUrl = null;
        for (int i = 0; i < urls.size(); i++) {
            if (EventLists.displayName(urls.get(i)).equals(selectedServer)) {
                chosenUrl = urls.get(i);
                break;
            }
        }
        if (chosenUrl == null) {
            chosenUrl = urls.get(0);
            selectedServer = EventLists.displayName(chosenUrl);
        }
        loading = true;
        updateLoading();
        final String listUrl = chosenUrl;
        final String serverName = EventLists.displayName(listUrl);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<EventItem> loaded = new ArrayList<EventItem>();
                try {
                    String text = Net.getText(listUrl, null);
                    if (text != null && text.length() >= 8) {
                        M3uParser.Result parsed = M3uParser.parse(text, listUrl);
                        if (parsed != null && parsed.channels != null) {
                            // Últimos del archivo primero
                            for (int c = parsed.channels.size() - 1; c >= 0; c--) {
                                Channel channel = parsed.channels.get(c);
                                if (channel == null || channel.url == null
                                        || channel.url.length() == 0) {
                                    continue;
                                }
                                EventItem item = new EventItem();
                                item.name = channel.name == null ? "Evento" : channel.name;
                                item.url = channel.url;
                                item.logo = channel.logo == null ? "" : channel.logo;
                                item.server = serverName;
                                item.listUrl = listUrl;
                                item.group = channel.category == null ? "" : channel.category;
                                loaded.add(item);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(loaded);
                        loading = false;
                        rebuildRows();
                        updateLoading();
                    }
                });
            }
        });
    }

    private void updateLoading() {
        if (loadingBox != null) {
            loadingBox.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (statusView != null) {
            if (loading) {
                statusView.setText("Descargando listas de eventos…");
            } else {
                statusView.setText(all.size() + " eventos · ordenados por servidor");
            }
        }
    }

    private void rebuildRows() {
        if (rowsBox == null) return;
        rowsBox.removeAllViews();
        rebuildServerChips();
        if (all.isEmpty()) {
            rowsBox.addView(Ui.text(this,
                    "Sin eventos. Revisa los enlaces en Ajustes ⚙.",
                    13f, 0xFF99AABC, false));
            return;
        }
        if (selectedServer.length() == 0 && !all.isEmpty()) {
            selectedServer = all.get(0).server == null ? "" : all.get(0).server;
        }
        ArrayList<EventItem> filtered = new ArrayList<EventItem>();
        for (int i = 0; i < all.size(); i++) {
            EventItem e = all.get(i);
            if (!selectedServer.equals(e.server)) continue;
            filtered.add(e);
        }
        if (filtered.isEmpty()) {
            rowsBox.addView(Ui.text(this, "No hay eventos en este servidor.", 13f, 0xFF99AABC, false));
            return;
        }
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        for (int i = 0; i < filtered.size(); i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lineLp.bottomMargin = dpi(10);
                line.setLayoutParams(lineLp);
                grid.addView(line);
            }
            line.addView(eventCard(filtered.get(i)));
        }
        rowsBox.addView(grid);
        if (statusView != null && !loading) {
            statusView.setText(filtered.size() + " eventos"
                    + (selectedServer.length() > 0 ? " · " + selectedServer : ""));
        }
    }

    private void rebuildServerChips() {
        if (serverChips == null) return;
        serverChips.removeAllViews();
        ArrayList<String> urls = EventLists.urls(this);
        if (urls.isEmpty()) return;
        if (selectedServer.length() == 0) {
            selectedServer = EventLists.displayName(urls.get(0));
        }
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            String name = EventLists.displayName(u);
            int count = name.equals(selectedServer) ? all.size() : 0;
            String label = name + (count > 0 ? " (" + count + ")" : "");
            serverChips.addView(serverChip(label, name, name.equals(selectedServer)));
        }
    }

    private View serverChip(final String label, final String serverKey, boolean on) {
        TextView chip = Ui.text(this, label, 11f, on ? 0xFF001018 : 0xFFE8EEF8, true);
        chip.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        chip.setBackground(rounded(on ? CYAN : 0xFF1A2230, 16f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dpi(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedServer = serverKey == null ? "" : serverKey;
                reload();
            }
        });
        return chip;
    }

    private View eventCard(final EventItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpi(6), dpi(6), dpi(6), dpi(6));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardLp.leftMargin = dpi(4);
        cardLp.rightMargin = dpi(4);
        card.setLayoutParams(cardLp);
        card.setBackground(rounded(0xFF141A26, 12f));

        FrameLayout art = new FrameLayout(this);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackgroundColor(0xFF0A0E16);
        String logoUrl = item.logo;
        if (logoUrl == null || logoUrl.length() == 0) logoUrl = EventLists.PLACEHOLDER_LOGO;
        LogoLoader.load(this, logoUrl, logo, item.name);
        art.addView(logo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpi(100)));
        // brillo inferior
        View glow = new View(this);
        glow.setBackgroundColor(0x6600D4FF);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpi(3), Gravity.BOTTOM);
        art.addView(glow, glowLp);
        card.addView(art, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpi(100)));

        TextView name = Ui.text(this, item.name, 12f, 0xFFF0F4FA, true);
        name.setMaxLines(2);
        name.setPadding(dpi(6), dpi(8), dpi(6), dpi(2));
        card.addView(name);
        if (item.group != null && item.group.length() > 0) {
            TextView sub = Ui.text(this, item.group, 10f, 0xFF8899AA, false);
            sub.setMaxLines(1);
            sub.setPadding(dpi(6), 0, dpi(6), dpi(8));
            card.addView(sub);
        } else {
            View pad = new View(this);
            card.addView(pad, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpi(8)));
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(EventsActivity.this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_MODE, PlayerActivity.MODE_SINGLE);
                    i.putExtra(PlayerActivity.EXTRA_URL, item.url);
                    i.putExtra(PlayerActivity.EXTRA_NAME, item.name);
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(EventsActivity.this, "No se pudo reproducir", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return card;
    }

    private void showSettings() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dpi(16), dpi(14), dpi(16), dpi(14));
        panel.setBackground(rounded(0xF2140E14, 14f));
        panel.addView(Ui.text(this, "AJUSTES DE EVENTOS", 13f, CYAN, true));
        panel.addView(Ui.text(this,
                "Añade o cambia enlaces M3U de eventos. Las listas se agrupan por servidor.",
                11f, 0xFF99AABC, false));

        final EditText field = new EditText(this);
        field.setHint("https://…/lista.m3u");
        field.setHintTextColor(0xFF667788);
        field.setTextColor(0xFFE8EEF8);
        field.setSingleLine(true);
        field.setBackground(rounded(0x22182028, 10f));
        field.setPadding(dpi(12), dpi(10), dpi(12), dpi(10));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = dpi(12);
        panel.addView(field, fieldLp);

        TextView add = Ui.text(this, "  Añadir enlace  ", 13f, 0xFF001018, true);
        add.setBackground(rounded(CYAN, 10f));
        add.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = dpi(10);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = field.getText() == null ? "" : field.getText().toString().trim();
                if (u.length() < 8) {
                    Toast.makeText(EventsActivity.this, "Enlace no válido", Toast.LENGTH_SHORT).show();
                    return;
                }
                EventLists.addUrl(EventsActivity.this, u);
                field.setText("");
                Toast.makeText(EventsActivity.this, "Enlace añadido", Toast.LENGTH_SHORT).show();
                reload();
            }
        });
        panel.addView(add, addLp);

        TextView refresh = Ui.text(this, "  Actualizar listas  ", 13f, 0xFFE8EEF8, true);
        refresh.setBackground(rounded(0x44FFFFFF, 10f));
        refresh.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refLp.topMargin = dpi(8);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                reload();
            }
        });
        panel.addView(refresh, refLp);

        // Lista de URLs actuales
        ArrayList<String> urls = EventLists.urls(this);
        for (int i = 0; i < urls.size(); i++) {
            final String u = urls.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dpi(8), 0, 0);
            TextView label = Ui.text(this, EventLists.serverLabel(u), 11f, 0xFFCCD6E0, false);
            label.setMaxLines(1);
            line.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = Ui.text(this, "Quitar", 11f, 0xFFFF8899, true);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EventLists.removeUrl(EventsActivity.this, u);
                    dialog.dismiss();
                    showSettings();
                    reload();
                }
            });
            line.addView(del);
            panel.addView(line);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
    }
}

package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesGuard;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesPrefs;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesSettingsActivity;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ENLACES (antes «ADMINISTRAR REPOSITORIOS») de la interfaz de películas y series.
 *
 * Tiene tres partes, todas reales:
 *
 *   PROVEEDORES INTEGRADOS: AllCalidad, Pelisplus HD, Latanime y Flixlatam (los
 *   cuatro complementos .cs3 adjuntos) ya vienen creados dentro de la app
 *   (nombre, color, explicación y su hueco de dirección). Cada uno guarda su
 *   dirección: se pega una vez, se descarga y sus títulos entran en el catálogo.
 *   No hay que crear la ficha a mano.
 *
 *   COMPLEMENTOS .CS3 ADJUNTOS: informe de esos cuatro archivos, con lo que se
 *   aprovecha, lo que habría que rehacer y lo que no es posible. Sus fichas
 *   están creadas, pero el .cs3 no se puede ejecutar aquí.
 *
 *   COMPROBAR DIRECCIÓN: analiza cualquier enlace y explica qué es (catálogo
 *   JSON, lista M3U, índice de CloudStream, complemento de Stremio, página web…)
 *   y si la app puede usarlo. En los índices de CloudStream se leen y se enseñan
 *   los nombres de sus proveedores, avisando de que los .cs3 no se ejecutan aquí.
 *
 *   MIS REPOSITORIOS: añadir una dirección nueva, actualizarla, activarla,
 *   desactivarla y quitarla, con el error concreto a la vista cuando falla.
 */
public final class RepoActivity extends Activity {

    /** Pestañas de la pantalla: cada sección se ve entera sin hacer scroll. */
    private static final String TAB_PRESETS = "presets";
    private static final String TAB_CS3 = "cs3";
    private static final String TAB_MINE = "mine";

    private LinearLayout listBox;
    private LinearLayout tabRow;
    private String tab = TAB_PRESETS;
    private String helpText = "";
    private EditText urlField;
    private TextView resultView;
    private LinearLayout probeBox;
    private boolean working = false;
    /** Repositorios que quedan por actualizar cuando se pulsa ACTUALIZAR TODO. */
    private final ArrayList<CatalogStore.Repo> queue = new ArrayList<CatalogStore.Repo>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Esta pantalla pertenece al MÓDULO DE PELÍCULAS: su red de seguridad deja
        // cualquier fallo escrito en el registro del módulo (nunca en el de TV).
        MoviesGuard.install(this);
        try {
            CatalogStore.seedPresets(this);
            buildUi();
        } catch (Throwable t) {
            Diag.add("Repositorios · no se pudo dibujar la pantalla: " + Net.describe(t));
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setGravity(Gravity.CENTER);
            fallback.setBackgroundColor(0xFF08080F);
            fallback.setPadding(dpi(24), dpi(24), dpi(24), dpi(24));
            TextView message = Ui.text(this,
                    "No se pudo abrir ENLACES. Vuelve atrás y prueba otra vez.",
                    14f, RogStyle.TEXT, false);
            message.setGravity(Gravity.CENTER);
            fallback.addView(message);
            setContentView(fallback);
            return;
        }
        rebuildList();
        // Pantalla completa, igual que en la televisión: esta pantalla forma parte
        // de PELÍCULAS y antes dejaba ver las barras del sistema.
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
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
    protected void onPause() {
        super.onPause();
        try {
            Immersion.stopKeeper();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            try {
                Immersion.apply(this);
            } catch (Throwable ignored) {
            }
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        RogStyle.applyBackdrop(this, root);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ------------------------------------------------------------ cabecera
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(RogStyle.bar(this));
        bar.setPadding(dpi(10), dpi(5), dpi(8), dpi(5));

        TextView brand = Ui.text(this, "LENY TV", 15f, RogStyle.TEXT, true);
        bar.addView(brand);

        TextView tag = Ui.text(this, "ENLACES", 10f, 0xFF22D3EE, true);
        tag.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dpi(10);
        bar.addView(tag, tagParams);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(button("AYUDA", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        }), gap(8));

        bar.addView(button("VOLVER", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), gap(0));
        column.addView(bar);

        // ------------------------------------------------------------- añadir
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dpi(8);
        column.addView(addRow, addParams);

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setHint("Pega aquí tu lista completa (…/get.php?username=…&password=…) o un catálogo .json");
        urlField.setHintTextColor(0xFF8E8A90);
        urlField.setTextColor(RogStyle.TEXT);
        urlField.setTextSize(12f);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xFF1B1B28, 0x33FFFFFF, 6f));
        urlField.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        urlField.setMinHeight(dpi(44));
        urlField.setContentDescription("Dirección de la fuente");
        addRow.addView(urlField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addRow.addView(button("AÑADIR", RogStyle.BLUE_BOX, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRepository();
            }
        }), gap(8));

        addRow.addView(button("COMPROBAR DIRECCIÓN", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        checkAddress();
                    }
                }), gap(8));

        addRow.addView(button("ACTUALIZAR TODO", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        updateAll();
                    }
                }), gap(8));

        resultView = Ui.text(this, "", 11f, RogStyle.TEXT_DIM, false);
        resultView.setPadding(0, dpi(6), 0, 0);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resultParams.topMargin = dpi(4);
        column.addView(resultView, resultParams);

        // ------------------------------------------------- diagnóstico de enlace
        probeBox = new LinearLayout(this);
        probeBox.setOrientation(LinearLayout.VERTICAL);
        probeBox.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        probeBox.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xCC120E12, 0x33FFFFFF, 8f));
        probeBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams probeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        probeParams.topMargin = dpi(6);
        column.addView(probeBox, probeParams);

        // --------------------------------------------------------------- ayuda
        TextView help = Ui.text(this, "Los proveedores integrados ya vienen en la app: pega su "
                + "dirección y pulsa GUARDAR Y DESCARGAR.\n"
                + "LISTAS DE IPTV: pega la dirección completa que te dio tu proveedor (por ejemplo "
                + "…/get.php?username=USUARIO&password=CLAVE&type=m3u_plus&output=ts) y pulsa "
                + "AÑADIR. Si es una lista de Xtream, la app la reconoce sola y pregunta a su API "
                + "solo por las películas y las series (sin descargar la lista completa, que puede "
                + "pesar muchos megas); los episodios de cada serie se piden al abrirla. En una "
                + "lista .m3u/.m3u8 normal se añaden también solo sus películas y series (MKV, MP4, "
                + "AVI…). Los canales en directo se descartan: esos van en la pantalla de TV.\n"
                + "Formato que entiende el catálogo: JSON con \\\"items\\\" (título, cartel, géneros, "
                + "sinopsis y enlaces HLS/DASH/MP4/WebM/TS; en las series, \\\"seasons\\\" con sus "
                + "episodios).\n"
                + "Los complementos .cs3 de CloudStream no se pueden ejecutar aquí (son código "
                + "compilado para CloudStream): de un repositorio de CloudStream se leen y se "
                + "muestran sus proveedores, pero los títulos tienen que venir de un catálogo JSON.",
                10.5f, RogStyle.TEXT_DIM, false);
        help.setLineSpacing(0f, 1.12f);
        help.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        help.setBackground(Ui.buttonBg(this, 0x8C150E12, 0x8C150E12, 0x22000000, 8f));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helpParams.topMargin = dpi(8);
        helpText = help.getText() == null ? "" : help.getText().toString();
        help.setVisibility(View.GONE);
        column.addView(help, helpParams);

        // ------------------------------------------------------------- pestañas
        column.addView(buildTabs());

        // --------------------------------------------------------------- lista
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dpi(8);
        column.addView(scroll, scrollParams);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private TextView button(String label, int fill, int textColor, View.OnClickListener click) {
        TextView view = Ui.text(this, label, 11f, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setLetterSpacing(0.05f);
        view.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        view.setMinHeight(dpi(42));
        view.setMinWidth(dpi(56));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fill);
        background.setCornerRadius(dpi(5));
        background.setStroke(Math.max(1, dpi(1)), 0x33FFFFFF);
        view.setBackground(background);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(click);
        view.setContentDescription(label);
        return view;
    }

    private LinearLayout.LayoutParams gap(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpi(dp);
        return params;
    }

    // ------------------------------------------------------------------ lista

    private void rebuildList() {
        if (listBox == null) return;
        listBox.removeAllViews();

        if (TAB_PRESETS.equals(tab)) {
        listBox.addView(sectionTitle("PROVEEDORES INTEGRADOS",
                "Ya vienen creados en la app. Solo falta pegar su dirección."));

        ArrayList<CatalogStore.Repo> presets = presetRepos();
        for (int i = 0; i < presets.size(); i++) {
            Providers.Preset preset = Providers.fromRepoId(presets.get(i).id);
            listBox.addView(presetCard(presets.get(i), preset));
        }
        }

        if (TAB_CS3.equals(tab)) {
        listBox.addView(sectionTitle("COMPLEMENTOS .CS3 ADJUNTOS",
                Cs3Audit.summaryLine()));
        ArrayList<Cs3Audit.Entry> audit = Cs3Audit.entries();
        for (int i = 0; i < audit.size(); i++) {
            listBox.addView(cs3Card(audit.get(i)));
        }
        listBox.addView(fullReportCard());
        }

        if (TAB_MINE.equals(tab)) {
        listBox.addView(sectionTitle("MIS ENLACES",
                "Tus catálogos JSON y tus listas de IPTV completas. Solo aparecen en el catálogo "
                        + "los activados; de cada lista se guardan sus películas y series."));

        listBox.addView(xtreamCard());

        ArrayList<CatalogStore.Repo> custom = customRepos();
        if (custom.isEmpty()) {
            TextView empty = Ui.text(this, "Todavía no has añadido ningún enlace propio.\n"
                    + "Pega arriba la dirección completa de tu lista de IPTV (o la de un catálogo "
                    + "JSON) y pulsa AÑADIR.", 11.5f,
                    RogStyle.TEXT_DIM, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpi(14), 0, dpi(14));
            listBox.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        } else {
            for (int i = 0; i < custom.size(); i++) {
                listBox.addView(repoCard(custom.get(i)));
            }
        }

        listBox.addView(sectionTitle("CATÁLOGO DE DEMOSTRACIÓN",
                "Sigue dentro de la app, pero viene OCULTO para no aparentar contenido real."));
        listBox.addView(demoCard());
        }
    }

    /** Fila de pestañas: PROVEEDORES · COMPLEMENTOS .CS3 · MIS REPOSITORIOS. */
    private View buildTabs() {
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpi(8);
        tabRow.setLayoutParams(params);
        fillTabs();
        return tabRow;
    }

    private void fillTabs() {
        if (tabRow == null) return;
        tabRow.removeAllViews();
        tabRow.addView(tabButton("PROVEEDORES", TAB_PRESETS), gap(0));
        tabRow.addView(tabButton("COMPLEMENTOS .CS3", TAB_CS3), gap(8));
        tabRow.addView(tabButton("MIS ENLACES", TAB_MINE), gap(8));
    }

    private TextView tabButton(String label, final String value) {
        boolean selected = value.equals(tab);
        TextView view = Ui.text(this, label, 11f, selected ? 0xFFFFFFFF : RogStyle.TEXT, selected);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setLetterSpacing(0.05f);
        view.setPadding(dpi(14), dpi(8), dpi(14), dpi(8));
        view.setMinHeight(dpi(42));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(selected ? RogStyle.BLUE_BOX : 0x8C150E12);
        background.setCornerRadius(dpi(6));
        background.setStroke(Math.max(1, dpi(1)), selected ? RogStyle.BLUE_BOX_EDGE : 0x33FFFFFF);
        view.setBackground(background);
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (value.equals(tab)) return;
                tab = value;
                fillTabs();
                rebuildList();
            }
        });
        return view;
    }

    private void showHelp() {
        showReport("Ayuda de enlaces", helpText);
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);

        TextView label = Ui.text(this, title, 11f, 0xFF22D3EE, true);
        label.setLetterSpacing(0.1f);
        label.setPadding(0, dpi(12), 0, dpi(2));
        holder.addView(label);

        TextView sub = Ui.text(this, subtitle, 10.5f, RogStyle.TEXT_DIM, false);
        sub.setPadding(0, 0, 0, dpi(2));
        holder.addView(sub);
        return holder;
    }

    private ArrayList<CatalogStore.Repo> presetRepos() {
        ArrayList<CatalogStore.Repo> stored = CatalogStore.list(this);
        ArrayList<CatalogStore.Repo> out = new ArrayList<CatalogStore.Repo>();
        ArrayList<Providers.Preset> presets = Providers.all();
        for (int p = 0; p < presets.size(); p++) {
            Providers.Preset preset = presets.get(p);
            CatalogStore.Repo match = null;
            for (int i = 0; i < stored.size(); i++) {
                if (stored.get(i).id.equals(preset.repoId())) {
                    match = stored.get(i);
                    break;
                }
            }
            if (match == null) {
                match = new CatalogStore.Repo();
                match.id = preset.repoId();
                match.name = preset.name;
                match.preset = true;
                match.enabled = false;
            }
            out.add(match);
        }
        return out;
    }

    private ArrayList<CatalogStore.Repo> customRepos() {
        ArrayList<CatalogStore.Repo> stored = CatalogStore.list(this);
        ArrayList<CatalogStore.Repo> out = new ArrayList<CatalogStore.Repo>();
        for (int i = 0; i < stored.size(); i++) {
            CatalogStore.Repo repo = stored.get(i);
            if (repo == null || repo.preset) continue;
            if (CatalogStore.DEMO_ID.equals(repo.id)) continue;
            // Las listas Xtream tienen su propia pantalla (servidor, usuario y
            // clave); aquí solo se administran las direcciones de catálogo.
            if (repo.isXtream()) continue;
            out.add(repo);
        }
        return out;
    }

    /**
     * Tarjeta de acceso a las listas Xtream: aquí solo se informa de cuántas hay y
     * se abre su pantalla propia, porque necesitan tres datos (servidor, usuario y
     * clave) que se piden en un formulario aparte.
     */
    private View xtreamCard() {
        LinearLayout card = card();

        TextView title = Ui.text(this, "LISTAS XTREAM", 12.5f, RogStyle.TEXT, true);
        title.setLetterSpacing(0.04f);
        card.addView(title);

        int count = CatalogStore.xtreamRepos(this).size();
        TextView state = Ui.text(this, count == 0
                ? "Todavía no has añadido ninguna lista Xtream."
                : (count == 1 ? "Tienes 1 lista Xtream guardada." : "Tienes " + count
                + " listas Xtream guardadas."), 10.5f, count == 0 ? RogStyle.TEXT_DIM : Ui.OK,
                true);
        state.setPadding(0, dpi(4), 0, 0);
        card.addView(state);

        TextView detail = Ui.text(this, "Con una lista Xtream (servidor, usuario y contraseña) la "
                + "app pregunta a su API solo por las películas y las series (MKV, MP4, AVI y demás "
                + "formatos de vídeo) y las organiza en PELÍCULAS, sin descargar la lista completa. "
                + "Los episodios de cada serie se cargan al abrirla. Se administran en su propia "
                + "pantalla.", 10.5f, RogStyle.TEXT_DIM, false);
        detail.setLineSpacing(0f, 1.12f);
        detail.setPadding(0, dpi(4), 0, 0);
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(7);
        card.addView(actions, actionsParams);

        actions.addView(button("ADMINISTRAR LISTAS XTREAM", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new android.content.Intent(RepoActivity.this,
                                    XtreamActivity.class));
                        } catch (Throwable t) {
                            showResult("No se pudo abrir la pantalla de listas Xtream.");
                        }
                    }
                }), gap(0));

        return card;
    }

    private View demoCard() {
        LinearLayout card = card();
        TextView title = Ui.text(this, "Catálogo de demostración (incluido en la app)", 12.5f,
                RogStyle.TEXT, true);
        card.addView(title);

        boolean visible = MoviesPrefs.showDemo(this);
        TextView state = Ui.text(this, visible
                ? "Ahora VISIBLE en la cuadrícula de películas."
                : "Ahora OCULTO: solo se ven los títulos de tus direcciones.",
                10.5f, visible ? Ui.WARN : Ui.OK, true);
        state.setPadding(0, dpi(4), 0, 0);
        card.addView(state);

        TextView detail = Ui.text(this, "Enlaces públicos de prueba (Apple, Google, Mux, "
                + "Akamai). Sus datos siguen dentro de la app, pero de entrada no se enseñan "
                + "para no aparentar contenido que no es tuyo. Se puede encender o apagar "
                + "cuando quieras.", 10.5f, RogStyle.TEXT_DIM, false);
        detail.setLineSpacing(0f, 1.1f);
        detail.setPadding(0, dpi(4), 0, 0);
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(7);
        card.addView(actions, actionsParams);

        actions.addView(button(visible ? "OCULTAR EL CATÁLOGO DE PRUEBA"
                        : "MOSTRAR EL CATÁLOGO DE PRUEBA", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean now = MoviesPrefs.showDemo(RepoActivity.this);
                        MoviesPrefs.setShowDemo(RepoActivity.this, !now);
                        Diag.add("Películas · catálogo de demostración "
                                + (!now ? "visible" : "oculto"));
                        showResult(!now ? "Catálogo de demostración visible."
                                : "Catálogo de demostración oculto.");
                        rebuildList();
                    }
                }), gap(0));

        actions.addView(button("AJUSTES DEL MÓDULO", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new android.content.Intent(RepoActivity.this,
                                    MoviesSettingsActivity.class));
                        } catch (Throwable t) {
                            showResult("No se pudieron abrir los ajustes del módulo.");
                        }
                    }
                }), gap(8));

        return card;
    }

    /** Ficha de un proveedor integrado, con su hueco de dirección y sus botones. */
    private View presetCard(final CatalogStore.Repo repo, final Providers.Preset preset) {
        LinearLayout card = card();
        if (preset != null) card.setTag("preset:" + preset.id);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = Ui.text(this, preset == null ? "?" : preset.initial(), 16f,
                0xFFFFFFFF, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setColor(preset == null ? RogStyle.BLUE_BOX : preset.accent);
        badgeBg.setCornerRadius(dpi(8));
        badge.setBackground(badgeBg);
        head.addView(badge, new LinearLayout.LayoutParams(dpi(38), dpi(38)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dpi(10);
        head.addView(texts, textsParams);

        TextView title = Ui.text(this, repo.title(), 13f, RogStyle.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);

        if (preset != null && preset.blurb.length() > 0) {
            TextView blurb = Ui.text(this, preset.blurb, 10.5f, RogStyle.TEXT_DIM, false);
            texts.addView(blurb);
        }
        if (preset != null && preset.pluginLine().length() > 0) {
            TextView plugin = Ui.text(this, preset.pluginLine(), 9.5f, 0xFFE8A33D, false);
            plugin.setSingleLine(true);
            plugin.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(plugin);
        }
        card.addView(head);

        boolean missing = repo.needsUrl();
        boolean failed = repo.status != null
                && repo.status.toLowerCase(Locale.US).startsWith("error");
        String state;
        if (missing) {
            state = "Falta la dirección de este proveedor.";
        } else {
            state = repo.statusLine();
        }
        TextView status = Ui.text(this, state, 10.5f,
                missing ? Ui.WARN : (failed ? Ui.WARN : (repo.enabled ? Ui.OK : RogStyle.TEXT_DIM)),
                false);
        status.setLineSpacing(0f, 1.1f);
        status.setPadding(0, dpi(6), 0, 0);
        card.addView(status);

        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(preset == null ? "Dirección del catálogo"
                : preset.hint);
        field.setHintTextColor(0xFF8E8A90);
        field.setTextColor(RogStyle.TEXT);
        field.setTextSize(11.5f);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xFF1B1B28, 0x33FFFFFF, 6f));
        field.setPadding(dpi(10), dpi(7), dpi(10), dpi(7));
        field.setMinHeight(dpi(42));
        field.setText(repo.url == null ? "" : repo.url);
        field.setContentDescription("Dirección de " + repo.title());
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = dpi(7);
        card.addView(field, fieldParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(7);
        card.addView(actions, actionsParams);

        actions.addView(button("GUARDAR Y DESCARGAR", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        savePresetUrl(repo, field);
                    }
                }), gap(0));

        if (!missing) {
            actions.addView(button(repo.enabled ? "DESACTIVAR" : "ACTIVAR", 0xFF2B2B40,
                    RogStyle.TEXT, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            CatalogStore.setEnabled(RepoActivity.this, repo.id, !repo.enabled);
                            showResult((repo.enabled ? "Desactivado: " : "Activado: ") + repo.title());
                            rebuildList();
                        }
                    }), gap(8));

            actions.addView(button("ACTUALIZAR", 0xFF2B2B40, RogStyle.TEXT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            updateOne(repo);
                        }
                    }), gap(8));
        }

        actions.addView(button("RESTABLECER", 0xFF5A1A16, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CatalogStore.resetPreset(RepoActivity.this, repo.id);
                        showResult("Restablecido: " + repo.title());
                        rebuildList();
                    }
                }), gap(8));

        return card;
    }

    /** Ficha de un complemento .cs3 adjunto: qué es y qué se puede hacer con él. */
    private View cs3Card(final Cs3Audit.Entry entry) {
        LinearLayout card = card();

        TextView title = Ui.text(this, entry.titleLine(), 12.5f, RogStyle.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        TextView verdict = Ui.text(this, entry.sizeText() + " · " + entry.verdict, 10.5f,
                Ui.WARN, false);
        verdict.setPadding(0, dpi(4), 0, 0);
        card.addView(verdict);

        TextView detail = Ui.text(this, entry.detail, 10.5f, RogStyle.TEXT_DIM, false);
        detail.setLineSpacing(0f, 1.12f);
        detail.setPadding(0, dpi(6), 0, 0);
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(7);
        card.addView(actions, actionsParams);

        actions.addView(button("QUÉ SE PUEDE REUTILIZAR", 0xFF2B2B40, RogStyle.TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showReport(entry.provider,
                                Cs3Audit.reportFor(entry.presetId));
                    }
                }), gap(0));

        final Providers.Preset preset = Providers.find(entry.presetId);
        if (preset != null) {
            actions.addView(button("IR A SU FICHA", 0xFF2B2B40, RogStyle.TEXT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openPreset(preset);
                        }
                    }), gap(8));
        }

        return card;
    }

    /** Tarjeta con el informe completo de los cuatro complementos. */
    private View fullReportCard() {
        LinearLayout card = card();

        TextView title = Ui.text(this, "ANÁLISIS DE LOS CUATRO COMPLEMENTOS", 12.5f,
                RogStyle.TEXT, true);
        card.addView(title);

        TextView detail = Ui.text(this, "Se aprovecha su idea de repositorio, los datos de cada "
                + "título y que sus enlaces son HLS o MP4 (el reproductor interno ya los abre). "
                + "Habría que rehacer en Java el acceso a cada página. El archivo .cs3 en sí no se "
                + "puede ejecutar dentro de LENY TV.", 10.5f, RogStyle.TEXT_DIM, false);
        detail.setLineSpacing(0f, 1.12f);
        detail.setPadding(0, dpi(6), 0, 0);
        card.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(7);
        card.addView(actions, actionsParams);

        actions.addView(button("VER ANÁLISIS COMPLETO", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showReport("Complementos .cs3 adjuntos", Cs3Audit.fullReport());
                    }
                }), gap(0));

        return card;
    }

    /** Enseña un informe de compatibilidad en un cuadro de diálogo. */
    private void showReport(String title, String body) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(title);
            builder.setMessage(body == null ? "" : body);
            builder.setPositiveButton("CERRAR", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            builder.show();
        } catch (Throwable t) {
            showResult("No se pudo abrir el informe: " + Net.describe(t));
        }
    }

    /** Lleva la vista hasta la ficha de un proveedor integrado. */
    private void openPreset(Providers.Preset preset) {
        try {
            if (!TAB_PRESETS.equals(tab)) {
                tab = TAB_PRESETS;
                fillTabs();
                rebuildList();
            }
            View target = listBox == null ? null : listBox.findViewWithTag("preset:" + preset.id);
            if (target != null) {
                target.requestFocus();
            }
            showResult(preset.name + ": pega su dirección en su ficha y pulsa GUARDAR Y DESCARGAR.");
        } catch (Throwable t) {
            showResult("Busca «" + preset.name + "» en PROVEEDORES.");
        }
    }

    private View repoCard(final CatalogStore.Repo repo) {
        LinearLayout card = card();

        TextView title = Ui.text(this, repo.title(), 12.5f, RogStyle.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        TextView url = Ui.text(this, repo.url, 10f, RogStyle.TEXT_DIM, false);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        url.setPadding(0, dpi(2), 0, 0);
        card.addView(url);

        boolean failed = repo.status != null
                && repo.status.toLowerCase(Locale.US).startsWith("error");
        TextView status = Ui.text(this, repo.statusLine(), 10.5f,
                failed ? Ui.WARN : (repo.enabled ? Ui.OK : RogStyle.TEXT_DIM), false);
        status.setLineSpacing(0f, 1.1f);
        status.setPadding(0, dpi(5), 0, 0);
        card.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dpi(8);
        card.addView(actions, actionsParams);

        actions.addView(button("ACTUALIZAR", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        updateOne(repo);
                    }
                }), gap(0));

        actions.addView(button(repo.enabled ? "DESACTIVAR" : "ACTIVAR", 0xFF2B2B40,
                RogStyle.TEXT, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CatalogStore.setEnabled(RepoActivity.this, repo.id, !repo.enabled);
                        showResult((repo.enabled ? "Desactivado: " : "Activado: ") + repo.title());
                        rebuildList();
                    }
                }), gap(8));

        actions.addView(button("QUITAR", 0xFF5A1A16, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRemove(repo);
            }
        }), gap(8));

        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(0xB3150E12);
        background.setCornerRadius(dpi(8));
        background.setStroke(Math.max(1, dpi(1)), 0x33FFFFFF);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpi(8);
        card.setLayoutParams(params);
        return card;
    }

    // --------------------------------------------------------------- acciones

    private void savePresetUrl(final CatalogStore.Repo repo, EditText field) {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        String raw = field == null ? "" : field.getText().toString();
        final String url;
        try {
            url = CatalogLoader.normalizeUrl(raw);
        } catch (CatalogLoader.CatalogError error) {
            showResult(error.getMessage());
            toast(error.getMessage());
            return;
        }
        if (RepoProbe.isPluginUrl(url)) {
            showResult("Esa dirección es un complemento .cs3, no el catálogo de " + repo.title()
                    + ". Los complementos de CloudStream no se ejecutan aquí: pega la dirección "
                    + "de su catálogo en JSON.");
            toast("Ese enlace es un .cs3, no un catálogo.");
            return;
        }
        CatalogStore.Repo updated = CatalogStore.setUrl(this, repo.id, url);
        if (updated == null) {
            showResult("No se pudo guardar la dirección. Vuelve a abrir esta pantalla.");
            return;
        }
        CatalogStore.setEnabled(this, repo.id, true);
        rebuildList();
        showResult("Descargando el catálogo de " + repo.title() + " …");
        fetch(updated, "Guardado");
    }

    private void addRepository() {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        String raw = urlField == null ? "" : urlField.getText().toString();
        final String url;
        try {
            url = CatalogLoader.normalizeUrl(raw);
        } catch (CatalogLoader.CatalogError error) {
            showResult(error.getMessage());
            toast(error.getMessage());
            return;
        }
        if (RepoProbe.isPluginUrl(url)) {
            showResult("Esa dirección es un complemento .cs3 de CloudStream, no un catálogo: "
                    + "LENY TV no puede ejecutarlo. Pega la dirección de un catálogo en JSON.");
            toast("Ese enlace es un .cs3, no un catálogo.");
            return;
        }
        // addAny reconoce las listas Xtream y las guarda como tales: así se leen
        // por la API (solo películas y series) en vez de bajar la lista completa.
        final CatalogStore.Repo repo = CatalogStore.addAny(this, url);
        if (urlField != null) urlField.setText("");
        rebuildList();
        if (repo != null && repo.isXtream()) {
            showResult("Lista Xtream reconocida: se leen solo sus películas y series por la API "
                    + "(sin descargar la lista completa)…");
            Diag.add("Repositorios · lista Xtream añadida (lectura por API)");
        } else {
            showResult("Descargando " + CatalogLoader.hostOf(url) + " …");
        }
        fetch(repo, "Añadido");
    }

    private void updateOne(CatalogStore.Repo repo) {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        if (repo.needsUrl()) {
            toast("Ese proveedor todavía no tiene dirección.");
            return;
        }
        fetch(repo, "Actualizado");
    }

    private void updateAll() {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        ArrayList<CatalogStore.Repo> repos = CatalogStore.list(this);
        ArrayList<CatalogStore.Repo> ready = new ArrayList<CatalogStore.Repo>();
        for (int i = 0; i < repos.size(); i++) {
            CatalogStore.Repo repo = repos.get(i);
            if (repo != null && !repo.needsUrl()) ready.add(repo);
        }
        if (ready.isEmpty()) {
            showResult("No hay repositorios con dirección que actualizar.");
            toast("No hay repositorios con dirección.");
            return;
        }
        queue.clear();
        queue.addAll(ready);
        showResult("Actualizando " + queue.size()
                + (queue.size() == 1 ? " repositorio…" : " repositorios…"));
        nextInQueue();
    }

    /** Sigue con el siguiente repositorio pendiente de ACTUALIZAR TODO. */
    private void nextInQueue() {
        if (queue.isEmpty()) return;
        CatalogStore.Repo next = queue.remove(0);
        fetch(next, "Actualizado");
    }

    /**
     * Analiza la dirección escrita arriba y explica qué contiene. No guarda nada:
     * es una comprobación para saber si el enlace sirve antes de añadirlo.
     */
    private void checkAddress() {
        if (working) {
            toast("Espera a que termine la comprobación anterior.");
            return;
        }
        final String url;
        try {
            url = CatalogLoader.normalizeUrl(urlField == null ? "" : urlField.getText().toString());
        } catch (CatalogLoader.CatalogError error) {
            showResult(error.getMessage());
            toast(error.getMessage());
            return;
        }
        working = true;
        showResult("Comprobando " + url + " …");
        showProbeMessage("Comprobando la dirección…", RogStyle.TEXT_DIM);
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final RepoProbe.Result result = RepoProbe.inspect(RepoActivity.this, url);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            working = false;
                            showResult("Comprobación terminada. " + result.headline);
                            renderProbe(result);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            working = false;
            showResult("No se pudo iniciar la comprobación: " + Net.describe(t));
        }
    }

    /** Pinta el resultado de COMPROBAR DIRECCIÓN. */
    private void renderProbe(final RepoProbe.Result result) {
        if (probeBox == null) return;
        probeBox.removeAllViews();
        probeBox.setVisibility(View.VISIBLE);

        TextView headline = Ui.text(this, result.headline, 12f,
                result.usable ? Ui.OK : Ui.WARN, true);
        headline.setLineSpacing(0f, 1.1f);
        probeBox.addView(headline);

        for (int i = 0; i < result.lines.size() && i < 4; i++) {
            TextView line = Ui.text(this, result.lines.get(i), 10.5f, RogStyle.TEXT_DIM, false);
            line.setLineSpacing(0f, 1.1f);
            line.setPadding(0, dpi(5), 0, 0);
            probeBox.addView(line);
        }

        if (!result.plugins.isEmpty()) {
            TextView label = Ui.text(this, "PROVEEDORES ENCONTRADOS (" + result.plugins.size()
                    + ")", 10f, 0xFF22D3EE, true);
            label.setLetterSpacing(0.08f);
            label.setPadding(0, dpi(8), 0, dpi(3));
            probeBox.addView(label);
            for (int i = 0; i < result.plugins.size() && i < 12; i++) {
                RepoProbe.Plugin plugin = result.plugins.get(i);
                StringBuilder text = new StringBuilder("• ").append(plugin.name);
                if (plugin.detail.length() > 0) text.append(" · ").append(plugin.detail);
                TextView row = Ui.text(this, text.toString(), 10.5f, RogStyle.TEXT, false);
                row.setPadding(0, dpi(2), 0, 0);
                probeBox.addView(row);
            }
            if (result.plugins.size() > 12) {
                TextView more = Ui.text(this, "y " + (result.plugins.size() - 12) + " más",
                        10f, RogStyle.TEXT_DIM, false);
                more.setPadding(0, dpi(3), 0, 0);
                probeBox.addView(more);
            }
        }

        if (result.usable) {
            probeBox.addView(button("AÑADIR ESTA DIRECCIÓN", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            addProbed(result.address);
                        }
                    }), gap(0));
        }

        probeBox.addView(button("OCULTAR", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeBox.setVisibility(View.GONE);
            }
        }), gap(0));
    }

    private void showProbeMessage(String message, int color) {
        if (probeBox == null) return;
        probeBox.removeAllViews();
        probeBox.setVisibility(View.VISIBLE);
        probeBox.addView(Ui.text(this, message, 11.5f, color, false));
    }

    /** Añade la dirección comprobada a MIS REPOSITORIOS y la descarga. */
    private void addProbed(String url) {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        if (url == null || url.trim().length() == 0) return;
        CatalogStore.Repo repo = CatalogStore.addAny(this, url.trim());
        rebuildList();
        showResult(repo != null && repo.isXtream()
                ? "Añadida la lista Xtream: se leen solo sus películas y series por la API…"
                : "Añadido: " + url);
        fetch(repo, "Añadido");
    }

    /** Descarga un repositorio y deja el resultado escrito en la pantalla. */
    private void fetch(final CatalogStore.Repo repo, final String verb) {
        working = true;
        final long started = System.currentTimeMillis();
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    String problem = null;
                    CatalogLoader.Loaded loaded = null;
                    try {
                        // Enruta según el tipo de fuente: dirección de catálogo JSON
                        // o lista Xtream (servidor + usuario + clave).
                        loaded = CatalogLoader.fetchRepo(RepoActivity.this, repo);
                    } catch (Throwable t) {
                        problem = t.getMessage() == null ? Net.describe(t) : t.getMessage();
                    }
                    final CatalogLoader.Loaded result = loaded;
                    final String failure = problem;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                            working = false;
                            if (result != null) {
                                CatalogStore.setLoaded(RepoActivity.this, repo.id, result.name,
                                        result.itemsJson, result.count);
                                if (result.note != null && result.note.length() > 0) {
                                    CatalogStore.setStatus(RepoActivity.this, repo.id, result.note);
                                }
                                showResult(verb + " correctamente: " + result.name + " · "
                                        + result.count + (result.count == 1 ? " título" : " títulos")
                                        + (result.note != null && result.note.length() > 0
                                        ? "\n" + result.note : "")
                                        + " · " + seconds(started) + " s");
                                toast(verb + ": " + result.count + " títulos");
                            } else {
                                CatalogStore.setStatus(RepoActivity.this, repo.id, "Error: " + failure);
                                showResult("No se pudo leer el repositorio.\n" + failure);
                                toast("No se pudo leer el repositorio.");
                            }
                            rebuildList();
                            if (!queue.isEmpty()) nextInQueue();
                            } catch (Throwable error) {
                                working = false;
                                Diag.add("Repositorios · fallo al mostrar el resultado: "
                                        + Net.describe(error));
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            working = false;
            showResult("No se pudo iniciar la descarga: " + Net.describe(t));
        }
    }

    private String seconds(long started) {
        long elapsed = Math.max(0, System.currentTimeMillis() - started);
        return String.format(Locale.US, "%.1f", elapsed / 1000.0);
    }

    private void confirmRemove(final CatalogStore.Repo repo) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Quitar repositorio");
            builder.setMessage("¿Quitar «" + repo.title() + "» y su catálogo guardado?");
            builder.setPositiveButton("Quitar", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    CatalogStore.remove(RepoActivity.this, repo.id);
                    showResult("Quitado: " + repo.title());
                    rebuildList();
                }
            });
            builder.setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            builder.show();
        } catch (Throwable t) {
            CatalogStore.remove(this, repo.id);
            showResult("Quitado: " + repo.title());
            rebuildList();
        }
    }

    private void showResult(String message) {
        if (resultView == null) return;
        String text = message == null ? "" : message;
        resultView.setText(text);
        resultView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private int dpi(float value) {
        return Ui.dpi(this, value);
    }
}

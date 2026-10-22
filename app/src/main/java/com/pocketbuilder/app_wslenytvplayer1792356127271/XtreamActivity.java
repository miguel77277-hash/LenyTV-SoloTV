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

import com.pocketbuilder.app_wslenytvplayer1792356127271.Immersion;
import com.pocketbuilder.app_wslenytvplayer1792356127271.movies.MoviesGuard;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LISTAS XTREAM de LENY TV (pantalla propia del módulo de películas).
 *
 * Sirve para añadir una lista IPTV de DOS maneras, las dos disponibles:
 *
 *   1. Pegando la URL XTREAM COMPLETA en un solo campo (por ejemplo
 *      http://midominio.com:8080/get.php?username=ANA&password=1234&type=m3u_plus).
 *      La app reconoce sola el servidor, el usuario y la contraseña.
 *   2. Escribiendo servidor, usuario y contraseña por separado.
 *
 * Al pulsar AÑADIR Y DESCARGAR, la app:
 *
 *   1. Comprueba la cuenta contra el servidor (si la clave es incorrecta o la
 *      cuenta está caducada, lo dice tal cual, sin lista vacía sin explicación).
 *   2. Lee las categorías de la lista.
 *   3. Lee sus películas (VOD) y sus SERIES, se queda con las que son vídeo de
 *      verdad (MKV, MP4, AVI, MOV, WebM, TS, M3U8…) y guarda su cartel, año,
 *      nota y categoría.
 *
 * Las temporadas y los episodios de cada serie necesitan una consulta al
 * servidor POR CADA SERIE, así que no se piden aquí: se piden cuando el usuario
 * abre esa serie en PELÍCULAS. Así la lista se abre en segundos aunque tenga
 * cientos de series.
 *
 * Esas películas y series entran en la MISMA cuadrícula de PELÍCULAS que el
 * resto de fuentes y se reproducen con el reproductor interno del módulo. No hay
 * una pantalla de reproducción distinta para Xtream.
 *
 * Esta pantalla vive en el proceso del módulo (":movies"): un fallo aquí queda
 * en el registro del módulo y no cierra la televisión.
 */
public final class XtreamActivity extends Activity {

    private EditText urlField;
    private EditText serverField;
    private EditText userField;
    private EditText passField;
    private TextView resultView;
    private TextView spinnerText;
    private LinearLayout listBox;
    private boolean working = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MoviesGuard.install(this);
        try {
            buildUi();
        } catch (Throwable t) {
            Diag.add("Xtream · no se pudo dibujar la pantalla: " + Net.describe(t));
            showEmergencyScreen();
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

    // ------------------------------------------------------------------- dibujo

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        RogStyle.applyBackdrop(this, root);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // ----------------------------------------------------------- cabecera
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(RogStyle.bar(this));
        bar.setPadding(dpi(10), dpi(5), dpi(8), dpi(5));

        TextView brand = Ui.text(this, "LENY TV", 15f, RogStyle.TEXT, true);
        bar.addView(brand);

        TextView tag = Ui.text(this, "LISTAS XTREAM", 10f, 0xFF22D3EE, true);
        tag.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagParams.leftMargin = dpi(10);
        bar.addView(tag, tagParams);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        spinnerText = Ui.text(this, "", 10.5f, 0xFFE8A33D, true);
        spinnerText.setSingleLine(true);
        bar.addView(spinnerText, gap(10));

        bar.addView(button("VOLVER", 0xFF2B2B40, RogStyle.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), gap(8));
        column.addView(bar);

        // -------------------------------------------------------------- formulario
        LinearLayout form = card();

        TextView formTitle = Ui.text(this, "AÑADIR UNA LISTA XTREAM", 12.5f, RogStyle.TEXT, true);
        formTitle.setLetterSpacing(0.04f);
        form.addView(formTitle);

        TextView formHint = Ui.text(this, "Pega la URL completa que te dio tu proveedor o escribe "
                + "los tres datos por separado: las dos formas sirven. Los datos se guardan solo "
                + "en este teléfono.", 10.5f, RogStyle.TEXT_DIM, false);
        formHint.setLineSpacing(0f, 1.1f);
        formHint.setPadding(0, dpi(3), 0, dpi(2));
        form.addView(formHint);

        // ---- una sola URL con todo dentro
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        urlParams.topMargin = dpi(6);
        form.addView(urlRow, urlParams);

        urlField = field("URL Xtream completa (…/get.php?username=USUARIO&password=CLAVE)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        urlField.setContentDescription("URL Xtream completa");
        urlRow.addView(urlField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        urlRow.addView(button("RECONOCER DATOS", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyUrl(true);
                    }
                }), gap(8));

        LinearLayout fieldsRow = new LinearLayout(this);
        fieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        fieldsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fieldsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldsParams.topMargin = dpi(7);
        form.addView(fieldsRow, fieldsParams);

        serverField = field("Servidor (http://midominio.com:8080)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        serverField.setContentDescription("Servidor de la lista Xtream");
        fieldsRow.addView(serverField, weight(0, 1.7f));

        userField = field("Usuario", InputType.TYPE_CLASS_TEXT, false);
        userField.setContentDescription("Usuario de la lista Xtream");
        fieldsRow.addView(userField, weight(8, 1f));

        passField = field("Contraseña", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        passField.setContentDescription("Contraseña de la lista Xtream");
        fieldsRow.addView(passField, weight(8, 1f));

        TextView add = button("AÑADIR Y DESCARGAR", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addList();
                    }
                });
        fieldsRow.addView(add, gap(8));

        resultView = Ui.text(this, "", 11f, RogStyle.TEXT_DIM, false);
        resultView.setLineSpacing(0f, 1.12f);
        resultView.setPadding(0, dpi(6), 0, 0);
        resultView.setVisibility(View.GONE);
        form.addView(resultView);

        LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        formParams.topMargin = dpi(8);
        column.addView(form, formParams);

        // ------------------------------------------------------------------ lista
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dpi(6);
        column.addView(scroll, scrollParams);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private EditText field(String hint, int inputType, boolean password) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setHint(hint);
        view.setHintTextColor(0xFF8E8A90);
        view.setTextColor(RogStyle.TEXT);
        view.setTextSize(11.5f);
        view.setInputType(inputType);
        if (password) {
            view.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        view.setBackground(Ui.buttonBg(this, 0xCC120E12, 0xFF1B1B28, 0x33FFFFFF, 6f));
        view.setPadding(dpi(10), dpi(7), dpi(10), dpi(7));
        view.setMinHeight(dpi(44));
        return view;
    }

    private LinearLayout.LayoutParams weight(int leftDp, float value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, value);
        params.leftMargin = dpi(leftDp);
        return params;
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
        return card;
    }

    // -------------------------------------------------------------------- lista

    private void rebuildList() {
        if (listBox == null) return;
        listBox.removeAllViews();

        TextView title = Ui.text(this, "MIS LISTAS XTREAM", 11f, 0xFF22D3EE, true);
        title.setLetterSpacing(0.1f);
        title.setPadding(0, dpi(10), 0, dpi(2));
        listBox.addView(title);

        ArrayList<CatalogStore.Repo> lists = CatalogStore.xtreamRepos(this);
        if (lists.isEmpty()) {
            TextView empty = Ui.text(this, "Todavía no has añadido ninguna lista Xtream.\n"
                    + "Pega arriba la URL completa (y pulsa RECONOCER DATOS) o escribe el servidor, "
                    + "el usuario y la contraseña, y pulsa AÑADIR Y DESCARGAR.\n"
                    + "Sus películas y series aparecerán en la cuadrícula de PELÍCULAS; los "
                    + "episodios de cada serie se cargan al abrirla.", 11.5f,
                    RogStyle.TEXT_DIM, false);
            empty.setLineSpacing(0f, 1.15f);
            empty.setPadding(dpi(10), dpi(12), dpi(10), dpi(12));
            empty.setBackground(Ui.buttonBg(this, 0x8C150E12, 0x8C150E12, 0x22FFFFFF, 8f));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyParams.topMargin = dpi(8);
            listBox.addView(empty, emptyParams);
        } else {
            for (int i = 0; i < lists.size(); i++) {
                View card = listCard(lists.get(i));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.topMargin = dpi(8);
                listBox.addView(card, params);
            }
        }

        TextView help = Ui.text(this, "CÓMO FUNCIONA\n"
                + "· Dos formas de añadirla: pegar la URL Xtream completa y pulsar RECONOCER "
                + "DATOS, o escribir servidor, usuario y contraseña por separado.\n"
                + "· Se leen las películas y las series por la API de Xtream, no la lista "
                + "completa: así una lista enorme no bloquea el teléfono. Los canales en directo "
                + "pertenecen a la pantalla de televisión y ahí no se toca nada.\n"
                + "· Los episodios de cada serie se piden al servidor al abrir esa serie en "
                + "PELÍCULAS.\n"
                + "· Se descartan las entradas que no son vídeo y se ocultan los títulos "
                + "marcados como adultos.\n"
                + "· Que un título aparezca no garantiza que el teléfono pueda con su códec: si "
                + "falla, el reproductor lo explica.", 10f, RogStyle.TEXT_DIM, false);
        help.setLineSpacing(0f, 1.15f);
        help.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        help.setBackground(Ui.buttonBg(this, 0x8C150E12, 0x8C150E12, 0x22FFFFFF, 8f));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        helpParams.topMargin = dpi(10);
        listBox.addView(help, helpParams);
    }

    private View listCard(final CatalogStore.Repo repo) {
        LinearLayout card = card();

        TextView name = Ui.text(this, repo.title(), 12.5f, RogStyle.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        TextView server = Ui.text(this, repo.server + " · usuario: " + repo.user
                + " · clave: " + mask(repo.pass), 10f, RogStyle.TEXT_DIM, false);
        server.setSingleLine(true);
        server.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        server.setPadding(0, dpi(2), 0, 0);
        card.addView(server);

        boolean failed = repo.status != null
                && repo.status.toLowerCase(Locale.US).startsWith("error");
        String state = repo.statusLine();
        if (repo.needsUrl()) state = "Faltan datos de esta lista. Vuelve a escribirla arriba.";
        TextView status = Ui.text(this, state, 10.5f,
                failed || repo.needsUrl() ? Ui.WARN : (repo.enabled ? Ui.OK : RogStyle.TEXT_DIM),
                false);
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
                        refresh(repo);
                    }
                }), gap(0));

        actions.addView(button(repo.enabled ? "DESACTIVAR" : "ACTIVAR", 0xFF2B2B40,
                RogStyle.TEXT, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CatalogStore.setEnabled(XtreamActivity.this, repo.id, !repo.enabled);
                        showResult((repo.enabled ? "Desactivada: " : "Activada: ") + repo.title(),
                                RogStyle.TEXT_DIM);
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

    private String mask(String value) {
        if (value == null || value.length() == 0) return "(sin clave)";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(4, value.length()); i++) out.append('•');
        return out.toString();
    }

    // ----------------------------------------------------------------- acciones

    /**
     * Reconoce servidor, usuario y contraseña dentro de la URL completa y los
     * escribe en los tres campos. Devuelve falso (y explica el motivo) si la
     * dirección no trae lo necesario: así no se guarda una lista a medias.
     */
    private boolean applyUrl(boolean tellUser) {
        String raw = urlField == null ? "" : urlField.getText().toString();
        if (raw.trim().length() == 0) {
            if (tellUser) {
                showResult("Pega arriba la URL Xtream completa, o escribe servidor, usuario y "
                        + "contraseña por separado.", Ui.WARN);
                toast("Falta la URL o los tres datos.");
            }
            return false;
        }
        XtreamClient.Parsed parsed;
        try {
            parsed = XtreamClient.parseFullUrl(raw);
        } catch (Throwable t) {
            parsed = null;
        }
        if (parsed == null || !parsed.ok()) {
            String message = parsed == null || parsed.problem.length() == 0
                    ? "No se pudo reconocer esa URL Xtream. Comprueba que esté completa."
                    : parsed.problem;
            showResult(message, Ui.WARN);
            toast(message);
            Diag.add("Xtream · URL no reconocida: " + message);
            return false;
        }
        if (serverField != null) serverField.setText(parsed.server);
        if (userField != null) userField.setText(parsed.user);
        if (passField != null) passField.setText(parsed.pass);
        showResult(parsed.describe() + " · revisa los datos y pulsa AÑADIR Y DESCARGAR.", Ui.OK);
        toast("Datos reconocidos de la URL.");
        return true;
    }

    private void addList() {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        // Si hay una URL completa escrita arriba, se reconocen los datos de ahí
        // (servidor, usuario y contraseña) antes de seguir.
        String rawUrl = urlField == null ? "" : urlField.getText().toString().trim();
        if (rawUrl.length() > 0 && !applyUrl(false)) return;
        String rawServer = serverField == null ? "" : serverField.getText().toString();
        String rawUser = userField == null ? "" : userField.getText().toString();
        String rawPass = passField == null ? "" : passField.getText().toString();
        final String server;
        try {
            server = XtreamClient.normalizeServer(rawServer);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? "Revisa el servidor." : t.getMessage();
            showResult(message, Ui.WARN);
            toast(message);
            return;
        }
        final String user = rawUser == null ? "" : rawUser.trim();
        final String pass = rawPass == null ? "" : rawPass.trim();
        if (user.length() == 0) {
            showResult("Escribe el usuario de la lista Xtream.", Ui.WARN);
            toast("Falta el usuario.");
            return;
        }
        if (pass.length() == 0) {
            showResult("Escribe la contraseña de la lista Xtream.", Ui.WARN);
            toast("Falta la contraseña.");
            return;
        }

        CatalogStore.Repo repo = CatalogStore.addXtream(this, server, user, pass);
        CatalogStore.setEnabled(this, repo.id, true);
        showResult("Conectando con " + CatalogStore.Repo.hostOf(server) + " …", RogStyle.TEXT_DIM);
        rebuildList();
        download(repo, false);
    }

    private void refresh(CatalogStore.Repo repo) {
        if (working) {
            toast("Espera a que termine la descarga anterior.");
            return;
        }
        if (repo.needsUrl()) {
            toast("A esa lista le faltan datos: bórrala y vuelve a añadirla.");
            return;
        }
        download(repo, true);
    }

    /**
     * Descarga una lista Xtream. Todo el trabajo de red va en el hilo de fondo y
     * el resultado se pinta en el hilo principal, protegido: si algo falla al
     * pintar, queda en el registro del módulo y la pantalla sigue viva.
     */
    private void download(final CatalogStore.Repo repo, final boolean fromCard) {
        working = true;
        setBusy(true, (fromCard ? "Actualizando " : "Descargando ") + repo.title() + " …");
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    XtreamClient.Loaded loaded = null;
                    String problem = null;
                    try {
                        loaded = XtreamClient.load(XtreamActivity.this, repo.server, repo.user,
                                repo.pass);
                    } catch (Throwable t) {
                        problem = t.getMessage() == null ? Net.describe(t) : t.getMessage();
                    }
                    final XtreamClient.Loaded result = loaded;
                    final String failure = problem;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                working = false;
                                setBusy(false, "");
                                if (result != null) {
                                    CatalogStore.setLoaded(XtreamActivity.this, repo.id,
                                            result.name, result.itemsJson, result.count);
                                    CatalogStore.setStatus(XtreamActivity.this, repo.id,
                                            result.note());
                                    String note = result.notes.isEmpty() ? ""
                                            : " " + result.notes.get(0);
                                    showResult("Listo: " + result.movies
                                            + (result.movies == 1 ? " película y " : " películas y ")
                                            + result.series + (result.series == 1 ? " serie"
                                            : " series") + " en PELÍCULAS. Los episodios de cada "
                                            + "serie se cargan al abrirla." + note, Ui.OK);
                                    toast(result.movies + " películas y " + result.series
                                            + " series añadidas");
                                    Diag.add("Xtream · lista leída por la API: " + result.movies
                                            + " películas y " + result.series + " series");
                                } else {
                                    CatalogStore.setStatus(XtreamActivity.this, repo.id,
                                            "Error: " + failure);
                                    showResult("No se pudo leer la lista. " + failure, Ui.WARN);
                                    toast("No se pudo leer la lista.");
                                    Diag.add("Xtream · error al leer la lista: " + failure);
                                }
                                rebuildList();
                            } catch (Throwable t) {
                                working = false;
                                setBusy(false, "");
                                Diag.add("Xtream · fallo al mostrar el resultado: "
                                        + Net.describe(t));
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            working = false;
            setBusy(false, "");
            showResult("No se pudo iniciar la descarga: " + Net.describe(t), Ui.WARN);
        }
    }

    private void setBusy(boolean busy, String message) {
        if (spinnerText != null) {
            spinnerText.setText(busy ? message : "");
            spinnerText.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
    }

    private void confirmRemove(final CatalogStore.Repo repo) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Quitar lista Xtream");
            builder.setMessage("¿Quitar «" + repo.title() + "» y sus "
                    + repo.itemCount + " títulos guardados?");
            builder.setPositiveButton("Quitar", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    CatalogStore.remove(XtreamActivity.this, repo.id);
                    showResult("Quitada: " + repo.title(), RogStyle.TEXT_DIM);
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
            showResult("Quitada: " + repo.title(), RogStyle.TEXT_DIM);
            rebuildList();
        }
    }

    private void showResult(String message, int color) {
        if (resultView == null) return;
        String text = message == null ? "" : message;
        resultView.setText(text);
        resultView.setTextColor(color);
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

    /** Último recurso si el dibujo falla: avisa y deja volver, sin cerrar la app. */
    private void showEmergencyScreen() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xFF08080F);
            box.setPadding(dpi(24), dpi(24), dpi(24), dpi(24));
            TextView message = Ui.text(this, "No se pudo dibujar la pantalla de listas Xtream.\n"
                    + "Vuelve a PELÍCULAS y prueba otra vez.", 14f, RogStyle.TEXT, false);
            message.setGravity(Gravity.CENTER);
            box.addView(message);
            TextView back = button("VOLVER", RogStyle.BLUE_BOX, 0xFFFFFFFF,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            finish();
                        }
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dpi(14);
            box.addView(back, params);
            setContentView(box);
        } catch (Throwable ignored) {
        }
    }
}

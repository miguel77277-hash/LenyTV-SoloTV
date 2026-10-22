package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.pocketbuilder.app_wslenytvplayer1792356127271.Immersion;

/**
 * AJUSTES DEL MÓDULO DE PELÍCULAS Y SERIES.
 *
 * Todo lo que hay en esta pantalla afecta SOLO a películas y series. La
 * televisión tiene sus propios ajustes en la pantalla de canales y no se toca
 * desde aquí.
 *
 * Se puede cambiar:
 *   - Cabeceras HTTP del módulo (User-Agent, Referer y cabeceras adicionales).
 *   - Modo de imagen del reproductor del módulo.
 *   - Si se enseña el catálogo de demostración (oculto por defecto).
 *   - Ver y borrar el REGISTRO del módulo (independiente del de TV).
 *   - Consultar la tabla de adaptadores de proveedor.
 */
public final class MoviesSettingsActivity extends Activity {

    private static final int BG = 0xFF08080F;
    private static final int TEXT = 0xFFF3F1F1;
    private static final int DIM = 0xFF9C97A0;
    private static final int BLUE = 0xFF1F5AA6;
    private static final int WARN = 0xFFFFC857;

    private EditText userAgentField;
    private EditText refererField;
    private EditText extraField;
    private Switch demoSwitch;
    private TextView statusText;
    private TextView logView;
    private LinearLayout aspectRow;
    private String selectedAspect = MoviesPrefs.ASPECT_FIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MoviesGuard.install(this);
        MoviesDiag.init(this);
        selectedAspect = MoviesPrefs.aspect(this);
        try {
            buildUi();
            loadValues();
        } catch (Throwable error) {
            MoviesDiag.add("Ajustes · no se pudo dibujar: " + MoviesGuard.describe(error));
            showFallback();
        }
        // Pantalla completa, igual que en la televisión: sin barra de estado ni
        // botones de navegación. Solo afecta a este módulo.
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

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dpi(14), dpi(10), dpi(14), dpi(16));
        scroll.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // ---- cabecera
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("AJUSTES DEL MÓDULO", 16f, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("VER REGISTRO", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        }));
        header.addView(button("VOLVER", BLUE, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), gap(8));
        column.addView(header);

        // Versión instalada, leída del propio APK: sirve para comprobar de un vistazo
        // que el teléfono tiene la última copia (por ejemplo, tras actualizar).
        column.addView(text("LENY TV Player " + installedVersion() + " · módulo de películas "
                + "y series independiente", 10.5f, 0xFF22D3EE, true));

        column.addView(note("Estos ajustes son solo de PELÍCULAS Y SERIES. La televisión "
                + "conserva los suyos (User-Agent, Referer, modo de imagen) y no cambia al "
                + "tocar nada de aquí.", 11f, DIM));

        // ---- cabeceras
        column.addView(label("CABECERAS HTTP DEL MÓDULO"));
        column.addView(note("Algunos servidores solo entregan el catálogo o el vídeo si la "
                + "petición lleva su User-Agent o su Referer. Se envían al descargar catálogos y "
                + "al reproducir en el reproductor del módulo.", 10.5f, DIM));

        column.addView(label("User-Agent"));
        userAgentField = field("(automático del módulo)", false);
        column.addView(userAgentField);

        column.addView(label("Referer"));
        refererField = field("(ninguno)", false);
        column.addView(refererField);

        column.addView(label("Cabeceras adicionales (una por línea: Nombre: valor)"));
        extraField = field("Cookie: ...\nOrigin: https://…", true);
        column.addView(extraField);

        // ---- modo de imagen
        column.addView(label("MODO DE IMAGEN DEL REPRODUCTOR"));
        aspectRow = new LinearLayout(this);
        aspectRow.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(aspectRow);
        rebuildAspectRow();

        // ---- demostración
        column.addView(label("CATÁLOGO DE DEMOSTRACIÓN"));
        demoSwitch = new Switch(this);
        demoSwitch.setText("Mostrar el catálogo de demostración");
        demoSwitch.setTextColor(TEXT);
        demoSwitch.setTextSize(11.5f);
        demoSwitch.setPadding(0, dpi(4), 0, dpi(4));
        demoSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                         boolean isChecked) {
                try {
                    MoviesPrefs.setShowDemo(MoviesSettingsActivity.this, isChecked);
                    MoviesDiag.add("Ajustes · catálogo de demostración "
                            + (isChecked ? "visible" : "oculto"));
                    status("Catálogo de demostración " + (isChecked ? "visible" : "oculto")
                            + ". Se aplica al volver a la lista de películas.");
                } catch (Throwable error) {
                    status("No se pudo guardar: " + MoviesGuard.describe(error));
                }
            }
        });
        column.addView(demoSwitch);
        column.addView(note("Está oculto de entrada. Sus datos de prueba siguen dentro de la app, "
                + "pero mientras esté apagado solo se ven los títulos de tus direcciones.", 10.5f,
                DIM));

        // ---- guardar
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.setPadding(0, dpi(12), 0, 0);
        saveRow.addView(button("GUARDAR", BLUE, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        }));
        saveRow.addView(button("BORRAR REGISTRO", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MoviesDiag.clear();
                MoviesDiag.add("Registro del módulo vaciado a petición del usuario.");
                MoviesDiag.save(MoviesSettingsActivity.this);
                refreshLog();
                status("Registro del módulo borrado.");
            }
        }), gap(8));
        column.addView(saveRow);

        statusText = text("", 11f, WARN, false);
        statusText.setPadding(0, dpi(10), 0, 0);
        column.addView(statusText);

        // ---- registro
        column.addView(label("REGISTRO DEL MÓDULO (INDEPENDIENTE DEL DE TV)"));
        logView = text("", 10f, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.1f);
        logView.setTextIsSelectable(true);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setBackground(panelBg(0xAA120E14, 0x33FFFFFF, 8));
        logScroll.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpi(190));
        logParams.topMargin = dpi(6);
        column.addView(logScroll, logParams);

        // ---- adaptadores
        column.addView(label("ADAPTADORES DE PROVEEDOR"));
        TextView adapters = text(ProviderRegistry.report(), 10.5f, DIM, false);
        adapters.setLineSpacing(0f, 1.12f);
        LinearLayout adaptersBox = new LinearLayout(this);
        adaptersBox.setOrientation(LinearLayout.VERTICAL);
        adaptersBox.setBackground(panelBg(0xAA120E14, 0x22FFFFFF, 8));
        adaptersBox.setPadding(dpi(12), dpi(10), dpi(12), dpi(10));
        adaptersBox.addView(adapters);
        LinearLayout.LayoutParams adaptersParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        adaptersParams.topMargin = dpi(6);
        column.addView(adaptersBox, adaptersParams);

        setContentView(scroll);
        refreshLog();
    }

    private void loadValues() {
        try {
            userAgentField.setText(MoviesPrefs.userAgent(this));
            refererField.setText(MoviesPrefs.referer(this));
            extraField.setText(MoviesPrefs.extraHeaders(this));
            demoSwitch.setChecked(MoviesPrefs.showDemo(this));
        } catch (Throwable error) {
            MoviesDiag.add("Ajustes · no se pudieron leer los valores: "
                    + MoviesGuard.describe(error));
        }
    }

    private void save() {
        try {
            MoviesPrefs.setUserAgent(this, userAgentField.getText().toString());
            MoviesPrefs.setReferer(this, refererField.getText().toString());
            MoviesPrefs.setExtraHeaders(this, extraField.getText().toString());
            MoviesPrefs.setAspect(this, selectedAspect);
            MoviesDiag.add("Ajustes guardados · cabeceras: " + MoviesPrefs.describeHeaders(this)
                    + " · imagen: " + MoviesPrefs.aspectLabel(selectedAspect));
            status("Guardado. Solo afecta a películas y series; la televisión sigue igual.");
            refreshLog();
        } catch (Throwable error) {
            status("No se pudo guardar: " + MoviesGuard.describe(error));
        }
    }

    private void status(String message) {
        try {
            if (statusText != null) statusText.setText(message);
        } catch (Throwable ignored) {
        }
    }

    private void refreshLog() {
        try {
            if (logView == null) return;
            logView.setText(MoviesDiag.text());
        } catch (Throwable ignored) {
        }
    }

    /** Nombre de versión del APK instalado; nunca lanza error. */
    private String installedVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            if (info != null && info.versionName != null && info.versionName.length() > 0) {
                return info.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "(versión desconocida)";
    }

    private void rebuildAspectRow() {
        try {
            aspectRow.removeAllViews();
            String[] keys = MoviesPrefs.aspectKeys();
            String[] labels = MoviesPrefs.aspectLabels();
            for (int i = 0; i < keys.length; i++) {
                final String key = keys[i];
                boolean selected = key.equals(selectedAspect);
                TextView chip = button(labels[i], selected ? BLUE : 0xFF2B2B40, TEXT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                selectedAspect = key;
                                rebuildAspectRow();
                                MoviesPrefs.setAspect(MoviesSettingsActivity.this, key);
                                status("Modo de imagen: " + MoviesPrefs.aspectLabel(key));
                            }
                        });
                chip.setTextSize(10f);
                aspectRow.addView(chip, gap(i == 0 ? 0 : 8));
            }
        } catch (Throwable ignored) {
        }
    }

    private void showFallback() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(BG);
            box.addView(text("No se pudieron abrir los ajustes del módulo.\n"
                    + "La televisión no está afectada.", 14f, TEXT, false));
            box.addView(button("VOLVER A LA LISTA", BLUE, 0xFFFFFFFF, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            }));
            setContentView(box);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ piezas

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView note(String value, float size, int color) {
        TextView view = text(value, size, color, false);
        view.setLineSpacing(0f, 1.14f);
        view.setPadding(0, dpi(6), 0, dpi(6));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 10f, 0xFF22D3EE, true);
        view.setLetterSpacing(0.08f);
        view.setPadding(0, dpi(12), 0, dpi(4));
        return view;
    }

    private EditText field(String hint, boolean multiline) {
        EditText view = new EditText(this);
        view.setHint(hint == null ? "" : hint);
        view.setHintTextColor(0xFF8E8A90);
        view.setTextColor(TEXT);
        view.setTextSize(11.5f);
        view.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT);
        view.setSingleLine(!multiline);
        view.setMinLines(multiline ? 3 : 1);
        view.setBackground(panelBg(0xCC120E12, 0x33FFFFFF, 6));
        view.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
        return view;
    }

    private TextView button(String label, int fill, int textColor, View.OnClickListener click) {
        TextView view = text(label, 11f, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dpi(12), dpi(9), dpi(12), dpi(9));
        view.setMinHeight(dpi(42));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fill);
        background.setCornerRadius(dpi(6));
        background.setStroke(Math.max(1, dpi(1)), 0x33FFFFFF);
        view.setBackground(background);
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setOnClickListener(click);
        return view;
    }

    private GradientDrawable panelBg(int fill, int stroke, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fill);
        background.setCornerRadius(dpi(radius));
        background.setStroke(Math.max(1, dpi(1)), stroke);
        return background;
    }

    private LinearLayout.LayoutParams gap(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpi(dp);
        return params;
    }

    private int dpi(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

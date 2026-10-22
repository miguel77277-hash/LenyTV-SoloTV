package com.pocketbuilder.app_wslenytvplayer1792356127271.movies;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.pocketbuilder.app_wslenytvplayer1792356127271.Immersion;
import com.pocketbuilder.app_wslenytvplayer1792356127271.LocalProxy;
import com.pocketbuilder.app_wslenytvplayer1792356127271.Media3Engine;

import java.util.Locale;
import java.util.Map;

/**
 * REPRODUCTOR DEL MÓDULO DE PELÍCULAS Y SERIES.
 *
 * Es un reproductor PROPIO, independiente del reproductor de la televisión:
 *   - Usa las cabeceras del módulo (MoviesPrefs), nunca las de TV.
 *   - Escribe en el registro del módulo (MoviesDiag), nunca en el de TV.
 *   - Vive en el proceso ":movies", así que un fallo aquí no cierra la TV.
 *
 * Qué reproduce: HLS (.m3u8), MP4, WebM, TS y enlaces HTTP/HTTPS directos que el
 * decodificador del teléfono entienda. DASH (.mpd) NO está soportado por este
 * reproductor y lo dice en pantalla en vez de quedarse cargando.
 *
 * Qué incluye: reproducción y pausa, barra de avance, saltos de 10 segundos,
 * control de volumen, silencio, pantalla completa (sin barras del sistema),
 * indicador de carga, mensajes de error claros y liberación del reproductor al
 * salir. Si el enlace falla directo, reintenta una vez a través del puente local
 * que repite las cabeceras del módulo en cada petición.
 */
public final class MoviesPlayerActivity extends Activity implements SurfaceHolder.Callback {

    public static final String EXTRA_URL = "movies.player.url";
    public static final String EXTRA_NAME = "movies.player.name";
    public static final String EXTRA_TYPE = "movies.player.type";

    private static final int BG = 0xFF07060B;
    private static final int PANEL = 0xF0120E14;
    private static final int TEXT = 0xFFF3F1F1;
    private static final int DIM = 0xFF9C97A0;
    private static final int BLUE = 0xFF1F5AA6;
    private static final int WARN = 0xFFFFC857;

    private String url = "";
    private String title = "Reproducción";
    private String typeHint = "";

    private Media3Engine media3;
    private AudioManager audio;
    private VideoSurface surface;
    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean usesProxy = false;
    private int autoRetries = 0;
    private boolean fullscreen = false;
    private boolean finished = false;
    private int volumeBeforeMute = -1;
    private int durationMs = 0;

    private FrameLayout root;
    private LinearLayout centerBox;
    private ProgressBar spinner;
    private TextView statusView;
    private LinearLayout errorBox;
    private TextView errorTitle;
    private TextView errorBody;
    private LinearLayout topBar;
    private LinearLayout controls;
    private TextView playButton;
    private TextView fullscreenButton;
    private TextView muteButton;
    private TextView positionView;
    private TextView durationView;
    private TextView formatBadge;
    private SeekBar seekBar;
    private SeekBar volumeBar;
    private boolean draggingSeek = false;
    private boolean userPaused = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateProgress();
            } catch (Throwable ignored) {
            }
            main.postDelayed(ticker, 500);
        }
    };
    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            try {
                if (media3 != null && media3.isPlaying() && errorBox != null
                        && errorBox.getVisibility() != View.VISIBLE) {
                    setControlsVisible(false);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    // ------------------------------------------------------------------- ciclo

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MoviesGuard.install(this);
        MoviesDiag.init(this);
        // El puente local del módulo escribe en SU registro.
        try {
            LocalProxy.get().setLog(new LocalProxy.Log() {
                @Override
                public void add(String message) {
                    MoviesDiag.add(message);
                }
            });
        } catch (Throwable ignored) {
        }

        Intent intent = getIntent();
        if (intent != null) {
            String given = intent.getStringExtra(EXTRA_URL);
            if (given != null) url = given.trim();
            String name = intent.getStringExtra(EXTRA_NAME);
            if (name != null && name.trim().length() > 0) title = name.trim();
            String type = intent.getStringExtra(EXTRA_TYPE);
            if (type != null) typeHint = type.trim();
        }
        try {
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
            audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable ignored) {
        }

        try {
            buildUi();
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · no se pudo dibujar: " + MoviesGuard.describe(error));
            showError("No se pudo abrir el reproductor",
                    "El módulo de películas no pudo dibujar la pantalla del reproductor. "
                            + "La televisión no está afectada.", false, false);
            return;
        }

        if (url.length() == 0) {
            showError("Falta el enlace",
                    "No ha llegado ninguna direccion de video al reproductor.", false, false);
        } else {
            showLoading("Conectando con el enlace…");
        }
        main.postDelayed(ticker, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            Immersion.stopKeeper();
        } catch (Throwable ignored) {
        }
        try {
            if (media3 != null && media3.isPlaying()) {
                userPaused = true;
                media3.setPaused(true);
                updatePlayButton();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // El reproductor se mantiene a pantalla completa al volver de segundo plano.
        try {
            Immersion.keepHidden(this);
        } catch (Throwable ignored) {
        }
        if (surface != null) surface.setScaleMode(MoviesPrefs.aspect(this));
        MoviesGuard.run("aplicar el modo de imagen", new Runnable() {
            @Override
            public void run() {
                applyAspect();
            }
        });
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
            main.removeCallbacks(ticker);
            main.removeCallbacks(hideControls);
        } catch (Throwable ignored) {
        }
        releasePlayer();
        MoviesDiag.save(this);
    }

    // ------------------------------------------------------------------ dibujo

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        surface = new VideoSurface(this);
        surface.setScaleMode(MoviesPrefs.aspect(this));
        surface.getHolder().addCallback(this);
        surface.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setControlsVisible(controls == null || controls.getVisibility() != View.VISIBLE);
            }
        });
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        // ---- carga
        centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);
        spinner = new ProgressBar(this);
        centerBox.addView(spinner, new LinearLayout.LayoutParams(dpi(38), dpi(38)));
        statusView = text("", 13f, TEXT, false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dpi(10);
        statusParams.leftMargin = dpi(18);
        statusParams.rightMargin = dpi(18);
        centerBox.addView(statusView, statusParams);
        centerBox.setVisibility(View.GONE);
        root.addView(centerBox, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // ---- errores
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setPadding(dpi(20), dpi(18), dpi(20), dpi(18));
        GradientDrawable errorBg = new GradientDrawable();
        errorBg.setColor(PANEL);
        errorBg.setCornerRadius(dpi(12));
        errorBg.setStroke(Math.max(1, dpi(1)), 0x66FFC857);
        errorBox.setBackground(errorBg);
        errorBox.setVisibility(View.GONE);

        errorTitle = text("", 16f, WARN, true);
        errorBox.addView(errorTitle);
        errorBody = text("", 12f, TEXT, false);
        errorBody.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dpi(8);
        errorBox.addView(errorBody, bodyParams);

        LinearLayout errorButtons = new LinearLayout(this);
        errorButtons.setOrientation(LinearLayout.HORIZONTAL);
        errorButtons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams errorButtonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorButtonsParams.topMargin = dpi(14);
        errorBox.addView(errorButtons, errorButtonsParams);

        errorButtons.addView(button("REINTENTAR", BLUE, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoRetries = 0;
                hideError();
                open(false);
            }
        }));
        errorButtons.addView(button("AJUSTES DEL MÓDULO", 0xFF2B2B40, TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSettings();
                    }
                }), gap(8));
        errorButtons.addView(button("REGISTRO", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        }), gap(8));
        errorButtons.addView(button("CERRAR", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), gap(8));

        root.addView(errorBox, new FrameLayout.LayoutParams(
                Math.min((int) (getResources().getDisplayMetrics().widthPixels * 0.82f), dpi(760)),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // ---- barra superior
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0xCC120E14);
        topBar.setPadding(dpi(12), dpi(6), dpi(10), dpi(6));
        TextView heading = text(title, 13f, TEXT, true);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        formatBadge = text(formatLabel(), 10f, 0xFF22D3EE, false);
        formatBadge.setSingleLine(true);
        topBar.addView(formatBadge);

        topBar.addView(button("AJUSTES", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        }), gap(10));
        topBar.addView(button("CERRAR", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), gap(6));
        root.addView(topBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // ---- controles
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xCC120E14);
        controls.setPadding(dpi(12), dpi(8), dpi(12), dpi(10));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setContentDescription("Avance del vídeo");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) {
                    positionView.setText(timeText((long) progress * durationMs / 1000L) + " / ");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingSeek = false;
                try {
                    if (media3 != null && durationMs > 0) {
                        media3.seekTo((int) ((long) bar.getProgress() * durationMs / 1000L));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        controls.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        playButton = button("PAUSA", BLUE, 0xFFFFFFFF, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        row.addView(playButton);

        row.addView(button("-10 s", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(-10000);
            }
        }), gap(6));
        row.addView(button("+10 s", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jump(10000);
            }
        }), gap(6));

        muteButton = button("SILENCIO", 0xFF2B2B40, TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMute();
            }
        });
        row.addView(muteButton, gap(10));

        TextView volumeLabel = text("VOLUMEN", 9.5f, DIM, true);
        volumeLabel.setLetterSpacing(0.08f);
        row.addView(volumeLabel, gap(10));

        volumeBar = new SeekBar(this);
        volumeBar.setContentDescription("Volumen del reproductor");
        volumeBar.setMax(15);
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(dpi(120),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(volumeBar, volumeParams);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                try {
                    if (audio != null) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    }
                } catch (Throwable ignored) {
                }
                updateMuteLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        positionView = text("00:00", 11f, TEXT, false);
        row.addView(positionView);
        durationView = text(" / 00:00", 11f, DIM, false);
        row.addView(durationView);

        fullscreenButton = button("PANTALLA COMPLETA", 0xFF2B2B40, TEXT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleFullscreen();
                    }
                });
        row.addView(fullscreenButton, gap(12));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dpi(4);
        controls.addView(row, rowParams);

        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setContentView(root);
        syncVolumeBar();
        applyFullscreen();
    }

    private void syncVolumeBar() {
        try {
            if (audio == null || volumeBar == null) return;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) max = 15;
            volumeBar.setMax(max);
            volumeBar.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            updateMuteLabel();
        } catch (Throwable ignored) {
        }
    }

    private void updateMuteLabel() {
        try {
            if (muteButton == null || audio == null) return;
            boolean muted = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
            muteButton.setText(muted ? "SONIDO" : "SILENCIO");
        } catch (Throwable ignored) {
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView button(String label, int fill, int textColor, View.OnClickListener click) {
        TextView view = text(label, 11f, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dpi(13), dpi(9), dpi(13), dpi(9));
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

    private LinearLayout.LayoutParams gap(int dp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpi(dp);
        return params;
    }

    private int dpi(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setControlsVisible(boolean visible) {
        try {
            if (controls != null) controls.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (topBar != null) {
                topBar.setVisibility(visible && !fullscreen ? View.VISIBLE : View.GONE);
            }
            if (visible) {
                syncVolumeBar();
                main.removeCallbacks(hideControls);
                main.postDelayed(hideControls, 4500);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showLoading(String message) {
        try {
            if (errorBox != null) errorBox.setVisibility(View.GONE);
            if (centerBox != null) centerBox.setVisibility(View.VISIBLE);
            if (spinner != null) spinner.setVisibility(View.VISIBLE);
            if (statusView != null) statusView.setText(message);
        } catch (Throwable ignored) {
        }
    }

    private void hideLoading() {
        try {
            if (centerBox != null) centerBox.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    private void showError(String head, String body, boolean canRetry, boolean canRetryHeaders) {
        try {
            hideLoading();
            if (controls != null) controls.setVisibility(View.VISIBLE);
            if (topBar != null && !fullscreen) topBar.setVisibility(View.VISIBLE);
            if (errorTitle != null) errorTitle.setText(head);
            if (errorBody != null) errorBody.setText(body);
            if (errorBox != null) errorBox.setVisibility(View.VISIBLE);
            MoviesDiag.add("Reproductor · " + head + " · " + body);
            MoviesDiag.save(this);
        } catch (Throwable ignored) {
        }
    }

    private void hideError() {
        try {
            if (errorBox != null) errorBox.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- reproducción

    private void open(final boolean viaProxy) {
        releasePlayer();
        prepared = false;
        finished = false;
        durationMs = 0;
        hideError();
        showLoading(viaProxy ? "Conectando con las cabeceras del modulo…"
                : "Conectando con el enlace…");
        MoviesDiag.add("Reproductor · Media3 · abriendo " + (viaProxy ? "con cabeceras" : "directo")
                + ": " + MoviesDiag.shortUrl(url) + " · cabeceras: "
                + MoviesPrefs.describeHeaders(this));
        if (url.length() == 0) {
            showError("Falta el enlace", "No ha llegado ninguna direccion al reproductor.",
                    false, false);
            return;
        }
        try {
            usesProxy = viaProxy;
            java.util.Map<String, String> headers = MoviesPrefs.headers(this);
            String source = url;
            if (viaProxy && LocalProxy.isHttp(url)) {
                try {
                    String bridged = LocalProxy.get().proxyUrl(url, headers);
                    if (bridged != null && bridged.length() > 0) {
                        source = bridged;
                        MoviesDiag.add("Reproductor · usando puente local");
                    }
                } catch (Throwable t) {
                    MoviesDiag.add("Reproductor · puente local fallo: " + t.getMessage());
                }
            }
            if (media3 == null) {
                media3 = new Media3Engine(this, main, moviesMedia3Listener);
            }
            if (surfaceReady && surface != null) {
                try {
                    media3.setSurface(surface.getHolder().getSurface());
                } catch (Throwable ignored) {
                }
            }
            String type = detectFormat();
            String typeHintForEngine = type;
            if ("HLS".equalsIgnoreCase(type)) typeHintForEngine = com.pocketbuilder.app_wslenytvplayer1792356127271.FormatDetector.HLS;
            else if ("DASH".equalsIgnoreCase(type)) typeHintForEngine = com.pocketbuilder.app_wslenytvplayer1792356127271.FormatDetector.DASH;
            media3.start(source, headers, typeHintForEngine);
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · no se pudo abrir: " + MoviesGuard.describe(error));
            showError("No se pudo abrir el enlace",
                    "El reproductor del modulo no pudo preparar este enlace. "
                            + MoviesGuard.describe(error), true, true);
        }
    }

    private final Media3Engine.Listener moviesMedia3Listener = new Media3Engine.Listener() {
        @Override
        public void onStatus(String message) {
            MoviesDiag.add(message);
        }

        @Override
        public void onBuffering(final boolean active) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (active) showLoading("Cargando…");
                    else hideLoading();
                }
            });
        }

        @Override
        public void onVideoSize(final int width, final int height) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (surface != null && width > 0 && height > 0) {
                            surface.setVideoSize(width, height);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @Override
        public void onError(final String title, final String body, final boolean retryable) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    MoviesDiag.add("Reproductor · Media3 error: " + title + " · " + body);
                    if (retryable && !usesProxy && LocalProxy.isHttp(url) && autoRetries < 2) {
                        autoRetries++;
                        MoviesDiag.add("Reproductor · se reintenta enviando las cabeceras del modulo");
                        open(true);
                        return;
                    }
                    releasePlayer();
                    showError(title != null ? title : "No se pudo reproducir",
                            (body != null ? body : "") + " Comprueba la direccion y, si el servidor exige cabeceras "
                                    + "especiales, escribelas en AJUSTES DEL MODULO.", true, true);
                }
            });
        }

        @Override
        public void onPrepared() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    onReady();
                }
            });
        }

        @Override
        public void onFinished() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    finished = true;
                    updatePlayButton();
                    setControlsVisible(true);
                }
            });
        }

        @Override
        public void onPlayingChanged(final boolean isPlaying) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    updatePlayButton();
                }
            });
        }
    };

    private void handlePlayerError(int what, int extra) {
        String reason = reasonFor(what, extra);
        MoviesDiag.add("Reproductor · fallo (" + what + "/" + extra + "): " + reason);
        if (!usesProxy && LocalProxy.isHttp(url) && autoRetries < 2) {
            autoRetries++;
            MoviesDiag.add("Reproductor · se reintenta enviando las cabeceras del módulo");
            open(true);
            return;
        }
        releasePlayer();
        showError("No se pudo reproducir este enlace",
                reason + " Código " + what + "/" + extra
                        + ". Comprueba la dirección y, si el servidor exige cabeceras "
                        + "especiales, escríbelas en AJUSTES DEL MÓDULO.", true, true);
    }

    /** Traduce el código de error del reproductor a una explicación entendible. */
    private String reasonFor(int what, int extra) {
        if (extra == -1004) {
            return "Hubo un problema de conexión o el archivo no se pudo leer.";
        }
        if (extra == -1007) {
            return "El contenido no tiene un formato que el reproductor entienda.";
        }
        if (extra == -1010
                || what == -1010) {
            return "Este teléfono no puede reproducir ese formato o códec.";
        }
        if (extra == -110
                || what == -110) {
            return "El servidor tardó demasiado en responder.";
        }
        if (extra == -1004) {
            return "No se pudo conectar con el servidor del vídeo.";
        }
        if (extra == -110) {
            return "La conexión se cortó a mitad de la carga.";
        }
        return "El reproductor encontró un problema con este enlace.";
    }

    private void onReady() {
        try {
            prepared = true;
            hideLoading();
            if (surfaceReady && surface != null && media3 != null) {
                try {
                    media3.setSurface(surface.getHolder().getSurface());
                } catch (Throwable ignored) {
                }
            }
            durationMs = media3 == null ? 0 : (int) media3.getDuration();
            if (durationView != null) {
                durationView.setText(durationMs > 0 ? timeText(durationMs) : "00:00");
            }
            if (seekBar != null) {
                seekBar.setEnabled(durationMs > 0);
            }
            userPaused = false;
            updatePlayButton();
            setControlsVisible(true);
            MoviesDiag.add("Reproductor · Media3 reproduciendo (" + (usesProxy ? "con cabeceras" : "directo")
                    + ")");
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · no se pudo iniciar: " + MoviesGuard.describe(error));
            showError("No se pudo iniciar la reproduccion",
                    MoviesGuard.describe(error), true, true);
        }
    }

    private void togglePlay() {
        if (media3 == null || !prepared) {
            autoRetries = 0;
            open(false);
            return;
        }
        try {
            if (finished) {
                finished = false;
                hideLoading();
                media3.seekTo(0);
                media3.setPaused(false);
                userPaused = false;
            } else if (media3.isPlaying()) {
                media3.setPaused(true);
                userPaused = true;
            } else {
                media3.setPaused(false);
                userPaused = false;
            }
            updatePlayButton();
            setControlsVisible(true);
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · fallo al pausar o reanudar: "
                    + MoviesGuard.describe(error));
        }
    }

    private void updatePlayButton() {
        try {
            if (playButton == null) return;
            if (finished) {
                playButton.setText("REPETIR");
            } else if (media3 != null && media3.isPlaying()) {
                playButton.setText("PAUSA");
            } else {
                playButton.setText("REPRODUCIR");
            }
        } catch (Throwable ignored) {
        }
    }

    private void jump(int millis) {
        try {
            if (media3 == null || !prepared) return;
            int target = (int) media3.getCurrentPosition() + millis;
            if (target < 0) target = 0;
            if (durationMs > 0 && target > durationMs) target = durationMs;
            media3.seekTo(target);
            updateProgress();
        } catch (Throwable ignored) {
        }
    }

    private void updateProgress() {
        if (media3 == null || !prepared) return;
        try {
            if (!draggingSeek && durationMs > 0) {
                int position = (int) media3.getCurrentPosition();
                seekBar.setProgress((int) ((long) position * 1000L / durationMs));
                positionView.setText(timeText(position));
            }
        } catch (Throwable ignored) {
        }
    }

    private String timeText(long millis) {
        long seconds = millis / 1000L;
        if (seconds < 0) seconds = 0;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        long hours = minutes / 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60L, rest);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, rest);
    }

    // ------------------------------------------------------- volumen y pantalla

    private void toggleMute() {
        try {
            if (audio == null) return;
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (current > 0) {
                volumeBeforeMute = current;
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } else {
                int restore = volumeBeforeMute > 0 ? volumeBeforeMute
                        : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0);
            }
            syncVolumeBar();
        } catch (Throwable ignored) {
        }
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        applyFullscreen();
        setControlsVisible(true);
    }

    private void applyFullscreen() {
        try {
            // Este reproductor va SIEMPRE a pantalla completa, igual que el de la
            // televisión: ni barra de notificaciones ni botones de navegación. El
            // botón solo enseña u oculta los paneles de la propia app.
            Immersion.keepHidden(this);
            if (topBar != null) topBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
            if (fullscreenButton != null) {
                fullscreenButton.setText(fullscreen ? "MOSTRAR PANELES" : "OCULTAR PANELES");
            }
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · pantalla completa: " + MoviesGuard.describe(error));
        }
    }

    private void applyAspect() {
        if (surface != null) surface.setScaleMode(MoviesPrefs.aspect(this));
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, MoviesSettingsActivity.class));
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- superficie

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        try {
            if (media3 != null) {
                media3.setSurface(holder.getSurface());
            } else if (url.length() > 0 && errorBox != null
                    && errorBox.getVisibility() != View.VISIBLE) {
                open(false);
            }
        } catch (Throwable error) {
            MoviesDiag.add("Reproductor · no se pudo usar la superficie: "
                    + MoviesGuard.describe(error));
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        try {
            if (media3 != null) media3.setSurface(holder.getSurface());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        try {
            if (media3 != null) media3.setSurface(null);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------- tipos

    /** Formato que se anuncia en la barra superior. */
    private String formatLabel() {
        String format = detectFormat().toUpperCase(Locale.US);
        return "FORMATO: " + format;
    }

    private String detectFormat() {
        String hint = typeHint.toLowerCase(Locale.US);
        String path = url.toLowerCase(Locale.US);
        int cut = path.indexOf('?');
        if (cut > 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut > 0) path = path.substring(0, cut);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return "HLS";
        if (path.endsWith(".mpd")) return "DASH";
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "MP4";
        if (path.endsWith(".webm")) return "WebM";
        if (path.endsWith(".ts") || path.endsWith(".m2ts")) return "TS";
        if (path.endsWith(".mkv")) return "MKV";
        if (hint.indexOf("hls") >= 0 || hint.indexOf("m3u") >= 0) return "HLS";
        if (hint.indexOf("dash") >= 0 || hint.indexOf("mpd") >= 0) return "DASH";
        if (hint.indexOf("mp4") >= 0) return "MP4";
        if (hint.indexOf("webm") >= 0) return "WebM";
        if (hint.indexOf("ts") >= 0) return "TS";
        return "sin extensión";
    }

    private boolean isDash() {
        return "DASH".equalsIgnoreCase(detectFormat());
    }

    // ------------------------------------------------------------------ recursos

    private void releasePlayer() {
        Media3Engine old = media3;
        media3 = null;
        prepared = false;
        if (old == null) return;
        try {
            old.release();
        } catch (Throwable ignored) {
        }
    }

    /** Superficie que respeta el modo de imagen elegido en los ajustes del módulo. */
    private final class VideoSurface extends SurfaceView {

        private int videoWidth = 0;
        private int videoHeight = 0;
        private String mode = MoviesPrefs.ASPECT_FIT;

        VideoSurface(Context context) {
            super(context);
            try {
                getHolder().setFormat(PixelFormat.OPAQUE);
            } catch (Throwable ignored) {
            }
            setFocusable(false);
        }

        void setVideoSize(int width, int height) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        void setScaleMode(String value) {
            mode = value == null ? MoviesPrefs.ASPECT_FIT : value;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int availableWidth = MeasureSpec.getSize(widthSpec);
            int availableHeight = MeasureSpec.getSize(heightSpec);
            if (videoWidth <= 0 || videoHeight <= 0 || availableWidth <= 0
                    || availableHeight <= 0) {
                setMeasuredDimension(availableWidth, availableHeight);
                return;
            }
            if (MoviesPrefs.ASPECT_FILL.equals(mode)) {
                setMeasuredDimension(availableWidth, availableHeight);
                return;
            }
            if (MoviesPrefs.ASPECT_ORIGINAL.equals(mode)) {
                setMeasuredDimension(Math.min(videoWidth, availableWidth),
                        Math.min(videoHeight, availableHeight));
                return;
            }
            float videoRatio = videoWidth / (float) videoHeight;
            float viewRatio = availableWidth / (float) availableHeight;
            int outWidth;
            int outHeight;
            if (MoviesPrefs.ASPECT_CROP.equals(mode)) {
                if (videoRatio > viewRatio) {
                    outHeight = availableHeight;
                    outWidth = Math.round(availableHeight * videoRatio);
                } else {
                    outWidth = availableWidth;
                    outHeight = Math.round(availableWidth / videoRatio);
                }
            } else {
                if (videoRatio > viewRatio) {
                    outWidth = availableWidth;
                    outHeight = Math.round(availableWidth / videoRatio);
                } else {
                    outHeight = availableHeight;
                    outWidth = Math.round(availableHeight * videoRatio);
                }
            }
            setMeasuredDimension(Math.max(1, outWidth), Math.max(1, outHeight));
        }
    }
}

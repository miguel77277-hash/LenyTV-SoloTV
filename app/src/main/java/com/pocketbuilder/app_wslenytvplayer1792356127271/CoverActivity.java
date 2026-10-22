package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PORTADA DE BIENVENIDA de LENY TV.
 *
 * Se muestra al abrir la aplicación y lleva a la pantalla de canales con el
 * botón ENTRAR. Todo el aspecto de la portada está en el mismo archivo:
 *
 *  1. El bloque CONFIGURACIÓN DE LA PORTADA (justo debajo de la clase) guarda
 *     los textos y los colores. Cambiar una línea de ahí cambia la portada.
 *  2. Si existe el archivo assets/generated/cover.png, se usa como fondo (encima
 *     del degradado oscuro). Basta con poner esa imagen en el proyecto para
 *     cambiar la portada sin tocar el código; si no existe, se ve el degradado
 *     oscuro con los brillos celeste y morado.
 *  3. El resto del archivo solo compone esos datos: nada de la app depende del
 *     diseño de la portada, así que se puede rehacer entera sin miedo.
 */
public final class CoverActivity extends Activity {

    // ------------------ CONFIGURACIÓN DE LA PORTADA (cámbiala aquí) ------------------

    /** Nombre grande de la portada. */
    private static final String BRAND = "LENY TV";
    /** Rótulo pequeño encima del nombre. */
    private static final String EYEBROW = "REPRODUCTOR IPTV";
    /** Línea de debajo del nombre. */
    private static final String SUBTITLE = "HLS · MPEG-DASH · LISTAS M3U";
    /** Texto del botón de entrada. */
    private static final String ENTER_LABEL = "ENTRAR";
    /** Aviso pequeño al pie de la portada. */
    private static final String FOOTER = "Toca ENTRAR para empezar";
    /** Imagen opcional de fondo (si el archivo no existe, se usa el degradado). */
    private static final String BACKGROUND_ASSET = "generated/cover.png";
    /** Fondo oscuro: color de arriba y de abajo. */
    private static final int COLOR_BACK_TOP = 0xFF07070C;
    private static final int COLOR_BACK_BOTTOM = 0xFF101020;
    /** Detalles: celeste y morado. */
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_PURPLE = 0xFFA855F7;
    /** Color del texto de la portada. */
    private static final int COLOR_TEXT = 0xFFF4F4F8;
    private static final int COLOR_TEXT_DIM = 0xFF9E9EB2;

    // ------------------------- fin de la configuración -------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ocultar barras del sistema antes del primer dibujo (evita el parpadeo).
        Immersion.apply(this);

        // La portada va protegida: si en este teléfono fallara cualquier detalle del
        // dibujo, la app no se cierra en silencio; muestra la pantalla de reserva con
        // el botón ENTRAR. (Un cierre sin aviso era justo el síntoma de la revisión 47.)
        try {
            buildCover();
        } catch (Throwable t) {
            try {
                Diag.add("Portada · no se pudo dibujar: " + Net.describe(t));
            } catch (Throwable ignored) {
            }
            showSafeScreen();
        }
    }

    /** Monta la portada. Si algo falla, onCreate muestra la pantalla de reserva. */
    private void buildCover() {

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACK_TOP);

        // Fondo: degradado oscuro con dos brillos (celeste y morado).
        Backdrop backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Fondo opcional desde assets (se puede cambiar sin tocar el código).
        ImageView cover = loadBackgroundImage();
        if (cover != null) {
            cover.setAlpha(0.55f);
            root.addView(cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dpi(24), dpi(16), dpi(24), dpi(16));

        TextView eyebrow = new TextView(this);
        eyebrow.setText(EYEBROW);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        eyebrow.setTextColor(COLOR_CYAN);
        eyebrow.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.28f);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow);

        TextView brand = new TextView(this);
        brand.setText(BRAND);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f);
        brand.setTextColor(COLOR_TEXT);
        brand.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.16f);
        brand.setGravity(Gravity.CENTER);
        brand.setShadowLayer(18f, 0f, 2f, 0x6622D3EE);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.topMargin = dpi(4);
        content.addView(brand, brandParams);

        // Barra de acento celeste → morado.
        View accent = new View(this);
        GradientDrawable accentBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{COLOR_CYAN, COLOR_PURPLE});
        accentBg.setCornerRadius(dpi(3));
        accent.setBackground(accentBg);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dpi(148), dpi(4));
        accentParams.topMargin = dpi(10);
        content.addView(accent, accentParams);

        TextView subtitle = new TextView(this);
        subtitle.setText(SUBTITLE);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subtitle.setTextColor(COLOR_TEXT_DIM);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dpi(10);
        content.addView(subtitle, subtitleParams);

        TextView enter = new TextView(this);
        enter.setText(ENTER_LABEL);
        enter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        enter.setTextColor(0xFFFFFFFF);
        enter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        enter.setLetterSpacing(0.18f);
        enter.setGravity(Gravity.CENTER);
        enter.setContentDescription("Entrar en LENY TV");
        enter.setClickable(true);
        enter.setFocusable(true);
        enter.setMinHeight(dpi(56));
        enter.setMinWidth(dpi(210));
        enter.setPadding(dpi(34), dpi(16), dpi(34), dpi(16));
        GradientDrawable enterBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2BB8E6, COLOR_PURPLE});
        enterBg.setCornerRadius(dpi(30));
        enterBg.setStroke(dpi(1), 0x66FFFFFF);
        enter.setBackground(enterBg);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterApp();
            }
        });
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        enterParams.topMargin = dpi(22);
        content.addView(enter, enterParams);

        TextView footer = new TextView(this);
        footer.setText(FOOTER);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        footer.setTextColor(0xFF7A7A92);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dpi(14);
        content.addView(footer, footerParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.CENTER;
        root.addView(content, contentParams);

        setContentView(root);
    }

    /**
     * Pantalla de reserva: lo mínimo para poder entrar en la app si la portada no
     * se puede dibujar en este teléfono. No cambia nada del diseño normal.
     */
    private void showSafeScreen() {
        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(0xFF08080F);
            box.setPadding(dpi(24), dpi(24), dpi(24), dpi(24));

            TextView title = new TextView(this);
            title.setText(BRAND);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
            title.setTextColor(0xFFF4F4F8);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            box.addView(title);

            TextView button = new TextView(this);
            button.setText(ENTER_LABEL);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            button.setTextColor(0xFFFFFFFF);
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            button.setGravity(Gravity.CENTER);
            button.setContentDescription("Entrar en LENY TV");
            button.setClickable(true);
            button.setFocusable(true);
            button.setMinHeight(dpi(56));
            button.setMinWidth(dpi(200));
            button.setPadding(dpi(30), dpi(16), dpi(30), dpi(16));
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2BB8E6, COLOR_PURPLE});
            bg.setCornerRadius(dpi(30));
            button.setBackground(bg);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    enterApp();
                }
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            buttonParams.topMargin = dpi(20);
            box.addView(button, buttonParams);

            setContentView(box);
        } catch (Throwable t) {
            // Último recurso: texto plano, sin estilos, para que la app nunca se cierre sola.
            try {
                TextView plain = new TextView(this);
                plain.setText(BRAND);
                plain.setTextColor(0xFFFFFFFF);
                plain.setGravity(Gravity.CENTER);
                setContentView(plain);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Abre la pantalla de canales y cierra la portada. */
    private void enterApp() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Aviso para que la pantalla de canales NO vuelva a poner otra portada
            // encima: la portada ya se ha visto aquí.
            intent.putExtra(WelcomeCover.EXTRA_COVER_DONE, true);
            startActivity(intent);
        } catch (Throwable t) {
            Diag.add("Portada · no se pudo abrir la pantalla de canales: " + Net.describe(t));
        }
        finish();
    }

    /** Imagen de fondo opcional de assets/generated; null si no existe. */
    private ImageView loadBackgroundImage() {
        try {
            android.content.res.AssetManager assets = getAssets();
            java.io.InputStream in = assets.open(BACKGROUND_ASSET);
            try {
                android.graphics.Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) return null;
                ImageView view = new ImageView(this);
                view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                view.setImageBitmap(bitmap);
                view.setContentDescription("Fondo de la portada de LENY TV");
                return view;
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            // No hay imagen de fondo: se queda el degradado, que es el diseño provisional.
            return null;
        }
    }

    private int dpi(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /**
     * Fondo dibujado: degradado oscuro de arriba abajo y dos brillos suaves
     * (celeste arriba a la derecha, morado abajo a la izquierda).
     */
    private static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Backdrop(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[]{COLOR_BACK_TOP, 0xFF0B0B16, COLOR_BACK_BOTTOM},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);

            float cyanRadius = Math.max(width, height) * 0.55f;
            paint.setShader(new RadialGradient(width * 0.82f, height * 0.12f, cyanRadius,
                    new int[]{0x5522D3EE, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);

            float purpleRadius = Math.max(width, height) * 0.6f;
            paint.setShader(new RadialGradient(width * 0.16f, height * 0.9f, purpleRadius,
                    new int[]{0x55A855F7, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);

            paint.setShader(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Diag.add("Portada · pantalla de bienvenida mostrada");
        } catch (Throwable ignored) {
        }
    }
}

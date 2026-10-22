package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PORTADA DE BIENVENIDA que se muestra encima de la pantalla de canales.
 *
 * Existe para que SIEMPRE se vea la portada al entrar en la app, se llegue desde
 * donde se llegue (icono, Ajustes → Abrir, un icono antiguo que abra el menú
 * directamente o al volver a la app):
 *
 *   - La portada de la actividad CoverActivity (entrada del icono) marca el aviso
 *     "cover_done" al pulsar ENTRAR. Cuando ese aviso está, aquí NO se repite la
 *     portada, así nunca sale dos veces.
 *   - Si el menú se abre sin pasar por esa portada, esta capa aparece encima con
 *     el botón ENTRAR y, al pulsarlo, se descubre el menú que ya estaba cargado.
 *
 * No cambia nada de la pantalla de canales ni del reproductor: es solo una capa
 * visual que se coloca delante y se retira al entrar.
 */
final class WelcomeCover {

    /** Aviso que pone CoverActivity al pulsar ENTRAR (para no repetir portada). */
    static final String EXTRA_COVER_DONE = "cover_done";

    private static final String BRAND = "LENY TV";
    private static final String EYEBROW = "REPRODUCTOR IPTV";
    private static final String SUBTITLE = "HLS · MPEG-DASH · LISTAS M3U";
    private static final String ENTER_LABEL = "ENTRAR";

    private WelcomeCover() {
    }

    /** Añade la portada encima de la pantalla, salvo que ya se haya mostrado. */
    static void showIfNeeded(Activity activity) {
        if (activity == null) return;
        try {
            Intent intent = activity.getIntent();
            if (intent != null && intent.getBooleanExtra(EXTRA_COVER_DONE, false)) return;
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) return;
            FrameLayout cover = build(activity);
            if (cover == null) return;
            content.addView(cover, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            Diag.add("Portada · mostrada al entrar en el menú");
        } catch (Throwable t) {
            // Si esta capa no se pudiera dibujar, el menú sigue funcionando igual.
            try {
                Diag.add("Portada · no se pudo mostrar: " + Net.describe(t));
            } catch (Throwable ignored) {
            }
        }
    }

    private static FrameLayout build(final Activity activity) {
        final FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF07070C);
        root.setClickable(true);
        root.setFocusable(true);
        // Marca para poder retirar esta capa exactamente (ver remove()).
        root.setTag("leny_cover");

        // Fondo: primero el degradado oscuro, y encima la imagen de la portada si existe.
        Backdrop backdrop = new Backdrop(activity);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ImageView image = loadBackground(activity);
        if (image != null) {
            image.setAlpha(0.85f);
            root.addView(image, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dpi(activity, 20), dpi(activity, 14), dpi(activity, 20), dpi(activity, 14));

        TextView eyebrow = new TextView(activity);
        eyebrow.setText(EYEBROW);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        eyebrow.setTextColor(0xFF22D3EE);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.28f);
        eyebrow.setGravity(Gravity.CENTER);
        column.addView(eyebrow);

        TextView brand = new TextView(activity);
        brand.setText(BRAND);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f);
        brand.setTextColor(0xFFF4F4F8);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.16f);
        brand.setGravity(Gravity.CENTER);
        brand.setShadowLayer(18f, 0f, 2f, 0x6622D3EE);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brandParams.topMargin = dpi(activity, 4);
        column.addView(brand, brandParams);

        View accent = new View(activity);
        GradientDrawable accentBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF22D3EE, 0xFFA855F7});
        accentBg.setCornerRadius(dpi(activity, 3));
        accent.setBackground(accentBg);
        LinearLayout.LayoutParams accentParams =
                new LinearLayout.LayoutParams(dpi(activity, 168), dpi(activity, 4));
        accentParams.topMargin = dpi(activity, 10);
        column.addView(accent, accentParams);

        TextView subtitle = new TextView(activity);
        subtitle.setText(SUBTITLE);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        subtitle.setTextColor(0xFF9E9EB2);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dpi(activity, 10);
        column.addView(subtitle, subtitleParams);

        TextView enter = new TextView(activity);
        enter.setText(ENTER_LABEL);
        enter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        enter.setTextColor(0xFFFFFFFF);
        enter.setTypeface(Typeface.DEFAULT_BOLD);
        enter.setLetterSpacing(0.18f);
        enter.setGravity(Gravity.CENTER);
        enter.setContentDescription("Entrar en LENY TV");
        enter.setClickable(true);
        enter.setFocusable(true);
        enter.setMinHeight(dpi(activity, 54));
        enter.setMinWidth(dpi(activity, 220));
        enter.setPadding(dpi(activity, 34), dpi(activity, 14), dpi(activity, 34), dpi(activity, 14));
        GradientDrawable enterBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2BB8E6, 0xFFA855F7});
        enterBg.setCornerRadius(dpi(activity, 30));
        enterBg.setStroke(dpi(activity, 1), 0x66FFFFFF);
        enter.setBackground(enterBg);
        enter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                remove(activity);
            }
        });
        LinearLayout.LayoutParams enterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        enterParams.topMargin = dpi(activity, 20);
        column.addView(enter, enterParams);

        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        columnParams.gravity = Gravity.CENTER;
        root.addView(column, columnParams);
        return root;
    }

    /** Retira la portada y deja ver el menú que ya estaba detrás. */
    private static void remove(Activity activity) {
        try {
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) return;
            for (int i = content.getChildCount() - 1; i >= 0; i--) {
                View child = content.getChildAt(i);
                if (child != null && child.getTag() != null
                        && "leny_cover".equals(String.valueOf(child.getTag()))) {
                    content.removeView(child);
                }
            }
            Diag.add("Portada · cerrada con ENTRAR, se muestra el menú");
        } catch (Throwable t) {
            try {
                Diag.add("Portada · no se pudo cerrar: " + Net.describe(t));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Imagen opcional assets/generated/cover.png; null si no existe. */
    private static ImageView loadBackground(Context context) {
        try {
            java.io.InputStream in = context.getAssets().open("generated/cover.png");
            try {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) return null;
                ImageView view = new ImageView(context);
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
            return null;
        }
    }

    private static int dpi(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Degradado oscuro con dos brillos, para que el texto se lea siempre. */
    private static final class Backdrop extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Backdrop(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;
            paint.setShader(new android.graphics.LinearGradient(0, 0, width, height,
                    new int[]{0xFF07070C, 0xFF0B0B16, 0xFF101020},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);

            float cyanRadius = Math.max(width, height) * 0.55f;
            paint.setShader(new android.graphics.RadialGradient(width * 0.82f, height * 0.12f, cyanRadius,
                    new int[]{0x5522D3EE, 0x00000000}, new float[]{0f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);

            float purpleRadius = Math.max(width, height) * 0.6f;
            paint.setShader(new android.graphics.RadialGradient(width * 0.16f, height * 0.9f, purpleRadius,
                    new int[]{0x55A855F7, 0x00000000}, new float[]{0f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
        }
    }
}

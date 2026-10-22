package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Fila de controles que se reparte en varias líneas cuando no cabe.
 *
 * El aviso de error del reproductor lleva cinco botones (Reintentar, Elegir
 * canal, Copiar motivo, Probar enlace y Volver). Con una fila normal, en una
 * pantalla estrecha o en vertical los últimos botones se salían de su cuadro y
 * quedaban cortados. Este contenedor coloca cada botón dentro del ancho real
 * disponible y, cuando no cabe, empieza otra línea debajo.
 *
 * No cambia el tamaño de los botones: siguen teniendo el mismo alto táctil.
 */
final class WrapRow extends ViewGroup {

    private final int gapH;
    private final int gapV;

    WrapRow(Context context, int gapHorizontalPx, int gapVerticalPx) {
        super(context);
        this.gapH = Math.max(0, gapHorizontalPx);
        this.gapV = Math.max(0, gapVerticalPx);
    }

    private int contentWidth(int widthMeasureSpec) {
        int size = MeasureSpec.getSize(widthMeasureSpec);
        if (size <= 0) {
            size = getResources().getDisplayMetrics().widthPixels;
        }
        int available = size - getPaddingLeft() - getPaddingRight();
        return Math.max(1, available);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = contentWidth(widthMeasureSpec);
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int childWidth = Math.min(child.getMeasuredWidth(), available);
            int childHeight = child.getMeasuredHeight();
            if (x > 0 && x + gapH + childWidth > available) {
                x = 0;
                y += rowHeight + gapV;
                rowHeight = 0;
            }
            x += (x > 0 ? gapH : 0) + childWidth;
            if (childHeight > rowHeight) rowHeight = childHeight;
        }
        int height = getPaddingTop() + getPaddingBottom() + y + rowHeight;
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = Math.max(1, right - left - getPaddingLeft() - getPaddingRight());
        int x = 0;
        int y = getPaddingTop();
        int rowHeight = 0;
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == View.GONE) continue;
            int childWidth = Math.min(child.getMeasuredWidth(), available);
            int childHeight = child.getMeasuredHeight();
            if (x > 0 && x + gapH + childWidth > available) {
                x = 0;
                y += rowHeight + gapV;
                rowHeight = 0;
            }
            int childLeft = getPaddingLeft() + x + (x > 0 ? gapH : 0);
            child.layout(childLeft, y, childLeft + childWidth, y + childHeight);
            x = childLeft - getPaddingLeft() + childWidth;
            if (childHeight > rowHeight) rowHeight = childHeight;
        }
    }
}

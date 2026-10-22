package com.pocketbuilder.generatedapp;

public final class AppView extends android.widget.FrameLayout {
    public AppView(android.content.Context context) {
        super(context);
        setBackgroundColor(android.graphics.Color.rgb(15, 23, 42));
        setPadding(dp(24), dp(24), dp(24), dp(24));

        android.widget.LinearLayout content = new android.widget.LinearLayout(context);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER);

        android.widget.TextView title = new android.widget.TextView(context);
        title.setText("Leny TV");
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(28f);
        title.setGravity(android.view.Gravity.CENTER);

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText("Ready for your PocketBuilder prompt");
        hint.setTextColor(android.graphics.Color.rgb(148, 163, 184));
        hint.setTextSize(15f);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, 0);

        content.addView(title, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(hint, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(content, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
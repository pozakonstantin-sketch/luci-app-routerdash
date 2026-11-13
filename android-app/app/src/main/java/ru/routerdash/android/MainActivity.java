package ru.routerdash.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SafeBrowsingResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "routerdash";
    private static final String KEY_URL = "router_url";
    private static final int BG = Color.rgb(11, 15, 20);
    private static final int CARD = Color.rgb(18, 26, 35);
    private static final int ACCENT = Color.rgb(255, 138, 61);
    private static final int FG = Color.rgb(230, 237, 243);
    private static final int MUTED = Color.rgb(139, 152, 165);

    private FrameLayout root;
    private LinearLayout page;
    private WebView webView;
    private ProgressBar progress;
    private TextView status;
    private String routerUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        routerUrl = getPreferences(MODE_PRIVATE).getString(KEY_URL, "");
        showHome();
        if (!routerUrl.isEmpty()) openRouter(routerUrl);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.rgb(26, 18, 5));
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackground(rounded(ACCENT, 0));
        return b;
    }

    private void showHome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(34), dp(24), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView logo = text("RouterDash", 27, FG, true);
        logo.setGravity(Gravity.CENTER);
        page.addView(logo, new LinearLayout.LayoutParams(-1, -2));
        TextView subtitle = text("Управление роутером OpenWrt", 14, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(8);
        page.addView(subtitle, subLp);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(rounded(CARD, Color.rgb(31, 42, 55)));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(-1, -2);
        panelLp.topMargin = dp(34);
        page.addView(panel, panelLp);

        panel.addView(text("Адрес роутера", 13, MUTED, true));
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(16);
        address.setTextColor(FG);
        address.setHintTextColor(MUTED);
        address.setHint("http://192.168.1.1");
        address.setInputType( android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(routerUrl.isEmpty() ? "http://192.168.1.1" : routerUrl);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(12), dp(10), dp(12), dp(10));
        address.setBackground(rounded(BG, Color.rgb(45, 58, 73)));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.topMargin = dp(8);
        panel.addView(address, inputLp);

        TextView hint = text("Укажите адрес OpenWrt, доступный с телефона. Поддерживаются HTTP и HTTPS.", 12, MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(8);
        panel.addView(hint, hintLp);

        Button connect = button("Подключиться");
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(48));
        buttonLp.topMargin = dp(18);
        panel.addView(connect, buttonLp);
        connect.setOnClickListener(v -> {
            String value = address.getText().toString().trim();
            if (openRouter(value)) {
                getPreferences(MODE_PRIVATE).edit().putString(KEY_URL, routerUrl).apply();
            }
        });

        TextView security = text("Вход выполняется штатной страницей LuCI. Пароль приложение не сохраняет.", 12, MUTED, false);
        security.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams securityLp = new LinearLayout.LayoutParams(-1, -2);
        securityLp.topMargin = dp(18);
        page.addView(security, securityLp);
        setContentView(root);
    }

    private boolean validUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean openRouter(String value) {
        String candidate = value.trim();
        if (!candidate.matches("(?i)^https?://.*")) candidate = "http://" + candidate;
        while (candidate.endsWith("/")) candidate = candidate.substring(0, candidate.length() - 1);
        if (!validUrl(candidate)) {
            Toast.makeText(this, "Введите корректный адрес, например http://192.168.1.1", Toast.LENGTH_LONG).show();
            return false;
        }
        routerUrl = candidate;
        showDashboard();
        webView.loadUrl(routerUrl + "/");
        return true;
    }

    private void showDashboard() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(4), dp(8), dp(4));
        toolbar.setBackgroundColor(BG);
        container.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView brand = text("RouterDash", 17, FG, true);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("Подключение…", 11, MUTED, false);
        toolbar.addView(status);
        Button settings = button("⋮");
        settings.setTextSize(20);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(48), dp(42));
        settingsLp.leftMargin = dp(6);
        toolbar.addView(settings, settingsLp);
        settings.setOnClickListener(v -> showSettings());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        container.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSafeBrowsingEnabled(true);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                UriCheck check = new UriCheck(request.getUrl().toString());
                if (check.sameOrigin(routerUrl)) return false;
                Toast.makeText(MainActivity.this, "Внешняя ссылка заблокирована в приложении", Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                status.setText("Загрузка…");
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                status.setText("Подключено");
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText("Нет связи");
                    Toast.makeText(MainActivity.this, "Не удалось открыть роутер. Проверьте адрес и подключение.", Toast.LENGTH_LONG).show();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        container.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void showSettings() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(routerUrl);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Адрес роутера")
                .setView(field)
                .setPositiveButton("Подключиться", (dialog, which) -> {
                    if (openRouter(field.getText().toString())) {
                        getPreferences(MODE_PRIVATE).edit().putString(KEY_URL, routerUrl).apply();
                    }
                })
                .setNeutralButton("Перезагрузить", (dialog, which) -> {
                    if (webView != null) webView.reload();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else if (webView != null) new AlertDialog.Builder(this)
                .setMessage("Закрыть RouterDash?")
                .setPositiveButton("Закрыть", (d, w) -> finish())
                .setNegativeButton("Отмена", null).show();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static class UriCheck {
        private final URI uri;
        UriCheck(String value) {
            URI parsed;
            try { parsed = new URI(value); } catch (Exception e) { parsed = URI.create("about:blank"); }
            uri = parsed;
        }
        boolean sameOrigin(String base) {
            try {
                URI origin = new URI(base);
                int p1 = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
                int p2 = origin.getPort() == -1 ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80) : origin.getPort();
                return uri.getHost() != null && origin.getHost() != null
                        && uri.getHost().equalsIgnoreCase(origin.getHost())
                        && p1 == p2 && uri.getScheme().equalsIgnoreCase(origin.getScheme());
            } catch (Exception e) { return false; }
        }
    }
}

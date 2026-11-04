package br.com.sharvin.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 77;
    private static final int REQ_FILE = 78;
    private WebView webView;
    private LinearLayout loadingScreen;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button retryButton;
    private boolean loaded;
    private boolean loadFailed;
    private ValueCallback<Uri[]> fileCallback;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 21, 44));
        getWindow().setNavigationBarColor(Color.rgb(7, 21, 44));
        createInterface();
        observeNetwork();
        requestNotificationPermission();
        openSharvin();
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value); tv.setTextColor(color); tv.setTextSize(sp);
        tv.setGravity(Gravity.CENTER); return tv;
    }

    private void createInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 21, 44));
        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        webView.setBackgroundColor(Color.WHITE);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        loadingScreen = new LinearLayout(this);
        loadingScreen.setOrientation(LinearLayout.VERTICAL);
        loadingScreen.setGravity(Gravity.CENTER);
        loadingScreen.setPadding(dp(26), dp(28), dp(26), dp(28));
        loadingScreen.setBackgroundColor(Color.rgb(7, 21, 44));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_horizontal);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadingScreen.addView(logo, new LinearLayout.LayoutParams(-1, dp(116)));

        TextView subtitle = text("PAINEL OPERACIONAL", 12, Color.rgb(132, 174, 242));
        subtitle.setLetterSpacing(.17f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, dp(28));
        subParams.topMargin = dp(6);
        loadingScreen.addView(subtitle, subParams);

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(48, 123, 255)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pp.gravity = Gravity.CENTER; pp.topMargin = dp(32);
        loadingScreen.addView(progressBar, pp);

        statusText = text("Conectando ao Sharvin...", 14, Color.rgb(212, 225, 244));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(18);
        loadingScreen.addView(statusText, stp);

        retryButton = new Button(this);
        retryButton.setText("Tentar novamente");
        retryButton.setTextColor(Color.WHITE);
        retryButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(22, 99, 239)));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> openSharvin());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(195), dp(50));
        rp.topMargin = dp(20); rp.gravity = Gravity.CENTER;
        loadingScreen.addView(retryButton, rp);
        root.addView(loadingScreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                if (SharvinConfig.isSharvinUrl(uri)) return false;
                // Nunca carregar uma tela externa dentro do aplicativo.
                String scheme = uri != null ? uri.getScheme() : null;
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException e) { Toast.makeText(MainActivity.this, "Nenhum navegador disponível", Toast.LENGTH_SHORT).show(); }
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!loadFailed && SharvinConfig.isSharvinUrl(Uri.parse(url)) && SharvinConfig.isWifiConnected(MainActivity.this)) {
                    loaded = true;
                    webView.setVisibility(View.VISIBLE);
                    loadingScreen.setVisibility(View.GONE);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame()) showError("Não foi possível acessar o Sharvin. Confira o Wi-Fi da empresa e o servidor.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse response) {
                if (req.isForMainFrame() && response.getStatusCode() >= 400)
                    showError("O servidor Sharvin retornou um erro. Tente novamente.");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Nunca ignorar erros de TLS; a confiança é configurada pelo certificado do servidor.
                handler.cancel();
                if (error != null && error.getUrl() != null && SharvinConfig.isSharvinUrl(Uri.parse(error.getUrl())))
                    showError("Certificado HTTPS do Sharvin não validado. Verifique a instalação/renovação do certificado.");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                    return true;
                }
            }
        });
    }

    private void observeNetwork() {
        connectivity = getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { networkChanged(); }
            @Override public void onLost(Network network) { networkChanged(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { networkChanged(); }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    private void networkChanged() {
        runOnUiThread(() -> {
            if (webView == null || isFinishing()) return;
            if (!SharvinConfig.isWifiConnected(this))
                showError("Conecte o celular ao Wi-Fi da empresa para acessar o Sharvin.");
            else if (!loaded && retryButton.getVisibility() == View.VISIBLE)
                openSharvin();
        });
    }

    private void openSharvin() {
        if (!SharvinConfig.isWifiConnected(this)) {
            showError("Conecte o celular ao Wi-Fi da empresa para acessar o Sharvin.");
            return;
        }
        loaded = false;
        loadFailed = false;
        webView.setVisibility(View.INVISIBLE);
        loadingScreen.setVisibility(View.VISIBLE);
        statusText.setText("Conectando ao Sharvin...");
        progressBar.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        webView.loadUrl(SharvinConfig.HOME);
    }

    private void showError(String message) {
        loaded = false;
        loadFailed = true;
        webView.setVisibility(View.INVISIBLE);
        loadingScreen.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        } else startMonitor();
    }

    private void startMonitor() {
        Intent intent = new Intent(this, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível iniciar o monitoramento de notificações.", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) {
            startMonitor();
            if (results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this, "Ative as notificações nas configurações para receber os avisos.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null && !SharvinConfig.isWifiConnected(this)) {
            showError("Conecte o celular ao Wi-Fi da empresa para acessar o Sharvin.");
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && loaded && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (webView != null) {
            webView.stopLoading(); webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}

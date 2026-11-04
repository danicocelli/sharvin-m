package br.com.sharvin.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.Uri;

final class SharvinConfig {
    static final String SERVER = "https://192.168.15.9:3002";
    static final String HOME = SERVER + "/index.html";
    static final String STATE = SERVER + "/api/painel-operacional/state";
    static final long POLL_INTERVAL_SECONDS = 15;

    private SharvinConfig() { }

    static boolean isWifiConnected(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities nc = active != null ? cm.getNetworkCapabilities(active) : null;
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    static boolean isSharvinUrl(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "192.168.15.9".equals(uri.getHost()) && uri.getPort() == 3002;
    }
}

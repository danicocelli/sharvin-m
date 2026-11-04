package br.com.sharvin.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Serviço local sem Firebase: consulta apenas a API do Sharvin na rede da empresa. */
public class MonitorService extends Service {
    private static final String STATUS = "sharvin_status";
    private static final String ALERTS = "sharvin_urgent";
    private static final String UPDATES = "sharvin_updates";
    private static final String PREFS = "sharvin_notifications_v1";
    private static final int STATUS_ID = 15;
    private ScheduledExecutorService scheduler;
    private SSLSocketFactory pinnedTls;
    private String lastStatus = "";

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(STATUS, "Monitoramento Sharvin", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel urgent = new NotificationChannel(ALERTS, "Avisos urgentes", NotificationManager.IMPORTANCE_HIGH);
        urgent.enableVibration(true);
        nm.createNotificationChannel(urgent);
        nm.createNotificationChannel(new NotificationChannel(UPDATES, "Produção, avisos e coletas", NotificationManager.IMPORTANCE_DEFAULT));
        pinnedTls = createPinnedTls();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = foregroundNotification("Monitorando avisos, produção e coletas");
        if (Build.VERSION.SDK_INT >= 29) startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(STATUS_ID, n);
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleWithFixedDelay(this::pollSafely, 0L, SharvinConfig.POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }

    private PendingIntent openApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification foregroundNotification(String message) {
        return new Notification.Builder(this, STATUS)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle("Sharvin • Equipe conectada")
                .setContentText(message)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void status(String message) {
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        getSystemService(NotificationManager.class).notify(STATUS_ID, foregroundNotification(message));
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void postEvent(StateDiffer.Event event, SharedPreferences prefs) {
        if (!canNotify()) return;
        String channel = event.urgent ? ALERTS : UPDATES;
        Notification.Builder builder = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_status)
                .setContentTitle(event.title)
                .setContentText(event.message)
                .setStyle(new Notification.BigTextStyle().bigText(event.message))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setCategory(event.urgent ? Notification.CATEGORY_ALARM : Notification.CATEGORY_STATUS)
                .setColor(0xFF1665EF)
                .setPriority(event.urgent ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        int id = prefs.getInt("notification_id", 1000) + 1;
        if (id > 2000000000) id = 1001;
        prefs.edit().putInt("notification_id", id).apply();
        getSystemService(NotificationManager.class).notify(id, builder.build());
    }

    private void pollSafely() {
        try {
            if (!SharvinConfig.isWifiConnected(this)) {
                status("Aguardando o Wi-Fi da empresa");
                return;
            }
            JSONObject response = downloadState();
            if (!response.optBoolean("ok") || response.optJSONObject("state") == null)
                throw new IllegalStateException("API não retornou estado válido");
            JSONObject state = response.getJSONObject("state");
            if (state.optInt("version", -1) != 3)
                throw new IllegalStateException("Versão inesperada do painel");
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String previous = prefs.getString("previous_state", null);
            if (previous == null) {
                // Primeira sincronização: avisar sobre as pendências, sem disparar dezenas de alertas antigos.
                postEvent(new StateDiffer.Event(StateDiffer.ALERT, "Sharvin sincronizado",
                        StateDiffer.initialSummary(state), false), prefs);
            } else {
                List<StateDiffer.Event> events = StateDiffer.diff(new JSONObject(previous), state);
                int limit = Math.min(events.size(), 45);
                for (int i = 0; i < limit; i++) postEvent(events.get(i), prefs);
                if (events.size() > limit)
                    postEvent(new StateDiffer.Event(StateDiffer.ALERT, "Outras alterações no Sharvin",
                            (events.size() - limit) + " alterações adicionais no painel. Abra o aplicativo.", false), prefs);
            }
            prefs.edit().putString("previous_state", state.toString()).apply();
            status("Monitorando avisos, produção e coletas");
        } catch (Exception ex) {
            Log.w("SharvinMobile", "Falha ao consultar o Sharvin", ex);
            status("Sem conexão com o servidor • aguardando rede");
        }
    }

    private SSLSocketFactory createPinnedTls() {
        try (InputStream in = getResources().openRawResource(R.raw.sharvin_server)) {
            X509Certificate allowed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            final byte[] expectedDer = allowed.getEncoded();
            X509TrustManager tm = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] certificates, String authType) throws java.security.cert.CertificateException {
                    throw new java.security.cert.CertificateException("Client TLS não suportado");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                    if (chain == null || chain.length == 0 || !MessageDigest.isEqual(expectedDer, chain[0].getEncoded()))
                        throw new java.security.cert.CertificateException("Certificado do servidor não corresponde ao Sharvin");
                    chain[0].checkValidity();
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{allowed}; }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{tm}, new SecureRandom());
            return ssl.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao carregar o certificado público do Sharvin", e);
        }
    }

    private JSONObject downloadState() throws Exception {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(SharvinConfig.STATE).openConnection();
        conn.setSSLSocketFactory(pinnedTls);
        // O hostname/IP é verificado normalmente pela HttpsURLConnection (SAN IP 192.168.15.9).
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setUseCaches(false);
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new IllegalStateException("HTTP " + status);
            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 4_000_000) throw new IllegalStateException("Resposta muito grande");
                }
                return new JSONObject(sb.toString());
            }
        } finally { conn.disconnect(); }
    }

    @Override public void onDestroy() {
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

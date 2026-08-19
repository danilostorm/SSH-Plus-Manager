package hev.sockstun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Storm SSH foreground VPN service.
 *
 * Flow: SSH -> local SOCKS5 (127.0.0.1:1080) -> hev tun2socks -> Android TUN.
 * This replaces the experimental packet-by-packet TCP implementation used by
 * the first Storm SSH build.
 */
public class TProxyService extends VpnService {
    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();
    private static native boolean TProxyIsRunning();
    private static native long[] TProxyGetStats();

    public static final String ACTION_CONNECT = "cloud.hoststorm.stormssh.CONNECT";
    public static final String ACTION_DISCONNECT = "cloud.hoststorm.stormssh.DISCONNECT";

    public static final String PREFS = "storm_ssh";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_PROFILE = "profile";
    public static final String KEY_STATE = "state";
    public static final String KEY_MESSAGE = "message";

    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_CONNECTING = "CONNECTING";
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_ERROR = "ERROR";

    private static final String SSH_HOST = "ssh.hoststorm.cloud";
    private static final int SSH_PORT = 2222;
    private static final int SOCKS_PORT = 1080;
    private static final int NOTIFICATION_ID = 77;
    private static final String CHANNEL_ID = "storm_ssh_vpn";

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private volatile boolean starting;
    private volatile boolean stopping;
    private ParcelFileDescriptor tunFd;
    private SshSocksProxy sshProxy;
    private Thread monitorThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            new Thread(this::stopAll, "StormStop").start();
            return START_NOT_STICKY;
        }

        ensureForeground("Conectando ao servidor...");
        if (!starting && !isFullyRunning()) {
            starting = true;
            new Thread(this::startAll, "StormStart").start();
        }
        return START_STICKY;
    }

    private boolean isFullyRunning() {
        return tunFd != null && TProxyIsRunning() && sshProxy != null && sshProxy.isConnected();
    }

    private void startAll() {
        stopping = false;
        try {
            setState(STATE_CONNECTING, "Autenticando SSH...");
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String username = prefs.getString(KEY_USERNAME, "");
            String password = prefs.getString(KEY_PASSWORD, "");
            String profile = prefs.getString(KEY_PROFILE, "Automático");

            if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
                throw new IllegalStateException("Informe usuário e senha.");
            }

            sshProxy = new SshSocksProxy(
                    SSH_HOST,
                    SSH_PORT,
                    username.trim(),
                    password,
                    SOCKS_PORT
            );
            sshProxy.start();

            setState(STATE_CONNECTING, "Criando VPN...");
            int mtu = mtuForProfile(profile);
            VpnService.Builder builder = new VpnService.Builder()
                    .setSession("Storm SSH")
                    .setBlocking(false)
                    .setMtu(mtu)
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("198.18.0.2");

            // The SSH transport and local SOCKS server must stay outside the VPN,
            // otherwise the tunnel would route into itself.
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) {
            }

            tunFd = builder.establish();
            if (tunFd == null) throw new IllegalStateException("Android não criou a interface VPN.");

            File configFile = new File(getCacheDir(), "storm-tun2socks.yml");
            writeTunConfig(configFile, mtu);
            boolean started = TProxyStartService(configFile.getAbsolutePath(), tunFd.getFd());
            if (!started && !TProxyIsRunning()) {
                throw new IllegalStateException("Falha ao iniciar o motor tun2socks.");
            }

            setState(STATE_CONNECTED, "VPN ativa");
            ensureForeground("Conectado • " + SSH_HOST + ":" + SSH_PORT);
            startMonitor();
        } catch (Exception e) {
            setState(STATE_ERROR, safeMessage(e));
            cleanup(false);
            stopForeground(true);
            stopSelf();
        } finally {
            starting = false;
        }
    }

    private void writeTunConfig(File file, int mtu) throws IOException {
        String conf =
                "misc:\n" +
                "  task-stack-size: 81920\n" +
                "  tcp-buffer-size: 65536\n" +
                "  connect-timeout: 12000\n" +
                "  log-level: warn\n" +
                "tunnel:\n" +
                "  mtu: " + mtu + "\n" +
                "  icmp: 'reply'\n" +
                "socks5:\n" +
                "  port: " + SOCKS_PORT + "\n" +
                "  address: '127.0.0.1'\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 240.0.0.0\n" +
                "  netmask: 240.0.0.0\n" +
                "  cache-size: 10000\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(conf.getBytes(StandardCharsets.UTF_8));
        }
    }

    private int mtuForProfile(String profile) {
        if (profile == null) return 1400;
        String p = profile.toLowerCase(Locale.ROOT);
        if (p.contains("wi-fi") || p.contains("wifi")) return 1500;
        if (p.contains("claro") || p.contains("tim") || p.contains("vivo") || p.contains("algar")) return 1400;
        return 1420;
    }

    private void startMonitor() {
        if (monitorThread != null && monitorThread.isAlive()) return;
        monitorThread = new Thread(() -> {
            while (!stopping && tunFd != null) {
                try {
                    if (sshProxy != null) sshProxy.reconnectIfNeeded();
                    long[] stats = TProxyGetStats();
                    if (stats != null && stats.length >= 4) {
                        long tx = stats[2];
                        long rx = stats[3];
                        ensureForeground("Conectado • ↓ " + formatBytes(rx) + " ↑ " + formatBytes(tx));
                    }
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    setState(STATE_CONNECTING, "Reconectando SSH...");
                    try { Thread.sleep(2_000); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "StormMonitor");
        monitorThread.start();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private void ensureForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Storm SSH",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disconnect = new Intent(this, TProxyService.class);
        disconnect.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPi = PendingIntent.getService(
                this, 1, disconnect, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Storm SSH")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desconectar", disconnectPi)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setState(String state, String message) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE, state)
                .putString(KEY_MESSAGE, message == null ? "" : message)
                .commit();
        Intent changed = new Intent("cloud.hoststorm.stormssh.STATE");
        changed.setPackage(getPackageName());
        sendBroadcast(changed);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Falha ao conectar.";
        return message;
    }

    private void stopAll() {
        stopping = true;
        setState(STATE_DISCONNECTED, "Desconectado");
        cleanup(true);
        stopForeground(true);
        stopSelf();
    }

    private synchronized void cleanup(boolean stopNative) {
        if (stopNative || TProxyIsRunning()) {
            try { TProxyStopService(); } catch (Exception ignored) {}
        }
        if (tunFd != null) {
            try { tunFd.close(); } catch (IOException ignored) {}
            tunFd = null;
        }
        if (sshProxy != null) {
            sshProxy.stop();
            sshProxy = null;
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    @Override
    public void onRevoke() {
        stopAll();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        cleanup(true);
        super.onDestroy();
    }
}

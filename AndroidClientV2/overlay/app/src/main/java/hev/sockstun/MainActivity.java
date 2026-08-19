package hev.sockstun;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

/** Storm SSH main screen. */
public class MainActivity extends AppCompatActivity {
    private static final int VPN_REQUEST = 7001;
    private static final int NOTIFICATION_REQUEST = 7002;
    private static final String[] PROFILES = {
            "Automático", "Wi-Fi", "Claro", "TIM", "Vivo", "Algar"
    };

    private EditText username;
    private EditText password;
    private Spinner profile;
    private MaterialButton connect;
    private TextView status;
    private TextView statusDetail;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable statePoll = new Runnable() {
        @Override public void run() {
            renderState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        username = findViewById(R.id.usernameInput);
        password = findViewById(R.id.passwordInput);
        profile = findViewById(R.id.profileSpinner);
        connect = findViewById(R.id.connectButton);
        status = findViewById(R.id.statusText);
        statusDetail = findViewById(R.id.statusDetail);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, PROFILES);
        profile.setAdapter(adapter);

        SharedPreferences prefs = getSharedPreferences(TProxyService.PREFS, MODE_PRIVATE);
        username.setText(prefs.getString(TProxyService.KEY_USERNAME, ""));
        password.setText(prefs.getString(TProxyService.KEY_PASSWORD, ""));
        String savedProfile = prefs.getString(TProxyService.KEY_PROFILE, PROFILES[0]);
        for (int i = 0; i < PROFILES.length; i++) {
            if (PROFILES[i].equals(savedProfile)) profile.setSelection(i);
        }

        connect.setOnClickListener(v -> toggleConnection());
        findViewById(R.id.reconnectButton).setOnClickListener(v -> reconnect());
        findViewById(R.id.menuButton).setOnClickListener(v -> showToolsMenu());
        findViewById(R.id.logButton).setOnClickListener(v -> showConnectionInfo());
        findViewById(R.id.webButton).setOnClickListener(v -> openUrl("https://hoststorm.cloud"));
        findViewById(R.id.serverButton).setOnClickListener(v -> showServers());

        findViewById(R.id.serverChip).setOnClickListener(v -> showServers());

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != getPackageManager().PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        }

        renderState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statePoll);
        handler.post(statePoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statePoll);
    }

    private void toggleConnection() {
        String state = currentState();
        if (TProxyService.STATE_CONNECTED.equals(state) || TProxyService.STATE_CONNECTING.equals(state)) {
            disconnectVpn();
        } else {
            prepareConnect();
        }
    }

    private void prepareConnect() {
        String user = username.getText().toString().trim();
        String pass = password.getText().toString();
        if (user.isEmpty()) {
            username.setError("Informe o usuário");
            username.requestFocus();
            return;
        }
        if (pass.isEmpty()) {
            password.setError("Informe a senha");
            password.requestFocus();
            return;
        }

        getSharedPreferences(TProxyService.PREFS, MODE_PRIVATE)
                .edit()
                .putString(TProxyService.KEY_USERNAME, user)
                .putString(TProxyService.KEY_PASSWORD, pass)
                .putString(TProxyService.KEY_PROFILE, String.valueOf(profile.getSelectedItem()))
                .apply();

        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            startActivityForResult(permission, VPN_REQUEST);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST) {
            if (resultCode == Activity.RESULT_OK) startVpn();
            else Toast.makeText(this, "Permissão de VPN necessária.", Toast.LENGTH_LONG).show();
        }
    }

    private void startVpn() {
        Intent service = new Intent(this, TProxyService.class);
        service.setAction(TProxyService.ACTION_CONNECT);
        ContextCompat.startForegroundService(this, service);
    }

    private void disconnectVpn() {
        Intent service = new Intent(this, TProxyService.class);
        service.setAction(TProxyService.ACTION_DISCONNECT);
        startService(service);
    }

    private void reconnect() {
        disconnectVpn();
        handler.postDelayed(this::prepareConnect, 1200);
    }

    private String currentState() {
        return getSharedPreferences(TProxyService.PREFS, MODE_PRIVATE)
                .getString(TProxyService.KEY_STATE, TProxyService.STATE_DISCONNECTED);
    }

    private void renderState() {
        SharedPreferences prefs = getSharedPreferences(TProxyService.PREFS, MODE_PRIVATE);
        String state = prefs.getString(TProxyService.KEY_STATE, TProxyService.STATE_DISCONNECTED);
        String message = prefs.getString(TProxyService.KEY_MESSAGE, "");

        boolean online = TProxyService.STATE_CONNECTED.equals(state);
        boolean connecting = TProxyService.STATE_CONNECTING.equals(state);

        if (online) {
            status.setText("CONECTADO");
            status.setTextColor(Color.parseColor("#22C55E"));
            connect.setText("DESCONECTAR");
        } else if (connecting) {
            status.setText("CONECTANDO...");
            status.setTextColor(Color.parseColor("#38BDF8"));
            connect.setText("CANCELAR");
        } else if (TProxyService.STATE_ERROR.equals(state)) {
            status.setText("ERRO");
            status.setTextColor(Color.parseColor("#EF4444"));
            connect.setText("INICIAR");
        } else {
            status.setText("DESCONECTADO");
            status.setTextColor(Color.parseColor("#EF4444"));
            connect.setText("INICIAR");
        }

        statusDetail.setText(message == null || message.isEmpty()
                ? "ssh.hoststorm.cloud • Porta 2222"
                : message);
        username.setEnabled(!online && !connecting);
        password.setEnabled(!online && !connecting);
        profile.setEnabled(!online && !connecting);
    }

    private void showServers() {
        new AlertDialog.Builder(this)
                .setTitle("Servidores")
                .setMessage("🇧🇷 Brasil 01\nssh.hoststorm.cloud:2222\n\nServidor automático configurado pelo Storm SSH.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showConnectionInfo() {
        SharedPreferences prefs = getSharedPreferences(TProxyService.PREFS, MODE_PRIVATE);
        String state = prefs.getString(TProxyService.KEY_STATE, TProxyService.STATE_DISCONNECTED);
        String message = prefs.getString(TProxyService.KEY_MESSAGE, "");
        String selected = prefs.getString(TProxyService.KEY_PROFILE, "Automático");
        new AlertDialog.Builder(this)
                .setTitle("Detalhes da conexão")
                .setMessage("Estado: " + state +
                        "\nMensagem: " + message +
                        "\nServidor: ssh.hoststorm.cloud:2222" +
                        "\nPerfil de rede: " + selected +
                        "\nMotor: SSH + tun2socks")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showToolsMenu() {
        String[] items = {
                "Hotspot / Roteador Wi-Fi",
                "Modo avião",
                "Configuração de APN",
                "Configuração de rede",
                "Desativar otimização de bateria",
                "Configurações do app / limpar dados"
        };
        new AlertDialog.Builder(this)
                .setTitle("MENU")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: openSettingsAction("android.settings.TETHER_SETTINGS"); break;
                        case 1: openSettingsAction(Settings.ACTION_AIRPLANE_MODE_SETTINGS); break;
                        case 2: openSettingsAction(Settings.ACTION_APN_SETTINGS); break;
                        case 3: openSettingsAction(Settings.ACTION_WIRELESS_SETTINGS); break;
                        case 4: requestBatteryException(); break;
                        case 5: openAppDetails(); break;
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void requestBatteryException() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettingsAction(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }
}

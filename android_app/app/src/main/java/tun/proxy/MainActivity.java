package tun.proxy;

import android.net.VpnService;
import android.net.Uri;
import android.os.Bundle;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.provider.MediaStore;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tun.proxy.service.Tun2HttpVpnService;
import tun.utils.IPUtil;

public class MainActivity extends AppCompatActivity implements
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    public static final int REQUEST_VPN = 1;

    Button start;
    Button stop;
    Button downloadBurpCert;
    EditText hostEditText;
    Handler statusHandler = new Handler(Looper.getMainLooper());
    Handler reachabilityHandler = new Handler(Looper.getMainLooper());
    ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    int reachabilityToken = 0;
    boolean isProxyReachable = false;

    private Tun2HttpVpnService service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        start = findViewById(R.id.start);
        stop = findViewById(R.id.stop);
        downloadBurpCert = findViewById(R.id.download_burp_cert);
        hostEditText = findViewById(R.id.host);

        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class))
        );

        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVpn();
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopVpn();
            }
        });
        downloadBurpCert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadBurpCertificate();
            }
        });
        hostEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                hostEditText.setError(null);
                scheduleReachabilityCheck();
            }
        });
        start.setEnabled(true);
        stop.setEnabled(false);
        downloadBurpCert.setEnabled(false);

        loadHostPort();
        scheduleReachabilityCheck();

    }
    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        final Bundle args = pref.getExtras();
        final Fragment fragment = getSupportFragmentManager().getFragmentFactory().instantiate(getClassLoader(), pref.getFragment());
        fragment.setArguments(args);
        fragment.setTargetFragment(caller, 0);
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.activity_settings, fragment)
            .addToBackStack(null)
            .commit();
        setTitle(pref.getTitle());
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_activity_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        if (id == R.id.action_show_about) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.app_name) + " " + getVersionName())
                    .setMessage(R.string.app_name)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }

        // This line is CRITICAL — without it the overflow menu closes immediately
        return super.onOptionsItemSelected(item);
    }

    protected String getVersionName() {
        PackageManager packageManager = getPackageManager();
        if (packageManager == null) {
            return null;
        }

        try {
            return packageManager.getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Tun2HttpVpnService.ServiceBinder serviceBinder = (Tun2HttpVpnService.ServiceBinder) binder;
            service = serviceBinder.getService();
        }

        public void onServiceDisconnected(ComponentName className) {
            service = null;
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        start.setEnabled(false);
        stop.setEnabled(false);
        scheduleReachabilityCheck();
        updateStatus();

        statusHandler.post(statusRunnable);

        Intent intent = new Intent(this, Tun2HttpVpnService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    boolean isRunning() {
        return service != null && service.isRunning();
    }

    Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
        updateStatus();
        statusHandler.post(statusRunnable);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        statusHandler.removeCallbacks(statusRunnable);
        reachabilityHandler.removeCallbacksAndMessages(null);
        unbindService(serviceConnection);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        networkExecutor.shutdownNow();
    }

    void updateStatus() {
        if (service == null) {
            updateBurpCertButtonState();
            return;
        }
        if (isRunning()) {
            start.setEnabled(false);
            hostEditText.setEnabled(false);
            stop.setEnabled(true);
        } else {
            start.setEnabled(true);
            hostEditText.setEnabled(true);
            stop.setEnabled(false);
        }
        updateBurpCertButtonState();
    }

    private void stopVpn() {
        start.setEnabled(true);
        stop.setEnabled(false);
        Tun2HttpVpnService.stop(this);
    }

    private void startVpn() {
        Intent i = VpnService.prepare(this);
        if (i != null) {
            startActivityForResult(i, REQUEST_VPN);
        } else {
            onActivityResult(REQUEST_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == REQUEST_VPN && parseAndSaveHostPort()) {
            start.setEnabled(false);
            stop.setEnabled(true);
            Tun2HttpVpnService.start(this);
        }
    }

    private void loadHostPort() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String proxyHost = prefs.getString(Tun2HttpVpnService.PREF_PROXY_HOST, "");
        int proxyPort = prefs.getInt(Tun2HttpVpnService.PREF_PROXY_PORT, 0);

        if (TextUtils.isEmpty(proxyHost)) {
            return;
        }
        hostEditText.setText(proxyHost + ":" + proxyPort);
    }

    private void scheduleReachabilityCheck() {
        final ProxyEndpoint endpoint = parseHostPortInput();
        reachabilityHandler.removeCallbacksAndMessages(null);

        if (endpoint == null) {
            isProxyReachable = false;
            updateBurpCertButtonState();
            return;
        }

        isProxyReachable = false;
        updateBurpCertButtonState();

        final int token = ++reachabilityToken;
        reachabilityHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                networkExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final boolean reachable = isHostReachable(endpoint.host, endpoint.port);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (token != reachabilityToken) {
                                    return;
                                }
                                isProxyReachable = reachable;
                                updateBurpCertButtonState();
                            }
                        });
                    }
                });
            }
        }, 300);
    }

    private void updateBurpCertButtonState() {
        final ProxyEndpoint endpoint = parseHostPortInput();
        boolean canDownload = endpoint != null && isProxyReachable;
        downloadBurpCert.setEnabled(canDownload);
    }

    private ProxyEndpoint parseHostPortInput() {
        String hostPort = hostEditText.getText().toString().trim();
        if (!IPUtil.isValidIPv4Address(hostPort)) {
            return null;
        }
        String[] parts = hostPort.split(":");
        if (parts.length != 2) {
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }

        return new ProxyEndpoint(parts[0], port);
    }

    private boolean isHostReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void downloadBurpCertificate() {
        final ProxyEndpoint endpoint = parseHostPortInput();
        if (endpoint == null) {
            hostEditText.setError(getString(R.string.enter_host));
            return;
        }
        if (!isProxyReachable) {
            Toast.makeText(this, getString(R.string.burp_proxy_not_reachable), Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, getString(R.string.burp_cert_downloading), Toast.LENGTH_SHORT).show();
        downloadBurpCert.setEnabled(false);

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final boolean downloaded = downloadCertToDownloads(endpoint.host, endpoint.port);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scheduleReachabilityCheck();
                        if (downloaded) {
                            Toast.makeText(MainActivity.this, getString(R.string.burp_cert_download_complete), Toast.LENGTH_SHORT).show();
                            Toast.makeText(MainActivity.this, getString(R.string.burp_cert_install_hint), Toast.LENGTH_LONG).show();
                            openCertificateSettings();
                        } else {
                            Toast.makeText(MainActivity.this, getString(R.string.burp_cert_download_failed), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void openCertificateSettings() {
        Intent installCertIntent = new Intent("android.credentials.INSTALL");
        if (installCertIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(installCertIntent);
            return;
        }

        Intent securityIntent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        if (securityIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(securityIntent);
            return;
        }

        Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        if (settingsIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(settingsIntent);
        }
    }

    private boolean downloadCertToDownloads(String host, int port) {
        HttpURLConnection connection = null;
        try {
            URL certUrl = new URL("http://" + host + ":" + port + "/cert");
            connection = (HttpURLConnection) certUrl.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return false;
            }

            try (InputStream input = connection.getInputStream()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    return saveToDownloadsScoped(input);
                }
                return saveToDownloadsLegacy(input);
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean saveToDownloadsScoped(InputStream input) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "burp-ca-cert.cer");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (destination == null) {
            return false;
        }

        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) {
                return false;
            }
            copyStream(input, output);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(destination, done, null, null);
        return true;
    }

    private boolean saveToDownloadsLegacy(InputStream input) throws IOException {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir == null) {
            return false;
        }
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            return false;
        }

        File destination = new File(downloadsDir, "burp-ca-cert.cer");
        try (OutputStream output = new FileOutputStream(destination, false)) {
            copyStream(input, output);
        }
        MediaScannerConnection.scanFile(this, new String[]{destination.getAbsolutePath()}, null, null);
        return true;
    }

    private void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private static final class ProxyEndpoint {
        final String host;
        final int port;

        ProxyEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private boolean parseAndSaveHostPort() {
        String hostPort = hostEditText.getText().toString();
        if (!IPUtil.isValidIPv4Address(hostPort)) {
            hostEditText.setError(getString(R.string.enter_host));
            return false;
        }
        String parts[] = hostPort.split(":");
        int port = 0;
        if (parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                hostEditText.setError(getString(R.string.enter_host));
                return false;
            }
        }
        String[] ipParts = parts[0].split("\\.");
        String host = parts[0];
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putString(Tun2HttpVpnService.PREF_PROXY_HOST, host);
        edit.putInt(Tun2HttpVpnService.PREF_PROXY_PORT, port);
        edit.commit();
        return true;
    }
}

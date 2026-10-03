package com.aedora.frp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Main screen: USB descriptor scan, VCOM mode classification, scatter layout parsing.
 * Read-only diagnostics. Erasing FRP / writing to a target through a preloader or Download
 * Agent channel is deliberately NOT implemented.
 */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.aedora.frp.USB_PERMISSION";
    private static final int REQ_SCATTER = 42;

    private Logger log;
    private UsbManager usb;
    private TextView logView, statusView, permView, scatterView;
    private ScatterParser scatter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        log = Logger.get(this);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();

        log.add("Diagnostic logger started on Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ").");
        log.add("Host: " + Build.MANUFACTURER + " " + Build.MODEL);
        log.add("Logger version 2.3. Log persists on this host until Clear or app data deletion.");
        log.add("No target-device data is changed. USB descriptor enumeration only.");
        log.add("Preloader/BROM VCOM channels are identified for diagnostics; no DA session is opened and nothing is written or erased.");
        log.add("Partition backup works over an authorized ADB connection (target confirms this host's key).");
        log.add("Recovery sideload is not normal ADB shell access. Tap Recovery help for explanation.");

        log.addListener(new LogWatcher());
        refreshLog();
        loadBundledScatter();
        MtkSession.scanDescriptors(this);
    }

    @Override protected void onResume() { super.onResume(); registerUsbReceivers(); refreshPermStatus(); }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
    }

    private final UsbEventReceiver rx = new UsbEventReceiver();

    private final class UsbEventReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                log.add("USB event: android.hardware.usb.action.USB_DEVICE_ATTACHED");
                MtkSession.scanDescriptors(MainActivity.this);
                if (d != null && !usb.hasPermission(d)) requestPermission(d, true);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                log.add("USB event: android.hardware.usb.action.USB_DEVICE_DETACHED");
                MtkSession.scanDescriptors(MainActivity.this);
                refreshPermStatus();
            } else if (ACTION_USB_PERMISSION.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (d != null) {
                    log.add("USB permission for VID=" + MtkConstants.hex4(d.getVendorId())
                            + " PID=" + MtkConstants.hex4(d.getProductId()) + ": " + (granted ? "GRANTED" : "DENIED"));
                    if (granted && AdbClient.findAdbInterface(d) != null) {
                        log.add("ADB interface available on granted device. Opening ADB Terminal...");
                        openTerminal(d);
                    }
                }
                refreshPermStatus();
            }
        }
    }

    private void registerUsbReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rx, f);
    }

    // ---------------- UI ----------------

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF101418);

        TextView title = new TextView(this);
        title.setText("MTK FRP Tool v2.3");
        title.setTextSize(20);
        title.setTextColor(0xFF7FE3A0);
        root.addView(title);

        statusView = mkText(13, 0xFFC9D3DC);
        root.addView(statusView);
        permView = mkText(13, 0xFFE0B060);
        permView.setPadding(0, 4, 0, 8);
        root.addView(permView);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(mkBtn("Scan USB", v -> { MtkSession.scanDescriptors(this); refreshPermStatus(); }));
        r1.addView(mkBtn("Request connection", v -> requestAllPermissions()));
        root.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(mkBtn("ADB Terminal", v -> openTerminalForFirstAdb()));
        r2.addView(mkBtn("Load scatter", v -> pickScatter()));
        root.addView(r2);

        LinearLayout r3 = new LinearLayout(this);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.addView(mkBtn("Sample scatter", v -> loadBundledScatter()));
        r3.addView(mkBtn("Save manifest", v -> saveManifest()));
        root.addView(r3);

        LinearLayout r4 = new LinearLayout(this);
        r4.setOrientation(LinearLayout.HORIZONTAL);
        r4.addView(mkBtn("Save .txt", v -> saveLog()));
        r4.addView(mkBtn("Copy log", v -> copyLog()));
        r4.addView(mkBtn("Clear", v -> { log.clear(); refreshLog(); }));
        root.addView(r4);

        TextView hint = mkText(11, 0xFF8A93A0);
        hint.setText("ROM backup (read-only) runs inside ADB Terminal once the target has authorized this host. "
                + "Preloader/BROM VCOM is identified only — no Download Agent session, no write, no erase.");
        hint.setPadding(0, 6, 0, 6);
        root.addView(hint);

        scatterView = mkText(11, 0xFF9FD8B0);
        scatterView.setTextIsSelectable(true);
        ScrollView sv2 = new ScrollView(this);
        sv2.addView(scatterView);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, 0);
        lp2.weight = 1f;
        sv2.setLayoutParams(lp2);
        root.addView(sv2);

        logView = mkText(11, 0xFFD7E0E8);
        logView.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0);
        lp.weight = 1f;
        lp.topMargin = pad / 2;
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);
    }

    private TextView mkText(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button mkBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(2, 2, 2, 2);
        b.setLayoutParams(lp);
        return b;
    }

    private final class LogWatcher implements Logger.Listener {
        @Override public void onChanged() { refreshLog(); }
    }

    private void refreshLog() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (logView == null) return;
                StringBuilder sb = new StringBuilder();
                for (String e : log.snapshot()) sb.append(e).append('\n');
                logView.setText(sb);
            }
        });
    }

    private void refreshPermStatus() {
        HashMap<String, UsbDevice> devs = usb.getDeviceList();
        if (devs.isEmpty()) {
            statusView.setText("Connected USB devices: 0");
            permView.setText("Connection status: no device. Connect the target through an OTG adapter.");
            return;
        }
        StringBuilder st = new StringBuilder("Connected USB devices: " + devs.size() + "\n");
        StringBuilder pv = new StringBuilder();
        for (UsbDevice dev : devs.values()) {
            VcomClassifier.Info info = VcomClassifier.classify(dev);
            st.append("  ").append(MtkConstants.hex4(dev.getVendorId())).append(':')
              .append(MtkConstants.hex4(dev.getProductId())).append("  ")
              .append(info.mode).append('\n');
            pv.append(MtkConstants.hex4(dev.getVendorId())).append(':')
              .append(MtkConstants.hex4(dev.getProductId()))
              .append(" permission: ").append(usb.hasPermission(dev) ? "GRANTED" : "NOT granted")
              .append(info.adbCapable ? "  [ADB interface]" : "").append('\n');
        }
        statusView.setText(st.toString());
        permView.setText(pv.toString());
    }

    private void requestAllPermissions() {
        HashMap<String, UsbDevice> devs = usb.getDeviceList();
        if (devs.isEmpty()) { toast("No USB device connected"); return; }
        boolean any = false;
        for (UsbDevice d : devs.values()) {
            if (!usb.hasPermission(d)) { requestPermission(d, false); any = true; }
        }
        if (!any) {
            toast("Permission already granted for all devices");
            log.add("Connection request: permission already granted for all connected devices.");
        }
    }

    private void requestPermission(UsbDevice d, boolean auto) {
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        log.add((auto ? "Auto" : "User") + " requesting USB permission for VID=" + MtkConstants.hex4(d.getVendorId())
                + " PID=" + MtkConstants.hex4(d.getProductId()) + "...");
        usb.requestPermission(d, pi);
    }

    private void openTerminalForFirstAdb() {
        UsbDevice adbDev = null, anyDev = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (AdbClient.findAdbInterface(d) != null && usb.hasPermission(d)) { adbDev = d; break; }
            if (anyDev == null) anyDev = d;
        }
        if (adbDev != null) openTerminal(adbDev);
        else if (anyDev != null) {
            if (!usb.hasPermission(anyDev)) {
                requestPermission(anyDev, false);
                toast("Grant permission first — the terminal opens automatically if an ADB interface is found");
            } else openTerminal(anyDev);
        } else toast("No USB device connected");
    }

    private void openTerminal(UsbDevice d) {
        Intent i = new Intent(this, AdbActivity.class);
        i.putExtra("deviceName", d.getDeviceName());
        startActivity(i);
    }

    // ---------------- scatter ----------------

    private void pickScatter() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        try { startActivityForResult(i, REQ_SCATTER); }
        catch (Exception e) { toast("No file picker available"); }
    }

    private void loadBundledScatter() {
        try (InputStream in = getAssets().open("MT6765_Android_scatter.txt")) {
            showScatter(readAll(in), "assets/MT6765_Android_scatter.txt");
        } catch (Exception e) {
            showScatter("", "bundled sample unavailable");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCATTER || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            showScatter(readAll(in), uri.getLastPathSegment());
            log.add("Scatter loaded: " + uri.getLastPathSegment());
        } catch (Exception e) {
            log.add("Scatter load failed: " + e.getMessage());
            toast("Scatter load failed: " + e.getMessage());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private void showScatter(String text, String name) {
        scatter = ScatterParser.parse(text, name);
        StringBuilder sb = new StringBuilder();
        sb.append(scatter.summary()).append('\n');
        sb.append(scatter.table());
        scatterView.setText(sb.toString());
        log.add("Scatter parsed: platform=" + scatter.platform() + " project=" + scatter.project()
                + " partitions=" + scatter.partitions.size());
        for (ScatterParser.Partition p : scatter.partitions) {
            if ("frp".equalsIgnoreCase(p.name)) {
                log.add("Scatter note: the layout contains an 'frp' partition (" + MtkConstants.hex(p.size)
                        + "). This tool does not erase or modify it.");
            }
        }
    }

    private void saveManifest() {
        if (scatter == null || scatter.partitions.isEmpty()) { toast("Load a scatter file first"); return; }
        String name = "scatter-manifest-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".csv";
        if (writeToDownloads(name, scatter.manifestCsv(), "text/csv")) {
            log.add("Scatter manifest exported to Downloads/" + name);
            toast("Saved to Downloads/" + name);
        }
    }

    // ---------------- log export ----------------

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("mtk-log", log.exportText()));
        toast("Log copied to clipboard");
    }

    private void saveLog() {
        String name = "mtk-frp-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        if (writeToDownloads(name, log.exportText(), "text/plain")) {
            log.add("Full stored text log exported to Downloads/" + name);
            toast("Saved to Downloads/" + name);
        }
    }

    private boolean writeToDownloads(String name, String body, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(body.getBytes("UTF-8")); }
                return true;
            }
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
            try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(body.getBytes("UTF-8")); }
            return true;
        } catch (Exception e) {
            log.add("Export failed: " + e.getMessage());
            toast("Export failed: " + e.getMessage());
            return false;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}

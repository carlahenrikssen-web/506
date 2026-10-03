package com.aedora.frp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ADB Terminal plus read-only partition backup (ROM archive).
 * The target device must authorize this host through its own RSA prompt before anything runs.
 */
public class AdbActivity extends Activity {

    private TextView termView;
    private EditText input;
    private ScrollView scroll;
    private UsbManager usb;
    private AdbClient client;
    private UsbDeviceConnection conn;
    private boolean connected;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        connectFromIntent();
    }

    private void buildUi() {
        int pad = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF0B0E11);

        TextView title = new TextView(this);
        title.setText("ADB Terminal");
        title.setTextSize(18);
        title.setTextColor(0xFF7FE3A0);
        root.addView(title);

        termView = new TextView(this);
        termView.setTextSize(11);
        termView.setTextColor(0xFFD7E0E8);
        termView.setTextIsSelectable(true);
        termView.setText("Terminal ready.\n");
        scroll = new ScrollView(this);
        scroll.addView(termView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0);
        lp.weight = 1f;
        lp.topMargin = pad / 2;
        scroll.setLayoutParams(lp);
        root.addView(scroll);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(mkBtn("id", v -> runCommand("id")));
        presets.addView(mkBtn("getprop", v -> runCommand("getprop | grep -E 'ro.product|ro.boot|ro.build.version' ")));
        presets.addView(mkBtn("df", v -> runCommand("df -h /data 2>/dev/null")));
        presets.addView(mkBtn("parts", v -> runCommand("ls -1 /dev/block/by-name 2>/dev/null")));
        root.addView(presets);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(mkBtn("ROM backup", v -> startBackup()));
        actions.addView(mkBtn("Copy log", v -> copyTerm()));
        actions.addView(mkBtn("Copy errors", v -> copyErrors()));
        actions.addView(mkBtn("Disconnect", v -> disconnect()));
        root.addView(actions);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setHint("adb shell command…");
        input.setTextSize(13);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        input.setLayoutParams(ilp);
        row.addView(input);
        Button send = new Button(this);
        send.setText("Run");
        send.setTextSize(12);
        send.setOnClickListener(v -> {
            String c = input.getText().toString().trim();
            if (!c.isEmpty()) { runCommand(c); input.setText(""); }
        });
        row.addView(send);
        root.addView(row);

        setContentView(root);
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

    // ---------------- connection ----------------

    private void connectFromIntent() {
        String name = getIntent().getStringExtra("deviceName");
        UsbDevice target = null;
        HashMap<String, UsbDevice> devs = usb.getDeviceList();
        for (UsbDevice d : devs.values()) if (d.getDeviceName().equals(name)) { target = d; break; }
        if (target == null && !devs.isEmpty()) target = devs.values().iterator().next();
        if (target == null) { appendTerm("[!] No USB device. Attach the target and retry.\n"); return; }

        VcomClassifier.Info info = VcomClassifier.classify(target);
        appendTerm("[*] Mode: " + info.mode + "\n");

        UsbInterface adbIf = AdbClient.findAdbInterface(target);
        if (adbIf == null) {
            appendTerm("[!] No ADB interface (class 255/66/1) on this device.\n");
            appendTerm("    " + VcomClassifier.note(info) + "\n");
            return;
        }
        if (!usb.hasPermission(target)) {
            appendTerm("[!] USB permission not granted. Go back and tap Request connection.\n");
            return;
        }
        try {
            conn = usb.openDevice(target);
            if (conn == null) { appendTerm("[!] openDevice returned null.\n"); return; }
            conn.claimInterface(adbIf, true);
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < adbIf.getEndpointCount(); i++) {
                UsbEndpoint ep = adbIf.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) in = ep;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) out = ep;
            }
            if (in == null || out == null) { appendTerm("[!] Missing bulk endpoints.\n"); return; }
            client = new AdbClient(conn, in, out, Logger.get(this));
            appendTerm("[*] Connecting ADB handshake…\n");
            new Thread(new Connector()).start();
        } catch (Exception e) {
            appendTerm("[!] Open failed: " + e.getMessage() + "\n");
        }
    }

    private final class Connector implements Runnable {
        @Override public void run() {
            try {
                client.connect(AdbActivity.this);
                connected = true;
                ui("[+] ADB connected. Type a command, or tap ROM backup for a read-only archive.\n");
            } catch (Exception e) {
                final String m = e.getMessage();
                ui("[!] Handshake failed: " + m
                        + "\n    If the target showed an RSA prompt, accept it and reconnect.\n");
            }
        }
    }

    // ---------------- commands ----------------

    private void runCommand(String cmd) {
        appendTerm("\n$ " + cmd + "\n");
        if (client == null) { appendTerm("[!] Not connected.\n"); return; }
        new Thread(new TermRunner(cmd)).start();
    }

    private final class TermRunner implements Runnable {
        private final String cmd;
        TermRunner(String c) { this.cmd = c; }
        @Override public void run() {
            try {
                client.openShell(cmd, new TermListener());
            } catch (Exception e) {
                ui("[!] " + e.getMessage() + "\n");
            }
        }
    }

    private final class TermListener implements AdbClient.Listener {
        @Override public void onLine(String chunk) { ui(chunk); }
        @Override public void onError(String err) { ui("[!] " + err + "\n"); }
        @Override public void onClosed() { }
    }

    // ---------------- read-only ROM backup ----------------

    private void startBackup() {
        if (client == null || !connected) { toast("Not connected — connect over ADB first"); return; }
        appendTerm("\n=== Read-only partition backup (archive only) ===\n");
        new Thread(new BackupRunner()).start();
    }

    private final class BackupRunner implements Runnable {
        @Override public void run() {
            try {
                DeviceBackup bk = new DeviceBackup(AdbActivity.this, client);
                List<String> available = bk.listPartitions();
                if (available.isEmpty()) {
                    ui("[!] No /dev/block/by-name entries. Root or userdebug access may be required on the target.\n");
                    return;
                }
                ui("[*] Target exposes " + available.size() + " named partitions.\n");
                List<String> targets = new ArrayList<>();
                for (String want : DeviceBackup.DEFAULT_TARGETS) {
                    for (String have : available) if (have.equals(want)) { targets.add(want); break; }
                }
                if (targets.isEmpty()) {
                    targets.addAll(available.subList(0, Math.min(4, available.size())));
                    ui("[*] None of the common presets matched; archiving the first " + targets.size() + " entries.\n");
                }
                ui("[*] Archiving: " + targets + "\n");
                bk.backup(targets, true, new BackupProgress());
            } catch (Exception e) {
                ui("[!] Backup error: " + e.getMessage() + "\n");
            }
        }
    }

    private final class BackupProgress implements DeviceBackup.Progress {
        @Override public void onStatus(String line) { ui("    " + line + "\n"); }
        @Override public void onDone(String reportPath, String manifestPath, String error) {
            if (error != null) ui("[!] Backup finished with error: " + error + "\n");
            else {
                ui("[+] Backup complete.\n");
                ui("    report:   " + reportPath + "\n");
                ui("    manifest: " + manifestPath + "\n");
                ui("    (read-only archive — nothing was written to or erased on the target)\n");
            }
        }
    }

    // ---------------- output helpers ----------------

    private synchronized void appendTerm(String s) {
        termView.append(s);
        scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
    }

    private void ui(final String s) { runOnUiThread(new Runnable() { @Override public void run() { appendTerm(s); } }); }

    private void copyTerm() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("term", termView.getText().toString()));
        toast("Terminal log copied");
    }

    private void copyErrors() {
        StringBuilder sb = new StringBuilder();
        for (String line : termView.getText().toString().split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("error") || lower.contains("fail") || lower.contains("exception")
                    || lower.contains("[!]") || lower.contains("denied") || lower.contains("not found")
                    || lower.contains("incomplete")) {
                sb.append(line).append('\n');
            }
        }
        if (sb.length() == 0) sb.append("(no error lines in terminal output)\n");
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("term-errors", sb.toString()));
        toast("Error log copied");
    }

    private void disconnect() {
        connected = false;
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        appendTerm("[*] Disconnected.\n");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        connected = false;
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}

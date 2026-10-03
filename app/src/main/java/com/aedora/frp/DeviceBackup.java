package com.aedora.frp;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Partition (ROM) read-back for archiving, performed over an AUTHORIZED ADB connection:
 * the target device must already have accepted this host's RSA key on its own screen.
 * Read-only: partitions are copied out, never written to or erased.
 */
public final class DeviceBackup {

    public interface Progress { void onStatus(String line); void onDone(String reportPath, String manifestPath, String error); }

    /** Partition names that are pure bootloader/metadata internals; not offered in the UI presets. */
    public static final String[] DEFAULT_TARGETS = {
            "boot", "recovery", "system", "vendor", "nvram", "nvdata", "oem", "dtbo", "odmdtbo"
    };

    /** Small, sensitive dividers commonly dumped first for diagnostics. */
    public static final String[] SMALL_TARGETS = {
            "nvram", "nvdata", "proinfo", "protect1", "protect2", "sec1", "seccfg", "para", "expdb", "metadata"
    };

    private static final int CHUNK = 1024 * 1024; // 1 MiB per dd call

    private final AdbClient adb;
    private final Context ctx;
    private final Logger log;

    public DeviceBackup(Context ctx, AdbClient adb) {
        this.ctx = ctx;
        this.adb = adb;
        this.log = Logger.get(ctx);
    }

    /** Lists block-device by-name entries exposed by the target. */
    public List<String> listPartitions() throws Exception {
        String out = adb.exec("ls -1 /dev/block/by-name 2>/dev/null");
        List<String> names = new ArrayList<>();
        for (String s : out.split("\n")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.contains(" ")) names.add(t);
        }
        return names;
    }

    /** Resolves a partition name to an absolute device path that exists on the target. */
    private String resolve(String name) throws Exception {
        String p = "/dev/block/by-name/" + name;
        String r = adb.exec("ls " + p + " 2>/dev/null");
        if (r != null && r.contains(p)) return p;
        String alt = "/dev/block/bootdevice/by-name/" + name;
        String r2 = adb.exec("ls " + alt + " 2>/dev/null");
        if (r2 != null && r2.contains(alt)) return alt;
        return null;
    }

    /** Reads partition size in bytes via blockdev, falling back to toybox stat. */
    public long sizeOf(String devPath) {
        try {
            String outs = adb.exec("blockdev --getsize64 " + devPath + " 2>/dev/null");
            long v = parseFirstLong(outs);
            if (v > 0) return v;
        } catch (Exception ignored) { }
        try {
            String outs = adb.exec("toybox stat -c %s " + devPath + " 2>/dev/null");
            long v = parseFirstLong(outs);
            if (v > 0) return v;
        } catch (Exception ignored) { }
        return -1L;
    }

    private static long parseFirstLong(String s) {
        if (s == null) return -1;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.matches("\\d{3,}")) {
                try { return Long.parseLong(t); } catch (Exception ignored) { }
            }
        }
        return -1;
    }

    /**
     * Copies the named partitions into the app's external backup folder.
     * Runs on a background thread provided by the caller.
     */
    public void backup(List<String> names, boolean alsoManifest, Progress p) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(ctx.getExternalFilesDir(null), "backup/" + stamp);
        if (!dir.exists() && !dir.mkdirs()) {
            p.onDone(null, null, "cannot create backup folder");
            return;
        }
        StringBuilder manifest = new StringBuilder();
        manifest.append("partition,path,size_bytes,sha256,status\n");
        StringBuilder report = new StringBuilder();
        report.append("Backup started ").append(stamp).append('\n');
        report.append("Destination: ").append(dir.getAbsolutePath()).append('\n');
        report.append("Mode: read-only copy over authorized ADB (target confirmed this host's key)\n\n");

        try {
            for (String name : names) {
                String dev = resolve(name);
                if (dev == null) {
                    report.append(name).append(": SKIPPED (not present)\n");
                    manifest.append(name).append(",,,,\"not present\"\n");
                    p.onStatus(name + ": skipped (not present)");
                    continue;
                }
                long size = sizeOf(dev);
                report.append(name).append(": ").append(dev).append(" size=")
                      .append(size < 0 ? "unknown" : MtkConstants.humanSize(size)).append('\n');
                p.onStatus("Reading " + name + (size > 0 ? " (" + MtkConstants.humanSize(size) + ")" : "") + "…");
                File out = new File(dir, name + ".img");
                long written = 0;
                boolean ok = true;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    if (size > 0) {
                        for (long off = 0; off < size; off += CHUNK) {
                            long count = Math.min(CHUNK, size - off);
                            long bs = 512;
                            long skip = off / bs;
                            long cnt = count / bs;
                            String cmd = "dd if=" + dev + " bs=" + bs + " skip=" + skip + " count=" + cnt + " 2>/dev/null";
                            byte[] data = adb.execRaw(cmd);
                            if (data == null || data.length == 0) { ok = false; break; }
                            fos.write(data);
                            written += data.length;
                            p.onStatus("  " + name + ": " + MtkConstants.humanSize(written) + " / " + MtkConstants.humanSize(size));
                        }
                    } else {
                        byte[] data = adb.execRaw("cat " + dev + " 2>/dev/null");
                        if (data == null || data.length == 0) ok = false;
                        else { fos.write(data); written = data.length; }
                    }
                }
                String sha = "";
                try { sha = Sha256.ofFile(out); } catch (Exception ignored) { }
                report.append("  written=").append(MtkConstants.humanSize(written))
                      .append("  sha256=").append(sha.isEmpty() ? "-" : sha)
                      .append("  status=").append(ok ? "ok" : "incomplete").append('\n');
                manifest.append(name).append(',').append(dev).append(',').append(written).append(',')
                        .append(sha.isEmpty() ? "" : sha).append(',').append(ok ? "ok" : "incomplete").append('\n');
                p.onStatus(name + ": " + (ok ? "done" : "incomplete") + " (" + MtkConstants.humanSize(written) + ")");
                log.add("Backup: " + name + " -> " + out.getName() + " (" + MtkConstants.humanSize(written) + ", " + (ok ? "ok" : "incomplete") + ")");
            }

            File manifestFile = new File(dir, "manifest.csv");
            write(manifestFile, manifest.toString());
            File reportFile = new File(dir, "report.txt");
            report.append("\nRead-only archive. No partition was written to or erased.\n");
            write(reportFile, report.toString());
            p.onDone(reportFile.getAbsolutePath(), manifestFile.getAbsolutePath(), null);
        } catch (Exception e) {
            log.add("Backup failed: " + e.getMessage());
            try {
                File reportFile = new File(dir, "report.txt");
                report.append("\nFAILED: ").append(e.getMessage()).append('\n');
                write(reportFile, report.toString());
            } catch (Exception ignored) { }
            p.onDone(null, null, e.getMessage());
        }
    }

    private static void write(File f, String body) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(body.getBytes("UTF-8")); }
    }
}

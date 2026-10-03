package com.aedora.frp;

import java.util.Locale;

/** MediaTek USB (VCOM) identifiers and small formatting helpers. Tasks are read-only diagnostics. */
public final class MtkConstants {
    private MtkConstants() {}

    public static final int MTK_VID = 0x0E8D;

    /** Known MediaTek USB product ids -> human readable VCOM mode. */
    public static String modeForPid(int pid) {
        switch (pid) {
            case 0x0003: return "BootROM (BROM) VCOM";
            case 0x0004: return "BootROM (BROM) VCOM (alt)";
            case 0x2000: return "Preloader VCOM";
            case 0x2001: return "Download Agent (DA) VCOM";
            case 0x2002: return "Preloader VCOM (alt)";
            case 0x2006: return "META mode";
            case 0x2007: return "META mode (alt)";
            case 0x2008: return "META mode (alt 2)";
            case 0x200C: return "BROM/DA composite";
            case 0x201C: return "Preloader (alt 2)";
            case 0x201D: return "DA (alt)";
            default: return null;
        }
    }

    public static boolean isMediaTek(int vid) { return vid == MTK_VID; }

    public static String hex4(int v) {
        String s = Integer.toHexString(v).toUpperCase(Locale.US);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    public static String hex(long v) { return "0x" + Long.toHexString(v).toUpperCase(Locale.US); }

    public static long parseHex(String s) {
        if (s == null) return 0L;
        s = s.trim();
        if (s.isEmpty()) return 0L;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.2f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }
}

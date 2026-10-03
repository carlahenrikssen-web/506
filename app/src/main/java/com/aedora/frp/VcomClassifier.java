package com.aedora.frp;

import android.hardware.usb.UsbDevice;

/** Classifies a connected USB device: MediaTek VCOM mode (BROM / Preloader / DA / META) or ordinary ADB. */
public final class VcomClassifier {
    private VcomClassifier() {}

    public static final class Info {
        public final String mode;
        public final boolean mediaTek;
        public final boolean preloaderOrBrom;
        public final boolean adbCapable;

        Info(String mode, boolean mediaTek, boolean preloaderOrBrom, boolean adbCapable) {
            this.mode = mode;
            this.mediaTek = mediaTek;
            this.preloaderOrBrom = preloaderOrBrom;
            this.adbCapable = adbCapable;
        }
    }

    public static Info classify(UsbDevice d) {
        if (d == null) return new Info("no device", false, false, false);
        int vid = d.getVendorId();
        int pid = d.getProductId();
        boolean adb = AdbClient.findAdbInterface(d) != null;

        if (MtkConstants.isMediaTek(vid)) {
            String known = MtkConstants.modeForPid(pid);
            String mode = known != null
                    ? "MediaTek " + known
                    : "MediaTek device (PID " + MtkConstants.hex4(pid) + ", mode not in table)";
            boolean pre = pid == 0x0003 || pid == 0x0004 || pid == 0x2000 || pid == 0x2002 || pid == 0x201C;
            return new Info(mode, true, pre, adb);
        }
        if (adb) return new Info("ADB interface present (normal adb mode)", false, false, true);
        return new Info("Non-MediaTek device (VID " + MtkConstants.hex4(vid) + ")", false, false, false);
    }

    /**
     * MediaTek BROM/Preloader VCOM carries no user-visible authorization step and no ADB interface:
     * it is a flashing/DA channel. This app records the mode for diagnostics only and does not open
     * a Download Agent session or issue any write/erase command.
     */
    public static String note(Info info) {
        if (info.preloaderOrBrom) {
            return "VCOM preloader/BROM channel detected. Diagnostic identification only: no DA session, no write/erase is performed.";
        }
        if (info.mediaTek && info.adbCapable) {
            return "MediaTek device exposing a normal ADB interface.";
        }
        if (info.adbCapable) return "Authorized ADB path available (device confirms access on its own screen).";
        return "No ADB interface and not a MediaTek VCOM channel.";
    }
}

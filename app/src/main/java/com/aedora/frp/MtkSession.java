package com.aedora.frp;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.util.HashMap;

/**
 * USB descriptor enumeration and VCOM mode classification.
 * Read-only: no target-device data is changed and no Download Agent session is opened.
 */
public final class MtkSession {

    /** Enumerates all connected devices, logs descriptors (v2.1-compatible wording) and the VCOM class. */
    public static void scanDescriptors(Context ctx) {
        Logger log = Logger.get(ctx);
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> devs = um.getDeviceList();
        log.add("--- USB descriptor scan ---");
        if (devs.isEmpty()) {
            log.add("Connected USB devices: 0");
            log.add("No USB device detected. Check the OTG adapter, USB cable and phone connection mode.");
            return;
        }
        log.add("Connected USB devices: " + devs.size());
        for (UsbDevice d : devs.values()) describe(ctx, log, um, d);
    }

    private static void describe(Context ctx, Logger log, UsbManager um, UsbDevice d) {
        log.add("VID=" + MtkConstants.hex4(d.getVendorId()) + " PID=" + MtkConstants.hex4(d.getProductId())
                + " class=" + d.getDeviceClass() + " interfaces=" + d.getInterfaceCount());
        boolean perm = um.hasPermission(d);
        log.add("Descriptor name: " + d.getDeviceName() + "; permission granted: " + perm);
        log.add("Manufacturer: " + d.getManufacturerName() + "; product: " + d.getProductName());

        VcomClassifier.Info info = VcomClassifier.classify(d);
        log.add("Mode: " + info.mode);
        log.add(VcomClassifier.note(info));

        boolean adbIf = false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            log.add("Interface " + i + ": class=" + it.getInterfaceClass() + ", subclass=" + it.getInterfaceSubclass()
                    + ", protocol=" + it.getInterfaceProtocol() + ", endpoints=" + it.getEndpointCount());
            for (int j = 0; j < it.getEndpointCount(); j++) {
                UsbEndpoint ep = it.getEndpoint(j);
                String dir = ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT";
                log.add("  Endpoint " + j + ": address=" + ep.getAddress() + ", direction=" + dir
                        + ", type=" + ep.getType() + ", maxPacket=" + ep.getMaxPacketSize());
            }
            if (AdbClient.findAdbInterface(d) == it) adbIf = true;
        }
        if (adbIf) {
            log.add("ADB-like USB interface advertised. Mode UNCONFIRMED: descriptors cannot distinguish normal ADB vs recovery sideload. No protocol handshake is performed.");
        } else if (info.preloaderOrBrom) {
            log.add("No ADB interface: this VCOM channel is a flashing/DA channel. Identification only — nothing is written or erased.");
        } else {
            log.add("No standard ADB USB interface advertised by this device.");
        }
    }
}

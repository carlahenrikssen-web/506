package com.aedora.frp;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** SHA-256 helper for backup integrity records. */
public final class Sha256 {
    private Sha256() {}

    public static String ofFile(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String ofBytes(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            String s = Integer.toHexString(x & 0xff);
            if (s.length() == 1) sb.append('0');
            sb.append(s);
        }
        return sb.toString();
    }
}

package com.aedora.frp;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import java.io.ByteArrayOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * ADB over USB Host: CNXN/AUTH handshake plus shell and sync (file transfer) streams.
 * Requires the TARGET device to authorize this host (RSA prompt on the target screen).
 */
public final class AdbClient {

    public static final int A_VERSION = 0x01000001;
    public static final int MAX_PAYLOAD = 256 * 1024;

    private static final int CMD_CNXN = 0x4e584e43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4e45504f;
    private static final int CMD_OKAY = 0x59414b4f;
    private static final int CMD_CLSE = 0x45534c43;
    private static final int CMD_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    // sync (file transfer) sub-protocol on a "sync:" stream
    private static final int SYNC_STAT = 0x54415453; // STAT
    private static final int SYNC_SEND = 0x444e4553; // SEND
    private static final int SYNC_DATA = 0x41544144; // DATA
    private static final int SYNC_DONE = 0x454e4f44; // DONE
    private static final int SYNC_OKAY = 0x59414b4f; // OKAY
    private static final int SYNC_FAIL = 0x4c494146; // FAIL
    private static final int SYNC_QUIT = 0x54495551; // QUIT
    private static final int SYNC_DATA_MAX = 64 * 1024;

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epIn, epOut;
    private final Logger log;
    private final Object ioLock = new Object();
    private int localId = 1;
    private KeyPair kp;

    public AdbClient(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut, Logger log) {
        this.conn = conn; this.epIn = epIn; this.epOut = epOut; this.log = log;
    }

    public interface Listener { void onLine(String chunk); void onError(String err); void onClosed(); }

    /** Finds the adb interface: class 255 (vendor), subclass 66, protocol 1. */
    public static UsbInterface findAdbInterface(UsbDevice d) {
        if (d == null) return null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            if (it.getInterfaceClass() == 0xff && it.getInterfaceSubclass() == 0x42 && it.getInterfaceProtocol() == 1) {
                return it;
            }
        }
        return null;
    }

    // ---------------- framing ----------------

    private void send(int cmd, int arg0, int arg1, byte[] data) throws Exception {
        if (data == null) data = new byte[0];
        byte[] hb = new byte[24];
        put32(hb, 0, cmd); put32(hb, 4, arg0); put32(hb, 8, arg1);
        put32(hb, 12, data.length); put32(hb, 16, checksum(data)); put32(hb, 20, cmd ^ 0xffffffff);
        synchronized (ioLock) {
            int r = conn.bulkTransfer(epOut, hb, hb.length, 3000);
            if (r != hb.length) throw new Exception("bulk out header failed (" + r + ")");
            if (data.length > 0) {
                r = conn.bulkTransfer(epOut, data, data.length, 10000);
                if (r != data.length) throw new Exception("bulk out payload failed (" + r + ")");
            }
        }
    }

    private int[] readMsg(ByteArrayOutputStream payloadOut) throws Exception {
        byte[] h = new byte[24];
        readFully(h);
        int cmd = get32(h, 0), a0 = get32(h, 4), a1 = get32(h, 8), len = get32(h, 12);
        if (len < 0 || len > MAX_PAYLOAD) throw new Exception("bad payload len " + len);
        byte[] data = new byte[len];
        if (len > 0) readFully(data);
        if (payloadOut != null && len > 0) payloadOut.write(data, 0, len);
        return new int[]{cmd, a0, a1, len};
    }

    private void readFully(byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = conn.bulkTransfer(epIn, buf, off, buf.length - off, 15000);
            if (n < 0) throw new Exception("bulk in timeout at " + off + "/" + buf.length);
            off += n;
        }
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    private static int get32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static int checksum(byte[] d) {
        int s = 0;
        for (byte b : d) s += (b & 0xff);
        return s;
    }

    // ---------------- handshake ----------------

    /** CNXN + AUTH handshake. Returns after the target authorizes this host. */
    public void connect(Context ctx) throws Exception {
        log.add("ADB: sending CNXN v" + (A_VERSION >>> 16) + "." + (A_VERSION & 0xffff));
        send(CMD_CNXN, A_VERSION, MAX_PAYLOAD,
                ("host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb,abb_exec,shell\u0000").getBytes("UTF-8"));

        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_CNXN) {
                log.add("ADB: connected (version " + m[1] + ", maxdata " + m[2] + ")");
                return;
            }
            if (m[0] == CMD_AUTH && m[1] == AUTH_TOKEN) {
                byte[] tok = pl.toByteArray();
                log.add("ADB: AUTH token " + tok.length + " bytes -> signing");
                send(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(ctx, tok));
                byte[] pk = publicKeyPayload(ctx);
                send(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pk);
                log.add("ADB: waiting for the target to accept this host key (confirm the RSA dialog on the target screen).");
            } else if (m[0] == CMD_CLSE) {
                throw new Exception("target closed the connection during handshake");
            }
        }
    }

    // ---------------- shell ----------------

    /** Opens a service stream, returns {localId, remoteId}. */
    private int[] openService(String service) throws Exception {
        int id = ++localId;
        send(CMD_OPEN, id, 0, (service + "\u0000").getBytes("UTF-8"));
        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_OKAY && m[1] == id) return new int[]{id, m[2]};
            if (m[0] == CMD_CLSE && m[1] == id) {
                String why = new String(pl.toByteArray(), "UTF-8").trim();
                throw new Exception("service rejected: " + (why.isEmpty() ? service : why));
            }
        }
    }

    /** Runs "adb shell <cmd>" and returns combined stdout+stderr as text. */
    public String exec(String cmd) throws Exception {
        int[] s = openService("shell:" + cmd);
        int lid = s[0], rid = s[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_WRTE && m[1] == rid) {
                out.write(pl.toByteArray());
                send(CMD_OKAY, lid, rid, null);
            } else if (m[0] == CMD_CLSE) {
                try { send(CMD_CLSE, lid, rid, null); } catch (Exception ignored) {}
                break;
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    /** Runs "adb shell <cmd>" returning raw bytes (for dd-based reads). */
    public byte[] execRaw(String cmd) throws Exception {
        int[] s = openService("shell:" + cmd);
        int lid = s[0], rid = s[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_WRTE && m[1] == rid) {
                out.write(pl.toByteArray());
                send(CMD_OKAY, lid, rid, null);
            } else if (m[0] == CMD_CLSE) {
                try { send(CMD_CLSE, lid, rid, null); } catch (Exception ignored) {}
                break;
            }
        }
        return out.toByteArray();
    }

    /** Streams a long-running shell command, invoking the listener for each chunk. */
    public void openShell(String command, final Listener l) throws Exception {
        int[] s = openService("shell:" + command);
        int lid = s[0], rid = s[1];
        log.add("ADB: shell stream open, remote " + rid);
        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_WRTE && m[1] == rid) {
                l.onLine(new String(pl.toByteArray(), "UTF-8"));
                send(CMD_OKAY, lid, rid, null);
            } else if (m[0] == CMD_CLSE) {
                l.onClosed();
                try { send(CMD_CLSE, lid, rid, null); } catch (Exception ignored) {}
                return;
            }
        }
    }

    // ---------------- sync: file transfer ----------------

    private static byte[] syncReq(int id, int value) {
        byte[] b = new byte[8];
        put32(b, 0, id); put32(b, 4, value);
        return b;
    }

    private static byte[] syncPath(String path) {
        byte[] p = path.getBytes();
        byte[] b = new byte[4 + p.length];
        put32(b, 0, p.length);
        System.arraycopy(p, 0, b, 4, p.length);
        return b;
    }

    /** Pushes bytes to remotePath via the sync protocol. Returns true on success. */
    public boolean put(byte[] data, String remotePath) throws Exception {
        int[] s = openService("sync:");
        int lid = s[0], rid = s[1];
        try {
            // SEND + "path,mode"
            byte[] pl = syncReq(SYNC_SEND, 0);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(pl);
            body.write(syncPath(remotePath + ",0644"));
            send(CMD_WRTE, lid, rid, body.toByteArray());
            if (!awaitOkay(lid, rid)) return false;

            int off = 0;
            while (off < data.length) {
                int n = Math.min(SYNC_DATA_MAX, data.length - off);
                byte[] chunk = new byte[8 + n];
                put32(chunk, 0, SYNC_DATA); put32(chunk, 4, n);
                System.arraycopy(data, off, chunk, 8, n);
                send(CMD_WRTE, lid, rid, chunk);
                if (!awaitOkay(lid, rid)) return false;
                off += n;
            }
            send(CMD_WRTE, lid, rid, syncReq(SYNC_DONE, (int) (System.currentTimeMillis() / 1000)));
            if (!awaitOkay(lid, rid)) return false;

            send(CMD_WRTE, lid, rid, syncReq(SYNC_QUIT, 0));
            try { send(CMD_CLSE, lid, rid, null); } catch (Exception ignored) {}
            return true;
        } finally {
            try { send(CMD_CLSE, lid, rid, null); } catch (Exception ignored) {}
        }
    }

    private boolean awaitOkay(int lid, int rid) throws Exception {
        while (true) {
            ByteArrayOutputStream pl = new ByteArrayOutputStream();
            int[] m = readMsg(pl);
            if (m[0] == CMD_OKAY) return true;
            if (m[0] == CMD_CLSE) {
                byte[] b = pl.toByteArray();
                if (b.length >= 4 && get32(b, 0) == SYNC_FAIL) {
                    log.add("ADB: sync FAIL");
                    return false;
                }
                return false;
            }
            byte[] b = pl.toByteArray();
            if (b.length >= 4 && get32(b, 0) == SYNC_FAIL) {
                log.add("ADB: sync FAIL");
                return false;
            }
        }
    }

    // ---------------- keys ----------------

    private static final String PREFS = "adb_keys";
    private static final String K_PRIV = "priv", K_PUB = "pub";

    public static KeyPair loadOrCreateKeyPair(Context ctx) throws Exception {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String privB64 = sp.getString(K_PRIV, null), pubB64 = sp.getString(K_PUB, null);
        if (privB64 != null && pubB64 != null) {
            try {
                byte[] priv = android.util.Base64.decode(privB64, android.util.Base64.NO_WRAP);
                byte[] pub = android.util.Base64.decode(pubB64, android.util.Base64.NO_WRAP);
                PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(priv));
                PublicKey pubKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pub));
                return new KeyPair(pubKey, pk);
            } catch (Exception ignored) { }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        sp.edit()
          .putString(K_PRIV, android.util.Base64.encodeToString(kp.getPrivate().getEncoded(), android.util.Base64.NO_WRAP))
          .putString(K_PUB, android.util.Base64.encodeToString(kp.getPublic().getEncoded(), android.util.Base64.NO_WRAP))
          .apply();
        return kp;
    }

    /** Reads the device-side public key out of the authorized_keys format for display/logging. */
    public static String publicKeyText(Context ctx) {
        try {
            KeyPair kp = loadOrCreateKeyPair(ctx);
            byte[] pub = kp.getPublic().getEncoded();
            return android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "(unavailable: " + e.getMessage() + ")";
        }
    }

    private byte[] signToken(Context ctx, byte[] token) throws Exception {
        if (kp == null) kp = loadOrCreateKeyPair(ctx);
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(kp.getPrivate());
        s.update(token);
        return s.sign();
    }

    /** Android adb wire format: base64( len32(prefix) || prefix || x509 ) + " user@host\0". */
    private byte[] publicKeyPayload(Context ctx) throws Exception {
        if (kp == null) kp = loadOrCreateKeyPair(ctx);
        byte[] prefix = "user@host".getBytes("UTF-8");
        byte[] x509 = kp.getPublic().getEncoded();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] l = new byte[4];
        put32(l, 0, prefix.length);
        o.write(l, 0, 4);
        o.write(prefix, 0, prefix.length);
        o.write(x509, 0, x509.length);
        String b64 = android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP);
        return Arrays.copyOf((b64 + " user@host\u0000").getBytes("UTF-8"), (b64 + " user@host\u0000").getBytes("UTF-8").length);
    }

    public int nextLocalId() { return ++localId; }
}

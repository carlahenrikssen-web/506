package com.aedora.frp;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Persistent diagnostic log, same text format as v2.1/v2.2: "[yyyy-MM-dd HH:mm:ss] message". */
public final class Logger {
    private static final String PREFS = "diag_log";
    private static final String KEY = "entries";
    private static final int MAX = 5000;

    private final SharedPreferences sp;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final List<String> entries = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    public interface Listener { void onChanged(); }

    private static Logger inst;

    public static synchronized Logger get(Context ctx) {
        if (inst == null) inst = new Logger(ctx.getApplicationContext());
        return inst;
    }

    private Logger(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String all = sp.getString(KEY, "");
        if (all != null && !all.isEmpty()) {
            for (String s : all.split("\n", -1)) if (!s.isEmpty()) entries.add(s);
        }
    }

    public synchronized void add(String msg) {
        entries.add("[" + fmt.format(new Date()) + "] " + msg);
        if (entries.size() > MAX) entries.subList(0, entries.size() - MAX).clear();
        persist();
        for (Listener l : new ArrayList<>(listeners)) l.onChanged();
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (String e : entries) sb.append(e).append('\n');
        sp.edit().putString(KEY, sb.toString()).apply();
    }

    public synchronized List<String> snapshot() { return new ArrayList<>(entries); }

    public synchronized String exportText() {
        StringBuilder sb = new StringBuilder();
        for (String e : entries) sb.append(e).append('\n');
        return sb.toString();
    }

    public synchronized void clear() {
        entries.clear();
        sp.edit().remove(KEY).apply();
        for (Listener l : new ArrayList<>(listeners)) l.onChanged();
    }

    public synchronized void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public synchronized void removeListener(Listener l) { listeners.remove(l); }
}

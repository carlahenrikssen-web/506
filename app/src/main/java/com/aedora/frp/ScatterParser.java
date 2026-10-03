package com.aedora.frp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for MediaTek "Android_scatter.txt" layout files (V1.1.x).
 * Reads the General Setting block plus every partition entry. Read-only: no device access.
 */
public final class ScatterParser {

    public static final class Partition {
        public String index, name, fileName, type, region, storage, isDownload, boundaryCheck;
        public long linearStart, physStart, size;

        public String toLine() {
            return String.format(Locale.US, "%-8s %-14s phys=%-12s size=%-10s %-10s %s",
                    index == null ? "-" : index,
                    name == null ? "-" : name,
                    MtkConstants.hex(physStart),
                    MtkConstants.hex(size),
                    MtkConstants.humanSize(size),
                    region == null ? "-" : region);
        }

        public String toCsv() {
            return q(index) + "," + q(name) + "," + MtkConstants.hex(linearStart) + ","
                    + MtkConstants.hex(physStart) + "," + size + "," + q(region) + ","
                    + q(storage) + "," + q(type) + "," + q(isDownload) + "," + q(fileName);
        }

        private static String q(String s) {
            if (s == null) return "\"\"";
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
    }

    public final Map<String, String> general = new LinkedHashMap<>();
    public final List<Partition> partitions = new ArrayList<>();
    public String sourceName = "";

    public static ScatterParser parse(String text, String name) {
        ScatterParser sp = new ScatterParser();
        sp.sourceName = name == null ? "" : name;
        if (text == null) return sp;

        Map<String, String> cur = null;
        boolean inGeneral = false;

        for (String raw : text.split("\n")) {
            String line = raw.replace("\r", "").trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("- general:")) { inGeneral = true; cur = null; continue; }

            if (line.startsWith("- partition_index:")) {
                if (cur != null) sp.finishPartition(cur);
                cur = new LinkedHashMap<>();
                inGeneral = false;
                cur.put("partition_index", afterColon(line));
                continue;
            }

            String kv = line.startsWith("- ") ? line.substring(2) : line;
            int c = kv.indexOf(':');
            if (c < 0) continue;
            String k = kv.substring(0, c).trim();
            String v = kv.substring(c + 1).trim();
            if (k.isEmpty()) continue;

            if (inGeneral) sp.general.put(k, v);
            else if (cur != null) cur.put(k, v);
        }
        if (cur != null) sp.finishPartition(cur);
        return sp;
    }

    private static String afterColon(String line) {
        int c = line.indexOf(':');
        return c < 0 ? "" : line.substring(c + 1).trim();
    }

    private void finishPartition(Map<String, String> m) {
        Partition p = new Partition();
        p.index = m.get("partition_index");
        p.name = m.get("partition_name");
        p.fileName = m.get("file_name");
        p.type = m.get("type");
        p.region = m.get("region");
        p.storage = m.get("storage");
        p.isDownload = m.get("is_download");
        p.boundaryCheck = m.get("boundary_check");
        p.linearStart = MtkConstants.parseHex(m.get("linear_start_addr"));
        p.physStart = MtkConstants.parseHex(m.get("physical_start_addr"));
        p.size = MtkConstants.parseHex(m.get("partition_size"));
        partitions.add(p);
    }

    public String platform() { return general.get("platform"); }
    public String project() { return general.get("project"); }
    public String storage() { return general.get("storage"); }
    public String blockSize() { return general.get("block_size"); }

    public long totalSize() {
        long t = 0L;
        for (Partition p : partitions) t += p.size;
        return t;
    }

    public Partition byName(String name) {
        if (name == null) return null;
        for (Partition p : partitions) if (name.equalsIgnoreCase(p.name)) return p;
        return null;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Scatter: ").append(sourceName).append('\n');
        sb.append("Platform: ").append(nz(platform())).append("   Project: ").append(nz(project())).append('\n');
        sb.append("Storage: ").append(nz(storage())).append("   Block size: ").append(nz(blockSize())).append('\n');
        sb.append("Partitions: ").append(partitions.size())
          .append("   Total mapped: ").append(MtkConstants.humanSize(totalSize())).append('\n');
        return sb.toString();
    }

    public String table() {
        StringBuilder sb = new StringBuilder();
        for (Partition p : partitions) sb.append("  ").append(p.toLine()).append('\n');
        return sb.toString();
    }

    public String manifestCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("index,partition_name,linear_start,physical_start,size_bytes,region,storage,type,is_download,file_name\n");
        for (Partition p : partitions) sb.append(p.toCsv()).append('\n');
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "-" : s; }
}

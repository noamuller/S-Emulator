package server.core;

public class SimpleJson {
    public static String str(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"","\\\"") + "\"";
    }
    public static String kv(String k, String v) { return str(k)+":"+str(v); }
    public static String kv(String k, int v) { return str(k)+":"+v; }
}

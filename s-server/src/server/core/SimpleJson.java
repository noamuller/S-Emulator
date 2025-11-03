package server.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.*;

/**
 * Minimal JSON helper: supports Maps, Lists, String, Number, Boolean, null.
 * API:
 *   - parse(String) -> Map<String,Object> (wraps non-object roots under "value")
 *   - write(Writer, Object)
 *   - write(PrintWriter, Object)
 */
public final class SimpleJson {

    private SimpleJson() {}

    /* ===================== Public API ===================== */

    public static Map<String, Object> parse(String json) {
        Object v = Parser.parse(json == null ? "" : json);
        if (v instanceof Map<?,?> m) {
            @SuppressWarnings("unchecked")
            Map<String,Object> mm = (Map<String,Object>) m;
            return mm;
        }
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("value", v);
        return wrap;
    }

    public static void write(Writer w, Object value) throws IOException {
        w.write(WriterUtil.stringify(value));
    }

    public static void write(PrintWriter w, Object value) {
        w.write(WriterUtil.stringify(value));
    }

    /* ===================== Writer ===================== */

    private static final class WriterUtil {
        static String stringify(Object o) {
            StringBuilder sb = new StringBuilder();
            writeVal(sb, o);
            return sb.toString();
        }
        static void writeVal(StringBuilder sb, Object v) {
            if (v == null) { sb.append("null"); return; }
            if (v instanceof String s) { writeStr(sb, s); return; }
            if (v instanceof Number || v instanceof Boolean) { sb.append(String.valueOf(v)); return; }
            if (v instanceof Map<?,?> map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?,?> e : map.entrySet()) {
                    if (!first) sb.append(',');
                    writeStr(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeVal(sb, e.getValue());
                    first = false;
                }
                sb.append('}');
                return;
            }
            if (v instanceof Iterable<?> it) {
                sb.append('[');
                boolean first = true;
                for (Object x : it) {
                    if (!first) sb.append(',');
                    writeVal(sb, x);
                    first = false;
                }
                sb.append(']');
                return;
            }
            writeStr(sb, String.valueOf(v));
        }
        static void writeStr(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }

    /* ===================== Parser ===================== */

    private static final class Parser {
        final String s; int i=0, n;
        private Parser(String s){ this.s = (s==null? "" : s); this.n = this.s.length(); }
        static Object parse(String s){ return new Parser(s).readValue(); }

        Object readValue() {
            skipWs();
            if (i>=n) return null;
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> readObj();
                case '[' -> readArr();
                case '"' -> readStr();
                case 't', 'f' -> readBool();
                case 'n' -> readNull();
                default -> readNumOrBare();
            };
        }

        Map<String,Object> readObj() {
            i++; // {
            Map<String,Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) { i++; return m; }
            while (true) {
                String k = readStr();
                skipWs(); expect(':'); i++;
                Object v = readValue();
                m.put(k, v);
                skipWs();
                if (peek('}')) { i++; break; }
                expect(','); i++;
                skipWs();
            }
            return m;
        }

        List<Object> readArr() {
            i++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) { i++; return list; }
            while (true) {
                list.add(readValue());
                skipWs();
                if (peek(']')) { i++; break; }
                expect(','); i++;
                skipWs();
            }
            return list;
        }

        String readStr() {
            expect('"'); i++;
            StringBuilder sb = new StringBuilder();
            while (i<n) {
                char c = s.charAt(i++);
                if (c=='"') break;
                if (c=='\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            String hex = s.substring(i, Math.min(i+4, n));
                            int code = Integer.parseInt(hex, 16);
                            sb.append((char) code);
                            i += 4;
                            break;
                        }
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Boolean readBool() {
            if (s.startsWith("true", i)) { i+=4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i+=5; return Boolean.FALSE; }
            throw new RuntimeException("Invalid boolean at " + i);
        }

        Object readNull() {
            if (s.startsWith("null", i)) { i+=4; return null; }
            throw new RuntimeException("Invalid null at " + i);
        }

        Object readNumOrBare() {
            int j=i;
            while (i<n) {
                char c = s.charAt(i);
                if ((c>='0' && c<='9') || c=='+' || c=='-' || c=='.' || c=='e' || c=='E') { i++; continue; }
                break;
            }
            String token = s.substring(j,i).trim();
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.parseDouble(token);
                long L = Long.parseLong(token);
                if (L >= Integer.MIN_VALUE && L <= Integer.MAX_VALUE) return (int)L;
                return L;
            } catch (NumberFormatException e) {
                return token;
            }
        }

        void skipWs(){ while (i<n && Character.isWhitespace(s.charAt(i))) i++; }
        boolean peek(char c){ return i<n && s.charAt(i)==c; }
        void expect(char c){ if (!peek(c)) throw new RuntimeException("Expected '"+c+"' at "+i); }
    }
}

package sengine;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VariableRef implements Comparable<VariableRef> {
    public enum Kind { X, Z, Y }
    private final Kind kind;
    private final int index;

    private VariableRef(Kind kind, int index) {
        this.kind = kind;
        this.index = index;
    }

    public static VariableRef y() { return new VariableRef(Kind.Y, 0); }

    public static VariableRef of(Kind k, int idx) { return new VariableRef(k, idx); }

    public static VariableRef parse(String token) {
        token = token.trim().toLowerCase();
        if (token.equals("y")) return y();
        Pattern p = Pattern.compile("^([xz])(\\d+)$", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(token);
        if (!m.matches()) throw new IllegalArgumentException("Bad var: " + token);
        Kind k = m.group(1).equalsIgnoreCase("x") ? Kind.X : Kind.Z;
        int idx = Integer.parseInt(m.group(2));
        return of(k, idx);
    }

    public String name() {
        return switch (kind) {
            case Y -> "y";
            case X -> "x" + index;
            case Z -> "z" + index;
        };
    }

    public Kind kind() { return kind; }
    public int index() { return index; }
    @Override public String toString() { return name(); }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VariableRef v)) return false;
        return kind == v.kind && index == v.index;
    }
    @Override public int hashCode() { return Objects.hash(kind, index); }
    @Override public int compareTo(VariableRef o) {
        if (this.kind == Kind.Y && o.kind != Kind.Y) return -1;
        if (this.kind != Kind.Y && o.kind == Kind.Y) return 1;
        if (this.kind != o.kind) return this.kind.ordinal() - o.kind.ordinal();
        return Integer.compare(this.index, o.index);
    }
}

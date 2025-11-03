package sengine;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public abstract class Instruction {
    public final String label;
    public final String text;
    public final boolean basic;
    public final int declaredCycles;
    public final Instruction origin;

    protected Instruction(String label, String text, boolean basic, int declaredCycles, Instruction origin) {
        this.label = label;
        this.text = text;
        this.basic = basic;
        this.declaredCycles = declaredCycles;
        this.origin = origin;
    }

    public abstract int cycles();
    public abstract List<Instruction> expand();

    public String prettyType() { return basic ? "B" : "S"; }

    public String formatLine(int number) {
        String lbl = label == null ? "" : label;
        String lblBox = String.format("[%-5s]", lbl);
        int c = cycles();
        return String.format("#%d (%s) %s %s (%d)", number, prettyType(), lblBox, text, c);
    }

    private static final Pattern P_INC   = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*\\1\\s*\\+\\s*1\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DEC   = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*\\1\\s*-\\s*1\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_NOP   = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*\\1\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_IFNZ  = Pattern.compile("^\\s*IF\\s+([xyz]\\d*|y)\\s*!=\\s*0\\s*GOTO\\s*(EXIT|L\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_SET0  = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*0\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SETN  = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ADDN  = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*\\1\\s*\\+\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SUBN  = Pattern.compile("^\\s*([xyz]\\d*|y)\\s*<-\\s*\\1\\s*-\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ASSIGN= Pattern.compile("^\\s*ASSIGN\\s+([xyz]\\d*|y)\\s*<-\\s*([xyz]\\d*|y)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_GOTO  = Pattern.compile("^\\s*GOTO\\s*(EXIT|L\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_IFZ   = Pattern.compile("^\\s*IF\\s+([xyz]\\d*|y)\\s*==\\s*0\\s*GOTO\\s*(EXIT|L\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_IFEQC = Pattern.compile("^\\s*IF\\s+([xyz]\\d*|y)\\s*==\\s*(\\d+)\\s*GOTO\\s*(EXIT|L\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_IFEQV = Pattern.compile("^\\s*IF\\s+([xyz]\\d*|y)\\s*==\\s*([xyz]\\d*|y)\\s*GOTO\\s*(EXIT|L\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    public static Instruction parseFromText(String label, String cmdText, String typeHint, Integer cyclesOpt) {
        String t = cmdText.trim();
        int declared = cyclesOpt == null ? -1 : cyclesOpt;
        Matcher m;


        if ((m = P_INC.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            return new Inc(label, t, declared, v);
        }
        if ((m = P_DEC.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            return new Dec(label, t, declared, v);
        }
        if ((m = P_NOP.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            return new Nop(label, t, declared, v);
        }
        if ((m = P_IFNZ.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            String target = m.group(2).toUpperCase(Locale.ROOT);
            return new IfNzGoto(label, t, declared, v, target);
        }


        if ((m = P_SET0.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            return new SetZero(label, t, declared, v);
        }
        if ((m = P_SETN.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            int n = Integer.parseInt(m.group(2));
            return new SetConst(label, t, declared, v, n);
        }
        if ((m = P_ADDN.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            int n = Integer.parseInt(m.group(2));
            return new AddConst(label, t, declared, v, n);
        }
        if ((m = P_SUBN.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            int n = Integer.parseInt(m.group(2));
            return new SubConst(label, t, declared, v, n);
        }
        if ((m = P_ASSIGN.matcher(t)).matches()) {
            VariableRef dst = VariableRef.parse(m.group(1));
            VariableRef src = VariableRef.parse(m.group(2));
            return new Assign(label, t, declared, dst, src);
        }
        if ((m = P_GOTO.matcher(t)).matches()) {
            String target = m.group(1).toUpperCase(Locale.ROOT);
            return new Goto(label, t, declared, target);
        }
        if ((m = P_IFZ.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            String target = m.group(2).toUpperCase(Locale.ROOT);
            return new IfZeroGoto(label, t, declared, v, target);
        }
        if ((m = P_IFEQC.matcher(t)).matches()) {
            VariableRef v = VariableRef.parse(m.group(1));
            int c = Integer.parseInt(m.group(2));
            String target = m.group(3).toUpperCase(Locale.ROOT);
            return new IfEqConstGoto(label, t, declared, v, c, target);
        }
        if ((m = P_IFEQV.matcher(t)).matches()) {
            VariableRef a = VariableRef.parse(m.group(1));
            VariableRef b = VariableRef.parse(m.group(2));
            String target = m.group(3).toUpperCase(Locale.ROOT);
            return new IfEqVarGoto(label, t, declared, a, b, target);
        }

        if (typeHint != null && typeHint.equalsIgnoreCase("B"))
            return new OpaqueBasic(label, t, declared);
        return new OpaqueSynthetic(label, t, declared);
    }

    static final class Inc extends Instruction {
        final VariableRef v;
        Inc(String label, String text, int declaredCycles, VariableRef v) { super(label, text, true, declaredCycles, null); this.v=v; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class Dec extends Instruction {
        final VariableRef v;
        Dec(String label, String text, int declaredCycles, VariableRef v) { super(label, text, true, declaredCycles, null); this.v=v; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class Nop extends Instruction {
        final VariableRef v;
        Nop(String label, String text, int declaredCycles, VariableRef v) { super(label, text, true, declaredCycles, null); this.v=v; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class IfNzGoto extends Instruction {
        final VariableRef v; final String target;
        IfNzGoto(String label, String text, int declaredCycles, VariableRef v, String target) { super(label, text, true, declaredCycles, null); this.v=v; this.target=target; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 2; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class OpaqueBasic extends Instruction {
        OpaqueBasic(String label, String text, int declaredCycles) { super(label, text, true, declaredCycles, null); }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }

    static abstract class Synthetic extends Instruction {
        protected Synthetic(String label, String text, int declaredCycles) { super(label, text, false, declaredCycles, null); }
    }

    static final class Assign extends Synthetic {
        final VariableRef dst, src;
        Assign(String label, String text, int declaredCycles, VariableRef dst, VariableRef src) {
            super(label, text, declaredCycles); this.dst=dst; this.src=src;
        }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class Goto extends Synthetic {
        final String target;
        Goto(String label, String text, int declaredCycles, String target) { super(label, text, declaredCycles); this.target=target; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class SetZero extends Synthetic {
        final VariableRef v;
        SetZero(String label, String text, int declaredCycles, VariableRef v) { super(label, text, declaredCycles); this.v=v; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class SetConst extends Synthetic {
        final VariableRef v; final int n;
        SetConst(String label, String text, int declaredCycles, VariableRef v, int n) { super(label, text, declaredCycles); this.v=v; this.n=n; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : Math.max(1,n); }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class AddConst extends Synthetic {
        final VariableRef v; final int n;
        AddConst(String label, String text, int declaredCycles, VariableRef v, int n) { super(label, text, declaredCycles); this.v=v; this.n=n; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : n; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class SubConst extends Synthetic {
        final VariableRef v; final int n;
        SubConst(String label, String text, int declaredCycles, VariableRef v, int n) { super(label, text, declaredCycles); this.v=v; this.n=n; }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : n; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }

    static final class IfZeroGoto extends Instruction {
        final VariableRef v; final String target;
        IfZeroGoto(String label, String text, int declaredCycles, VariableRef v, String target) {
            super(label, text, true, declaredCycles, null); this.v=v; this.target=target;
        }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 2; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class IfEqConstGoto extends Instruction {
        final VariableRef v; final int c; final String target;
        IfEqConstGoto(String label, String text, int declaredCycles, VariableRef v, int c, String target) {
            super(label, text, true, declaredCycles, null); this.v=v; this.c=c; this.target=target;
        }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 2; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
    static final class IfEqVarGoto extends Instruction {
        final VariableRef a,b; final String target;
        IfEqVarGoto(String label, String text, int declaredCycles, VariableRef a, VariableRef b, String target) {
            super(label, text, true, declaredCycles, null); this.a=a; this.b=b; this.target=target;
        }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 2; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }

    static final class OpaqueSynthetic extends Synthetic {
        OpaqueSynthetic(String label, String text, int declaredCycles) { super(label, text, declaredCycles); }
        @Override public int cycles() { return declaredCycles > 0 ? declaredCycles : 1; }
        @Override public List<Instruction> expand() { return List.of(this); }
    }
}

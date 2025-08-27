package sengine;

import java.util.*;

public final class RunHistory {
    public static final class Entry {
        public final int runNo;
        public final int degree;
        public final List<Integer> inputs;
        public final int y;
        public final int cycles;
        Entry(int runNo, int degree, List<Integer> inputs, int y, int cycles) {
            this.runNo=runNo; this.degree=degree; this.inputs=List.copyOf(inputs); this.y=y; this.cycles=cycles;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(int degree, java.util.List<Integer> inputs, int y, int cycles) {
        entries.add(new Entry(entries.size()+1, degree, inputs, y, cycles));
    }

    public List<Entry> all() { return List.copyOf(entries); }

    public boolean isEmpty() { return entries.isEmpty(); }
}

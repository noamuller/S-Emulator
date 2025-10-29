package gui;

/** Single row in the instructions table. */
public class TraceRow {
    private final int index;
    private final String type;
    private final String label;
    private final String instr;
    private final int cycles;

    public TraceRow(int index, String type, String label, String instr, int cycles) {
        this.index = index;
        this.type = type == null ? "" : type;
        this.label = label == null ? "" : label;
        this.instr = instr == null ? "" : instr;
        this.cycles = cycles;
    }

    // JavaFX getters
    public int getIndex() { return index; }
    public String getType() { return type; }
    public String getLabel() { return label; }
    public String getInstr() { return instr; }
    public int getCycles() { return cycles; }

    // Aliases if your cell value factories call these names
    public int index() { return index; }
    public String type() { return type; }
    public String label() { return label; }
    public String text() { return instr; }
    public int cycles() { return cycles; }
}

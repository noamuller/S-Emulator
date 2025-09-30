package gui;

import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import sengine.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S-Emulator – תרגיל 2
 * Full controller: load XML (Task), render degree 0/1, run, history, highlight,
 * and step-by-step debugger (start/step/resume/stop) with current-row highlight.
 */
public class MainController {

    // ---------- FXML controls ----------
    @FXML private TextField programNameField;
    @FXML private TextField inputsField;
    @FXML private TextField degreeField;
    @FXML private TextField maxDegreeField;

    @FXML private TableView<Row> instructionsTable;
    @FXML private TableColumn<Row, Number> colNum;
    @FXML private TableColumn<Row, String>  colType;
    @FXML private TableColumn<Row, String>  colLabel;
    @FXML private TableColumn<Row, String>  colText;
    @FXML private TableColumn<Row, Number> colCycles;

    @FXML private ListView<String> originList;

    @FXML private TextArea labelsArea;
    @FXML private ListView<String> varsList;

    @FXML private Label pcLabel;
    @FXML private Label cyclesLabel;
    @FXML private Label haltLabel;

    @FXML private ProgressBar loadProgress;
    @FXML private TextField highlightLabelField;
    @FXML private TextField highlightVarField;

    @FXML private ComboBox<String> functionCombo;

    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, Number> hColRun;
    @FXML private TableColumn<HistoryRow, Number> hColDegree;
    @FXML private TableColumn<HistoryRow, String>  hColInputs;
    @FXML private TableColumn<HistoryRow, Number> hColY;
    @FXML private TableColumn<HistoryRow, Number> hColCycles;

    @FXML private TextArea debugSummaryArea;
    @FXML private TextArea statusArea;

    @FXML private TitledPane historyPane;


    // ---------- Model ----------
    private Program currentProgram = null;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    // simple GUI history (degree, inputs, y, cycles)
    private final List<HistoryItem> history = new ArrayList<>();
    private final ObservableList<HistoryRow> historyRows = FXCollections.observableArrayList();

    // ---------- Debugger state ----------
    private Debugger debugger = null;
    private volatile boolean debugAutoRun = false; // used by Resume loop
    private int currentDebugPc = -1;               // for row highlight during debug

    // ---------- Init ----------
    @FXML
    private void initialize() {
        // instructions table
        colNum.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().num));
        colType.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().type));
        colLabel.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().label));
        colText.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().text));
        colCycles.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().cycles));
        instructionsTable.setItems(rows);
        instructionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        // selection → origin chain
        instructionsTable.getSelectionModel().selectedIndexProperty().addListener((obs, oldI, newI) -> {
            if (newI == null || newI.intValue() < 0 || currentProgram == null) {
                originList.setItems(FXCollections.observableArrayList());
                return;
            }
            int idx = newI.intValue();
            try {
                int deg = parseIntOrZero(degreeField.getText());
                Program.Rendered r = currentProgram.expandToDegree(clamp(deg, 0, currentProgram.maxDegree()));
                List<String> chain = r.originChains.get(idx);
                if (chain == null || chain.isEmpty()) {
                    originList.setItems(FXCollections.observableArrayList("No origin (basic)."));
                } else {
                    originList.setItems(FXCollections.observableArrayList(chain));
                }
            } catch (Exception ex) {
                originList.setItems(FXCollections.observableArrayList("No origin"));
            }
        });

        // function selector (unused in view mode)
        if (functionCombo != null) functionCombo.setDisable(true);

        // history table
        hColRun.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().runNo));
        hColDegree.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().degree));
        hColInputs.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().inputs));
        hColY.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().y));
        hColCycles.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().cycles));
        historyTable.setItems(historyRows);

        // initial UI state
        if (pcLabel != null) pcLabel.setText("-");
        if (cyclesLabel != null) cyclesLabel.setText("0");
        if (haltLabel != null) haltLabel.setText("false");
        if (statusArea != null) statusArea.setText("Ready.\n");
        if (loadProgress != null) { loadProgress.setVisible(false); loadProgress.setManaged(false); }
    }

    // ---------- Actions: Load / Show / Expand / Run / History ----------

    @FXML
    private void onLoadXml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load S-Program XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File f = fc.showOpenDialog(programNameField.getScene().getWindow());
        if (f == null) return;

        loadProgress.setProgress(0);
        loadProgress.setVisible(true);
        loadProgress.setManaged(true);

        javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
            @Override protected Void call() throws Exception {
                updateProgress(0.1, 1);
                Thread.sleep(200);

                Program p;
                try {
                    File xsd = new File("S-Emulator-v2.xsd");
                    if (xsd.exists()) ProgramParser.validateWithXsd(f, xsd);
                    p = ProgramParser.parseFromXml(f);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Failed to load: " + ex.getMessage(), ex);
                }
                updateProgress(0.5, 1);

                String err = ProgramParser.validateLabels(p);
                if (err != null) throw new IllegalArgumentException(err);

                Thread.sleep(800);
                final Program program = p;

                Platform.runLater(() -> {
                    currentProgram = program;
                    programNameField.setText(program.name);
                    maxDegreeField.setText(String.valueOf(program.maxDegree()));
                    degreeField.setText("0");
                    labelsArea.setText(joinLabels(program));

                    rows.clear();
                    originList.setItems(FXCollections.observableArrayList());
                    varsList.setItems(FXCollections.observableArrayList());
                    pcLabel.setText("-");
                    cyclesLabel.setText("0");
                    haltLabel.setText("false");

                    // variables referenced preview
                    Set<VariableRef> vars = program.referencedVariables();
                    List<String> vnames = vars.stream().map(VariableRef::toString).toList();
                    varsList.setItems(FXCollections.observableArrayList(vnames));

                    appendStatus("Loaded \"" + program.name + "\".");
                });

                updateProgress(1.0, 1);
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            loadProgress.setVisible(false);
            loadProgress.setManaged(false);
        });
        task.setOnFailed(e -> {
            loadProgress.setVisible(false);
            loadProgress.setManaged(false);
            Throwable ex = task.getException();
            showError(ex == null ? "Unknown error" : ex.getMessage());
        });

        loadProgress.progressProperty().bind(task.progressProperty());
        new Thread(task, "xml-loader").start();
    }

    @FXML
    private void onShowProgram() {
        clearHighlightsIfAny();
        if (!ensureProgram()) return;
        try {
            int deg = clamp(parseIntOrZero(degreeField.getText()), 0, currentProgram.maxDegree());
            Program.Rendered r = currentProgram.expandToDegree(deg);
            rows.setAll(toRows(r));
            appendStatus("Program listed (degree " + deg + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    @FXML
    private void onExpand() { onShowProgram(); }

    @FXML
    private void onRun() {
        if (!ensureProgram()) return;
        try {
            int deg = clamp(parseIntOrZero(degreeField.getText()), 0, currentProgram.maxDegree());
            List<Integer> inputs = parseInputs(inputsField.getText());
            Runner.RunResult rr = Runner.run(currentProgram, deg, inputs);

            // refresh table to the rendered we executed
            rows.setAll(toRows(rr.rendered));

            // show simple live values
            pcLabel.setText("-");
            cyclesLabel.setText(String.valueOf(rr.cycles));
            haltLabel.setText("true");

            // variables box
            setVariablesBox(rr.variables);

            // push to GUI history
            int runNo = history.size() + 1;
            history.add(new HistoryItem(runNo, rr.degree, inputs, rr.y, rr.cycles));
            historyRows.add(new HistoryRow(runNo, rr.degree, inputsToString(inputs), rr.y, rr.cycles));

            appendStatus("Run finished • y = " + rr.y + " • cycles = " + rr.cycles);
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    @FXML
    private void onShowHistory() {
        if (historyPane == null) return;

        // Toggle expanded state
        boolean nowExpanded = !historyPane.isExpanded();
        historyPane.setExpanded(nowExpanded);

        if (nowExpanded) {
            // Only when opening: show last run(s)
            if (!historyRows.isEmpty()) {
                historyTable.getSelectionModel().selectLast();
                historyTable.scrollTo(historyRows.size() - 1);
                appendStatus("Showing " + historyRows.size() + " run(s).");
            } else {
                appendStatus("No runs yet.");
            }
        } else {
            appendStatus("History hidden.");
        }
    }




    @FXML
    private void onRerunSelected() {
        HistoryRow sel = historyTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            appendStatus("Select a history row first.");
            return;
        }
        degreeField.setText(String.valueOf(sel.degree));
        inputsField.setText(sel.inputs);
        onRun();
    }

    // ---------- Highlighting ----------

    @FXML
    private void onHighlightLabel() {
        String want = highlightLabelField.getText();
        if (want == null || want.isBlank()) return;
        applyRowHighlight(r -> want.equalsIgnoreCase(r.label), "highlight-label");
    }

    @FXML
    private void onHighlightVar() {
        String want = highlightVarField.getText();
        if (want == null || want.isBlank()) return;
        applyRowHighlight(r -> r.text != null && r.text.matches(".*\\b" + want + "\\b.*"), "highlight-var");
    }

    @FXML
    private void onClearHighlight() {
        instructionsTable.setRowFactory(tv -> new TableRow<>());
    }

    private interface RowPredicate { boolean test(Row r); }

    private void applyRowHighlight(RowPredicate pred, String styleClass) {
        instructionsTable.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("highlight-label", "highlight-var", "debug-current");
                if (!empty && item != null && pred.test(item)) {
                    if (!getStyleClass().contains(styleClass)) getStyleClass().add(styleClass);
                }
                if (!empty && getIndex() == currentDebugPc) {
                    if (!getStyleClass().contains("debug-current")) getStyleClass().add("debug-current");
                }
            }
        });
    }

    // ---------- Debugger (Start / Step / Resume / Stop) ----------

    @FXML private void onStartRegular() {
        onStartDebug();
        onResume();
    }

    @FXML private void onStartDebug() {
        try {
            if (!ensureProgram()) return;
            int deg = clamp(parseIntOrZero(degreeField.getText()), 0, currentProgram.maxDegree());
            List<Integer> inputs = parseInputs(inputsField.getText());

            debugger = new Debugger(currentProgram, deg, inputs);

            // sync table to what we'll step through
            syncTableToRendered(debugger.rendered());

            Debugger.Snapshot s = debugger.snapshot();
            refreshDebugUi(s);
            appendStatus("Debug session started (degree " + deg + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
            debugger = null;
            highlightPc(-1);
        }
    }

    @FXML private void onStepOver() {
        if (debugger == null) { appendStatus("Start Debug first."); return; }
        try {
            Debugger.Snapshot s = debugger.step();
            refreshDebugUi(s);
            if (s.halted) appendStatus("Halted.");
        } catch (Exception ex) {
            showError(ex.getMessage());
            debugger = null;
            highlightPc(-1);
        }
    }

    @FXML private void onResume() {
        if (debugger == null) { appendStatus("Start Debug first."); return; }
        if (debugAutoRun) return;

        debugAutoRun = true;
        Thread t = new Thread(() -> {
            try {
                while (debugAutoRun && debugger != null) {
                    Debugger.Snapshot s = debugger.step();
                    Platform.runLater(() -> refreshDebugUi(s));
                    if (s.halted) {
                        debugAutoRun = false;
                        Platform.runLater(() -> appendStatus("Halted."));
                        break;
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ignore) {}
                }
            } catch (Exception ex) {
                debugAutoRun = false;
                Platform.runLater(() -> showError(ex.getMessage()));
            }
        }, "debug-resume");
        t.setDaemon(true);
        t.start();
    }

    @FXML private void onStop() {
        debugAutoRun = false;
        debugger = null;
        highlightPc(-1);
        appendStatus("Debug session stopped.");
    }

    // ---------- Helpers used by debugger ----------

    private void highlightPc(int pc) {
        currentDebugPc = pc;
        instructionsTable.setRowFactory(tv -> new TableRow<Row>() {
            @Override protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("debug-current", "highlight-label", "highlight-var");
                if (!empty && getIndex() == currentDebugPc) {
                    if (!getStyleClass().contains("debug-current")) getStyleClass().add("debug-current");
                }
            }
        });
        if (pc >= 0) {
            instructionsTable.getSelectionModel().select(pc);
            instructionsTable.scrollTo(Math.max(pc - 3, 0));
        } else {
            instructionsTable.getSelectionModel().clearSelection();
        }
    }

    private void refreshDebugUi(Debugger.Snapshot s) {
        if (pcLabel != null)     pcLabel.setText(s.pc < 0 ? "-" : String.valueOf(s.pc + 1));
        if (cyclesLabel != null) cyclesLabel.setText(String.valueOf(s.cycles));
        if (haltLabel != null)   haltLabel.setText(String.valueOf(s.halted));
        setVariablesBox(s.vars);
        if (debugSummaryArea != null && !s.changed.isEmpty()) {
            String msg = s.changed.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            debugSummaryArea.appendText("Changed: " + msg + "\n");
        }
        highlightPc(s.pc);
    }

    private void syncTableToRendered(Program.Rendered r) {
        rows.setAll(toRows(r));
    }

    // ---------- Utilities ----------

    private boolean ensureProgram() {
        if (currentProgram != null) return true;
        showError("No program loaded.");
        return false;
    }

    private void showError(String msg) {
        appendStatus("Error: " + msg);
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }

    private List<Row> toRows(Program.Rendered r) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < r.list.size(); i++) {
            Instruction inst = r.list.get(i);
            String type = inst.basic ? "B" : "S";
            out.add(new Row(i + 1, type, inst.label == null ? "" : inst.label, inst.text, inst.cycles()));
        }
        return out;
    }

    private String joinLabels(Program p) { return String.join(", ", p.labelsUsed()); }

    private void setVariablesBox(LinkedHashMap<String,Integer> map) {
        List<String> xs = new ArrayList<>();
        List<String> zs = new ArrayList<>();
        for (String k : map.keySet()) {
            if (k.equals("y")) continue;
            if (k.startsWith("x")) xs.add(k);
            else if (k.startsWith("z")) zs.add(k);
        }
        xs.sort(Comparator.comparingInt(s -> safeInt(s.substring(1))));
        zs.sort(Comparator.comparingInt(s -> safeInt(s.substring(1))));

        List<String> lines = new ArrayList<>();
        lines.add("y = " + map.getOrDefault("y", 0));
        for (String k : xs) lines.add(k + " = " + map.get(k));
        for (String k : zs) lines.add(k + " = " + map.get(k));
        varsList.setItems(FXCollections.observableArrayList(lines));
    }

    private List<Integer> parseInputs(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .map(this::safeInt)
                .map(v -> Math.max(0, v))
                .collect(Collectors.toList());
    }

    private String inputsToString(List<Integer> xs) {
        if (xs == null || xs.isEmpty()) return "";
        return xs.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private int parseIntOrZero(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private int safeInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void clearHighlightsIfAny() {
        onClearHighlight();
        if (highlightLabelField != null) highlightLabelField.clear();
        if (highlightVarField != null)   highlightVarField.clear();
        highlightPc(-1);
    }

    private void appendStatus(String msg) { statusArea.appendText(msg + "\n"); }

    // ---------- Small view models ----------
    public static final class Row {
        final int num;
        final String type;
        final String label;
        final String text;
        final int cycles;
        Row(int num, String type, String label, String text, int cycles) {
            this.num=num; this.type=type; this.label=label; this.text=text; this.cycles=cycles;
        }
    }
    private static final class HistoryItem {
        final int runNo; final int degree; final List<Integer> inputs; final int y; final int cycles;
        HistoryItem(int runNo, int degree, List<Integer> inputs, int y, int cycles) {
            this.runNo=runNo; this.degree=degree; this.inputs=inputs; this.y=y; this.cycles=cycles;
        }
    }
    public static final class HistoryRow {
        final int runNo, degree, y, cycles;
        final String inputs;
        HistoryRow(int runNo, int degree, String inputs, int y, int cycles) {
            this.runNo = runNo; this.degree = degree; this.inputs = inputs; this.y = y; this.cycles = cycles;
        }
    }
}

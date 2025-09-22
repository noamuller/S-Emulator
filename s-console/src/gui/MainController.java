package gui;

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
 * GUI controller for Exercise 2.
 * Implements: Load XML, Show Program, Expand (view only), Run, History view,
 * plus minimal UI scaffolding for function selector, debugger panel, and a history table.
 */
public class MainController {

    // ---------- FXML controls ----------
    @FXML private TextField programNameField;
    @FXML private TextField inputsField;
    @FXML private TextField degreeField;
    @FXML private TextField maxDegreeField;
    @FXML private TextArea  labelsArea;

    @FXML private TableView<Row> instructionsTable;
    @FXML private TableColumn<Row, Number> colNum;
    @FXML private TableColumn<Row, String> colType;
    @FXML private TableColumn<Row, String> colLabel;
    @FXML private TableColumn<Row, String> colText;
    @FXML private TableColumn<Row, Number> colCycles;

    @FXML private ListView<String> originList;
    @FXML private ListView<String> varsList;

    @FXML private TextArea statusArea;
    @FXML private Label pcLabel;
    @FXML private Label cyclesLabel;
    @FXML private Label haltLabel;

    // new fields for this step
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

    // ---------- Model ----------
    private Program currentProgram = null;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    // simple in-memory history for GUI (degree, inputs, y, cycles)
    private final List<HistoryItem> history = new ArrayList<>();
    private final ObservableList<HistoryRow> historyRows = FXCollections.observableArrayList();

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
                originList.setItems(FXCollections.observableArrayList(chain));
            } catch (Exception ex) {
                originList.setItems(FXCollections.observableArrayList("No origin"));
            }
        });

        // function selector (not used yet)
        if (functionCombo != null) {
            functionCombo.setDisable(true);
        }

        // history table
        if (historyTable != null) {
            hColRun.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().runNo));
            hColDegree.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().degree));
            hColInputs.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().inputs));
            hColY.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().y));
            hColCycles.setCellValueFactory(cd -> new SimpleIntegerProperty(cd.getValue().cycles));
            historyTable.setItems(historyRows);
        }

        appendStatus("Ready.");
    }

    // ---------- Actions ----------

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
                Thread.sleep(200); // tiny wait to show bar

                Program p;
                try {
                    p = ProgramParser.parseFromXml(f);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Failed to load: " + ex.getMessage(), ex);
                }
                updateProgress(0.5, 1);

                String err = ProgramParser.validateLabels(p);
                if (err != null) throw new IllegalArgumentException(err);

                // Simulate a bit more work (UX)
                Thread.sleep(900);
                final Program program = p;

                javafx.application.Platform.runLater(() -> {
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

                    // function selector placeholder
                    if (functionCombo != null) {
                        functionCombo.getItems().setAll("(all)");
                        functionCombo.getSelectionModel().selectFirst();
                        functionCombo.setDisable(false);
                    }

                    appendStatus("Loaded OK: " + program.name);
                });

                updateProgress(1, 1);
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
            showError(ex == null ? "Failed to load file" : ex.getMessage());
        });

        loadProgress.progressProperty().bind(task.progressProperty());
        new Thread(task, "xml-loader").start();
    }

    @FXML
    private void onShowProgram() {
        clearHighlightsIfAny();
        if (!ensureProgram()) return;
        try {
            int deg = parseIntOrZero(degreeField.getText());
            deg = clamp(deg, 0, currentProgram.maxDegree());
            Program.Rendered r = currentProgram.expandToDegree(deg);
            rows.setAll(toRows(r));
            appendStatus("Program listed (degree " + deg + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    @FXML
    private void onExpand() {
        clearHighlightsIfAny();
        onShowProgram();
    }

    @FXML
    private void onRun() {
        if (!ensureProgram()) return;
        try {
            int deg = parseIntOrZero(degreeField.getText());
            deg = clamp(deg, 0, currentProgram.maxDegree());
            List<Integer> inputs = parseInputs(inputsField.getText());
            Runner.RunResult rr = Runner.run(currentProgram, deg, inputs);

            // show the program actually executed (expanded) and vars
            rows.setAll(toRows(rr.rendered));
            showVars(rr.variables);

            cyclesLabel.setText(String.valueOf(rr.cycles));
            haltLabel.setText("true");
            pcLabel.setText("-");

            history.add(new HistoryItem(history.size()+1, deg, new ArrayList<>(inputs), rr.y, rr.cycles));
            historyRows.add(new HistoryRow(history.size(), deg, inputs.toString(), rr.y, rr.cycles));

            appendStatus("Run finished. y=" + rr.y + ", cycles=" + rr.cycles);
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    @FXML
    private void onShowHistory() {
        if (history.isEmpty()) {
            showInfo("No runs yet.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Run# | Degree | Inputs | y | cycles\n");
        for (HistoryItem h : history) {
            sb.append(h.runNo).append(" | ")
                    .append(h.degree).append(" | ")
                    .append(h.inputs).append(" | ")
                    .append(h.y).append(" | ")
                    .append(h.cycles).append('\n');
        }
        showInfo(sb.toString());
    }

    // ---------- highlight ----------
    @FXML
    private void onHighlightLabel() {
        String target = (highlightLabelField.getText() == null) ? "" : highlightLabelField.getText().trim();
        applyRowHighlight((row) -> {
            String lbl = row.label == null ? "" : row.label.trim();
            return !target.isEmpty() && lbl.equalsIgnoreCase(target);
        }, "highlight-label");
    }

    @FXML
    private void onHighlightVar() {
        String target = (highlightVarField.getText() == null) ? "" : highlightVarField.getText().trim().toLowerCase(Locale.ROOT);
        applyRowHighlight((row) -> {
            if (target.isEmpty()) return false;
            String t = " " + row.text.toLowerCase(Locale.ROOT) + " ";
            return t.contains(" " + target + " ");
        }, "highlight-var");
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
                getStyleClass().removeAll("highlight-label", "highlight-var");
                if (!empty && item != null && pred.test(item)) {
                    if (!getStyleClass().contains(styleClass)) getStyleClass().add(styleClass);
                }
            }
        });
    }

    // ---------- Debugger buttons (stubs for now) ----------
    @FXML private void onStartRegular() { appendStatus("Start Regular (not wired yet)"); }
    @FXML private void onStartDebug()   { appendStatus("Start Debug (not wired yet)"); }
    @FXML private void onResume()       { appendStatus("Resume (not wired yet)"); }
    @FXML private void onStepOver()     { appendStatus("Step Over (not wired yet)"); }
    @FXML private void onStop()         { appendStatus("Stop (not wired yet)"); }

    // ---------- History table helpers ----------
    @FXML
    private void onRerunSelected() {
        HistoryRow sel = (historyTable == null) ? null : historyTable.getSelectionModel().getSelectedItem();
        if (sel == null) { showInfo("Pick a run row first."); return; }
        inputsField.setText(sel.inputs.replaceAll("[\\[\\]]",""));
        degreeField.setText(String.valueOf(sel.degree));
        onRun();
    }

    // ---------- Helpers ----------
    private boolean ensureProgram() {
        if (currentProgram == null) {
            showError("Load XML first (File → Load XML).");
            return false;
        }
        return true;
    }

    private String joinLabels(Program p) {
        List<String> labels = new ArrayList<>(p.labelsUsed());
        boolean hasExit = labels.removeIf(s -> s.equalsIgnoreCase("EXIT"));
        labels.sort(Comparator.comparing(this::labelSortKey));
        if (hasExit) labels.add("EXIT");
        return String.join(", ", labels);
    }

    private int labelSortKey(String s) {
        if (s == null) return Integer.MAX_VALUE;
        String u = s.toUpperCase(Locale.ROOT);
        if (u.startsWith("L")) {
            try { return Integer.parseInt(u.substring(1)); } catch (Exception ignored) {}
        }
        return Integer.MAX_VALUE - 1;
    }

    private List<Row> toRows(Program.Rendered r) {
        List<Row> list = new ArrayList<>();
        for (int i=0; i<r.lines.size(); i++) {
            String line = r.lines.get(i);

            String type = (line.contains("(B)")) ? "B" : (line.contains("(S)")) ? "S" : "?";

            String label = "     ";
            int lb = line.indexOf('['), rb = line.indexOf(']');
            if (lb >=0 && rb>lb) label = line.substring(lb+1, rb).trim();

            int lp = line.lastIndexOf('('), rp = line.lastIndexOf(')');
            int cyc = (lp>=0 && rp>lp) ? safeInt(line.substring(lp+1, rp).trim()) : 1;

            String text = line;
            if (rb>=0 && lp>rb) text = line.substring(rb+1, lp).trim();

            list.add(new Row(i+1, type, label, text, cyc));
        }
        return list;
    }

    private void showVars(Map<String,Integer> map) {
        List<String> xs = new ArrayList<>(), zs = new ArrayList<>();
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
                .map(v -> Math.max(0, v)) // S-lang is naturals only
                .collect(Collectors.toList());
    }

    private int parseIntOrZero(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private int safeInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void clearHighlightsIfAny() {
        onClearHighlight();
        if (highlightLabelField != null) highlightLabelField.clear();
        if (highlightVarField != null)   highlightVarField.clear();
    }

    private void appendStatus(String msg) { statusArea.appendText(msg + "\n"); }
    private void showError(String msg) {
        appendStatus("ERROR: " + msg);
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
    private void showInfo(String msg) { new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait(); }

    // ---------- table row + history models ----------
    public static final class Row {
        final int num; final String type; final String label; final String text; final int cycles;
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

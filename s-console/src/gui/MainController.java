package gui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import sengine.*;

import java.io.File;
import java.util.*;

/**
 * GUI controller for תרגיל 2 – S-Emulator.
 * This version matches the fx:id’s and handler names in main.fxml.
 */
public class MainController {

    // ---------- Top bar ----------
    @FXML private TextField programNameField;
    @FXML private ComboBox<String> functionCombo;
    @FXML private TextField degreeField;
    @FXML private TextField maxDegreeField;
    @FXML private ProgressBar loadProgress;

    // ---------- Center: instructions table ----------
    @FXML private TableView<Instruction> instructionsTable;
    @FXML private TableColumn<Instruction, String> colNum;
    @FXML private TableColumn<Instruction, String> colType;
    @FXML private TableColumn<Instruction, String> colLabel;
    @FXML private TableColumn<Instruction, String> colText;
    @FXML private TableColumn<Instruction, String> colCycles;

    // Origin chain list
    @FXML private ListView<String> originList;

    // ---------- Right: Program facts ----------
    @FXML private TextArea labelsArea;
    @FXML private ListView<String> varsList;

    // ---------- Right: Run ----------
    @FXML private TextField inputsField;
    @FXML private Label pcLabel;
    @FXML private Label cyclesLabel;
    @FXML private Label haltLabel;

    // ---------- Debugger / summary ----------
    @FXML private Button btnStartDebug, btnStep, btnResume, btnStop;
    @FXML private TextArea debugSummaryArea;

    // ---------- Highlight controls ----------
    @FXML private TextField highlightLabelField;
    @FXML private TextField highlightVarField;

    // ---------- History ----------
    @FXML private TitledPane historyPane;
    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, String> hColRun;
    @FXML private TableColumn<HistoryRow, String> hColDegree;
    @FXML private TableColumn<HistoryRow, String> hColInputs;
    @FXML private TableColumn<HistoryRow, String> hColY;
    @FXML private TableColumn<HistoryRow, String> hColCycles;
    private final ObservableList<HistoryRow> historyRows = FXCollections.observableArrayList();

    // ---------- Errors ----------
    @FXML private TitledPane errorsPane;
    @FXML private TableView<ErrorRow> errorsTable;
    @FXML private TableColumn<ErrorRow, String> eColWhen;
    @FXML private TableColumn<ErrorRow, String> eColWhat;
    private final ObservableList<ErrorRow> errorRows = FXCollections.observableArrayList();

    // ---------- Status ----------
    @FXML private TextArea statusArea;

    // ---------- Engine state ----------
    private Program currentProgram = null;
    private Program lastGoodProgram = null;

    // ======================================================
    // Initialize
    // ======================================================
    @FXML
    private void initialize() {
        if (programNameField != null) programNameField.setEditable(false);
        if (maxDegreeField != null)   maxDegreeField.setEditable(false);
        if (functionCombo != null)    functionCombo.setDisable(true); // (optional) not used yet
        if (loadProgress != null)     { loadProgress.setVisible(false); loadProgress.setManaged(false); }

        // Table columns
        if (instructionsTable != null) {
            if (colNum != null) {
                colNum.setCellValueFactory(cd ->
                        new SimpleStringProperty(Integer.toString(
                                instructionsTable.getItems().indexOf(cd.getValue()) + 1)));
            }
            if (colType != null) {
                colType.setCellValueFactory(cd ->
                        new SimpleStringProperty(isSynthetic(cd.getValue()) ? "S" : "B"));
            }
            if (colLabel != null) {
                colLabel.setCellValueFactory(cd ->
                        new SimpleStringProperty(cd.getValue().label == null ? "" : cd.getValue().label));
            }
            if (colText != null) {
                colText.setCellValueFactory(cd ->
                        new SimpleStringProperty(cd.getValue().text));
            }
            if (colCycles != null) {
                colCycles.setCellValueFactory(cd ->
                        new SimpleStringProperty(guessCycles(cd.getValue())));
            }

            // selection → origin chain
            instructionsTable.getSelectionModel().selectedIndexProperty().addListener((obs, oldI, newI) -> {
                if (newI == null || newI.intValue() < 0 || currentProgram == null) {
                    if (originList != null) originList.setItems(FXCollections.observableArrayList());
                    return;
                }
                try {
                    int deg = parseIntOrZero(degreeField == null ? "" : degreeField.getText());
                    Program.Rendered r = currentProgram.expandToDegree(clamp(deg, 0, currentProgram.maxDegree()));
                    int idx = newI.intValue();
                    List<String> chain = r.originChains.get(idx);
                    if (originList != null) {
                        originList.setItems(FXCollections.observableArrayList(chain));
                    }
                } catch (Exception ex) {
                    showError(ex.getMessage());
                }
            });
        }

        // History table setup
        if (historyTable != null) {
            historyTable.setItems(historyRows);
            if (hColRun != null)    hColRun.setCellValueFactory(cd -> new SimpleStringProperty(Integer.toString(cd.getValue().runNo)));
            if (hColDegree != null) hColDegree.setCellValueFactory(cd -> new SimpleStringProperty(Integer.toString(cd.getValue().degree)));
            if (hColInputs != null) hColInputs.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().inputsCsv));
            if (hColY != null)      hColY.setCellValueFactory(cd -> new SimpleStringProperty(Integer.toString(cd.getValue().y)));
            if (hColCycles != null) hColCycles.setCellValueFactory(cd -> new SimpleStringProperty(
                    cd.getValue().cycles < 0 ? "" : Integer.toString(cd.getValue().cycles)));
        }

        // Errors table setup
        if (errorsTable != null) {
            errorsTable.setItems(errorRows);
            if (eColWhen != null) eColWhen.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().when));
            if (eColWhat != null) eColWhat.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().what));
        }
    }

    // ======================================================
    // Top-bar actions
    // ======================================================

    @FXML
    private void onLoadXml() {
        try {
            FileChooser chooser = new FileChooser();
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("S-XML (*.xml)", "*.xml"));
            Window win = degreeField == null ? null : degreeField.getScene().getWindow();
            File file = chooser.showOpenDialog(win);
            if (file == null) return;

            Program p = ProgramParser.parseFromXml(file);
            String err = ProgramParser.validateLabels(p);
            if (err != null) throw new IllegalArgumentException(err);

            currentProgram   = p;
            lastGoodProgram  = p;

            if (programNameField != null) programNameField.setText(p.name);
            if (maxDegreeField != null)    maxDegreeField.setText(Integer.toString(p.maxDegree()));
            if (degreeField != null)       degreeField.setText("0");

            listProgram();   // degree 0
            setFactsBox(p);  // show labels/vars

            appendStatus("Loaded \"" + p.name + "\".");
        } catch (Exception ex) {
            if (lastGoodProgram != null) {
                currentProgram = lastGoodProgram;
                appendStatus("Keeping previous valid program loaded.");
            }
            showError(ex.getMessage());
        }
    }

    @FXML private void onShowProgram(ActionEvent e) { listProgram(); }
    @FXML private void onExpand(ActionEvent e)      { listProgram(); }

    // ======================================================
    // Run & Debug
    // ======================================================

    @FXML
    private void onRun() {
        if (currentProgram == null) { showError("Load a program first."); return; }

        // 1) Parse inputs from the text field (or empty)
        List<Integer> inputs = parseInputs(inputsField == null ? "" : inputsField.getText());

        try {
            // 2) Degree: parse → clamp
            int deg = parseIntoZero(degreeField == null ? "" : degreeField.getText());
            deg = clamp(deg, 0, currentProgram.maxDegree());

            // 3) Run
            Runner.RunResult rr = Runner.run(currentProgram, deg, inputs);

            // 4) Update quick labels
            if (cyclesLabel != null) cyclesLabel.setText(Integer.toString(rr.cycles));
            if (pcLabel != null)     pcLabel.setText("*");
            if (haltLabel != null)   haltLabel.setText("true");

// 5) Push to history
            int runNo = historyRows.size() + 1;
            historyRows.add(new HistoryRow(runNo, deg, inputsToString(inputs), rr.y, rr.cycles));

// 6) Status line
            appendStatus("Run finished • y = " + rr.y + " • cycles = " + rr.cycles);



        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    @FXML private void onStartRegular(ActionEvent e) { onRun(); }
    @FXML private void onStartDebug(ActionEvent e)   { appendStatus("Debug: start (stub)."); }
    @FXML private void onResume(ActionEvent e)       { appendStatus("Debug: resume (stub)."); }
    @FXML private void onStepOver(ActionEvent e)     { appendStatus("Debug: step over (stub)."); }
    @FXML private void onStop(ActionEvent e)         { appendStatus("Debug: stop (stub)."); }

    // ======================================================
    // Highlight (matches FXML handler names)
    // ======================================================

    @FXML
    private void onHighlightLabel(ActionEvent e) {
        if (instructionsTable == null) return;
        String wanted = highlightLabelField == null ? "" : highlightLabelField.getText();
        if (wanted == null || wanted.isBlank()) return;
        int idx = findFirstByLabel(wanted.trim());
        if (idx >= 0) instructionsTable.getSelectionModel().select(idx);
    }

    @FXML
    private void onHighlightVar(ActionEvent e) { // exact name used in main.fxml
        if (instructionsTable == null) return;
        String v = highlightVarField == null ? "" : highlightVarField.getText();
        if (v == null || v.isBlank()) return;
        int idx = findFirstByVar(v.trim());
        if (idx >= 0) instructionsTable.getSelectionModel().select(idx);
    }

    @FXML
    private void onClearHighlight(ActionEvent e) {
        if (instructionsTable != null) instructionsTable.getSelectionModel().clearSelection();
    }

    private int findFirstByLabel(String label) {
        var rows = instructionsTable.getItems();
        for (int i = 0; i < rows.size(); i++) {
            String l = rows.get(i).label;
            if (l != null && l.equals(label)) return i;
        }
        return -1;
    }

    private int findFirstByVar(String var) {
        var rows = instructionsTable.getItems();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).text != null &&
                    rows.get(i).text.matches(".*\\b" + java.util.regex.Pattern.quote(var) + "\\b.*")) {
                return i;
            }
        }
        return -1;
    }

    // ======================================================
    // History / Errors
    // ======================================================

    @FXML
    private void onShowHistory() {
        if (historyPane == null) { appendStatus("History pane not found."); return; }
        boolean now = !historyPane.isExpanded();
        historyPane.setExpanded(now);
        if (now) {
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
        if (historyTable == null) { appendStatus("History table not found."); return; }
        HistoryRow row = historyTable.getSelectionModel().getSelectedItem();
        if (row == null) { appendStatus("Select a run first."); return; }
        if (degreeField != null) degreeField.setText(Integer.toString(row.degree));
        if (inputsField != null) inputsField.setText(row.inputsCsv);
        onRun();
    }

    @FXML
    private void onClearErrors() {
        errorRows.clear();
        appendStatus("Errors cleared.");
    }

    // ======================================================
    // Helpers
    // ======================================================

    private void listProgram() {
        if (currentProgram == null) { showError("Load a program first."); return; }
        try {
            int deg = parseIntOrZero(degreeField == null ? "" : degreeField.getText());
            Program.Rendered r = currentProgram.expandToDegree(clamp(deg, 0, currentProgram.maxDegree()));

            if (instructionsTable != null) {
                instructionsTable.setItems(FXCollections.observableArrayList(r.list));
                instructionsTable.getSelectionModel().clearSelection();
            }
            if (maxDegreeField != null) maxDegreeField.setText(Integer.toString(currentProgram.maxDegree()));

            setFactsBox(currentProgram);

            appendStatus("Program listed (degree " + deg + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void setFactsBox(Program p) {
        if (p == null) return;
        try {
            Program.Rendered r0 = p.expandToDegree(0);

            // Labels → labelsArea
            if (labelsArea != null) {
                Set<String> labels = new TreeSet<>();
                for (Instruction ins : r0.list) {
                    if (ins.label != null && !ins.label.isBlank()) labels.add(ins.label);
                }
                labelsArea.setText(String.join(", ", labels));
            }

            // Variables (names only) → varsList
            if (varsList != null) {
                Set<String> vars = new TreeSet<>();
                for (Instruction ins : r0.list) extractVars(ins.text, vars);
                varsList.setItems(FXCollections.observableArrayList(vars));
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void setVariablesBox(Map<String, Integer> vars) {
        if (varsList == null || vars == null) return;
        List<String> lines = new ArrayList<>();
        vars.forEach((k, v) -> lines.add(k + " = " + v));
        lines.sort(String::compareTo);
        varsList.setItems(FXCollections.observableArrayList(lines));
    }

    private void extractVars(String text, Set<String> out) {
        if (text == null) return;
        String[] toks = text.replaceAll("[^A-Za-z0-9_]", " ").split("\\s+");
        for (String t : toks) if (t.matches("y|x\\d+|z\\d+")) out.add(t);
    }

    private static boolean isSynthetic(Instruction ins) {
        if (ins == null || ins.text == null) return false;
        return ins.text.trim().toUpperCase().startsWith("QUOTE");
    }

    private static String guessCycles(Instruction ins) {
        if (ins == null || ins.text == null) return "";
        return ins.text.trim().toUpperCase().startsWith("IF ") ? "2" : "1";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int parseIntOrZero(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return 0; }
    }

    // Parses an integer; if blank/invalid, returns 0.
    private static int parseIntoZero(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            if (t.isEmpty()) return 0;
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Integer> parseInputs(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split("[, ]+")) {
            if (part.isBlank()) continue;
            try { out.add(Integer.parseInt(part.trim())); } catch (Exception ignore) { }
        }
        return out;
    }

    private static String inputsToString(List<Integer> inputs) {
        if (inputs == null || inputs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(inputs.get(i));
        }
        return sb.toString();
    }

    private void appendStatus(String msg) {
        if (statusArea != null) {
            if (!statusArea.getText().isEmpty()) statusArea.appendText(System.lineSeparator());
            statusArea.appendText(msg);
        }
    }

    private static String nowTime() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    private void logErrorRow(String msg) {
        errorRows.add(new ErrorRow(nowTime(), msg));
        if (errorsPane != null) {
            errorsPane.setExpanded(true);
            if (errorsTable != null) {
                errorsTable.getSelectionModel().clearSelection();
                errorsTable.scrollTo(errorRows.size() - 1);
            }
        }
    }

    private void showError(String msg) {
        appendStatus("Error: " + msg);
        logErrorRow(msg);
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText("Error");
        a.showAndWait();
    }

    // ---------- Row models ----------
    public static final class HistoryRow {
        public final int runNo, degree, y, cycles;
        public final String inputsCsv;
        public HistoryRow(int runNo, int degree, String inputsCsv, int y, int cycles) {
            this.runNo = runNo; this.degree = degree; this.inputsCsv = inputsCsv; this.y = y; this.cycles = cycles;
        }
    }

    private static final class ErrorRow {
        final String when, what;
        ErrorRow(String when, String what) { this.when = when; this.what = what; }
    }
}

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GUI controller for תרגיל 2 – S-Emulator.
 * Compatible with your current engine (no reliance on hidden fields/methods).
 * Includes a persistent Errors pane (toggleable).
 */
public class MainController {

    // -------------------------
    // Top bar
    // -------------------------
    @FXML private TextField programField;          // shows program name
    @FXML private ComboBox<String> functionCombo;  // (unused for now)
    @FXML private TextField degreeField;           // chosen degree
    @FXML private TextField degreeMaxField;        // read-only max degree

    // -------------------------
    // Center: instructions table
    // -------------------------
    @FXML private TableView<Instruction> instructionsTable;
    @FXML private TableColumn<Instruction, String> colIdx;
    @FXML private TableColumn<Instruction, String> colBS;
    @FXML private TableColumn<Instruction, String> colLabel;
    @FXML private TableColumn<Instruction, String> colText;
    @FXML private TableColumn<Instruction, String> colCycles;

    // Bottom: origin chain list
    @FXML private ListView<String> originList;

    // -------------------------
    // Right: Program facts (labels / variables referenced)
    // -------------------------
    @FXML private ListView<String> labelsList;
    @FXML private ListView<String> varsList;

    // -------------------------
    // Right: Run box
    // -------------------------
    @FXML private TextField inputsField;

    // Optional live labels – we keep them but don’t rely on engine internals
    @FXML private Label pcLabel;
    @FXML private Label cyclesLabel;
    @FXML private Label haltLabel;

    // -------------------------
    // Debug (stubs)
    // -------------------------
    @FXML private Button btnStartDebug, btnStep, btnResume, btnStop;

    // -------------------------
    // History (toggleable)
    // -------------------------
    @FXML private TitledPane historyPane;
    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, String> hColRun;
    @FXML private TableColumn<HistoryRow, String> hColDegree;
    @FXML private TableColumn<HistoryRow, String> hColInputs;
    @FXML private TableColumn<HistoryRow, String> hColY;
    @FXML private TableColumn<HistoryRow, String> hColCycles;

    private final ObservableList<HistoryRow> historyRows =
            FXCollections.observableArrayList();

    // -------------------------
    // Errors pane (toggleable)
    // -------------------------
    @FXML private TitledPane errorsPane;
    @FXML private TableView<ErrorRow> errorsTable;
    @FXML private TableColumn<ErrorRow, String> eColWhen;
    @FXML private TableColumn<ErrorRow, String> eColWhat;

    private final ObservableList<ErrorRow> errorRows =
            FXCollections.observableArrayList();

    // -------------------------
    // Status box
    // -------------------------
    @FXML private TextArea statusArea;

    // -------------------------
    // Engine state
    // -------------------------
    private Program currentProgram = null;
    private Program lastGoodProgram = null;

    // ======================================================
    // Initialize
    // ======================================================
    @FXML
    private void initialize() {
        if (programField != null) programField.setEditable(false);
        if (degreeMaxField != null) degreeMaxField.setEditable(false);
        if (functionCombo != null) functionCombo.setDisable(true);

        // Instructions columns
        if (instructionsTable != null) {
            if (colIdx != null) {
                colIdx.setCellValueFactory(cd ->
                        new SimpleStringProperty(Integer.toString(
                                instructionsTable.getItems().indexOf(cd.getValue()) + 1)));
            }
            if (colBS != null) {
                // Heuristic: QUOTE… considered S, otherwise B
                colBS.setCellValueFactory(cd ->
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
                // We don’t depend on Instruction.cycles (not public in your engine).
                colCycles.setCellValueFactory(cd ->
                        new SimpleStringProperty(guessCycles(cd.getValue())));
            }

            // Selection -> origin chain
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
                    if (originList != null) originList.setItems(FXCollections.observableArrayList("No origin"));
                }
            });
        }

        // History table wiring
        if (historyTable != null) historyTable.setItems(historyRows);
        if (hColRun != null)    hColRun.setCellValueFactory(d -> new SimpleStringProperty(Integer.toString(d.getValue().runNo)));
        if (hColDegree != null) hColDegree.setCellValueFactory(d -> new SimpleStringProperty(Integer.toString(d.getValue().degree)));
        if (hColInputs != null) hColInputs.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().inputsCsv));
        if (hColY != null)      hColY.setCellValueFactory(d -> new SimpleStringProperty(Integer.toString(d.getValue().y)));
        if (hColCycles != null) hColCycles.setCellValueFactory(d -> new SimpleStringProperty(Integer.toString(d.getValue().cycles)));

        // Errors table wiring
        if (errorsTable != null) errorsTable.setItems(errorRows);
        if (eColWhen != null) eColWhen.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().when));
        if (eColWhat != null) eColWhat.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().what));

        appendStatus("Ready.");
    }

    // ======================================================
    // Commands
    // ======================================================
    @FXML
    private void onLoadXml(ActionEvent e) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Open XML");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML Files", "*.xml"));
            Window win = (degreeField == null) ? null : degreeField.getScene().getWindow();
            File file = chooser.showOpenDialog(win);
            if (file == null) return;

            Program p = ProgramParser.parseFromXml(file);
            String err = ProgramParser.validateLabels(p);
            if (err != null) throw new IllegalArgumentException(err);

            currentProgram = p;
            lastGoodProgram = p;

            if (programField != null) programField.setText(p.name);
            if (degreeMaxField != null) degreeMaxField.setText(Integer.toString(p.maxDegree()));
            if (degreeField != null) degreeField.setText("0");

            listProgram();               // shows degree 0
            setFactsBox(p);              // fills facts pane (best-effort)

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

    @FXML
    private void onRun() {
        if (currentProgram == null) {
            showError("Load a program first.");
            return;
        }

        try {
            // parse + clamp inputs/degree
            List<Integer> inputs = parseInputs(inputsField == null ? "" : inputsField.getText());
            int degRaw = parseIntOrZero(degreeField == null ? "" : degreeField.getText());
            int deg = clamp(degRaw, 0, currentProgram.maxDegree());

            // run (Runner.run expands internally)
            Runner.RunResult rr = Runner.run(currentProgram, deg, inputs);

            setVariablesBox(rr.variables);


            // simple live values
            if (cyclesLabel != null) cyclesLabel.setText(Integer.toString(rr.cycles));
            if (pcLabel != null)     pcLabel.setText("-");        // PC not exposed by engine (yet)
            if (haltLabel != null)   haltLabel.setText("true");   // program completed

            // push to UI history
            int runNo = historyRows.size() + 1;
            historyRows.add(new HistoryRow(runNo, deg, inputsToString(inputs), rr.y, rr.cycles));

            appendStatus("Run finished • y = " + rr.y + " • cycles = " + rr.cycles);
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }
    // Show current variable values in the Program facts → "Variables referenced" list
    private void setVariablesBox(java.util.Map<String, Integer> vars) {
        if (varsList == null || vars == null) return;
        java.util.List<String> lines = new java.util.ArrayList<>();
        vars.forEach((k, v) -> lines.add(k + " = " + v));
        lines.sort(String::compareTo);
        varsList.setItems(javafx.collections.FXCollections.observableArrayList(lines));
    }


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

    // Debug stubs
    @FXML private void onStartDebug() { appendStatus("Debug: start (stub)."); }
    @FXML private void onStep()       { appendStatus("Debug: step (stub)."); }
    @FXML private void onResume()     { appendStatus("Debug: resume (stub)."); }
    @FXML private void onStop()       { appendStatus("Debug: stop (stub)."); }

    // Errors pane
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
            if (degreeMaxField != null) degreeMaxField.setText(Integer.toString(currentProgram.maxDegree()));

            setFactsBox(currentProgram);  // recompute facts quickly

            appendStatus("Program listed (degree " + deg + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    private void setFactsBox(Program p) {
        if (p == null) return;
        try {
            // Best-effort facts from the degree-0 listing
            Program.Rendered r0 = p.expandToDegree(0);

            // Labels used
            if (labelsList != null) {
                Set<String> labels = new HashSet<>();
                for (Instruction ins : r0.list) {
                    if (ins.label != null && !ins.label.isBlank()) labels.add(ins.label);
                }
                labelsList.setItems(FXCollections.observableArrayList(labels));
            }

            // Variables referenced: quick parse
            if (varsList != null) {
                Set<String> vars = new HashSet<>();
                for (Instruction ins : r0.list) {
                    extractVars(ins.text, vars);
                }
                varsList.setItems(FXCollections.observableArrayList(vars));
            }
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }

    // very light variable extractor to populate "Program facts"
    private void extractVars(String text, Set<String> out) {
        if (text == null) return;
        String[] toks = text.replaceAll("[^A-Za-z0-9_]", " ").split("\\s+");
        for (String t : toks) {
            if (t.matches("y|x\\d+|z\\d+")) out.add(t);
        }
    }

    private static boolean isSynthetic(Instruction ins) {
        if (ins == null || ins.text == null) return false;
        String t = ins.text.trim().toUpperCase();
        return t.startsWith("QUOTE"); // synthetic displayed as S in this exercise
    }

    private static String guessCycles(Instruction ins) {
        if (ins == null || ins.text == null) return "";
        String t = ins.text.trim().toUpperCase();
        // if-branches usually take 2 in this exercise set; assignments 1
        return t.startsWith("IF ") ? "2" : "1";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int parseIntOrZero(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return 0; }
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
// --- Debugger • Execution button handlers from FXML ---

    @FXML
    private void onStartRegular(javafx.event.ActionEvent e) {
        // Run the program once in "regular" mode
        onRun();
    }

    // If your FXML uses these names, keep them; otherwise they are harmless.
    @FXML
    private void onStepOver(javafx.event.ActionEvent e) {
        appendStatus("Debug: step over (stub).");
    }

    @FXML
    private void onResume(javafx.event.ActionEvent e) {
        appendStatus("Debug: resume (stub).");
    }

    @FXML
    private void onStop(javafx.event.ActionEvent e) {
        appendStatus("Debug: stop (stub).");
    }


    // ======================================================
    // Row models
    // ======================================================
    public static final class HistoryRow {
        public final int runNo;
        public final int degree;
        public final String inputsCsv;
        public final int y;
        public final int cycles;
        public HistoryRow(int runNo, int degree, String inputsCsv, int y, int cycles) {
            this.runNo = runNo; this.degree = degree; this.inputsCsv = inputsCsv; this.y = y; this.cycles = cycles;
        }
    }

    private static final class ErrorRow {
        final String when;
        final String what;
        ErrorRow(String when, String what) { this.when = when; this.what = what; }
    }
}

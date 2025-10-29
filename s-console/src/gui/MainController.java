package gui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainController {

    /* ======== Root / Navigation ======== */
    @FXML private StackPane rootStack;
    @FXML private BorderPane dashboardPane, executionPane;

    /* ======== Top bar ======== */
    @FXML private Label topTitle;
    @FXML private Label creditsValue;

    /* ======== Dashboard: top row ======== */
    @FXML private TextField loadedPathField;
    @FXML private TextField creditsField;

    /* ======== Dashboard: programs table ======== */
    @FXML private TableView<Row> programsTable;
    @FXML private TableColumn<Row,String> colProgramName;
    @FXML private TableColumn<Row,String> colProgramId;
    @FXML private TableColumn<Row,String> colMaxDegree;

    /* ======== Dashboard: functions / degree ======== */
    @FXML private ComboBox<String> functionCombo;
    @FXML private Spinner<Integer> degreeSpinner;

    /* ======== Execution: instructions table ======== */
    @FXML private TableView<Instr> instructionsTable;
    @FXML private TableColumn<Instr,String> colRowIdx;
    @FXML private TableColumn<Instr,String> colType;
    @FXML private TableColumn<Instr,String> colCycles;   // left cycles column
    @FXML private TableColumn<Instr,String> colText;
    @FXML private TableColumn<Instr,String> colCycles2;  // right cycles column
    @FXML private TableColumn<Instr,String> colArch;     // placeholder

    /* ======== Execution: side panels ======== */
    @FXML private Spinner<Integer> inputSpinner;
    @FXML private TextField inputsListField;
    @FXML private TextArea resultsArea;
    @FXML private TextArea variablesArea;
    @FXML private TextArea lineageArea;
    @FXML private TextField cyclesField;

    /* ======== Networking adapter ======== */
    // NOTE: switch to 8080 if your Tomcat runs there:
    // new EngineAdapter("http://localhost:8080/s-server");
    private final EngineAdapter api = new EngineAdapter("http://localhost:8081/s-server");

    /* ======== Local state ======== */
    private ProgramInfo currentProgram = null;
    private String currentRunId = null;

    private final ObservableList<Row> programs = FXCollections.observableArrayList();
    private final ObservableList<Instr> instrs   = FXCollections.observableArrayList();

    /* ======== Init ======== */
    @FXML
    private void initialize() {
        creditsValue.setText("___");

        degreeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 99, 0));
        inputSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(-1_000_000, 1_000_000, 0));

        // programs table
        colProgramName.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().name));
        colProgramId.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().id));
        colMaxDegree.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().maxDegree)));
        programsTable.setItems(programs);

        // instructions table
        colRowIdx.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().index)));
        colType.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().type));
        colCycles.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colText.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().text));
        colCycles2.setCellValueFactory(cd-> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colArch.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> "")); // placeholder
        instructionsTable.setItems(instrs);

        switchToDashboard();
    }

    /* ======== Navigation ======== */
    @FXML private void switchToExecution() {
        dashboardPane.setVisible(false); dashboardPane.setManaged(false);
        executionPane.setVisible(true);  executionPane.setManaged(true);
        topTitle.setText("S-Emulator — Execution");
    }
    @FXML private void switchToDashboard() {
        executionPane.setVisible(false); executionPane.setManaged(false);
        dashboardPane.setVisible(true);  dashboardPane.setManaged(true);
        topTitle.setText("S-Emulator — Dashboard");
    }
    @FXML private void showDashboard() { switchToDashboard(); }
    @FXML private void showExecution() { switchToExecution(); }

    /* ======== Actions ======== */

    @FXML
    private void loadXml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select program XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File file = fc.showOpenDialog(loadedPathField.getScene().getWindow());
        if (file == null) return;

        loadedPathField.setText(file.getAbsolutePath());
        try {
            ProgramInfo info = api.uploadXml(Path.of(file.getAbsolutePath()));
            currentProgram = info;

            programs.add(0, new Row(info.getName(), info.getId(), info.getMaxDegree()));
            programsTable.getSelectionModel().selectFirst();

            functionCombo.setItems(FXCollections.observableArrayList(info.getFunctions()));
            if (!info.getFunctions().isEmpty()) functionCombo.getSelectionModel().select(0);
            degreeSpinner.getValueFactory().setValue(0);

            switchToExecution();
        } catch (Exception ex) {
            error("Failed to load XML", ex.getMessage());
        }
    }

    @FXML
    private void showProgram() {
        if (currentProgram == null) { error("No program", "Load an XML first."); return; }
        String fn = sel(functionCombo, "(main)");
        int degree = degreeSpinner.getValue();
        try {
            List<TraceRow> rows = api.expand(currentProgram.getId(), fn, degree);
            instrs.clear();
            for (TraceRow r : rows) instrs.add(new Instr(r.index(), r.type(), r.text(), r.cycles()));
            lineageArea.clear();
            if (!executionPane.isVisible()) switchToExecution();
        } catch (Exception ex) {
            error("Failed to expand program", ex.getMessage());
        }
    }

    /* ======== Run / Debug ======== */

    @FXML
    private void runProgram() {
        if (currentProgram == null) { error("No program", "Load an XML first."); return; }
        String fn = sel(functionCombo, "(main)");
        int degree = degreeSpinner.getValue();
        List<Integer> inputs = parseInputs(inputsListField.getText());
        try {
            EngineAdapter.RunResult rr = api.runOnce(currentProgram.getId(), fn, inputs, degree, "");
            cyclesField.setText(String.valueOf(rr.cycles));
            resultsArea.setText("y = " + rr.y);
            variablesArea.setText(prettyVars(rr.vars));
        } catch (Exception ex) {
            error("Run failed", ex.getMessage());
        }
    }

    @FXML
    private void startDebug() {
        if (currentProgram == null) { error("No program", "Load an XML first."); return; }
        String fn = sel(functionCombo, "(main)");
        int degree = degreeSpinner.getValue();
        List<Integer> inputs = parseInputs(inputsListField.getText());
        try {
            EngineAdapter.DebugState s = api.startDebug(currentProgram.getId(), fn, inputs, degree, "");
            currentRunId = s.runId;
            applyDebugState(s);
        } catch (Exception ex) {
            error("Start debug failed", ex.getMessage());
        }
    }

    @FXML
    private void resumeDebug() {
        if (currentRunId == null) { error("No debug session", "Start Debug first."); return; }
        try { applyDebugState(api.resume(currentRunId)); }
        catch (Exception ex) { error("Resume failed", ex.getMessage()); }
    }

    @FXML
    private void stepDebug() {
        if (currentRunId == null) { error("No debug session", "Start Debug first."); return; }
        try { applyDebugState(api.step(currentRunId)); }
        catch (Exception ex) { error("Step failed", ex.getMessage()); }
    }

    @FXML
    private void stopDebug() {
        if (currentRunId == null) { error("No debug session", "Start Debug first."); return; }
        try { applyDebugState(api.stop(currentRunId)); currentRunId = null; }
        catch (Exception ex) { error("Stop failed", ex.getMessage()); }
    }

    /* ======== Inputs helpers ======== */
    @FXML private void addInput() {
        int v = inputSpinner.getValue();
        String s = inputsListField.getText();
        inputsListField.setText((s == null || s.isBlank()) ? String.valueOf(v) : s + ", " + v);
    }
    @FXML private void clearInputs() { inputsListField.clear(); }

    /* ======== Extra stubs ======== */
    @FXML private void chargeCredits()      { /* wire when users ready */ }
    @FXML private void logoutUser()         { /* wire when users ready */ }
    @FXML private void highlightSelection() { /* later */ }

    /* ======== Internal helpers ======== */

    private void applyDebugState(EngineAdapter.DebugState s) {
        cyclesField.setText(String.valueOf(s.cycles));
        variablesArea.setText(prettyVars(s.vars));
        if (s.current != null) {
            int idx = Math.max(0, s.current.index() - 1);
            if (idx >= 0 && idx < instrs.size()) {
                instructionsTable.getSelectionModel().select(idx);
                instructionsTable.scrollTo(idx);
            }
        }
    }

    private static String prettyVars(Map<String,Integer> vars) {
        if (vars == null || vars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String,Integer> e : vars.entrySet()) {
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static List<Integer> parseInputs(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String tok : text.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            try { out.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String sel(ComboBox<String> cb, String def) {
        String v = cb.getSelectionModel().getSelectedItem();
        return (v == null || v.isBlank()) ? def : v;
    }

    /* ======== Table row models ======== */
    public static final class Row {
        public final String name, id; public final int maxDegree;
        public Row(String name, String id, int maxDegree) { this.name = name; this.id = id; this.maxDegree = maxDegree; }
    }
    public static final class Instr {
        public final int index; public final String type, text; public final int cycles;
        public Instr(int index, String type, String text, int cycles) { this.index=index; this.type=type; this.text=text; this.cycles=cycles; }
    }

    /* ======== Utils ======== */
    private static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText(title);
        a.setContentText(msg);
        a.showAndWait();
    }
}

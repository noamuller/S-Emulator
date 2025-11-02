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
    @FXML private Label creditsValue;   // dashboard credits
    @FXML private Label execCredits;    // execution credits

    /* ======== Dashboard: top row ======== */
    @FXML private TextField loadedPathField;
    @FXML private TextField creditsField;           // amount to charge
    @FXML private TextField userNameTextField;      // login textbox

    /* ======== Dashboard: users table ======== */
    @FXML private TableView<UserRow> usersTable;
    @FXML private TableColumn<UserRow,String> colUserName;
    @FXML private TableColumn<UserRow,String> colUserCredits;

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
    @FXML private TableColumn<Instr,String> colCycles;
    @FXML private TableColumn<Instr,String> colText;
    @FXML private TableColumn<Instr,String> colCycles2;
    @FXML private TableColumn<Instr,String> colArch;

    /* ======== Execution: side panels ======== */
    @FXML private Spinner<Integer> inputSpinner;
    @FXML private TextField inputsListField;
    @FXML private TextArea resultsArea;
    @FXML private TextArea variablesArea;
    @FXML private TextArea lineageArea;
    @FXML private TextField cyclesField;

    /* ======== Networking adapter ======== */
    private final EngineAdapter api = new EngineAdapter("http://localhost:8080/s-server");

    /* ======== Local state ======== */
    private EngineAdapter.ProgramInfo currentProgram = null;
    private String currentRunId = null;

    private final ObservableList<UserRow> users = FXCollections.observableArrayList();
    private final ObservableList<Row> programs = FXCollections.observableArrayList();
    private final ObservableList<Instr> instrs = FXCollections.observableArrayList();

    /* ======== Init ======== */
    @FXML
    private void initialize() {
        creditsValue.setText("___");
        if (execCredits != null) execCredits.setText("___");

        degreeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 99, 0));
        inputSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(-1_000_000, 1_000_000, 0));

        if (usersTable != null) {
            colUserName.setCellValueFactory(cd ->
                    javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().username));
            colUserCredits.setCellValueFactory(cd ->
                    javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().credits)));
            usersTable.setItems(users);
        }

        colProgramName.setCellValueFactory(cd ->
                javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().name));
        colProgramId.setCellValueFactory(cd   ->
                javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().id));
        colMaxDegree.setCellValueFactory(cd   ->
                javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().maxDegree)));
        programsTable.setItems(programs);

        colRowIdx.setCellValueFactory(cd ->
                javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().index)));
        colType.setCellValueFactory(cd   ->
                javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().type));
        colCycles.setCellValueFactory(cd ->
                javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colText.setCellValueFactory(cd   ->
                javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().text));
        colCycles2.setCellValueFactory(cd->
                javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colArch.setCellValueFactory(cd   ->
                javafx.beans.binding.Bindings.createStringBinding(() -> ""));
        instructionsTable.setItems(instrs);

        switchToDashboard();
    }

    /* ======== Navigation (no-arg) ======== */
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

    /* ======== FXML event overloads (ActionEvent) ======== */
    @FXML private void showDashboard(javafx.event.ActionEvent e)       { switchToDashboard(); }
    @FXML private void switchToDashboard(javafx.event.ActionEvent e)   { switchToDashboard(); }
    @FXML private void switchToExecution(javafx.event.ActionEvent e)   { switchToExecution(); }
    @FXML private void loadXml(javafx.event.ActionEvent e)             { loadXml(); }
    @FXML private void showProgram(javafx.event.ActionEvent e)         { showProgram(); }
    @FXML private void runProgram(javafx.event.ActionEvent e)          { runProgram(); }
    @FXML private void startDebug(javafx.event.ActionEvent e)          { startDebug(); }
    @FXML private void stopDebug(javafx.event.ActionEvent e)           { stopDebug(); }
    @FXML private void resumeDebug(javafx.event.ActionEvent e)         { resumeDebug(); }
    @FXML private void stepDebug(javafx.event.ActionEvent e)           { stepDebug(); }
    @FXML private void chargeCredits(javafx.event.ActionEvent e)       { chargeCredits(); }  // wrapper
    @FXML private void addInput(javafx.event.ActionEvent e)            { addInput(); }
    @FXML private void clearInputs(javafx.event.ActionEvent e)         { clearInputs(); }
    @FXML private void logoutUser(javafx.event.ActionEvent e)          { logoutUser(); }
    @FXML private void highlightSelection(javafx.event.ActionEvent e)  { highlightSelection(); }

    /* ======== Users ======== */
    @FXML
    private void onAddUserClick() {
        try {
            String name = userNameTextField.getText() == null ? "" : userNameTextField.getText().trim();
            if (name.isEmpty()) { error("Missing name", "Please enter a user name."); return; }

            int credits = api.login(name);
            creditsValue.setText(String.valueOf(credits));
            if (execCredits != null) execCredits.setText(String.valueOf(credits));

            // add or update the user row (no replacement of the whole list)
            UserRow existing = null;
            for (UserRow r : users) {
                if (r.username.equals(name)) { existing = r; break; }
            }
            if (existing == null) {
                users.add(new UserRow(name, credits));
            } else {
                existing.credits = credits;
                usersTable.refresh();
            }
            usersTable.getSelectionModel().select(
                    users.stream().filter(r -> r.username.equals(name)).findFirst().orElse(null)
            );

            info("Logged in as " + name + " (credits: " + credits + ")");
        } catch (Exception ex) {
            error("Login failed", ex.getMessage());
        }
    }

    /* ======== Charge Credits (no-arg, called by wrapper) ======== */
    @FXML
    private void chargeCredits() {
        try {
            String txt = creditsField.getText();
            int amount = (txt == null || txt.isBlank()) ? 0 : Integer.parseInt(txt.trim());
            if (amount <= 0) { info("Enter a positive amount."); return; }

            int newCredits = api.chargeCredits(amount);

            // update labels
            creditsValue.setText(String.valueOf(newCredits));
            if (execCredits != null) execCredits.setText(String.valueOf(newCredits));

            // update the selected user row
            UserRow sel = usersTable.getSelectionModel().getSelectedItem();
            if (sel != null) { sel.credits = newCredits; usersTable.refresh(); }

            info("Credits updated: " + newCredits);
        } catch (Exception e) {
            error("Charge failed", e.getMessage());
        }
    }

    @FXML
    private void logoutUser() {
        usersTable.getSelectionModel().clearSelection();
        creditsValue.setText("___");
        if (execCredits != null) execCredits.setText("___");
        info("Logged out.");
    }

    /* ======== Programs ======== */
    @FXML
    private void loadXml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select program XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File file = fc.showOpenDialog(loadedPathField.getScene().getWindow());
        if (file == null) return;

        loadedPathField.setText(file.getAbsolutePath());
        try {
            EngineAdapter.ProgramInfo info = api.uploadXml(Path.of(file.getAbsolutePath()));
            currentProgram = info;

            programs.setAll(new Row(info.getName(), info.getId(), info.getMaxDegree()));
            programsTable.getSelectionModel().selectFirst();

            functionCombo.setItems(FXCollections.observableArrayList(info.getFunctions()));
            if (!info.getFunctions().isEmpty()) functionCombo.getSelectionModel().select(0);
            degreeSpinner.getValueFactory().setValue(0);

            switchToExecution();
            showProgram();
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
            List<EngineAdapter.TraceRow> rows = api.expand(currentProgram.getId(), fn, degree);
            instrs.clear();
            for (EngineAdapter.TraceRow r : rows) {
                instrs.add(new Instr(r.index(), r.type(), r.text(), r.cycles()));
            }
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

    @FXML private void startDebug() {
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
    @FXML private void resumeDebug() { if (currentRunId != null) try { applyDebugState(api.resume(currentRunId)); } catch (Exception e) { error("Resume failed", e.getMessage()); } }
    @FXML private void stepDebug()   { if (currentRunId != null) try { applyDebugState(api.step(currentRunId));   } catch (Exception e) { error("Step failed",   e.getMessage()); } }
    @FXML private void stopDebug()   { if (currentRunId != null) try { applyDebugState(api.stop(currentRunId)); currentRunId=null; } catch (Exception e) { error("Stop failed", e.getMessage()); } }

    /* ======== Helpers ======== */
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
    public static final class UserRow {
        public String username; public int credits;  // mutable so we can update credits
        public UserRow(String username, int credits) { this.username = username; this.credits = credits; }
    }
    public static final class Row {
        public final String name, id; public final int maxDegree;
        public Row(String name, String id, int maxDegree) { this.name = name; this.id = id; this.maxDegree = maxDegree; }
    }
    public static final class Instr {
        public final int index; public final String type, text; public final int cycles;
        public Instr(int index, String type, String text, int cycles) { this.index=index; this.type=type; this.text=text; this.cycles=cycles; }
    }

    /* ======== Basic alerts ======== */
    private static void error(String title, String msg) { Alert a = new Alert(Alert.AlertType.ERROR); a.setHeaderText(title); a.setContentText(msg); a.showAndWait(); }
    private static void info (String msg)               { Alert a = new Alert(Alert.AlertType.INFORMATION); a.setHeaderText(null); a.setContentText(msg); a.showAndWait(); }

    /* ======== Small helpers for FXML wrappers ======== */
    private void addInput() {
        int v = inputSpinner.getValue();
        String s = inputsListField.getText();
        inputsListField.setText((s == null || s.isBlank()) ? String.valueOf(v) : s + ", " + v);
    }
    private void clearInputs() { inputsListField.clear(); }
    private void highlightSelection() { /* later */ }
}

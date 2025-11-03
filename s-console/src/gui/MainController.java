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
import java.util.*;

public class MainController {

    @FXML private StackPane rootStack;
    @FXML private BorderPane dashboardPane, executionPane;
    @FXML private Label topTitle;
    @FXML private Label creditsValue;
    @FXML private Label execCredits;

    @FXML private TextField loadedPathField;
    @FXML private TextField creditsField;
    @FXML private TextField userNameTextField;

    @FXML private TableView<UserRow> usersTable;
    @FXML private TableColumn<UserRow,String> colUserName;
    @FXML private TableColumn<UserRow,String> colUserCredits;

    @FXML private TableView<Row> programsTable;
    @FXML private TableColumn<Row,String> colProgramName;
    @FXML private TableColumn<Row,String> colProgramId;
    @FXML private TableColumn<Row,String> colMaxDegree;

    @FXML private TableView<Instr> instructionsTable;
    @FXML private TableColumn<Instr,String> colRowIdx;
    @FXML private TableColumn<Instr,String> colType;
    @FXML private TableColumn<Instr,String> colCycles;
    @FXML private TableColumn<Instr,String> colText;
    @FXML private TableColumn<Instr,String> colCycles2;
    @FXML private TableColumn<Instr,String> colArch;

    @FXML private ComboBox<String> functionCombo;
    @FXML private Spinner<Integer> degreeSpinner;
    @FXML private Spinner<Integer> inputSpinner;
    @FXML private TextField inputsListField;
    @FXML private TextArea resultsArea, variablesArea, lineageArea;
    @FXML private TextField cyclesField;

    private final EngineAdapter api = new EngineAdapter("http://localhost:8080/s-server");

    private EngineAdapter.ProgramInfo currentProgram = null;
    private String currentRunId = null;
    private String selectedUserId = null;

    private final ObservableList<UserRow> users = FXCollections.observableArrayList();
    private final ObservableList<Row> programs = FXCollections.observableArrayList();
    private final ObservableList<Instr> instrs = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        creditsValue.setText("0");
        if (execCredits != null) execCredits.setText("0");

        degreeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 99, 0));
        inputSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(-1_000_000, 1_000_000, 0));

        colUserName.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().username));
        colUserCredits.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().credits)));
        usersTable.setItems(users);
        usersTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> selectedUserId = (n == null ? null : n.username));

        colProgramName.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().name));
        colProgramId.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().id));
        colMaxDegree.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().maxDegree)));
        programsTable.setItems(programs);

        colRowIdx.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().index)));
        colType.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().type));
        colCycles.setCellValueFactory(cd -> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colText.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> cd.getValue().text));
        colCycles2.setCellValueFactory(cd-> javafx.beans.binding.Bindings.createStringBinding(() -> String.valueOf(cd.getValue().cycles)));
        colArch.setCellValueFactory(cd   -> javafx.beans.binding.Bindings.createStringBinding(() -> ""));
        instructionsTable.setItems(instrs);

        switchToDashboard();
    }

    /* ===== FXML event overloads (ActionEvent) ===== */
    @FXML private void switchToExecution(javafx.event.ActionEvent e) { switchToExecution(); }
    @FXML private void switchToDashboard(javafx.event.ActionEvent e) { switchToDashboard(); }
    @FXML private void loadXml(javafx.event.ActionEvent e)           { loadXml(); }
    @FXML private void showProgram(javafx.event.ActionEvent e)       { showProgram(); }
    @FXML private void runProgram(javafx.event.ActionEvent e)        { runProgram(); }
    @FXML private void startDebug(javafx.event.ActionEvent e)        { startDebug(); }
    @FXML private void stopDebug(javafx.event.ActionEvent e)         { stopDebug(); }
    @FXML private void resumeDebug(javafx.event.ActionEvent e)       { resumeDebug(); }
    @FXML private void stepDebug(javafx.event.ActionEvent e)         { stepDebug(); }
    @FXML private void addInput(javafx.event.ActionEvent e)          { addInput(); }
    @FXML private void clearInputs(javafx.event.ActionEvent e)       { clearInputs(); }
    @FXML private void chargeCredits(javafx.event.ActionEvent e)     { chargeCredits(); }
    @FXML private void onUnselectUser(javafx.event.ActionEvent e)    { onUnselectUser(); }
    @FXML private void highlightSelection(javafx.event.ActionEvent e){ /* optional */ }

    /* ===== no-arg impls ===== */
    private void switchToExecution() {
        dashboardPane.setVisible(false); dashboardPane.setManaged(false);
        executionPane.setVisible(true);  executionPane.setManaged(true);
        topTitle.setText("S-Emulator — Execution");
    }
    private void switchToDashboard() {
        executionPane.setVisible(false); executionPane.setManaged(false);
        dashboardPane.setVisible(true);  dashboardPane.setManaged(true);
        topTitle.setText("S-Emulator — Dashboard");
    }

    /* ===== Users ===== */
    @FXML
    private void onAddUserClick() {
        try {
            String name = userNameTextField.getText() == null ? "" : userNameTextField.getText().trim();
            if (name.isEmpty()) { error("Missing name", "Please enter a user name."); return; }
            EngineAdapter.UserDto u = api.loginAny(name);
            users.add(new UserRow(u.username, u.credits));
            usersTable.getSelectionModel().selectLast();
            selectedUserId = u.id;
            creditsValue.setText(String.valueOf(u.credits));
            if (execCredits != null) execCredits.setText(String.valueOf(u.credits));
            info("Logged in as " + u.username + " (credits: " + u.credits + ")");
        } catch (Exception ex) {
            error("Login failed", ex.getMessage());
        }
    }

    private void onUnselectUser() {
        usersTable.getSelectionModel().clearSelection();
        selectedUserId = null;
    }

    private void chargeCredits() {
        try {
            if (selectedUserId == null || selectedUserId.isBlank()) { error("No user selected", "Select a user in the table first."); return; }
            String txt = creditsField.getText();
            int amount = (txt == null || txt.isBlank()) ? 0 : Integer.parseInt(txt.trim());
            if (amount <= 0) { info("Enter a positive amount."); return; }

            int newCredits = api.chargeCreditsAny(selectedUserId, amount);
            UserRow sel = usersTable.getSelectionModel().getSelectedItem();
            if (sel != null) { sel.credits = newCredits; usersTable.refresh(); }
            creditsValue.setText(String.valueOf(newCredits));
            if (execCredits != null) execCredits.setText(String.valueOf(newCredits));
            info("Credits updated: " + newCredits);
        } catch (Exception e) { error("Charge failed", e.getMessage()); }
    }

    /* ===== Programs ===== */
    private void loadXml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select program XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML files", "*.xml"));
        File file = fc.showOpenDialog(loadedPathField.getScene().getWindow());
        if (file == null) return;

        loadedPathField.setText(file.getAbsolutePath());
        try {
            EngineAdapter.ProgramInfo info = api.uploadXmlAny(Path.of(file.getAbsolutePath()));
            currentProgram = info;
            programs.setAll(new Row(info.getName(), info.getId(), info.getMaxDegree()));
            programsTable.getSelectionModel().selectFirst();
            functionCombo.setItems(FXCollections.observableArrayList(info.getFunctions()));
            if (!info.getFunctions().isEmpty()) functionCombo.getSelectionModel().select(0);
            degreeSpinner.getValueFactory().setValue(0);
            switchToExecution();
            showProgram();
        } catch (Exception ex) { error("Failed to load XML", ex.getMessage()); }
    }

    private void showProgram() {
        if (currentProgram == null) { error("No program", "Load an XML first."); return; }
        String fn = sel(functionCombo, "(main)");
        int degree = degreeSpinner.getValue();
        try {
            List<EngineAdapter.TraceRow> rows = api.expand(currentProgram.getId(), fn, degree);
            instrs.clear();
            for (EngineAdapter.TraceRow r : rows) instrs.add(new Instr(r.index(), r.type(), r.text(), r.cycles()));
            lineageArea.clear();
            if (!executionPane.isVisible()) switchToExecution();
        } catch (Exception ex) { error("Failed to expand program", ex.getMessage()); }
    }

    /* ===== Run / Debug ===== */
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
        } catch (Exception ex) { error("Run failed", ex.getMessage()); }
    }

    private void startDebug() {
        if (currentProgram == null) { error("No program", "Load an XML first."); return; }
        String fn = sel(functionCombo, "(main)");
        int degree = degreeSpinner.getValue();
        List<Integer> inputs = parseInputs(inputsListField.getText());
        try {
            EngineAdapter.DebugState s = api.startDebug(currentProgram.getId(), fn, inputs, degree, "");
            currentRunId = s.runId;
            applyDebugState(s);
        } catch (Exception ex) { error("Start debug failed", ex.getMessage()); }
    }
    private void resumeDebug() { if (currentRunId != null) try { applyDebugState(api.resume(currentRunId)); } catch (Exception e) { error("Resume failed", e.getMessage()); } }
    private void stepDebug()   { if (currentRunId != null) try { applyDebugState(api.step(currentRunId));   } catch (Exception e) { error("Step failed",   e.getMessage()); } }
    private void stopDebug()   { if (currentRunId != null) try { applyDebugState(api.stop(currentRunId)); currentRunId=null; } catch (Exception e) { error("Stop failed", e.getMessage()); } }

    private void addInput() {
        int v = inputSpinner.getValue();
        String s = inputsListField.getText();
        inputsListField.setText((s == null || s.isBlank()) ? String.valueOf(v) : s + ", " + v);
    }
    private void clearInputs() { inputsListField.clear(); }

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
        for (Map.Entry<String,Integer> e : vars.entrySet())
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        return sb.toString();
    }

    private static List<Integer> parseInputs(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String tok : text.split(",")) {
            String t = tok.trim();
            if (!t.isEmpty()) try { out.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String sel(ComboBox<String> cb, String def) {
        String v = cb.getSelectionModel().getSelectedItem();
        return (v == null || v.isBlank()) ? def : v;
    }

    public static final class UserRow { public final String username; public int credits;
        public UserRow(String username, int credits) { this.username=username; this.credits=credits; } }
    public static final class Row { public final String name, id; public final int maxDegree;
        public Row(String name, String id, int maxDegree) { this.name=name; this.id=id; this.maxDegree=maxDegree; } }
    public static final class Instr { public final int index; public final String type, text; public final int cycles;
        public Instr(int index, String type, String text, int cycles) { this.index=index; this.type=type; this.text=text; this.cycles=cycles; } }

    private static void error(String title, String msg) { Alert a=new Alert(Alert.AlertType.ERROR); a.setHeaderText(title); a.setContentText(msg); a.showAndWait(); }
    private static void info (String msg)               { Alert a=new Alert(Alert.AlertType.INFORMATION); a.setHeaderText(null); a.setContentText(msg); a.showAndWait(); }
}

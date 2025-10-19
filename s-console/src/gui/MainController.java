package gui;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Callback;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class MainController {

    //toolbar
    @FXML private TextField loadedPathField;
    @FXML private ComboBox<String> functionCombo;
    @FXML private TextField degreeField;
    @FXML private TextField maxDegreeField;

    //instructions (execution trace)
    @FXML private TableView<Object> instructionTable;
    @FXML private TableColumn<Object, String> colIndex, colType, colLabel, colInstruction, colCycles;

    //debug
    @FXML private Label pcLabel, cyclesLabel, haltedLabel;
    @FXML private TextArea debugLogArea;

    //inputs/results
    @FXML private TextField singleInputField;
    @FXML private TextArea inputsArea, resultsArea;

    //history
    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, String> hColRun, hColDegree, hColInputs, hColY, hColCycles;
    private final ObservableList<HistoryRow> history = FXCollections.observableArrayList();
    private int runCounter = 0;

    private final EngineAdapter engine = new EngineAdapter("http://localhost:8080/s-server/api");

    private String userId;
    private String programId;
    private String programName;

    private Long currentRunId = null;
    private boolean currentRunDebug = false;

    private final ObservableList<Object> traceRows = FXCollections.observableArrayList();
    private int traceIndexCounter = 0;

    public static final class TraceRow {
        private final int index;
        private final String instr;
        private final int cycles;
        public TraceRow(int index, String instr, int cycles) { this.index=index; this.instr=instr==null?"":instr; this.cycles=cycles; }
        public String getIndex(){ return String.valueOf(index); }
        public String getType(){ return "S"; }
        public String getLabel(){ return ""; }
        public String getInstructionText(){ return instr; }
        public String getCycles(){ return String.valueOf(cycles); }
    }

    private Stage stage() {
        if (instructionTable != null && instructionTable.getScene() != null) return (Stage) instructionTable.getScene().getWindow();
        if (loadedPathField != null && loadedPathField.getScene() != null) return (Stage) loadedPathField.getScene().getWindow();
        return null;
    }

    @FXML
    private void initialize() {
        // history table
        hColRun.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().runNo())));
        hColDegree.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().degree())));
        hColInputs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().inputs()));
        hColY.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().y())));
        hColCycles.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().cycles())));
        historyTable.setItems(history);

        degreeField.setText("0");
        maxDegreeField.setEditable(false);

        functionCombo.getItems().setAll("(main)");
        functionCombo.getSelectionModel().selectFirst();

        installSmartCellValueFactories();

        instructionTable.setFixedCellSize(22);
        instructionTable.setStyle("-fx-cell-size: 22px;");
        colInstruction.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setGraphic(null);
                setWrapText(false);
            }
        });

        enableAutoScalingTableHeaderFonts();
        instructionTable.setRowFactory(tv -> new TableRow<>());

        // login "Noa"
        try {
            var login = engine.login("Noa"); // returns userId, username, credits
            userId = String.valueOf(login.get("userId"));
        } catch (Exception e) {
            showError("Login failed", e);
        }

        clearDebugUi();
        instructionTable.setItems(traceRows);
    }

    @FXML
    private void onLoadXml() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("S-Emulator XML", "*.xml"));
        File f = fc.showOpenDialog(stage());
        if (f == null) return;

        try {
            loadedPathField.setText(f.getAbsolutePath());

            var resp = engine.uploadProgram(f, userId);
            programId   = String.valueOf(resp.get("id"));
            programName = Objects.toString(resp.get("name"), "");

            // we don't compute max degree on the server yet
            maxDegreeField.setText("0");
            degreeField.setText("0");

            // placeholder function list
            functionCombo.getItems().setAll("(main)");
            functionCombo.getSelectionModel().selectFirst();

            traceRows.clear();
            traceIndexCounter = 0;
            clearDebugUi();

        } catch (Exception ex) {
            showError("Failed to load XML", ex);
        }
    }

    @FXML
    private void onShowProgram() {
        try {
            ensureProgramLoaded();
            degreeField.setText("0");
            traceRows.clear();
            traceIndexCounter = 0;
            clearDebugUi();
        } catch (Exception ex) {
            showError("Show Program failed", ex);
        }
    }

    @FXML
    private void onExpand() {
        try {
            ensureProgramLoaded();
            int maxDeg = parseIntSafe(maxDegreeField.getText(), 0);
            int cur = parseIntSafe(degreeField.getText(), 0);
            int next = Math.min(maxDeg, Math.max(0, cur == 0 ? 1 : cur));
            degreeField.setText(String.valueOf(next));
        } catch (Exception ex) {
            showError("Expand failed", ex);
        }
    }

    @FXML
    private void onCollapse() { onShowProgram(); }

    // Inputs helpers
    @FXML private void onRunAddInput() {
        String s = singleInputField.getText();
        if (s == null || s.isBlank()) return;
        if (!inputsArea.getText().isBlank()) inputsArea.appendText(", ");
        inputsArea.appendText(s.trim());
        singleInputField.clear();
    }
    @FXML private void onRunRemoveSelected() {
        String t = inputsArea.getText().trim();
        if (t.isEmpty()) return;
        var parts = new ArrayList<>(Arrays.asList(t.split("\\s*,\\s*")));
        if (!parts.isEmpty()) parts.remove(parts.size() - 1);
        inputsArea.setText(String.join(", ", parts));
    }
    @FXML private void onRunClear() { inputsArea.clear(); resultsArea.clear(); }

    // Run (regular)
    @FXML
    private void onRunExecute() {
        try {
            ensureProgramLoaded();

            int deg = parseIntSafe(degreeField.getText(), 0);
            var inputs = parseInputs(inputsArea.getText());

            var start = engine.startRun(userId, programId, deg, inputs, false);
            long runId = ((Number) start.get("runId")).longValue();
            var st = engine.getRunStatus(runId);

            int y = getYFromVars(st);
            int cycles = ((Number) st.getOrDefault("cycles", 0)).intValue();
            resultsArea.setText("y = " + y + System.lineSeparator() + "cycles = " + cycles);

            runCounter++;
            history.add(new HistoryRow(runCounter, deg, inputsAsString(inputs), y, cycles));

            appendTraceFromStatus(st);

        } catch (Exception ex) {
            showError("Run failed", ex);
        }
    }

    // Debugger
    @FXML
    private void onDebugStart() {
        try {
            ensureProgramLoaded();
            int deg = parseIntSafe(degreeField.getText(), 0);
            var inputs = parseInputs(inputsArea.getText());

            var start = engine.startRun(userId, programId, deg, inputs, true);
            currentRunId = ((Number) start.get("runId")).longValue();
            currentRunDebug = true;

            traceRows.clear();
            traceIndexCounter = 0;

            refreshFromStatus(engine.getRunStatus(currentRunId));
            maybeFinishIntoHistory();

        } catch (Exception ex) {
            showError("Start Debug failed", ex);
        }
    }

    @FXML
    private void onDebugResume() {
        try {
            ensureDebugRun();
            refreshFromStatus(engine.resume(currentRunId));
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Resume failed", ex);
        }
    }

    @FXML
    private void onDebugStep() {
        try {
            ensureDebugRun();
            refreshFromStatus(engine.step(currentRunId));
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Step failed", ex);
        }
    }

    @FXML
    private void onDebugStop() {
        try {
            ensureDebugRun();
            refreshFromStatus(engine.stop(currentRunId));
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Stop failed", ex);
        }
    }

    private void ensureDebugRun() {
        if (currentRunId == null || !currentRunDebug) {
            throw new IllegalStateException("Start Debug first.");
        }
    }

    // Status/trace/vars UI
    private void refreshFromStatus(Map<String, Object> status) {
        pcLabel.setText(String.valueOf(status.getOrDefault("pc", -1)));
        cyclesLabel.setText(String.valueOf(status.getOrDefault("cycles", 0)));
        haltedLabel.setText(Boolean.TRUE.equals(status.get("finished")) ? "true" : "false");

        appendTraceFromStatus(status);
        renderVarsFromStatus(status);
        highlightPcFromStatus(status);
    }

    private void appendTraceFromStatus(Map<String, Object> status) {
        String instr = Objects.toString(status.get("currentInstruction"), "");
        int cycles = ((Number) status.getOrDefault("cycles", 0)).intValue();
        if (instr != null && !instr.isBlank()) {
            traceRows.add(new TraceRow(++traceIndexCounter, instr, cycles));
        }
    }

    @SuppressWarnings("unchecked")
    private void renderVarsFromStatus(Map<String, Object> status) {
        Object varsObj = status.get("variables");
        Map<String, Object> vars = (varsObj instanceof Map) ? (Map<String, Object>) varsObj : Map.of();
        StringBuilder sb = new StringBuilder();
        if (!vars.isEmpty()) {
            sb.append("Vars:\n");
            List<String> keys = new ArrayList<>(vars.keySet());
            keys.sort((a,b) -> {
                if (a.equals("y")) return -1;
                if (b.equals("y")) return 1;
                return a.compareTo(b);
            });
            for (String k : keys) sb.append("  ").append(k).append(" = ").append(String.valueOf(vars.get(k))).append('\n');
        }
        debugLogArea.setText(sb.toString());
    }

    private void highlightPcFromStatus(Map<String, Object> status) {
        int pc = ((Number) status.getOrDefault("pc", -1)).intValue();
        var items = instructionTable.getItems();
        if (pc < 0 || pc >= items.size()) return;
        var sel = instructionTable.getSelectionModel();
        if (sel.getSelectedIndex() != pc) sel.select(pc);
        instructionTable.getFocusModel().focus(pc);
        instructionTable.scrollTo(Math.max(0, pc - 3));
    }

    private void clearDebugUi() {
        pcLabel.setText("-");
        cyclesLabel.setText("0");
        haltedLabel.setText("false");
        debugLogArea.clear();
    }

    private void maybeFinishIntoHistory() {
        if (currentRunId == null) return;
        try {
            var st = engine.getRunStatus(currentRunId);
            boolean finished = Boolean.TRUE.equals(st.get("finished"));
            if (!finished) return;

            int y = getYFromVars(st);
            int cycles = ((Number) st.getOrDefault("cycles", 0)).intValue();
            int deg = parseIntSafe(degreeField.getText(), 0);
            String inputs = inputsArea.getText().trim();
            runCounter++;
            history.add(new HistoryRow(runCounter, deg, inputs, y, cycles));
        } catch (Exception ignore) {}
    }

    private void ensureProgramLoaded() {
        if (programId == null || programId.isBlank())
            throw new IllegalStateException("Load an XML first.");
    }

    private int parseIntSafe(String s, int defVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defVal; }
    }
    private List<Integer> parseInputs(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.split("\\s*,\\s*");
        List<Integer> out = new ArrayList<>();
        for (String p : parts) if (!p.isBlank()) out.add(Integer.parseInt(p));
        return out;
    }
    private String inputsAsString(List<Integer> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    private int getYFromVars(Map<String,Object> status) {
        Object varsObj = status.get("variables");
        if (varsObj instanceof Map<?,?> m) {
            Object y = m.get("y");
            if (y instanceof Number n) return n.intValue();
            if (y != null) { try { return Integer.parseInt(String.valueOf(y)); } catch (Exception ignore) {} }
        }
        return 0;
    }

    private void enableAutoScalingTableHeaderFonts() {
        instructionTable.widthProperty().addListener((obs, o, n) -> Platform.runLater(this::rescaleHeaderFonts));
        instructionTable.sceneProperty().addListener((obs, o, n) -> { if (n != null) Platform.runLater(this::rescaleHeaderFonts); });
        Platform.runLater(this::rescaleHeaderFonts);
    }
    private void rescaleHeaderFonts() {
        if (instructionTable == null || instructionTable.getScene() == null) return;
        double w = instructionTable.getWidth();
        double min = 11.0, max = 18.0;
        double t = Math.max(0, Math.min(1, (w - 520.0) / 900.0));
        double size = min + (max - min) * t;
        instructionTable.lookupAll(".column-header .label").forEach(n -> {
            if (n instanceof Label lbl) lbl.setStyle("-fx-font-size: " + Math.round(size) + "px; -fx-font-weight: bold;");
        });
    }

    private void installSmartCellValueFactories() {
        colIndex.setCellValueFactory(reflecting("getIndex"));
        colType.setCellValueFactory(reflecting("getType"));
        colLabel.setCellValueFactory(reflecting("getLabel"));
        colInstruction.setCellValueFactory(reflecting("getInstructionText"));
        colCycles.setCellValueFactory(reflecting("getCycles"));
    }
    private Callback<TableColumn.CellDataFeatures<Object, String>, ObservableValue<String>>
    reflecting(String methodName) {
        return c -> {
            Object row = c.getValue();
            if (row == null) return new SimpleStringProperty("");
            try {
                var m = row.getClass().getMethod(methodName);
                Object v = m.invoke(row);
                return new SimpleStringProperty(v == null ? "" : String.valueOf(v));
            } catch (Exception e) {
                return new SimpleStringProperty("");
            }
        };
    }

    private void showError(String header, Throwable ex) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
        a.setHeaderText(header);
        a.setContentText(ex == null ? "" : (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        a.showAndWait();
    }

    public record HistoryRow(int runNo, int degree, String inputs, int y, int cycles) {}
}

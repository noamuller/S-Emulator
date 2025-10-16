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

    //instructions
    @FXML private TableView<Object> instructionTable;
    @FXML private TableColumn<Object, String> colIndex, colType, colLabel, colInstruction, colCycles;

    //debug
    @FXML private Label pcLabel, cyclesLabel, haltedLabel;
    @FXML private TextArea debugLogArea; // we’ll use top section of this to show live vars


    @FXML private TextField singleInputField;
    @FXML private TextArea inputsArea, resultsArea;

    //history
    @FXML private TableView<HistoryRow> historyTable;
    @FXML private TableColumn<HistoryRow, String> hColRun, hColDegree, hColInputs, hColY, hColCycles;
    private final ObservableList<HistoryRow> history = FXCollections.observableArrayList();
    private int runCounter = 0;

    private final EngineAdapter engine = new EngineAdapter();

    private Stage stage() {
        if (instructionTable != null && instructionTable.getScene() != null) return (Stage) instructionTable.getScene().getWindow();
        if (loadedPathField != null && loadedPathField.getScene() != null) return (Stage) loadedPathField.getScene().getWindow();
        return null;
    }

    @FXML
    private void initialize() {
        //history table
        hColRun.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().runNo())));
        hColDegree.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().degree())));
        hColInputs.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().inputs()));
        hColY.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().y())));
        hColCycles.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().cycles())));
        historyTable.setItems(history);

        degreeField.setText("0");
        maxDegreeField.setEditable(false);
        functionCombo.getItems().setAll("(all)");
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
    }



    @FXML
    private void onLoadXml() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("S-Emulator XML", "*.xml"));
        File f = fc.showOpenDialog(stage());
        if (f == null) return;

        try {
            loadedPathField.setText(f.getAbsolutePath());
            engine.load(f);

            maxDegreeField.setText(String.valueOf(engine.getMaxDegree()));
            degreeField.setText("0");

            instructionTable.setItems(FXCollections.observableArrayList(engine.getOriginalRows()));
            instructionTable.getSelectionModel().clearSelection();
            refreshDebugUi(); // clears labels/log

        } catch (Exception ex) {
            showError("Failed to load XML", ex);
        }
    }

    @FXML
    private void onShowProgram() {
        try {
            ensureLoaded();
            degreeField.setText("0");
            instructionTable.setItems(FXCollections.observableArrayList(engine.getOriginalRows()));
            instructionTable.getSelectionModel().clearSelection();
        } catch (Exception ex) {
            showError("Show Program failed", ex);
        }
    }

    @FXML
    private void onExpand() {
        try {
            ensureLoaded();
            int maxDeg = engine.getMaxDegree();
            int cur = parseIntSafe(degreeField.getText(), 0);


            int next = Math.min(maxDeg, Math.max(0, cur == 0 ? 1 : cur));
            degreeField.setText(String.valueOf(next));

            instructionTable.setItems(FXCollections.observableArrayList(engine.getExpandedRows(next)));

        } catch (Exception ex) {
            showError("Expand failed", ex);
        }
    }

    @FXML
    private void onCollapse() { onShowProgram(); }

    /*Run*/

    @FXML
    private void onRunAddInput() {
        String s = singleInputField.getText();
        if (s == null || s.isBlank()) return;
        if (!inputsArea.getText().isBlank()) inputsArea.appendText(", ");
        inputsArea.appendText(s.trim());
        singleInputField.clear();
    }

    @FXML
    private void onRunRemoveSelected() {
        String t = inputsArea.getText().trim();
        if (t.isEmpty()) return;
        var parts = new ArrayList<>(Arrays.asList(t.split("\\s*,\\s*")));
        if (!parts.isEmpty()) parts.remove(parts.size() - 1);
        inputsArea.setText(String.join(", ", parts));
    }

    @FXML
    private void onRunClear() {
        inputsArea.clear();
        resultsArea.clear();
    }


    @FXML
    private void onRunExecute() {
        try {
            ensureLoaded();

            int deg = parseIntSafe(degreeField.getText(), 0);
            var inputs = parseInputs(inputsArea.getText());


            engine.dbgStart(deg, inputs);

            instructionTable.setItems(FXCollections.observableArrayList(engine.getDebuggerRows()));
            refreshDebugUi();
            highlightPc();


            while (!engine.isHalted()) {
                engine.dbgStep();

                refreshDebugUi();
                highlightPc();
            }

            int y = engine.getCurrentY();
            int cycles = engine.getCycles();
            resultsArea.setText("y = " + y + System.lineSeparator() + "cycles = " + cycles);

            runCounter++;
            history.add(new HistoryRow(runCounter, deg, inputsAsString(inputs), y, cycles));

        } catch (Exception ex) {
            showError("Run failed", ex);
        }
    }


    @FXML
    private void onHistoryRerunSelected() {
        var row = historyTable.getSelectionModel().getSelectedItem();
        if (row == null) return;
        degreeField.setText(String.valueOf(row.degree()));
        inputsArea.setText(row.inputs());
        onRunExecute();
    }

    /*debugger*/

    @FXML
    private void onDebugStart() {
        try {
            ensureLoaded();
            int deg = parseIntSafe(degreeField.getText(), 0);
            var inputs = parseInputs(inputsArea.getText());

            engine.dbgStart(deg, inputs);
            instructionTable.setItems(FXCollections.observableArrayList(engine.getDebuggerRows()));
            refreshDebugUi();
            highlightPc();
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Start Debug failed", ex);
        }
    }

    @FXML
    private void onDebugResume() {
        try {
            engine.dbgResume();
            refreshDebugUi();
            highlightPc();
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Resume failed", ex);
        }
    }

    @FXML
    private void onDebugStep() {
        try {
            engine.dbgStep();
            refreshDebugUi();
            highlightPc();
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Step failed", ex);
        }
    }

    @FXML
    private void onDebugStop() {
        try {
            engine.dbgStop();
            refreshDebugUi();
            highlightPc();
            maybeFinishIntoHistory();
        } catch (Exception ex) {
            showError("Stop failed", ex);
        }
    }


    private void maybeFinishIntoHistory() {
        if (!engine.isHalted()) return;
        int y = engine.getCurrentY();
        int cycles = engine.getCycles();
        int deg = parseIntSafe(degreeField.getText(), 0);
        String inputs = inputsArea.getText().trim();
        runCounter++;
        history.add(new HistoryRow(runCounter, deg, inputs, y, cycles));
    }


    private void refreshDebugUi() {
        pcLabel.setText(engine.getPcText());
        cyclesLabel.setText(String.valueOf(engine.getCycles()));
        haltedLabel.setText(String.valueOf(engine.isHalted()));


        var vars = engine.getVars();
        var changed = engine.getChanged();

        StringBuilder varsView = new StringBuilder();
        if (!vars.isEmpty()) {
            varsView.append("Vars:\n");
            for (var e : vars.entrySet()) {
                boolean ch = changed.containsKey(e.getKey());
                varsView.append("  ")
                        .append(ch ? "* " : "  ")
                        .append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        if (varsView.length() > 0) varsView.append('\n');

        debugLogArea.setText(varsView.toString() + engine.getLog());
    }


    private void highlightPc() {
        int pc = engine.getPcIndex(); // 0-based; -1 when halted
        var items = instructionTable.getItems();
        if (pc < 0 || pc >= items.size()) {

            return;
        }
        var sel = instructionTable.getSelectionModel();
        if (sel.getSelectedIndex() != pc) {
            sel.select(pc);
        }
        instructionTable.getFocusModel().focus(pc);
        instructionTable.scrollTo(Math.max(0, pc - 3));
    }



    private void ensureLoaded() {
        if (!engine.isLoaded()) throw new IllegalStateException("Load an XML first.");
    }

    private int parseIntSafe(String s, int defVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defVal; }
    }

    private List<Integer> parseInputs(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.split("\\s*,\\s*");
        List<Integer> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            out.add(Integer.parseInt(p));
        }
        return out;
    }

    private String inputsAsString(List<Integer> xs) {
        return xs.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private void showError(String header, Throwable ex) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
        a.setHeaderText(header);
        a.setContentText(ex.getMessage() == null ? ex.toString() : ex.getMessage());
        a.showAndWait();
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


    public record HistoryRow(int runNo, int degree, String inputs, int y, int cycles) {}
}

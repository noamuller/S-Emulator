package gui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import sengine.*;

import javafx.scene.control.SpinnerValueFactory;

// ===== add these imports =====
import javafx.concurrent.Task;


import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;



// Engine types (if not present already)
import sengine.Program;
import sengine.ProgramParser;
import sengine.Instruction;
import sengine.Debugger;




import java.io.File;
import java.util.*;

import java.util.*;

import javafx.scene.control.*;





/**
 * GUI controller for תרגיל 2 – S-Emulator.
 * This version matches the fx:id’s and handler names in main.fxml.
 */
public class MainController {

    // UI
    @FXML private TextArea selectedChainArea;

    // Keep the last debug session’s step-by-step snapshots (for the chain)
    private final java.util.List<Debugger.Snapshot> pcTrace = new java.util.ArrayList<>();

    // ---------- Top bar ----------
    @FXML private TextField programNameField;
    @FXML private ComboBox<String> functionCombo;
    @FXML private TextField degreeField;
    @FXML private TextField maxDegreeField;
    @FXML private ProgressBar loadProgress;
    @FXML private Label       loadStatus;
    @FXML private Spinner<Integer>      addInputSpinner;
    @FXML private ListView<Integer>     inputsList;


    // ---------- Center: instructions table ----------
    @FXML private TableView<Instruction> instructionsTable;
    @FXML private TableColumn<Instruction, String> colNum;
    @FXML private TableColumn<Instruction, String> colType;
    @FXML private TableColumn<Instruction, String> colLabel;
    @FXML private TableColumn<Instruction, String> colText;
    @FXML private TableColumn<Instruction, String> colCycles;

    @FXML
    private void onApplyLabelFilter(ActionEvent e) {
        String s = (labelFilterField != null) ? labelFilterField.getText() : null;
        setLabelFilter(s == null ? "" : s);
        appendStatus("Label filter: " + (s == null ? "(cleared)" : s));
    }

    @FXML
    private void onApplyVarFilter(ActionEvent e) {
        String s = (varFilterField != null) ? varFilterField.getText() : null;
        setVarFilter(s == null ? "" : s);
        appendStatus("Var filter: " + (s == null ? "(cleared)" : s));
    }


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

    // --- Additional UI area for full run output (safe if not in FXML yet) ---
    @FXML private TextArea runSummaryArea;

    // --- Legacy debug helpers used by some code paths ---
    private javafx.animation.Timeline debugTimeline = null;
    private int debugPc = -1;
    private javafx.collections.ObservableList<Integer> inputsModel;

    // --- History counter ---
    private int runCounter = 0;


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
    @FXML private TextField labelFilterField, varFilterField;

    private final java.util.Map<Integer, java.util.Map<String,Integer>> historyVars =
            new java.util.LinkedHashMap<>();

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

    // ---------- Debug state ----------
    private Debugger debugger = null;
    private int debuggerDegree = 0;
    private List<Integer> debuggerInputs = List.of();
    private Timeline resumeTimer = null;

    // --------- Highlight filters (label/variable) ----------
    private String labelFilter = null;                      // e.g., "L7" (case-insensitive)
    private String varFilter = null;                        // e.g., "x2" / "y" / "z3" (case-insensitive)
    private final java.util.Set<Integer> labelRows = new java.util.HashSet<>();
    private final java.util.Set<Integer> varRows   = new java.util.HashSet<>();


    // ---------- History details ----------


    // ---------- Row highlight filters ----------
    private String highlightLabelFilter = "";
    private String highlightVarFilter   = "";


    // Build the instruction table columns once
    private boolean instrColsConfigured = false;
    private void ensureInstructionTableColumns() {
        if (instrColsConfigured || instructionsTable == null) return;
        instrColsConfigured = true;

        // # (1-based row number)
        TableColumn<Instruction, String> colIdx = new TableColumn<>("#");
        colIdx.setPrefWidth(60);
        colIdx.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(Integer.toString(
                        instructionsTable.getItems().indexOf(cell.getValue()) + 1)));

        // B \ S (basic/synthetic)
        TableColumn<Instruction, String> colType = new TableColumn<>("B\\S");
        colType.setPrefWidth(60);
        colType.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().prettyType()));

        // Label
        TableColumn<Instruction, String> colLabel = new TableColumn<>("Label");
        colLabel.setPrefWidth(100);
        colLabel.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().label == null ? "" : cell.getValue().label));

        // Instruction text
        TableColumn<Instruction, String> colText = new TableColumn<>("Instruction");
        colText.setPrefWidth(480);
        colText.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().text));

        // Cycles
        TableColumn<Instruction, String> colCycles = new TableColumn<>("Cycles");
        colCycles.setPrefWidth(80);
        colCycles.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(Integer.toString(cell.getValue().cycles())));

        instructionsTable.getColumns().setAll(colIdx, colType, colLabel, colText, colCycles);
    }


    // ======================================================
    // Initialize
    // ======================================================
    @FXML
    private void initialize() {
        if (programNameField != null) programNameField.setEditable(false);
        if (maxDegreeField != null)   maxDegreeField.setEditable(false);
        if (functionCombo != null)    functionCombo.setDisable(true); // will enable after load
        if (loadProgress != null)     { loadProgress.setVisible(false); loadProgress.setManaged(false); }

        if (historyTable != null) {
            historyTable.setItems(historyRows);
            historyTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                if (sel != null) showRunResults(sel.snapshot, sel.cycles);
            });
        }


        // Table columns
        if (instructionsTable != null) {
            ensureInstructionTableColumns();
// When the user selects a row, recompute its visit chain from the current trace
            instructionsTable.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
                updateSelectedChain();
            });

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
                colText.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().text));
            }
            if (colCycles != null) {
                colCycles.setCellValueFactory(cd -> new SimpleStringProperty(guessCycles(cd.getValue())));
            }
// Inputs editor setup
            if (inputsList != null) {
                inputsModel = javafx.collections.FXCollections.observableArrayList();
                inputsList.setItems(inputsModel);
            }
            if (addInputSpinner != null) {
                var intFactory = new javafx.util.converter.IntegerStringConverter();
                addInputSpinner.setValueFactory(
                        new SpinnerValueFactory.IntegerSpinnerValueFactory(-1_000_000, 1_000_000, 0)
                );
                addInputSpinner.setEditable(true);
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


                // Row highlight for current PC + filters for label/var usage
                instructionsTable.setRowFactory(tv -> new TableRow<>() {
                    @Override
                    protected void updateItem(Instruction item, boolean empty) {
                        super.updateItem(item, empty);
                        // clear old classes
                        getStyleClass().remove("debug-current");
                        getStyleClass().remove("highlight-label");
                        getStyleClass().remove("highlight-var");

                        if (empty || item == null) return;

                        // Debug current PC highlight
                        if (debugger != null) {
                            Debugger.Snapshot s = debugger.snapshot();
                            if (!s.halted && s.pc >= 0 && getIndex() == s.pc) {
                                getStyleClass().add("debug-current");
                            }
                        }

                        // Label/Var usage highlights (precomputed index sets)
                        if (labelRows.contains(getIndex())) getStyleClass().add("highlight-label");
                        if (varRows.contains(getIndex()))   getStyleClass().add("highlight-var");
                    }



                });

            });

            // Row factory for: current PC, label filter, var filter
            instructionsTable.setRowFactory(tv -> new TableRow<Instruction>() {
                @Override
                protected void updateItem(Instruction item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("debug-current", "highlight-label", "highlight-var");
                    if (empty || item == null) return;

                    int idx = getIndex();

                    // current PC highlight
                    if (idx == debugPc) getStyleClass().add("debug-current");

                    String text  = item.text  == null ? "" : item.text;
                    String label = item.label == null ? "" : item.label;

                    if (!highlightLabelFilter.isBlank()) {
                        boolean lblMatch = label.equalsIgnoreCase(highlightLabelFilter);
                        boolean txtMatch = text.matches(".*\\b" + java.util.regex.Pattern.quote(highlightLabelFilter) + "\\b.*");
                        if (lblMatch || txtMatch) getStyleClass().add("highlight-label");
                    }
                    if (!highlightVarFilter.isBlank()) {
                        boolean varMatch = text.matches(".*\\b" + java.util.regex.Pattern.quote(highlightVarFilter) + "\\b.*");
                        if (varMatch) getStyleClass().add("highlight-var");
                    }
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

            // Show variables of selected run
            historyTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
                if (newRow == null) return;
                // We already store the full variables snapshot in each row.
                showRunResults(newRow.snapshot, newRow.cycles);
            });

        }

        // Errors table setup
        if (errorsTable != null) {
            errorsTable.setItems(errorRows);
            if (eColWhen != null) eColWhen.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().when));
            if (eColWhat != null) eColWhat.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().what));
        }

        // When user changes function from combo (we'll populate after load)
        if (functionCombo != null) {
            functionCombo.setOnAction(e -> switchFunctionView());
        }
    }


    // ======================================================
    // Top-bar actions
    // ======================================================

    @FXML
    private void onAddInput(ActionEvent e) {
        if (inputsModel == null) return;
        int v = (addInputSpinner != null && addInputSpinner.getValue() != null) ? addInputSpinner.getValue() : 0;
        inputsModel.add(v);
    }

    @FXML
    private void onRemoveSelectedInput(ActionEvent e) {
        if (inputsModel == null || inputsList == null) return;
        Integer sel = inputsList.getSelectionModel().getSelectedItem();
        if (sel != null) inputsModel.remove(sel);
    }

    @FXML
    private void onClearInputs(ActionEvent e) {
        if (inputsModel != null) inputsModel.clear();
    }


    @FXML
    private void onLoadXml(ActionEvent e) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open S Program (Exercise 2 XML)");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("XML Files", "*.xml"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        // choose owner window safely if table exists
        Window owner = (instructionsTable == null || instructionsTable.getScene() == null)
                ? null : instructionsTable.getScene().getWindow();
        File file = fc.showOpenDialog(owner);
        if (file == null) return;

        appendStatus("Loading: " + file.getName());

        Task<Program> task = new Task<>() {
            @Override
            protected Program call() throws Exception {
                updateProgress(0, 100);
                updateMessage("Validating…");

                // Parse + validate (ProgramParser from sengine)
                Program p = ProgramParser.parseFromXml(file);
                updateProgress(40, 100);
                updateMessage("Checking labels…");
                String labelErr = ProgramParser.validateLabels(p);
                if (labelErr != null) throw new IllegalArgumentException(labelErr);

                // Tiny simulated delay so progress bar is visible
                updateMessage("Finalizing…");
                for (int i = 40; i <= 100; i += 6) {
                    Thread.sleep(30);
                    updateProgress(i, 100);
                }
                return p;
            }
        };

        if (loadProgress != null) loadProgress.progressProperty().bind(task.progressProperty());
        if (loadStatus   != null) loadStatus.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(ev -> {
            if (loadProgress != null) loadProgress.progressProperty().unbind();
            if (loadStatus   != null) loadStatus.textProperty().unbind();
            Program p = task.getValue();
            try {
                refreshUiAfterProgramLoad(p);
                if (loadStatus != null) loadStatus.setText("");
                if (loadProgress != null) loadProgress.setProgress(0);

                updateControlsEnabled();
            } catch (Exception ex) {
                showError(ex.getMessage());
            }
        });

        task.setOnFailed(ev -> {
            if (loadProgress != null) loadProgress.progressProperty().unbind();
            if (loadStatus   != null) loadStatus.textProperty().unbind();
            Throwable ex = task.getException();
            showError(ex == null ? "Unknown XML load error" : ex.getMessage());
            if (loadStatus != null) loadStatus.setText("");
            if (loadProgress != null) loadProgress.setProgress(0);

        });

        Thread t = new Thread(task, "xml-loader");
        t.setDaemon(true);
        t.start();
    }



    // ----- Highlight recompute helpers -----
    private void recomputeLabelRows() {
        labelRows.clear();
        if (labelFilter == null || labelFilter.isBlank() || instructionsTable == null) return;
        String needle = labelFilter.trim().toUpperCase(Locale.ROOT);

        var items = instructionsTable.getItems();
        for (int i = 0; i < items.size(); i++) {
            Instruction ins = items.get(i);
            String text = (ins == null || ins.text == null) ? "" : ins.text.toUpperCase(Locale.ROOT);
            // Consider both uses (in command like IF ... GOTO L7) and definition (label == L7:)
            boolean used = text.contains(needle);
            boolean defined = (ins.label != null) && ins.label.trim().toUpperCase(Locale.ROOT).equals(needle);
            if (used || defined) labelRows.add(i);
        }
    }

    private void recomputeVarRows() {
        varRows.clear();
        if (varFilter == null || varFilter.isBlank() || instructionsTable == null) return;
        String v = varFilter.trim().toUpperCase(Locale.ROOT);

        // Word-boundary style check (rough but works for x10 vs x1)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(^|[^A-Z0-9_])" + java.util.regex.Pattern.quote(v) + "([^A-Z0-9_]|$)");

        var items = instructionsTable.getItems();
        for (int i = 0; i < items.size(); i++) {
            Instruction ins = items.get(i);
            String text = (ins == null || ins.text == null) ? "" : ins.text.toUpperCase(Locale.ROOT);
            if (p.matcher(text).find()) varRows.add(i);
        }
    }

    // Public setters (we'll wire to UI later)
    public void setLabelFilter(String labelUpperOrMixed) {
        labelFilter = labelUpperOrMixed;
        recomputeLabelRows();
        if (instructionsTable != null) instructionsTable.refresh();
    }
    public void setVarFilter(String varName) {
        varFilter = varName;
        recomputeVarRows();
        if (instructionsTable != null) instructionsTable.refresh();
    }

    private void populateFunctionCombo() {
        if (functionCombo == null) return;
        functionCombo.setDisable(false);
        functionCombo.getItems().clear();
        functionCombo.getItems().add("(all)");
        if (currentProgram != null && currentProgram.functions != null) {
            java.util.List<String> names = new java.util.ArrayList<>(currentProgram.functions.keySet());
            java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            functionCombo.getItems().addAll(names);
        }
        functionCombo.getSelectionModel().select(0);
    }

    private void listProgramAtDegreeField() {
        if (currentProgram == null || instructionsTable == null) return;
        int deg = clamp(parseIntoZero(degreeField == null ? "" : degreeField.getText()),
                0, currentProgram.maxDegree());
        Program.Rendered r = currentProgram.expandToDegree(deg);
        instructionsTable.setItems(FXCollections.observableArrayList(r.list));
        // Update label/var highlighting for the new list
        recomputeLabelRows();
        recomputeVarRows();
        instructionsTable.refresh();
    }

    @FXML
    private void onFunctionChanged(ActionEvent e) {
        if (functionCombo == null || currentProgram == null || instructionsTable == null) return;
        String sel = functionCombo.getSelectionModel().getSelectedItem();
        if (sel == null || "(all)".equalsIgnoreCase(sel)) {
            listProgramAtDegreeField();
            return;
        }
        java.util.List<Instruction> body = currentProgram.functions.get(sel);
        if (body == null) return;

        // Show the function body at degree 0
        Program p = new Program(sel, body, currentProgram.functions);
        Program.Rendered r = p.expandToDegree(0);
        instructionsTable.setItems(FXCollections.observableArrayList(r.list));

        // Clear debug-current (different view than the debugger’s rendered list)
        recomputeLabelRows();
        recomputeVarRows();
        instructionsTable.refresh();

        appendStatus("Viewing function: " + sel);
    }

    private void showRunResults(Map<String,Integer> vars, int cycles) {
        // Build the ordered lines using our existing helpers
        List<String> lines = formatVarsOrdered(vars, /*changed=*/null);
        StringBuilder sb = new StringBuilder();
        sb.append("RESULTS").append(System.lineSeparator());
        for (String s : lines) sb.append(s.replaceFirst("^\\*?\\s?", "  ")).append(System.lineSeparator());
        sb.append("  total cycles = ").append(cycles);

        if (runSummaryArea != null) {
            runSummaryArea.setText(sb.toString());
        } else {
            appendStatus(sb.toString()); // fallback if TextArea not wired yet
        }
    }


    // ----- Debug UI helpers -----
    private void applyDebugSnapshot(Debugger.Snapshot snap) {
        if (pcLabel != null)     pcLabel.setText(snap.halted ? "HALT" : Integer.toString(snap.pc + 1));
        if (cyclesLabel != null) cyclesLabel.setText(Integer.toString(snap.cycles));
        if (haltLabel != null)   haltLabel.setText(snap.halted ? "true" : "false");

        // Summary text with changed vars marked clearly
        if (debugSummaryArea != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("pc=").append(snap.halted ? "HALT" : (snap.pc + 1))
                    .append(", cycles=").append(snap.cycles)
                    .append(snap.halted ? " [halted]" : "");
            sb.append(System.lineSeparator());

            // put changed ones first, then the rest, all marked
            java.util.Map<String,Integer> changed = (snap.changed == null) ? java.util.Map.of() : snap.changed;
            java.util.List<String> lines = formatVarsOrdered(snap.vars, changed);

            for (String line : lines) {
                // Changed lines already prefixed by "* " via mark(); make it more visible:
                if (line.startsWith("* ")) sb.append("** ").append(line.substring(2)).append(System.lineSeparator());
                else                       sb.append("   ").append(line.substring(2)).append(System.lineSeparator());
            }
            debugSummaryArea.setText(sb.toString());
        }

        // refresh table & keep current PC in view
        if (instructionsTable != null) {
            instructionsTable.refresh();
            if (!snap.halted && snap.pc >= 0 && snap.pc < instructionsTable.getItems().size()) {
                instructionsTable.getSelectionModel().select(snap.pc);
                instructionsTable.scrollTo(snap.pc);
            } else {
                instructionsTable.getSelectionModel().clearSelection();
            }
        }

        // enable/disable relevant buttons
        updateControlsEnabled();
    }
    private void updateControlsEnabled() {
        boolean hasProg = (currentProgram != null);
        boolean hasDbg  = (debugger != null);

        // If you have these buttons as @FXML fields, wire them here.
        // If not, you can skip—this method is safe to leave as-is.
        // Example (if you later add @FXML Button startDebugBtn, resumeBtn, stepBtn, stopBtn):
        // if (startDebugBtn != null) startDebugBtn.setDisable(!hasProg);
        // if (resumeBtn     != null) resumeBtn.setDisable(!hasDbg);
        // if (stepBtn       != null) stepBtn.setDisable(!hasDbg);
        // if (stopBtn       != null) stopBtn.setDisable(!hasDbg);
    }


    private static List<String> formatVarsOrdered(Map<String,Integer> vars, Map<String,Integer> changed) {
        List<String> out = new ArrayList<>();

        // y first
        if (vars.containsKey("y")) out.add(mark("y", vars.get("y"), changed));

        // then x's increasing
        List<Integer> xs = new ArrayList<>();
        for (String k : vars.keySet()) if (k.startsWith("x")) {
            try { xs.add(Integer.parseInt(k.substring(1))); } catch (Exception ignored) {}
        }
        Collections.sort(xs);
        for (int i : xs) out.add(mark("x" + i, vars.getOrDefault("x" + i, 0), changed));

        // then z's increasing
        List<Integer> zs = new ArrayList<>();
        for (String k : vars.keySet()) if (k.startsWith("z")) {
            try { zs.add(Integer.parseInt(k.substring(1))); } catch (Exception ignored) {}
        }
        Collections.sort(zs);
        for (int i : zs) out.add(mark("z" + i, vars.getOrDefault("z" + i, 0), changed));

        return out;
    }

    private static String mark(String name, Integer value, Map<String,Integer> changed) {
        boolean ch = changed != null && changed.containsKey(name);
        return (ch ? "* " : "  ") + name + " = " + value;
    }

    // Build the visit chain text for the currently selected row from pcTrace
    private void updateSelectedChain() {
        if (selectedChainArea == null || instructionsTable == null) return;
        int sel = instructionsTable.getSelectionModel().getSelectedIndex();
        if (sel < 0) { selectedChainArea.clear(); return; }

        StringBuilder sb = new StringBuilder();
        sb.append("Row #").append(sel + 1).append(" visit chain").append(System.lineSeparator());

        int hits = 0;
        for (int t = 0; t < pcTrace.size(); t++) {
            Debugger.Snapshot s = pcTrace.get(t);
            if (!s.halted && s.pc == sel) {
                hits++;
                // show a compact view: t index and y/x/z summary line
                sb.append("t=").append(t).append(": ");
                sb.append(shortVarLine(s.vars)).append(System.lineSeparator());
            }
        }
        if (hits == 0) sb.append("(not visited in this debug session)");

        selectedChainArea.setText(sb.toString());
    }

    private String shortVarLine(Map<String,Integer> vars) {
        // y, then first few x’s and z’s (compact)
        StringBuilder line = new StringBuilder();
        if (vars.containsKey("y")) line.append("y=").append(vars.get("y")).append("  ");
        // first three x’s
        for (int i = 0, seen = 0; seen < 3; i++) {
            String k = "x" + i;
            if (vars.containsKey(k)) { line.append(k).append("=").append(vars.get(k)).append("  "); seen++; }
            if (i > 50) break; // safety
        }
        // first three z’s
        for (int i = 0, seen = 0; seen < 3; i++) {
            String k = "z" + i;
            if (vars.containsKey(k)) { line.append(k).append("=").append(vars.get(k)).append("  "); seen++; }
            if (i > 50) break; // safety
        }
        return line.toString().trim();
    }

    @FXML
    private void onExpandDegree(ActionEvent e) {
        if (currentProgram == null || degreeField == null) { showError("Load a program first."); return; }
        int max = currentProgram.maxDegree();
        int d = clamp(parseIntoZero(degreeField.getText()), 0, max);
        if (d < max) {
            degreeField.setText(Integer.toString(d + 1));
            listProgramAtDegreeField();
            appendStatus("Expanded to degree " + (d + 1) + ".");
        } else {
            appendStatus("Already at max degree " + max + ".");
        }
    }

    @FXML
    private void onShowProgram(ActionEvent e) {
        if (currentProgram == null) { showError("Load a program first."); return; }
        if (maxDegreeField != null) maxDegreeField.setText(Integer.toString(currentProgram.maxDegree()));
        listProgramAtDegreeField();
        appendStatus("Listed program at degree " +
                clamp(parseIntoZero(degreeField == null ? "" : degreeField.getText()),
                        0, currentProgram.maxDegree()) + ".");
    }

    @FXML private void onExpand(ActionEvent e)      { listProgram(); }

    // ======================================================
    // Run & Debug
    // ======================================================

    @FXML
    private void onRun() {
        if (currentProgram == null) { showError("Load a program first."); return; }

        int deg = clamp(parseIntoZero(degreeField == null ? "" : degreeField.getText()),
                0, currentProgram.maxDegree());
        List<Integer> inputs =
                (inputsList != null && inputsList.getItems() != null && !inputsList.getItems().isEmpty())
                        ? new ArrayList<>(inputsList.getItems())
                        : parseInputs(inputsField == null ? "" : inputsField.getText());


        try {
            // Ensure the table shows the exact program that will run
            Program.Rendered r = currentProgram.expandToDegree(deg);
            if (instructionsTable != null) {
                instructionsTable.setItems(FXCollections.observableArrayList(r.list));
                instructionsTable.refresh();
            }

            // Run to completion using the Debugger engine (step until HALT)
            Debugger dbg = new Debugger(currentProgram, deg, inputs);
            Debugger.Snapshot snap = dbg.snapshot();
            while (!snap.halted) {
                snap = dbg.step();
            }

            // Show results
            showRunResults(snap.vars, snap.cycles);
            addHistoryEntry(deg, inputs, snap.vars, snap.cycles);

            // (We’ll add proper history in 1N)
            appendStatus("Run done (degree " + deg + ", inputs=" + inputsToString(inputs) + ").");

        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }


    @FXML private void onStartRegular(ActionEvent e) { onRun(); }
    @FXML
    private void onStartDebug(ActionEvent e) {
        if (currentProgram == null) { showError("Load a program first."); return; }

        // Degree: parse and clamp
        int deg = clamp(parseIntoZero(degreeField == null ? "" : degreeField.getText()),
                0, currentProgram.maxDegree());

        // Inputs: reuse existing parser (we will replace the input UI later)
        List<Integer> inputs =
                (inputsList != null && inputsList.getItems() != null && !inputsList.getItems().isEmpty())
                        ? new ArrayList<>(inputsList.getItems())
                        : parseInputs(inputsField == null ? "" : inputsField.getText());


        try {
            // Create debugger and remember state
            debugger = new Debugger(currentProgram, deg, inputs);
            debuggerDegree = deg;
            debuggerInputs = inputs;

            // Ensure the table lists the same rendered program as the debugger
            Program.Rendered r = currentProgram.expandToDegree(deg);
            populateFunctionCombo();

            if (instructionsTable != null) {
                instructionsTable.setItems(FXCollections.observableArrayList(r.list));
                instructionsTable.getSelectionModel().clearSelection();
            }
            if (maxDegreeField != null) maxDegreeField.setText(Integer.toString(currentProgram.maxDegree()));

            // Show initial snapshot
            applyDebugSnapshot(debugger.snapshot());
            pcTrace.clear();
            pcTrace.add(debugger.snapshot());
            updateSelectedChain();

            // recompute highlights for current filters
            recomputeLabelRows();
            recomputeVarRows();
            if (instructionsTable != null) instructionsTable.refresh();

            appendStatus("Debugger: started (degree " + deg + ", inputs=" + inputsToString(inputs) + ").");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
        updateControlsEnabled();

    }


    @FXML
    private void onResume(ActionEvent e) {
        if (debugger == null) { showError("Start Debug first."); return; }

        // Stop any existing timer (avoid double-running)
        if (resumeTimer != null) {
            resumeTimer.stop();
            resumeTimer = null;
        }

        resumeTimer = new Timeline(new KeyFrame(Duration.millis(80), ev -> {
            try {
                Debugger.Snapshot snap = debugger.step();
                applyDebugSnapshot(snap);
                pcTrace.add(snap);
                updateSelectedChain();

                if (snap.halted) {
                    appendStatus("Debugger: halted.");
                    resumeTimer.stop();
                    resumeTimer = null;
                }
            } catch (Exception ex) {
                showError(ex.getMessage());
                resumeTimer.stop();
                resumeTimer = null;

            }
        }));
        resumeTimer.setCycleCount(Timeline.INDEFINITE);
        resumeTimer.play();
        appendStatus("Debugger: resumed.");
        updateControlsEnabled();

    }


    @FXML
    private void onStepOver(ActionEvent e) {
        if (debugger == null) { showError("Start Debug first."); return; }
        try {
            Debugger.Snapshot snap = debugger.step();
            applyDebugSnapshot(snap);
            pcTrace.add(snap);
            updateSelectedChain();

            if (snap.halted) appendStatus("Debugger: halted.");
        } catch (Exception ex) {
            showError(ex.getMessage());
        }
    }


    @FXML
    private void onStop(ActionEvent e) {
        if (resumeTimer != null) {
            resumeTimer.stop();
            resumeTimer = null;
        }
        if (debugger != null) {
            debugger = null;
            appendStatus("Debugger: stopped.");
        }
        pcTrace.clear();
        updateSelectedChain();

        if (pcLabel != null)   pcLabel.setText("*");
        if (haltLabel != null) haltLabel.setText("false");
        if (instructionsTable != null) instructionsTable.refresh();
        updateControlsEnabled();
// remove highlight
    }



    private void doOneStep() {
        if (debugger == null) return;
        Debugger.Snapshot s = debugger.step();
        debugPc = s.pc;
        if (pcLabel != null)     pcLabel.setText(s.pc < 0 ? "-" : Integer.toString(s.pc + 1));
        if (cyclesLabel != null) cyclesLabel.setText(Integer.toString(s.cycles));
        if (haltLabel != null)   haltLabel.setText(Boolean.toString(s.halted));
        showVariablesInDebugPane(s.vars, s.changed.keySet(), "Step");
        instructionsTable.refresh();
        if (s.halted && debugTimeline != null) { debugTimeline.stop(); debugTimeline = null; }
    }

    private void refreshUiAfterProgramLoad(Program p) {
        currentProgram = p;
        lastGoodProgram = p;

        if (maxDegreeField != null) maxDegreeField.setText(Integer.toString(p.maxDegree()));
        if (degreeField != null) {
            int d = clamp(parseIntoZero(degreeField.getText()), 0, p.maxDegree());
            degreeField.setText(Integer.toString(d));
        }
        listProgramAtDegreeField();   // render table at current degree
        populateFunctionCombo();      // enable + fill "(all)" + function names
        appendStatus("Program loaded: " + p.name + " (max degree " + p.maxDegree() + ").");
        updateControlsEnabled();

    }


    // ======================================================
    // Highlight (matches FXML handler names)
    // ======================================================

    @FXML
    private void onHighlightLabel(ActionEvent e) {
        if (instructionsTable == null) return;
        String wanted = highlightLabelField == null ? "" : highlightLabelField.getText();
        if (wanted == null) return;
        highlightLabelFilter = wanted.trim();
        int idx = highlightLabelFilter.isBlank() ? -1 : findFirstByLabel(highlightLabelFilter);
        if (idx >= 0) instructionsTable.getSelectionModel().select(idx);
        instructionsTable.refresh(); // re-style rows
    }

    @FXML
    private void onHighlightVar(ActionEvent e) {
        if (instructionsTable == null) return;
        String wanted = highlightVarField == null ? "" : highlightVarField.getText();
        if (wanted == null) return;
        highlightVarFilter = wanted.trim();
        int idx = highlightVarFilter.isBlank() ? -1 : findFirstByVar(highlightVarFilter);
        if (idx >= 0) instructionsTable.getSelectionModel().select(idx);
        instructionsTable.refresh(); // re-style rows
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

    private void switchFunctionView() {
        // Delegate to the same logic as the FXML handler
        onFunctionChanged(null);
    }


    // ======================================================
    // Helpers
    // ======================================================

    // ====================
// Utility printouts
// ====================
    private void showVariablesInDebugPane(Map<String,Integer> vars, java.util.Set<String> changed, String header) {
        if (debugSummaryArea == null) return;

        Map<String,Integer> changedMap = null;
        if (changed != null && !changed.isEmpty()) {
            changedMap = new LinkedHashMap<>();
            for (String k : changed) changedMap.put(k, 1);
        }

        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header).append(System.lineSeparator());
        }
        for (String line : formatVarsOrdered(vars, changedMap)) {
            sb.append(line).append(System.lineSeparator());
        }
        debugSummaryArea.setText(sb.toString());
    }



    private static int readFinalY(Map<String,Integer> vars) {
        return vars.getOrDefault("y", 0);
    }

    private void addHistoryEntry(int degree, List<Integer> inputs, Map<String,Integer> vars, int cycles) {
        runCounter++;
        HistoryRow row = new HistoryRow(runCounter, degree, inputsToString(inputs), readFinalY(vars), cycles,
                new LinkedHashMap<>(vars));

        // keep a lookup by run #
        historyVars.put(row.runNo, new LinkedHashMap<>(vars));

        // drive the table via our observable list
        historyRows.add(row);
        if (historyTable != null) {
            if (historyTable.getItems() == null) {
                historyTable.setItems(historyRows);
            }
            historyTable.refresh();
            historyTable.getSelectionModel().selectLast();
        }
    }


    @FXML
    private void onRerunSelected(ActionEvent e) {
        if (historyTable == null) { showError("History table not available."); return; }
        HistoryRow row = historyTable.getSelectionModel().getSelectedItem();
        if (row == null) { showError("Select a history row first."); return; }
        // Put its degree & inputs into the fields (if present)
        if (degreeField != null) degreeField.setText(Integer.toString(row.degree));
        if (inputsField != null) inputsField.setText(row.inputsCsv);

        onRun();
    }



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
        public final int runNo;
        public final int degree;
        public final String inputsCsv;
        public final int y;
        public final int cycles;
        public final java.util.Map<String,Integer> snapshot; // full variables snapshot

        public HistoryRow(int runNo, int degree, String inputsCsv, int y, int cycles,
                          java.util.Map<String,Integer> snapshot) {
            this.runNo = runNo;
            this.degree = degree;
            this.inputsCsv = inputsCsv;
            this.y = y;
            this.cycles = cycles;
            this.snapshot = snapshot;
        }

        // --- getters required by PropertyValueFactory in FXML ---
        public int getRunNo()        { return runNo; }
        public int getDegree()       { return degree; }
        public String getInputsCsv() { return inputsCsv; }
        public int getY()            { return y; }
        public int getCycles()       { return cycles; }
    }


    private static final class ErrorRow {
        final String when, what;
        ErrorRow(String when, String what) { this.when = when; this.what = what; }
    }
}

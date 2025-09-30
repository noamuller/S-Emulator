package sengine;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import javax.xml.transform.stream.StreamSource;

import java.io.File;
import java.util.*;
import java.util.function.Function;

/**
 * Course XML parser (v2) with back-compat to a very simple <program> format.
 * Returns a Program that contains the main instruction list and a function library
 * (used for QUOTE / nested calls expansion in Program.expandToDegree()).
 */
public final class ProgramParser {

    // ---------- Public API ----------

    /** Parse XML file (throws with a clear message if anything is off). */
    public static Program parseFromXml(File file) throws Exception {
        if (file == null) throw new IllegalArgumentException("File is null");
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder b = dbf.newDocumentBuilder();
        Document doc = b.parse(file);
        Element root = doc.getDocumentElement();
        String tag = root.getTagName();

        if ("program".equalsIgnoreCase(tag)) {
            return parseSimple(root);
        }
        if ("S-Program".equalsIgnoreCase(tag)) {
            return parseCourse(root);
        }
        throw new IllegalArgumentException("Unknown root element: <" + tag + ">");
    }

    /** Shortcut that accepts a String path. */
    public static Program parseFromXml(String path) throws Exception {
        return parseFromXml(new File(path));
    }

    /** Optional: XSD validation (call from GUI before parse if you want). */
    public static void validateWithXsd(File xml, File xsd) throws Exception {
        if (xsd == null) return;
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(xsd);
        Validator validator = schema.newValidator();
        validator.validate(new StreamSource(xml));
    }

    /** Validate that all goto targets are legal within the given instruction list. */
    public static String validateLabels(Program program) {
        if (program == null) return "Program is null";
        Program.Rendered r = program.expandToDegree(0);
        return validateLabels(r.list);
    }

    // ---------- SIMPLE FORMAT ----------

    private static Program parseSimple(Element root) {
        String name = attrOr(root, "name", "Unnamed");
        Element instrsEl = firstChild(root, "instructions");
        if (instrsEl == null) throw new IllegalArgumentException("Missing <instructions>");

        List<Instruction> ins = new ArrayList<>();
        for (Element e : children(instrsEl, "instruction")) {
            String type = attrOr(e, "type", "B");
            String label = attrOrNull(e, "label");
            String command = textOfRequired(e, "command");
            Integer cycles = parseIntOrNull(textOfOptional(firstChildOpt(e, "cycles")));
            ins.add(Instruction.parseFromText(label, command, type, cycles));

        }
        String err = validateLabels(ins);
        if (err != null) throw new IllegalArgumentException(err);
        return new Program(name, ins);
    }

    // ---------- COURSE FORMAT (v2) ----------

    private static Program parseCourse(Element root) {
        String name = attrOr(root, "name", "Unnamed");

        // Main instructions
        Element instrsEl = firstChild(root, "S-Instructions");
        if (instrsEl == null) throw new IllegalArgumentException("Missing <S-Instructions>");

        List<Instruction> main = parseInstructionBlock(instrsEl);

        // Optional function library
        Map<String, List<Instruction>> functions = new LinkedHashMap<>();
        Element funsEl = firstChildOpt(root, "S-Functions");
        if (funsEl != null) {
            for (Element fEl : children(funsEl, "S-Function")) {
                String fname = attrOr(fEl, "name", null);
                if (fname == null || fname.isBlank()) {
                    throw new IllegalArgumentException("S-Function without name");
                }
                Element finstr = firstChild(fEl, "S-Instructions");
                List<Instruction> body = parseInstructionBlock(finstr);
                functions.put(fname, body);
            }
        }

        String err = validateLabels(main);
        if (err != null) throw new IllegalArgumentException(err);

        return new Program(name, main, functions);
    }

    private static List<Instruction> parseInstructionBlock(Element instrsEl) {
        List<Instruction> out = new ArrayList<>();
        for (Element e : children(instrsEl, "S-Instruction")) {
            String typeAttr = attrOr(e, "type", "basic");
            String nameAttr = attrOr(e, "name", "").trim();
            String label = textOfOptional(firstChildOpt(e, "S-Label"));
            String var   = textOfOptional(firstChildOpt(e, "S-Variable"));

            // arguments map
            final Map<String,String> args = new HashMap<>();
            Element argsEl = firstChildOpt(e, "S-Instruction-Arguments");
            if (argsEl != null) {
                for (Element a : children(argsEl, "S-Instruction-Argument")) {
                    String n = attrOr(a, "name", "");
                    String v = attrOr(a, "value", "");
                    if (!n.isBlank()) args.put(n, v);
                }
            }
            Function<String,String> arg = k -> args.get(k);

            String cmdText = null;
            String typeHint = "B";
            Integer cycles = null;

            // ----- basic names -----
            if (eq(nameAttr, "INCREASE")) {
                require(var, "INCREASE needs <S-Variable>");
                cmdText = var + " <- " + var + " + 1";
            } else if (eq(nameAttr, "DECREASE")) {
                require(var, "DECREASE needs <S-Variable>");
                cmdText = var + " <- " + var + " - 1";
            } else if (eq(nameAttr, "NOP")) {
                require(var, "NOP needs <S-Variable>");
                cmdText = var + " <- " + var;
            } else if (eq(nameAttr, "JUMP_NOT_ZERO")) {
                require(var, "JUMP_NOT_ZERO needs <S-Variable>");
                String target = arg.apply("JNZLabel");
                require(target, "JUMP_NOT_ZERO needs JNZLabel");
                cmdText = "IF " + var + " != 0 GOTO " + target;
            } else if (eq(nameAttr, "JUMP_ZERO")) {
                require(var, "JUMP_ZERO needs <S-Variable>");
                String target = arg.apply("JZLabel");
                require(target, "JUMP_ZERO needs JZLabel");
                cmdText = "IF " + var + " == 0 GOTO " + target;
            } else if (eq(nameAttr, "GOTO_LABEL")) {
                String target = arg.apply("gotoLabel");
                require(target, "GOTO_LABEL needs gotoLabel");
                cmdText = "GOTO " + target;
            } else if (eq(nameAttr, "JUMP_EQUAL_CONST")) {
                require(var, "JUMP_EQUAL_CONST needs <S-Variable>");
                String c = arg.apply("constantValue");
                String target = arg.apply("JZLabel");
                require(c, "JUMP_EQUAL_CONST needs constantValue");
                require(target, "JUMP_EQUAL_CONST needs JZLabel");
                cmdText = "IF " + var + " == " + c + " GOTO " + target;
            } else if (eq(nameAttr, "JUMP_EQUAL_VARIABLES")) {
                String a1 = arg.apply("variableA");
                String a2 = arg.apply("variableB");
                String target = arg.apply("JZLabel");
                require(a1, "JUMP_EQUAL_VARIABLES needs variableA");
                require(a2, "JUMP_EQUAL_VARIABLES needs variableB");
                require(target, "JUMP_EQUAL_VARIABLES needs JZLabel");
                cmdText = "IF " + a1 + " == " + a2 + " GOTO " + target;

                // ----- synthetic "sugar" we keep as synthetic -----
            } else if (eq(nameAttr, "ZERO_VARIABLE")) {
                require(var, "ZERO_VARIABLE needs <S-Variable>");
                typeHint = "S";
                cmdText = var + " <- 0";
            } else if (eq(nameAttr, "ASSIGNMENT")) {
                require(var, "ASSIGNMENT needs <S-Variable> (destination)");
                String src = arg.apply("assignedVariable");
                require(src, "ASSIGNMENT needs assignedVariable");
                typeHint = "S";
                cmdText = var + " <- " + src;
            } else if (eq(nameAttr, "CONSTANT_ASSIGNMENT")) {
                require(var, "CONSTANT_ASSIGNMENT needs <S-Variable>");
                String c = arg.apply("constantValue");
                require(c, "CONSTANT_ASSIGNMENT needs constantValue");
                typeHint = "S";
                cmdText = var + " <- " + c;

                // ----- תרגיל 2 synthetics used by Program.expandToDegree() -----
            } else if (eq(nameAttr, "QUOTE")) {
                require(var, "QUOTE needs <S-Variable> (destination)");
                String fname = arg.apply("functionName");
                String fargs = arg.apply("functionArguments"); // may be empty or contain nested calls notation
                require(fname, "QUOTE needs functionName");
                typeHint = "S";
                if (fargs == null) fargs = "";
                cmdText = "QUOTE " + var + " <- " + fname + "(" + fargs + ")";
            } else if (eq(nameAttr, "JUMP_EQUAL_FUNCTION")) {
                require(var, "JUMP_EQUAL_FUNCTION needs <S-Variable> (to compare)");
                String fname = arg.apply("functionName");
                String fargs = arg.apply("functionArguments");
                String target = arg.apply("JZLabel");
                require(fname, "JUMP_EQUAL_FUNCTION needs functionName");
                require(target, "JUMP_EQUAL_FUNCTION needs JZLabel");
                typeHint = "S";
                if (fargs == null) fargs = "";
                cmdText = "JUMP_EQUAL_FUNCTION " + var + " == " + fname + "(" + fargs + ") GOTO " + target;

            } else {
                // Fallback: opaque instruction with its display name
                // Treat as basic or synthetic depending on typeAttr
                typeHint = typeAttr.toLowerCase(Locale.ROOT).startsWith("b") ? "B" : "S";
                String display = nameAttr;
                if (var != null && !var.isBlank()) {
                    display = display + " " + var;
                }
                // Keep as opaque so UI still shows something
                cmdText = display;
            }

            Instruction inst = Instruction.parseFromText(label, cmdText, typeHint, cycles);
            out.add(inst);
        }
        return out;
    }

    // ---------- Label validation on a list ----------

    static String validateLabels(List<Instruction> ins) {
        Set<String> labels = new HashSet<>();
        for (Instruction i : ins) {
            if (i.label != null && !i.label.isBlank())
                labels.add(i.label.trim().toUpperCase(Locale.ROOT));
        }
        for (Instruction i : ins) {
            if (i instanceof Instruction.IfNzGoto j) {
                String t = j.target.toUpperCase(Locale.ROOT);
                if (!"EXIT".equals(t) && !labels.contains(t)) return "Illegal GOTO to missing label: " + j.target;
            } else if (i instanceof Instruction.Goto g) {
                String t = g.target.toUpperCase(Locale.ROOT);
                if (!"EXIT".equals(t) && !labels.contains(t)) return "Illegal GOTO to missing label: " + g.target;
            } else if (i instanceof Instruction.IfZeroGoto z) {
                String t = z.target.toUpperCase(Locale.ROOT);
                if (!"EXIT".equals(t) && !labels.contains(t)) return "Illegal GOTO to missing label: " + z.target;
            } else if (i instanceof Instruction.IfEqConstGoto ec) {
                String t = ec.target.toUpperCase(Locale.ROOT);
                if (!"EXIT".equals(t) && !labels.contains(t)) return "Illegal GOTO to missing label: " + ec.target;
            } else if (i instanceof Instruction.IfEqVarGoto ev) {
                String t = ev.target.toUpperCase(Locale.ROOT);
                if (!"EXIT".equals(t) && !labels.contains(t)) return "Illegal GOTO to missing label: " + ev.target;
            }
        }
        return null;
    }

    // ---------- Tiny DOM helpers ----------

    private static String attrOr(Element e, String name, String dflt) {
        if (!e.hasAttribute(name)) return dflt;
        String v = e.getAttribute(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }
    private static String attrOrNull(Element e, String name) { return attrOr(e, name, null); }

    private static Element firstChild(Element parent, String childName) {
        NodeList nl = parent.getElementsByTagName(childName);
        if (nl.getLength() == 0) return null;
        // must be direct child: scan children
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (childName.equals(el.getTagName())) return el;
            }
        }
        return null;
    }
    private static Element firstChildOpt(Element parent, String childName) {
        if (parent == null) return null;
        return firstChild(parent, childName);
    }

    private static List<Element> children(Element parent, String childName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (childName.equals(el.getTagName())) out.add(el);
            }
        }
        return out;
    }

    private static String textOfOptional(Element el) {
        return el == null ? null : el.getTextContent().trim();
    }
    private static String textOfRequired(Element parent, String childName) {
        Element el = firstChild(parent, childName);
        if (el == null) throw new IllegalArgumentException("Missing <" + childName + ">");
        return el.getTextContent().trim();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return null; }
    }

    private static void require(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(msg);
    }
    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }
}

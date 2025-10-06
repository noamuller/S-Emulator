package sengine;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/** Parses XML into a Program for תרגיל 2 (simple format + S-Program course format). */
public final class ProgramParser {

    private ProgramParser() {}

    // ------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------

    /** Parse either the simple <program> format or the course <S-Program> format. */
    public static Program parseFromXml(File file) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            Element root = doc.getDocumentElement();
            root.normalize();

            if (eq(root.getTagName(), "program")) {
                return parseSimple(root);
            } else if (eq(root.getTagName(), "S-Program")) {
                return parseCourse(root);
            } else {
                throw new IllegalArgumentException("Unknown root: " + root.getTagName());
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to load: " + ex.getMessage(), ex);
        }
    }

    /** Optional XSD validation. */
    public static void validateWithXsd(File xml, File xsd) throws Exception {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = sf.newSchema(xsd);
        Validator validator = schema.newValidator();
        validator.validate(new javax.xml.transform.stream.StreamSource(xml));
    }

    // ------------------------------------------------------------
    // Simple <program> format (kept minimal/back-compat)
    // ------------------------------------------------------------

    private static Program parseSimple(Element root) {
        String name = attrOr(root, "name", "Unnamed");
        Element instrWrap = firstChild(root, "instructions");
        if (instrWrap == null) throw new IllegalArgumentException("Missing <instructions>");

        List<Instruction> list = new ArrayList<>();
        for (Element e : children(instrWrap, "instruction")) {
            String type = attrOr(e, "type", "B"); // "B" or "S"
            String label = textOfOptional(e, "label");
            String command = textOfRequired(e, "command");
            Integer cycles = parseIntOrNull(textOfOptional(e, "cycles"));
            list.add(Instruction.parseFromText(label, command, type, cycles == null ? 1 : cycles));
        }
        return new Program(name, list);
    }

    // ------------------------------------------------------------
    // S-Program course format (supports your XMLs like divide.xml)
    // ------------------------------------------------------------

    private static Program parseCourse(Element root) {
        String name = attrOr(root, "name", "Unnamed");

        Element sInstrWrap = firstChild(root, "S-Instructions");
        if (sInstrWrap == null) throw new IllegalArgumentException("Missing <S-Instructions>");

        // Collect function names defined under <S-Functions>
        Set<String> definedFnNames = collectFunctionNames(root);
        // Allow a small “stdlib” used by provided XMLs (can appear inside QUOTE):
        definedFnNames.addAll(Arrays.asList(
                "CONST", "NOT", "EQUAL", "AND", "OR",
                "Smaller_Than", "Bigger_Equal_Than", "Smaller_Equal_Than",
                "Minus", "Successor"
        ));

        // Top-level program body
        List<Instruction> out = new ArrayList<>();

        for (Element e : children(sInstrWrap, "S-Instruction")) {
            String type = attrOr(e, "name", "");
            String label = readOptionalLabel(e);

            // ----- JUMP_EQUAL_FUNCTION (synthetic): keep synthetic text for expansion
            if (eq(type, "JUMP_EQUAL_FUNCTION")) {
                out.add(buildJumpEqualFunction(root, e, label));
                continue;
            }

            // ----- QUOTE (synthetic): keep as QUOTE text for degree>0 expansion
            // Supports both legacy constant QUOTE and callable QUOTE.
            if (eq(type, "QUOTE")) {
                out.add(buildQuoteSynthetic(root, e, label, definedFnNames));
                continue;
            }

            // ----- ASSIGNMENT (synthetic): <S-Variable>dst</S-Variable>, arg assignedVariable="src"
            if (eq(type, "ASSIGNMENT")) {
                String dst = textOfRequired(e, "S-Variable");
                Element args = firstChild(e, "S-Instruction-Arguments");
                String src = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "assignedVariable")) {
                            src = attrOr(a, "value", "");
                        }
                    }
                }
                if (src == null || src.isBlank())
                    throw new IllegalArgumentException("ASSIGNMENT missing assignedVariable");
                out.add(Instruction.parseFromText(label, dst + " <- " + src, "B", 1));
                continue;
            }

            // ----- CONSTANT_ASSIGNMENT (synthetic): dst <- const
            if (eq(type, "CONSTANT_ASSIGNMENT")) {
                String dst = textOfRequired(e, "S-Variable");
                Element args = firstChild(e, "S-Instruction-Arguments");
                String val = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "constantValue")) {
                            val = attrOr(a, "value", "");
                        }
                    }
                }
                if (val == null || val.isBlank())
                    throw new IllegalArgumentException("CONSTANT_ASSIGNMENT missing constantValue");
                out.add(Instruction.parseFromText(label, dst + " <- " + val, "B", 1));
                continue;
            }

            // ----- ZERO_VARIABLE (synthetic): dst <- 0
            if (eq(type, "ZERO_VARIABLE")) {
                String dst = textOfRequired(e, "S-Variable");
                out.add(Instruction.parseFromText(label, dst + " <- 0", "B", 1));
                continue;
            }

            // ----- INCREASE / DECREASE (basic)
            if (eq(type, "INCREASE")) {
                String v = textOfRequired(e, "S-Variable");
                out.add(Instruction.parseFromText(label, v + " <- " + v + " + 1", "B", 1));
                continue;
            }
            if (eq(type, "DECREASE")) {
                String v = textOfRequired(e, "S-Variable");
                out.add(Instruction.parseFromText(label, v + " <- " + v + " - 1", "B", 1));
                continue;
            }

            // ----- JUMP_NOT_ZERO (basic) → IF v != 0 GOTO label
            if (eq(type, "JUMP_NOT_ZERO")) {
                String v = textOfRequired(e, "S-Variable");
                String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JNZLabel");
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("JUMP_NOT_ZERO missing JNZLabel");
                out.add(Instruction.parseFromText(label, "IF " + v + " != 0 GOTO " + target, "B", 2));
                continue;
            }

            // ----- JUMP_ZERO (synthetic in some XMLs) → IF v == 0 GOTO label
            if (eq(type, "JUMP_ZERO")) {
                String v = textOfRequired(e, "S-Variable");
                String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JZLabel");
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("JUMP_ZERO missing JZLabel");
                out.add(Instruction.parseFromText(label, "IF " + v + " == 0 GOTO " + target, "B", 2));
                continue;
            }

            // ----- GOTO_LABEL (synthetic) → basic "GOTO <label>"
            if (eq(type, "GOTO_LABEL") || (eq(type, "SYNTHETIC") && eq(attrOr(e, "name", ""), "GOTO_LABEL"))) {
                String target = null;
                Element args = firstChild(e, "S-Instruction-Arguments");
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        String an = attrOr(a, "name", "");
                        if (eq(an, "gotoLabel") || eq(an, "label")) {
                            target = attrOr(a, "value", "");
                        }
                    }
                }
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("GOTO_LABEL missing label");
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                continue;
            }

            // Fallback: keep as synthetic so it still displays in the UI (rare)
            out.add(Instruction.parseFromText(label, type, "S", 1));
        }

        // Build the functions library and attach to Program
        Map<String, List<Instruction>> functions = parseFunctions(root);
        return new Program(name, out, functions);
    }

    // ------------------------------------------------------------
    // Functions map: parse <S-Functions> into Program.functions
    // ------------------------------------------------------------

    /** Parse <S-Functions> into Program's function map: name -> list of textual instructions. */
    private static Map<String, List<Instruction>> parseFunctions(Element root) {
        Map<String, List<Instruction>> fnMap = new LinkedHashMap<>();

        Element functions = firstChild(root, "S-Functions");
        if (functions == null) return fnMap;

        for (Element sf : children(functions, "S-Function")) {
            String fname = attrOr(sf, "name", "");
            if (fname == null || fname.isBlank()) continue;
            fname = fname.trim();

            Element body = firstChild(sf, "S-Instructions");
            if (body == null) {
                fnMap.put(fname, List.of());
                continue;
            }

            List<Instruction> bodyList = new ArrayList<>();

            for (Element e : children(body, "S-Instruction")) {
                String label = readOptionalLabel(e);
                String type = attrOr(e, "name", "");

                // Basic/synthetic forms normalized to textual commands:

                if (eq(type, "ASSIGNMENT")) {
                    String dst = textOfRequired(e, "S-Variable");
                    String src = readArgValue(firstChild(e, "S-Instruction-Arguments"), "assignedVariable");
                    if (src == null || src.isBlank())
                        throw new IllegalArgumentException("ASSIGNMENT missing assignedVariable");
                    bodyList.add(Instruction.parseFromText(label, dst + " <- " + src, "B", 1));
                    continue;
                }

                if (eq(type, "CONSTANT_ASSIGNMENT")) {
                    String dst = textOfRequired(e, "S-Variable");
                    String val = readArgValue(firstChild(e, "S-Instruction-Arguments"), "constantValue");
                    if (val == null || val.isBlank())
                        throw new IllegalArgumentException("CONSTANT_ASSIGNMENT missing constantValue");
                    bodyList.add(Instruction.parseFromText(label, dst + " <- " + val, "B", 1));
                    continue;
                }

                if (eq(type, "ZERO_VARIABLE")) {
                    String dst = textOfRequired(e, "S-Variable");
                    bodyList.add(Instruction.parseFromText(label, dst + " <- 0", "B", 1));
                    continue;
                }

                if (eq(type, "INCREASE")) {
                    String v = textOfRequired(e, "S-Variable");
                    bodyList.add(Instruction.parseFromText(label, v + " <- " + v + " + 1", "B", 1));
                    continue;
                }
                if (eq(type, "DECREASE")) {
                    String v = textOfRequired(e, "S-Variable");
                    bodyList.add(Instruction.parseFromText(label, v + " <- " + v + " - 1", "B", 1));
                    continue;
                }

                if (eq(type, "JUMP_NOT_ZERO")) {
                    String v = textOfRequired(e, "S-Variable");
                    String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JNZLabel");
                    if (target == null || target.isBlank())
                        throw new IllegalArgumentException("JUMP_NOT_ZERO missing JNZLabel");
                    bodyList.add(Instruction.parseFromText(label, "IF " + v + " != 0 GOTO " + target, "B", 2));
                    continue;
                }

// ----- JUMP_ZERO (synthetic in some XMLs) → IF v == 0 GOTO label
                if (eq(type, "JUMP_ZERO")) {
                    String v = textOfRequired(e, "S-Variable");
                    String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JZLabel");
                    if (target == null || target.isBlank())
                        throw new IllegalArgumentException("JUMP_ZERO missing JZLabel");
                    bodyList.add(Instruction.parseFromText(label, "IF " + v + " == 0 GOTO " + target, "B", 2));
                    continue;
                }


                if (eq(type, "GOTO_LABEL") || (eq(type, "SYNTHETIC") && eq(attrOr(e, "name", ""), "GOTO_LABEL"))) {
                    String target = null;
                    Element args = firstChild(e, "S-Instruction-Arguments");
                    if (args != null) {
                        for (Element a : children(args, "S-Instruction-Argument")) {
                            String an = attrOr(a, "name", "");
                            if (eq(an, "gotoLabel") || eq(an, "label")) {
                                target = attrOr(a, "value", "");
                            }
                        }
                    }
                    if (target == null || target.isBlank())
                        throw new IllegalArgumentException("GOTO_LABEL missing label");
                    bodyList.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                    continue;
                }

                // Keep QUOTE/JEF *inside functions* as textual synthetic lines.
                if (eq(type, "QUOTE")) {
                    String dst = textOfRequired(e, "S-Variable");
                    Element args = firstChild(e, "S-Instruction-Arguments");
                    String fnName = null;
                    String fnArgs = "";
                    if (args != null) {
                        for (Element a : children(args, "S-Instruction-Argument")) {
                            String n = attrOr(a, "name", "");
                            if (eq(n, "functionName"))           fnName = attrOr(a, "value", "");
                            else if (eq(n, "functionArguments")) fnArgs = attrOr(a, "value", "");
                        }
                    }
                    if (fnName == null || fnName.isBlank())
                        throw new IllegalArgumentException("QUOTE missing functionName");
                    if (fnArgs == null) fnArgs = "";
                    String argsNorm = normalizeFunctionArguments(fnArgs);
                    String text = "QUOTE " + dst + " <- " + fnName + "(" + argsNorm + ")";
                    bodyList.add(Instruction.parseFromText(label, text, "S", null));
                    continue;
                }

                if (eq(type, "JUMP_EQUAL_FUNCTION")) {
                    bodyList.add(buildJumpEqualFunction(root, e, label));
                    continue;
                }

                // Fallback: keep synthetic name visible
                bodyList.add(Instruction.parseFromText(label, type, "S", 1));
            }

            fnMap.put(fname, List.copyOf(bodyList));
        }

        return fnMap;
    }

    // ------------------------------------------------------------
    // Builders for synthetic instructions
    // ------------------------------------------------------------

    /** Build a *synthetic* QUOTE instruction for top-level program: "QUOTE dst <- Func(args)". */
    private static Instruction buildQuoteSynthetic(Element root, Element e, String label, Set<String> definedFnNames) {
        String dst = textOfRequired(e, "S-Variable");

        Element argsWrap = firstChild(e, "S-Instruction-Arguments");
        String fnName = null;
        String fnArgs = ""; // e.g. "(Successor,x1)" or "x1,x2"
        if (argsWrap != null) {
            for (Element a : children(argsWrap, "S-Instruction-Argument")) {
                String n = attrOr(a, "name", "");
                if (eq(n, "functionName"))           fnName = attrOr(a, "value", "");
                else if (eq(n, "functionArguments")) fnArgs = attrOr(a, "value", "");
            }
        }
        if (fnName == null || fnName.isBlank())
            throw new IllegalArgumentException("QUOTE: missing functionName");

        if (fnArgs == null) fnArgs = "";
        if (!fnArgs.isBlank()) {
            forbidOuterWrappingOfMultiArgs(fnArgs);
            String argsNorm = normalizeFunctionArguments(fnArgs);
            verifyFunctionsExist(definedFnNames, topLevelFunctionNames(argsNorm));
            String text = "QUOTE " + dst + " <- " + fnName + "(" + argsNorm + ")";
            return Instruction.parseFromText(label, text, "S", null);
        }

        // Legacy constant QUOTE form: resolve to constant if possible
        Integer constVal = tryResolveConstFunction(root, fnName);
        if (constVal == null)
            throw new IllegalArgumentException("QUOTE: function '" + fnName + "' must be a constant function");
        return Instruction.parseFromText(label, dst + " <- " + constVal, "B", 1);
    }

    /** Build a *synthetic* JUMP_EQUAL_FUNCTION: "JUMP_EQUAL_FUNCTION var == Func(args) GOTO label". */
    private static Instruction buildJumpEqualFunction(Element root, Element e, String label) {
        String varName = textOfRequired(e, "S-Variable");

        Element args = firstChild(e, "S-Instruction-Arguments");
        if (args == null) {
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION missing <S-Instruction-Arguments>");
        }

        String fnName = null;
        String jumpTarget = null;
        List<String> argVals = new ArrayList<>();

        for (Element a : children(args, "S-Instruction-Argument")) {
            String an = attrOr(a, "name", "");
            String av = attrOr(a, "value", "");
            if (eq(an, "JEFunctionLabel") || eq(an, "JZLabel")) {
                jumpTarget = av;
            } else if (eq(an, "functionName")) {
                fnName = av;
            } else {
                if (av != null && !av.isBlank()) {
                    argVals.add(av.trim());
                }
            }
        }

        if (fnName == null || fnName.isBlank()) {
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION missing functionName");
        }
        if (jumpTarget == null || jumpTarget.isBlank()) {
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION needs a jump target label (JEFunctionLabel/JZLabel)");
        }

        String argsCsv = String.join(", ", argVals);
        String text = "JUMP_EQUAL_FUNCTION " + varName + " == " + fnName + "(" + argsCsv + ") GOTO " + jumpTarget;
        return Instruction.parseFromText(label, text, "S", null);
    }

    // ------------------------------------------------------------
    // Validation helpers for QUOTE arguments
    // ------------------------------------------------------------

    /** Gather function names defined under <S-Functions>. */
    private static Set<String> collectFunctionNames(Element root) {
        Set<String> names = new HashSet<>();
        Element functions = firstChild(root, "S-Functions");
        if (functions == null) return names;
        for (Element sf : children(functions, "S-Function")) {
            String name = attrOr(sf, "name", "").trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /** Verify every function name referenced in args exists in <S-Functions>/stdlib. */
    private static void verifyFunctionsExist(Set<String> defined, List<String> names) {
        for (String n : names) {
            if (!defined.contains(n)) {
                throw new IllegalArgumentException("Unknown function: " + n);
            }
        }
    }

    /** Resolve a function that returns a known constant (single CONSTANT_ASSIGNMENT to y). */
    private static Integer tryResolveConstFunction(Element root, String functionName) {
        Element functions = firstChild(root, "S-Functions");
        if (functions == null) return null;

        for (Element sf : children(functions, "S-Function")) {
            String name = attrOr(sf, "name", "");
            if (!name.equals(functionName)) continue;

            Element insWrap = firstChild(sf, "S-Instructions");
            if (insWrap == null) return null;

            Element one = firstChild(insWrap, "S-Instruction");
            if (one == null) return null;

            String type = attrOr(one, "name", "");
            if (!eq(type, "CONSTANT_ASSIGNMENT")) return null;

            // ensure the constant is applied to y
            String dst = textOfRequired(one, "S-Variable");
            if (!"y".equals(dst)) return null;

            Element args = firstChild(one, "S-Instruction-Arguments");
            if (args == null) return null;

            for (Element a : children(args, "S-Instruction-Argument")) {
                if (eq(attrOr(a, "name", ""), "constantValue")) {
                    String val = attrOr(a, "value", "");
                    if (val != null && !val.isBlank()) {
                        try {
                            return Integer.parseInt(val.trim());
                        } catch (NumberFormatException ignore) {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Parse top-level function names referenced directly in a canonical args string. */
    private static List<String> topLevelFunctionNames(String argsNorm) {
        // We only need to identify names at the start of “(FuncName, …)” call tuples.
        List<String> names = new ArrayList<>();
        if (argsNorm == null || argsNorm.isBlank()) return names;
        int i = 0;
        String s = argsNorm.trim();
        while (i < s.length()) {
            while (i < s.length() && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i++;
            if (i >= s.length()) break;
            if (s.charAt(i) == '(') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != ',' && s.charAt(j) != ')' && !Character.isWhitespace(s.charAt(j))) j++;
                if (j <= s.length()) {
                    String fn = s.substring(i + 1, j).trim();
                    if (!fn.isEmpty()) names.add(fn);
                }
                // skip until matching ')'
                int depth = 1; j++;
                while (j < s.length() && depth > 0) {
                    char c = s.charAt(j++);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                i = j;
            } else {
                // bare variable token → no function here; skip token
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != ',' && !Character.isWhitespace(s.charAt(j))) j++;
                i = j;
            }
        }
        return names;
    }

    /** Normalize functionArguments strings into canonical call tuples, allowing "(Name, args...)" and chaining. */
    private static String normalizeFunctionArguments(String fnArgs) {
        // Accept either "(Name,arg,...)" tuples and/or flat "x1,x2" variables, possibly comma-separated.
        // We just preserve the text but trim outer whitespace.
        return fnArgs.trim();
    }

    /** Reject a single *outer* pair of parentheses around *multiple* top-level args. */
    // ProgramParser.java — REPLACE THIS WHOLE METHOD

    /**
     * Reject only when there is a single pair of *outer* parentheses that wraps the
     * ENTIRE string *and* there is a top-level comma inside those outer parens.
     * Otherwise (e.g., multiple adjacent tuples like "(A,...),(B,...)"), allow it.
     */
    private static void forbidOuterWrappingOfMultiArgs(String s) {
        if (s == null) return;
        String str = s.trim();
        if (str.length() < 2) return;
        if (str.charAt(0) != '(') return;

        // Find the matching ')' for the very first '(' at position 0
        int depth = 1;
        int i = 1;
        int match = -1;
        while (i < str.length()) {
            char c = str.charAt(i++);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) {
                    match = i - 1; // index of the matching ')'
                    break;
                }
            }
        }

        // If the matching ')' is NOT the last character, then the first '('
        // does not wrap the entire string → this is fine (e.g., "(A,...),(B,...)")
        if (match != str.length() - 1) return;

        // The outermost parentheses wrap the entire string.
        // If there's a top-level comma inside them, that's invalid outer wrapping.
        depth = 0;
        for (int j = 1; j < match; j++) { // scan inside the outer parens
            char c = str.charAt(j);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                throw new IllegalArgumentException("Invalid outer wrapping of multiple arguments: " + s);
            }
        }
    }

    // ------------------------------------------------------------
    // DOM helpers
    // ------------------------------------------------------------

    private static String readArgValue(Element args, String wantedName) {
        if (args == null) return null;
        for (Element a : children(args, "S-Instruction-Argument")) {
            if (eq(attrOr(a, "name", ""), wantedName)) {
                return attrOr(a, "value", "");
            }
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception ignore) { return null; }
    }

    private static String textOfRequired(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        if (c == null) throw new IllegalArgumentException("Missing <" + tag + ">");
        String t = text(c);
        if (t == null || t.isBlank()) throw new IllegalArgumentException("Empty <" + tag + ">");
        return t.trim();
    }

    private static String textOfOptional(Element parent, String tag) {
        Element c = firstChild(parent, tag);
        if (c == null) return null;
        String t = text(c);
        return (t == null || t.isBlank()) ? null : t.trim();
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null) return null;
        NodeList nl = parent.getElementsByTagName(tag);
        return nl.getLength() == 0 ? null : (Element) nl.item(0);
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> list = new ArrayList<>();
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent && n instanceof Element) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private static String text(Node node) {
        return node == null ? null : node.getTextContent();
    }

    private static String attrOr(Element el, String attr, String def) {
        if (el == null) return def;
        String v = el.getAttribute(attr);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /** Read label from either <S-Label>, <S-Instruction-Label>, or @label. */
    private static String readOptionalLabel(Element insEl) {
        Element c = firstChild(insEl, "S-Label");
        if (c != null) {
            String t = text(c);
            if (t != null && !t.isBlank()) return t.trim();
        }
        Element legacy = firstChild(insEl, "S-Instruction-Label"); // legacy support
        if (legacy != null) {
            String t = text(legacy);
            if (t != null && !t.isBlank()) return t.trim();
        }
        String attr = insEl.getAttribute("label");
        return (attr == null || attr.isBlank()) ? null : attr.trim();
    }
}

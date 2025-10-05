package sengine;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.validation.*;
import javax.xml.XMLConstants;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/** Parses XML into a Program for תרגיל 2 (simple format + S-Program course format). */
public final class ProgramParser {

    private ProgramParser() {}

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------
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

    /** Validate labels against rendered degree-0 (no access to Program internals needed). */
    public static String validateLabels(Program p) {
        try {
            Program.Rendered r = p.expandToDegree(0);
            List<Instruction> list = r.list;

            // duplicate labels?
            Set<String> seen = new HashSet<>();
            for (Instruction ins : list) {
                if (ins.label == null || ins.label.isBlank()) continue;
                String key = ins.label.toUpperCase(Locale.ROOT);
                if (!seen.add(key)) return "Duplicate label: " + ins.label;
            }

            // missing targets (ignore EXIT)?
            Set<String> targets = new HashSet<>();
            for (Instruction ins : list) {
                String tgt = parseGotoTarget(ins.text);
                if (tgt != null && !"EXIT".equalsIgnoreCase(tgt)) targets.add(tgt.toUpperCase(Locale.ROOT));
            }
            for (String tgt : targets) {
                boolean ok = list.stream().anyMatch(i -> i.label != null && tgt.equalsIgnoreCase(i.label));
                if (!ok) return "Unknown label target: " + tgt;
            }
            return null;
        } catch (Exception ignore) { return null; }
    }

    // ------------------------------------------------------------
    // Simple format (unchanged)
    // ------------------------------------------------------------
    private static Program parseSimple(Element root) {
        String name = attrOr(root, "name", "Unnamed");
        Element instrsEl = firstChild(root, "instructions");
        if (instrsEl == null) throw new IllegalArgumentException("Missing <instructions>");

        List<Instruction> list = new ArrayList<>();
        for (Element e : children(instrsEl, "instruction")) {
            String type = attrOr(e, "type", "B"); // "B" or "S"
            String label = textOfOptional(e, "label");
            String command = textOfRequired(e, "command");
            Integer cycles = parseIntOrNull(textOfOptional(e, "cycles"));
            list.add(Instruction.parseFromText(label, command, type, cycles == null ? 1 : cycles));
        }
        return new Program(name, list);
    }

    // ------------------------------------------------------------
    // S-Program course format (supports quotation.xml structure)
    // ------------------------------------------------------------
    private static Program parseCourse(Element root) {
        String name = attrOr(root, "name", "Unnamed");
        Element sInstrWrap = firstChild(root, "S-Instructions");
        if (sInstrWrap == null) throw new IllegalArgumentException("Missing <S-Instructions>");

        java.util.Set<String> definedFnNames = collectFunctionNames(root);

        List<Instruction> out = new ArrayList<>();

        for (Element e : children(sInstrWrap, "S-Instruction")) {
            String label = readOptionalLabel(e);            // supports <S-Label>
            String type  = attrOr(e, "name", "");           // e.g., JUMP_EQUAL_FUNCTION, QUOTE, INCREASE...

            // ----- JUMP_EQUAL_FUNCTION → IF var == CONST GOTO label/EXIT
            if (eq(type, "JUMP_EQUAL_FUNCTION")) {
                out.add(buildJumpEqualFunction(root, e, label));
                continue;
            }

            // ----- QUOTE
// Supports two forms:
//  (A) Legacy/constant:     QUOTE y <- ConstFunction()           → y <- CONST
//  (B) Callable (new):       QUOTE y <- Func(args...)             → synthetic, expanded at degree>0
            if (eq(type, "QUOTE")) {
                String dst = textOfOptional(e, "S-Variable");
                if (dst == null || dst.isBlank())
                    throw new IllegalArgumentException("QUOTE missing <S-Variable>");

                Element argsWrap = firstChild(e, "S-Instruction-Arguments");
                String fnName = null;
                String fnArgs = ""; // e.g. "(Successor,x1)"
                if (argsWrap != null) {
                    for (Element a : children(argsWrap, "S-Instruction-Argument")) {
                        String n = attrOr(a, "name", "");
                        if (eq(n, "functionName"))       fnName = attrOr(a, "value", "");
                        else if (eq(n, "functionArguments")) fnArgs = attrOr(a, "value", "");
                    }
                }

                if (fnName == null || fnName.isBlank())
                    throw new IllegalArgumentException("QUOTE: missing functionName");

                // If functionArguments exist → normalize, validate names, create synthetic QUOTE
                // If functionArguments exist → normalize, validate names, keep as synthetic S-instruction.
// Program.expandToDegree(1) will inline it later.
                if (fnArgs != null && !fnArgs.isBlank()) {
                    forbidOuterWrappingOfMultiArgs(fnArgs);               // keep this guard
                    String argsNorm = normalizeFunctionArguments(fnArgs); // canonicalize
                    verifyFunctionsExist(definedFnNames, topLevelFunctionNames(argsNorm));

                    String text = "QUOTE " + dst + " <- " + fnName + "(" + argsNorm + ")";
                    out.add(Instruction.parseFromText(label, text, "S", null));
                    continue;
                }



                // Otherwise fall back to the old constant-function behavior
                Integer constVal = tryResolveConstFunction(root, fnName);
                if (constVal == null)
                    throw new IllegalArgumentException("QUOTE: function '" + fnName + "' must be a constant function");
                out.add(Instruction.parseFromText(label, dst + " <- " + constVal, "B", 1));
                continue;
            }

// ----- GOTO_LABEL (synthetic) → basic "GOTO <label>"
            if (eq(type, "SYNTHETIC") && eq(attrOr(e, "name", ""), "GOTO_LABEL")) {
                Element args = firstChild(e, "S-Instruction-Arguments");
                String target = null;
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
                // create a Basic instruction text that the engine understands
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                continue;
            }


            // ----- ASSIGNMENT (synthetic): <S-Variable>y</S-Variable>, arg assignedVariable="x1"
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

            // ----- GOTO_LABEL (synthetic) → basic "GOTO <label>"
            if (eq(type, "SYNTHETIC") && eq(attrOr(e, "name", ""), "GOTO_LABEL")) {
                Element args = firstChild(e, "S-Instruction-Arguments");
                String target = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "gotoLabel")) {
                            target = attrOr(a, "value", "");
                        }
                    }
                }
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("GOTO_LABEL missing gotoLabel");
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
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

            // ----- GOTO_LABEL (synthetic) → basic "GOTO <label>"
            if (eq(type, "SYNTHETIC") && eq(attrOr(e, "name", ""), "GOTO_LABEL")) {
                Element args = firstChild(e, "S-Instruction-Arguments");
                String target = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "gotoLabel")) {
                            target = attrOr(a, "value", "");
                        }
                    }
                }
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("GOTO_LABEL missing gotoLabel");
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                continue;
            }

            // ----- JUMP_NOT_ZERO (basic): <S-Variable>v</S-Variable>, arg JNZLabel="L1"
            if (eq(type, "JUMP_NOT_ZERO")) {
                String v = textOfRequired(e, "S-Variable");
                Element args = firstChild(e, "S-Instruction-Arguments");
                String target = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "JNZLabel")) {
                            target = attrOr(a, "value", "");
                        }
                    }
                }
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("JUMP_NOT_ZERO missing JNZLabel");
                out.add(Instruction.parseFromText(label, "IF " + v + " != 0 GOTO " + target, "B", 2));
                continue;
            }
// ----- GOTO_LABEL (synthetic) → basic "GOTO <label>"
            if (eq(type, "GOTO_LABEL")) {
                Element args = firstChild(e, "S-Instruction-Arguments");
                String target = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        String an = attrOr(a, "name", "");
                        if (eq(an, "gotoLabel") || eq(an, "label") || eq(an, "destLabel")) {
                            target = attrOr(a, "value", "");
                        }
                    }
                }
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("GOTO_LABEL missing label");
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                continue;
            }

            // Fallback: keep as synthetic so it still displays in the UI.
            out.add(Instruction.parseFromText(label, type, "S", 1));
        }

        return new Program(name, out);
    }

    // ------------------------------------------------------------
    // JUMP_EQUAL_FUNCTION support
    // ------------------------------------------------------------
    private static Instruction buildJumpEqualFunction(Element root, Element e, String label) {
        String varName = textOfRequired(e, "S-Variable");
        Element args = firstChild(e, "S-Instruction-Arguments");
        if (args == null) throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION missing arguments");

        String target = null;
        String fnName = null;

        for (Element a : children(args, "S-Instruction-Argument")) {
            String an = attrOr(a, "name", "");
            String av = attrOr(a, "value", "");
            if (eq(an, "JEFunctionLabel") || eq(an, "JZLabel")) target = av; // accept both
            if (eq(an, "functionName")) fnName = av;
        }
        if (target == null || target.isBlank())
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION needs JEFunctionLabel");
        if (fnName == null || fnName.isBlank())
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION missing functionName");

        Integer constVal = tryResolveConstFunction(root, fnName);
        if (constVal == null)
            throw new IllegalArgumentException("JUMP_EQUAL_FUNCTION: function '" + fnName + "' must be a constant function");

        String text = "IF " + varName + " == " + constVal + " GOTO " + target;
        return Instruction.parseFromText(label, text, "B", 1);
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

            Element args = firstChild(one, "S-Instruction-Arguments");
            if (args == null) return null;

            for (Element arg : children(args, "S-Instruction-Argument")) {
                if (eq(attrOr(arg, "name", ""), "constantValue")) {
                    try { return Integer.parseInt(attrOr(arg, "value", "")); }
                    catch (NumberFormatException ignored) { return null; }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------
    /** Reject ONLY the bad shape: a SINGLE outer pair of parentheses wrapping
     *  MULTIPLE top-level arguments (e.g., "((CONST7),(Successor,x1))").
     *  Allow the single-call encoding "(Name,arg1,...)" used by the course XML.
     *  Also allow "(Const7)" (zero-arg function) and "(Const7),(Successor,x1)" (no outer wrap).
     */
    private static void forbidOuterWrappingOfMultiArgs(String raw) {
        if (raw == null) throw new IllegalArgumentException("QUOTE: missing functionArguments");
        String s = raw.trim();
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') {
            // No single outer wrapper → fine.
            return;
        }

        // Does the very first '(' stay open until the last ')'? If not, no single outer wrapper.
        int depth = 0;
        boolean firstWrapsAll = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0 && i < s.length() - 1) { // closed before end → not one big outer pair
                firstWrapsAll = false;
                break;
            }
        }
        if (!firstWrapsAll) return; // e.g. "(Const7),(Successor,x1)" → allowed

        // Single outer wrapper: inspect inner at top level.
        String inner = s.substring(1, s.length() - 1).trim();

        // Split inner by top-level commas.
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder tok = new StringBuilder();
        depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(tok.toString().trim());
                tok.setLength(0);
            } else {
                tok.append(c);
            }
        }
        parts.add(tok.toString().trim());

        // Allowed cases with a single outer wrapper:
        //  - exactly 1 part: "(Const7)" → ok
        //  - exactly 2 parts AND first is an identifier: "(Successor,x1)" → single-call encoding → ok
        if (parts.size() == 1) return;
        if (parts.size() == 2 && parts.get(0).matches("[A-Za-z_][A-Za-z0-9_]*")) return;

        // Otherwise it's multiple top-level args wrapped by one outer pair → reject.
        throw new IllegalArgumentException("QUOTE: invalid functionArguments; remove the outer parentheses");
    }



    /** Collect function names exactly as written in <S-Functions>. (Case-SENSITIVE set) */
    private static java.util.Set<String> collectFunctionNames(Element root) {
        java.util.Set<String> out = new java.util.HashSet<>();
        Element functions = firstChild(root, "S-Functions");
        if (functions == null) return out;
        for (Element sf : children(functions, "S-Function")) {
            String name = attrOr(sf, "name", "");
            if (name != null && !name.isBlank()) out.add(name.trim());
        }
        return out;
    }

    /** Extract top-level function names from a canonical arg list like "Const7(), Successor(x1)". */
    private static java.util.List<String> topLevelFunctionNames(String argsNorm) {
        java.util.List<String> names = new java.util.ArrayList<>();
        String s = argsNorm;
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString().trim());
        for (String p : parts) {
            // accept only call form Name(...)
            int idx = p.indexOf('(');
            if (idx <= 0 || !p.endsWith(")")) continue;
            String name = p.substring(0, idx).trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /** Verify every function name referenced in args exists EXACTLY (case-sensitive) in <S-Functions>. */
    private static void verifyFunctionsExist(java.util.Set<String> defined, java.util.List<String> names) {
        for (String n : names) {
            if (!defined.contains(n)) {
                throw new IllegalArgumentException("Unknown function: " + n);
            }
        }
    }

    /** If s is Successor(Successor(...(x1)...)), return how many Successor layers; otherwise -1. */
    // Return count of nested Successor(...) over x1, or -1 if pattern doesn't match.
    private static int successorDepthOverX1(String rhs) {
        if (rhs == null) return -1;
        String t = rhs.trim();
        final String KW = "successor(";
        int count = 0;
        while (t.toLowerCase(java.util.Locale.ROOT).startsWith(KW) && t.endsWith(")")) {
            count++;
            t = t.substring(KW.length(), t.length() - 1).trim();
        }
        return t.equalsIgnoreCase("x1") ? count : -1;
    }
    // If this is S: "QUOTE <dst> <- Successor(Successor(...(x1)...))", expand to B steps.
// Returns null if not applicable.
    private java.util.List<Instruction> tryExpandSelfSuccessorQuote(Instruction ins) {
        if (ins == null || ins.text == null) return null;
        String t = ins.text.trim();
        if (!t.toUpperCase(java.util.Locale.ROOT).startsWith("QUOTE ")) return null;

        int arrow = t.indexOf("<-");
        if (arrow < 0) return null;
        String dst = t.substring("QUOTE".length(), arrow).trim(); // between QUOTE and <-
        if (dst.isEmpty()) return null;

        String rhs = t.substring(arrow + 2).trim(); // right-hand side
        int depth = successorDepthOverX1(rhs);
        if (depth < 1) return null;

        java.util.List<Instruction> out = new java.util.ArrayList<>();
        // y <- x1
        out.add(Instruction.parseFromText(ins.label, dst + " <- x1", "B", 1));
        // y <- y + 1  (repeat depth times)
        for (int i = 0; i < depth; i++) {
            out.add(Instruction.parseFromText(null, dst + " <- " + dst + " + 1", "B", 1));
        }
        return out;
    }


    /** Build a basic assignment like "v <- v + 1" (1 cycle). */
    private static Instruction inc(String var) {
        return Instruction.parseFromText(null, var + " <- " + var + " + 1", "B", 1);
    }


    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }
    /** Normalize functionArguments strings from XML into canonical call syntax.
     *  Examples:
     *    "(Const7)"              -> "Const7()"
     *    "(Successor,x1)"        -> "Successor(x1)"
     *    "(Const7),(Successor,x1)" -> "Const7(), Successor(x1)"
     */
    private static String normalizeFunctionArguments(String raw) {
        if (raw == null) throw new IllegalArgumentException("QUOTE: missing functionArguments");
        String s = raw.trim();
        // Split by top-level commas (ignore commas inside parentheses)
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder tok = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) { // top-level comma
                parts.add(tok.toString().trim());
                tok.setLength(0);
            } else {
                tok.append(c);
            }
        }
        parts.add(tok.toString().trim());

        java.util.List<String> norm = new java.util.ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) throw new IllegalArgumentException("QUOTE: empty argument in functionArguments");

            String t = p.trim();

            // Case A: "(Name,arg1,...)"  -> "Name(arg1,...)"
            if (t.startsWith("(") && t.endsWith(")")) {
                String inner = t.substring(1, t.length() - 1).trim();
                int firstComma = -1;
                int d = 0;
                for (int i = 0; i < inner.length(); i++) {
                    char c = inner.charAt(i);
                    if (c == '(') d++;
                    else if (c == ')') d--;
                    else if (c == ',' && d == 0) { firstComma = i; break; }
                }
                if (firstComma >= 0) {
                    String fname = inner.substring(0, firstComma).trim();
                    String args  = inner.substring(firstComma + 1).trim();
                    if (fname.isEmpty()) throw new IllegalArgumentException("QUOTE: missing function name in '" + t + "'");
                    t = fname + "(" + args + ")";
                } else {
                    // Case B: "(Const7)" -> "Const7()"
                    String fname = inner.trim();
                    if (fname.isEmpty()) throw new IllegalArgumentException("QUOTE: empty function name in '" + t + "'");
                    t = fname + "()";
                }
            }
            // else: already canonical (e.g., "Foo(x1)" or "Bar()")
            norm.add(t);
        }
        return String.join(", ", norm);
    }

    private static String parseGotoTarget(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.toUpperCase(Locale.ROOT).startsWith("GOTO "))
            return text.substring(5).trim(); // GOTO L123 or EXIT
        int p = text.toUpperCase(Locale.ROOT).lastIndexOf("GOTO ");
        if (p >= 0) return text.substring(p + 5).trim(); // IF ... GOTO L123
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }
    private static String attrOr(Element el, String name, String dft) {
        String v = el.getAttribute(name);
        return (v == null || v.isBlank()) ? dft : v;
    }

    /** Validate the shape of functionArguments for QUOTE/JEF: must be a single tuple like (arg1,arg2,...).
     *  Disallow wrapped bare groups like ((CONST7),(Successor,x1)) and bare-parenthesized constants "(CONST7)".
     */
    private static void validateFunctionArgumentsFormat(String fnArgs) {
        if (fnArgs == null) {
            throw new IllegalArgumentException("QUOTE: missing functionArguments");
        }
        String s = fnArgs.trim();
        if (!s.startsWith("(") || !s.endsWith(")")) {
            throw new IllegalArgumentException("QUOTE: functionArguments must be a single tuple like (a,b)");
        }

        // inner text without the outer parentheses
        String inner = s.substring(1, s.length() - 1).trim();

        // quick rejection: multiple top-level groups like ")(" indicate bad nesting e.g. ((CONST7),(Successor,x1))
        // (this also catches stray extra parentheses between comma-separated items)
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth < 0) {
                throw new IllegalArgumentException("QUOTE: invalid functionArguments (parentheses mismatch)");
            }
        }
        // Split args by commas at top level (no split inside nested parentheses)
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder tok = new StringBuilder();
        depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                parts.add(tok.toString().trim());
                tok.setLength(0);
            } else {
                tok.append(c);
            }
        }
        parts.add(tok.toString().trim());

        // Each top-level argument must be either:
        //  - a simple token (y, x1, z3, CONST7), WITHOUT extra wrapping parentheses, OR
        //  - a function call form: Name(...)
        for (String p : parts) {
            if (p.isEmpty()) {
                throw new IllegalArgumentException("QUOTE: empty argument in functionArguments");
            }
            boolean looksLikeCall = p.matches("[A-Za-z_][A-Za-z0-9_]*\\(.*\\)");
            boolean hasBareParens = p.startsWith("(") && !looksLikeCall;
            if (hasBareParens) {
                // e.g. "(CONST7)" or "(x1)" or "(Successor,x1)" – invalid wrapping
                throw new IllegalArgumentException("QUOTE: invalid argument '" + p + "'; remove extra parentheses");
            }
        }
    }

    private static String textOfRequired(Element parent, String childName) {
        Element c = firstChild(parent, childName);
        if (c == null) throw new IllegalArgumentException("Missing <" + childName + ">");
        String t = text(c);
        if (t == null || t.isBlank()) throw new IllegalArgumentException("<" + childName + "> is empty");
        return t.trim();
    }
    private static String textOfOptional(Element parent, String childName) {
        Element c = firstChild(parent, childName);
        return (c == null) ? null : (text(c) == null ? null : text(c).trim());
    }
    private static String text(Node n) { return n == null ? null : n.getTextContent(); }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node nn = nl.item(i);
            if (nn.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) nn;
                if (eq(e.getTagName(), tag)) out.add(e);
            }
        }
        return out;
    }
    private static Element firstChild(Element parent, String tag) {
        for (Element e : children(parent, tag)) return e;
        return null;
    }

    /** Also support <S-Label> for labels in S-Program files. */
    private static String readOptionalLabel(Element insEl) {
        Element c = firstChild(insEl, "S-Label");                 // <-- quotation.xml uses this
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

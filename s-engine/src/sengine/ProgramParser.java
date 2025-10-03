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

        List<Instruction> out = new ArrayList<>();

        for (Element e : children(sInstrWrap, "S-Instruction")) {
            String label = readOptionalLabel(e);            // supports <S-Label>
            String type  = attrOr(e, "name", "");           // e.g., JUMP_EQUAL_FUNCTION, QUOTE, INCREASE...

            // ----- JUMP_EQUAL_FUNCTION → IF var == CONST GOTO label/EXIT
            if (eq(type, "JUMP_EQUAL_FUNCTION")) {
                out.add(buildJumpEqualFunction(root, e, label));
                continue;
            }

            // ----- QUOTE (with <S-Variable>dst</S-Variable> + functionName=Const) → dst <- CONST
            if (eq(type, "QUOTE")) {
                String dst = textOfOptional(e, "S-Variable"); // quotation.xml uses this
                Element args = firstChild(e, "S-Instruction-Arguments");
                String fnName = null;
                if (args != null) {
                    for (Element a : children(args, "S-Instruction-Argument")) {
                        if (eq(attrOr(a, "name", ""), "functionName")) {
                            fnName = attrOr(a, "value", "");
                        }
                    }
                }
                if (dst == null || dst.isBlank())
                    throw new IllegalArgumentException("QUOTE missing <S-Variable>");
                Integer constVal = (fnName == null || fnName.isBlank()) ? null : tryResolveConstFunction(root, fnName);
                if (constVal == null)
                    throw new IllegalArgumentException("QUOTE: function '" + fnName + "' must be a constant function");
                out.add(Instruction.parseFromText(label, dst + " <- " + constVal, "B", 1));
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
    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }

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

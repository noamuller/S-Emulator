package sengine;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.*;

public final class ProgramParser {

    public static final class ParseResult {
        public final Program program;
        public final String error;
        ParseResult(Program program, String error) { this.program = program; this.error = error; }
        public static ParseResult ok(Program p){ return new ParseResult(p, null); }
        public static ParseResult err(String e){ return new ParseResult(null, e); }
    }

    public static ParseResult parseXml(File file) {
        if (!file.exists()) return ParseResult.err("File does not exist: " + file.getAbsolutePath());
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) return ParseResult.err("Not an XML file (.xml required)");
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            String rootName = root.getNodeName();

            if (equalsIgnoreCase(rootName, "program")) {
                return parseSimple(root);
            } else if (equalsIgnoreCase(rootName, "S-Program")) {
                return parseCourse(root);
            } else {
                return ParseResult.err("Unsupported root element <" + rootName + ">; expected <program> or <S-Program>");
            }
        } catch (Exception ex) {
            return ParseResult.err("Failed to read XML: " + ex.getMessage());
        }
    }


    private static ParseResult parseSimple(Element root) {
        String name = attrOr(root, "name", "Unnamed");

        Element instrsEl = firstChild(root, "instructions");
        if (instrsEl == null) return ParseResult.err("Missing <instructions>");

        List<Instruction> ins = new ArrayList<>();
        for (Element e : children(instrsEl, "instruction")) {
            String type = attrOr(e, "type", "B"); // B or S
            String label = attrOrNull(e, "label");
            String command = textOfRequired(e, "command");
            Integer cycles = parseIntOrNull(textOfOptional(e, "cycles"));
            Instruction inst = Instruction.parseFromText(label, command, type, cycles);
            ins.add(inst);
        }

        String labelErr = validateLabels(ins);
        if (labelErr != null) return ParseResult.err(labelErr);

        return ParseResult.ok(new Program(name, ins));
    }

    private static ParseResult parseCourse(Element root) {
        String name = attrOr(root, "name", "Unnamed");

        Element instrsEl = firstChild(root, "S-Instructions");
        if (instrsEl == null) return ParseResult.err("Missing <S-Instructions>");

        List<Instruction> ins = new ArrayList<>();
        for (Element e : children(instrsEl, "S-Instruction")) {
            String typeAttr = attrOr(e, "type", "basic");
            String nameAttr = attrOr(e, "name", "").trim();
            String label = textOfOptional(e, "S-Label");
            String var   = textOfOptional(e, "S-Variable");
            Integer cycles = parseIntOrNull(textOfOptional(e, "S-Cycles"));

            Element args = firstChild(e, "S-Instruction-Arguments");

            java.util.function.Function<String,String> argValue = (argName) -> {
                if (args == null) return null;
                String direct = textOfOptional(args, argName);
                if (direct != null && !direct.isBlank()) return direct.trim();
                for (Element aEl : children(args, "S-Instruction-Argument")) {
                    String n = attrOr(aEl, "name", "");
                    if (argName.equalsIgnoreCase(n)) {
                        String v = attrOr(aEl, "value", "");
                        if (!v.isBlank()) return v.trim();
                        String txt = aEl.getTextContent();
                        if (txt != null && !txt.isBlank()) return txt.trim();
                    }
                }
                return null;
            };

            String typeHint = typeAttr.equalsIgnoreCase("basic") ? "B" : "S";
            String command;

            if (equalsIgnoreCase(nameAttr, "DECREASE")) {
                requireNonEmpty(var, "DECREASE requires <S-Variable>");
                command = var + " <- " + var + " - 1";

            } else if (equalsIgnoreCase(nameAttr, "INCREASE")) {
                requireNonEmpty(var, "INCREASE requires <S-Variable>");
                command = var + " <- " + var + " + 1";

            } else if (equalsIgnoreCase(nameAttr, "NEUTRAL")) {
                requireNonEmpty(var, "NEUTRAL requires <S-Variable>");
                command = var + " <- " + var;

            } else if (equalsIgnoreCase(nameAttr, "JUMP_NOT_ZERO")) {
                requireNonEmpty(var, "JUMP_NOT_ZERO requires <S-Variable>");
                String jnzTarget = argValue.apply("JNZLabel");
                requireNonEmpty(jnzTarget, "JUMP_NOT_ZERO requires <S-Instruction-Arguments>/<JNZLabel>");
                command = "IF " + var + " != 0 GOTO " + jnzTarget;

                // SYNTHETIC we support
            } else if (equalsIgnoreCase(nameAttr, "ZERO_VARIABLE")) {
                requireNonEmpty(var, "ZERO_VARIABLE requires <S-Variable>");
                typeHint = "S";
                command = var + " <- 0";

            } else if (equalsIgnoreCase(nameAttr, "ASSIGNMENT")) {
                requireNonEmpty(var, "ASSIGNMENT requires <S-Variable> (destination)");
                String src = argValue.apply("assignedVariable");
                requireNonEmpty(src, "ASSIGNMENT requires argument assignedVariable");
                typeHint = "S";
                command = "ASSIGN " + var + " <- " + src;

            } else if (equalsIgnoreCase(nameAttr, "CONSTANT_ASSIGNMENT")) {
                requireNonEmpty(var, "CONSTANT_ASSIGNMENT requires <S-Variable>");
                String c = argValue.apply("constantValue");
                requireNonEmpty(c, "CONSTANT_ASSIGNMENT requires argument constantValue");
                typeHint = "S";
                command = var + " <- " + c;

            } else if (equalsIgnoreCase(nameAttr, "GOTO_LABEL")) {
                String target = argValue.apply("gotoLabel");
                requireNonEmpty(target, "GOTO_LABEL requires argument gotoLabel");
                typeHint = "S";
                command = "GOTO " + target;

            } else if (equalsIgnoreCase(nameAttr, "JUMP_ZERO")) {
                requireNonEmpty(var, "JUMP_ZERO requires <S-Variable>");
                String jz = argValue.apply("JZLabel");
                requireNonEmpty(jz, "JUMP_ZERO requires argument JZLabel");
                typeHint = "S";
                command = "IF " + var + " == 0 GOTO " + jz;

            } else if (equalsIgnoreCase(nameAttr, "JUMP_EQUAL_CONSTANT")) {
                requireNonEmpty(var, "JUMP_EQUAL_CONSTANT requires <S-Variable>");
                String labelArg = argValue.apply("JEConstLabel");
                if (labelArg == null) labelArg = argValue.apply("JEConstantLabel");
                if (labelArg == null) labelArg = argValue.apply("JELabel");
                if (labelArg == null) labelArg = argValue.apply("JECLabel");
                if (labelArg == null && args != null) {
                    for (Element aEl : children(args, "S-Instruction-Argument")) {
                        String n = attrOr(aEl, "name", "");
                        if (n.toLowerCase(Locale.ROOT).contains("label")) { labelArg = attrOr(aEl,"value",""); break; }
                    }
                }
                String constStr = argValue.apply("constantValue");
                if (constStr == null) constStr = argValue.apply("equalConstant");
                requireNonEmpty(labelArg, "JUMP_EQUAL_CONSTANT requires label argument (e.g. JEConstLabel)");
                requireNonEmpty(constStr, "JUMP_EQUAL_CONSTANT requires constantValue");
                typeHint = "S";
                command = "IF " + var + " == " + constStr + " GOTO " + labelArg;

            } else if (equalsIgnoreCase(nameAttr, "JUMP_EQUAL_VARIABLE")) {
                requireNonEmpty(var, "JUMP_EQUAL_VARIABLE requires <S-Variable>");
                String labelArg = argValue.apply("JEVariableLabel");
                if (labelArg == null) labelArg = argValue.apply("JEVarLabel");
                if (labelArg == null) labelArg = argValue.apply("JELabel");
                if (labelArg == null && args != null) {
                    for (Element aEl : children(args, "S-Instruction-Argument")) {
                        String n = attrOr(aEl, "name", "");
                        if (n.toLowerCase(Locale.ROOT).contains("label")) { labelArg = attrOr(aEl,"value",""); break; }
                    }
                }
                String other = argValue.apply("variableName");
                if (other == null) other = argValue.apply("otherVariable");
                requireNonEmpty(labelArg, "JUMP_EQUAL_VARIABLE requires label argument (e.g. JEVariableLabel)");
                requireNonEmpty(other, "JUMP_EQUAL_VARIABLE requires variableName");
                typeHint = "S";
                command = "IF " + var + " == " + other + " GOTO " + labelArg;

            } else {
                typeHint = "S";
                command = (nameAttr.isEmpty() ? "UNKNOWN" : nameAttr) + " " + (var == null ? "" : var);
            }

            Instruction inst = Instruction.parseFromText(label, command, typeHint, cycles);
            ins.add(inst);
        }

        String labelErr = validateLabels(ins);
        if (labelErr != null) return ParseResult.err(labelErr);

        return ParseResult.ok(new Program(name, ins));
    }

    private static String validateLabels(List<Instruction> ins) {
        Set<String> labels = new HashSet<>();
        for (Instruction i : ins) if (i.label != null && !i.label.isBlank())
            labels.add(i.label.trim().toUpperCase(Locale.ROOT));

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

    private static boolean equalsIgnoreCase(String a, String b) { return a != null && a.equalsIgnoreCase(b); }

    private static String attrOr(Element e, String attr, String defVal) {
        String v = e.getAttribute(attr);
        return (v == null || v.isBlank()) ? defVal : v.trim();
    }
    private static String attrOrNull(Element e, String attr) {
        String v = e.getAttribute(attr);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Element firstChild(Element parent, String name) {
        NodeList nl = parent.getChildNodes();
        for (int i=0;i<nl.getLength();i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (el.getNodeName().equalsIgnoreCase(name)) return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i=0;i<nl.getLength();i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (el.getNodeName().equalsIgnoreCase(name)) out.add(el);
            }
        }
        return out;
    }

    private static String textOfOptional(Element parent, String childName) {
        Element el = firstChild(parent, childName);
        return (el == null) ? null : el.getTextContent().trim();
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

    private static void requireNonEmpty(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(msg);
    }
}

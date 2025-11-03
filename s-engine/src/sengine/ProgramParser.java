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

public final class ProgramParser {

    private ProgramParser() {}



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


    public static void validateWithXsd(File xml, File xsd) throws Exception {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = sf.newSchema(xsd);
        Validator validator = schema.newValidator();
        validator.validate(new javax.xml.transform.stream.StreamSource(xml));
    }


    private static Program parseSimple(Element root) {
        String name = attrOr(root, "name", "Unnamed");
        Element instrWrap = firstChild(root, "instructions");
        if (instrWrap == null) throw new IllegalArgumentException("Missing <instructions>");

        List<Instruction> list = new ArrayList<>();
        for (Element e : children(instrWrap, "instruction")) {
            String type = attrOr(e, "type", "B");
            String label = textOfOptional(e, "label");
            String command = textOfRequired(e, "command");
            Integer cycles = parseIntOrNull(textOfOptional(e, "cycles"));
            list.add(Instruction.parseFromText(label, command, type, cycles == null ? 1 : cycles));
        }
        return new Program(name, list);
    }


    private static Program parseCourse(Element root) {
        String name = attrOr(root, "name", "Unnamed");

        Element sInstrWrap = firstChild(root, "S-Instructions");
        if (sInstrWrap == null) throw new IllegalArgumentException("Missing <S-Instructions>");

        Set<String> definedFnNames = collectFunctionNames(root);
        definedFnNames.addAll(Arrays.asList(
                "CONST", "NOT", "EQUAL", "AND", "OR",
                "Smaller_Than", "Bigger_Equal_Than", "Smaller_Equal_Than",
                "Minus", "Successor"
        ));


        List<Instruction> out = new ArrayList<>();

        for (Element e : children(sInstrWrap, "S-Instruction")) {
            String type = attrOr(e, "name", "");
            String label = readOptionalLabel(e);


            if (eq(type, "JUMP_EQUAL_FUNCTION")) {
                out.add(buildJumpEqualFunction(root, e, label));
                continue;
            }


            if (eq(type, "QUOTE")) {
                out.add(buildQuoteSynthetic(root, e, label, definedFnNames));
                continue;
            }


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

            if (eq(type, "ZERO_VARIABLE")) {
                String dst = textOfRequired(e, "S-Variable");
                out.add(Instruction.parseFromText(label, dst + " <- 0", "B", 1));
                continue;
            }


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


            if (eq(type, "JUMP_NOT_ZERO")) {
                String v = textOfRequired(e, "S-Variable");
                String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JNZLabel");
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("JUMP_NOT_ZERO missing JNZLabel");
                out.add(Instruction.parseFromText(label, "IF " + v + " != 0 GOTO " + target, "B", 2));
                continue;
            }


            if (eq(type, "JUMP_ZERO")) {
                String v = textOfRequired(e, "S-Variable");
                String target = readArgValue(firstChild(e, "S-Instruction-Arguments"), "JZLabel");
                if (target == null || target.isBlank())
                    throw new IllegalArgumentException("JUMP_ZERO missing JZLabel");
                out.add(Instruction.parseFromText(label, "IF " + v + " == 0 GOTO " + target, "B", 2));
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
                out.add(Instruction.parseFromText(label, "GOTO " + target, "B", 1));
                continue;
            }


            out.add(Instruction.parseFromText(label, type, "S", 1));
        }

        Map<String, List<Instruction>> functions = parseFunctions(root);
        return new Program(name, out, functions);
    }


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


                bodyList.add(Instruction.parseFromText(label, type, "S", 1));
            }

            fnMap.put(fname, List.copyOf(bodyList));
        }

        return fnMap;
    }

    private static Instruction buildQuoteSynthetic(Element root, Element e, String label, Set<String> definedFnNames) {
        String dst = textOfRequired(e, "S-Variable");

        Element argsWrap = firstChild(e, "S-Instruction-Arguments");
        String fnName = null;
        String fnArgs = "";
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


        Integer constVal = tryResolveConstFunction(root, fnName);
        if (constVal == null)
            throw new IllegalArgumentException("QUOTE: function '" + fnName + "' must be a constant function");
        return Instruction.parseFromText(label, dst + " <- " + constVal, "B", 1);
    }


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


    private static void verifyFunctionsExist(Set<String> defined, List<String> names) {
        for (String n : names) {
            if (!defined.contains(n)) {
                throw new IllegalArgumentException("Unknown function: " + n);
            }
        }
    }


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


    private static List<String> topLevelFunctionNames(String argsNorm) {

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

                int depth = 1; j++;
                while (j < s.length() && depth > 0) {
                    char c = s.charAt(j++);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                i = j;
            } else {

                int j = i + 1;
                while (j < s.length() && s.charAt(j) != ',' && !Character.isWhitespace(s.charAt(j))) j++;
                i = j;
            }
        }
        return names;
    }


    private static String normalizeFunctionArguments(String fnArgs) {

        return fnArgs.trim();
    }


    private static void forbidOuterWrappingOfMultiArgs(String s) {
        if (s == null) return;
        String str = s.trim();
        if (str.length() < 2 || str.charAt(0) != '(') return;


        int depth = 1, i = 1, match = -1;
        while (i < str.length()) {
            char c = str.charAt(i++);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) { match = i - 1; break; }
            }
        }

        if (match != str.length() - 1) return;


        String inner = str.substring(1, match).trim();


        boolean seenTopComma = false;
        boolean seenTopOpenParen = false;
        depth = 0;
        for (int j = 0; j < inner.length(); j++) {
            char c = inner.charAt(j);
            if (c == '(') {
                if (depth == 0) seenTopOpenParen = true;
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                seenTopComma = true;
            }
        }


        if (seenTopOpenParen && seenTopComma) {
            throw new IllegalArgumentException("Invalid outer wrapping of multiple arguments: " + s);
        }

    }



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


    private static String readOptionalLabel(Element insEl) {
        Element c = firstChild(insEl, "S-Label");
        if (c != null) {
            String t = text(c);
            if (t != null && !t.isBlank()) return t.trim();
        }
        Element legacy = firstChild(insEl, "S-Instruction-Label");
        if (legacy != null) {
            String t = text(legacy);
            if (t != null && !t.isBlank()) return t.trim();
        }
        String attr = insEl.getAttribute("label");
        return (attr == null || attr.isBlank()) ? null : attr.trim();
    }
}

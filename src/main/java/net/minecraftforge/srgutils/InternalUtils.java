/*
 * SRG Utils
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.srgutils;

import net.minecraftforge.srgutils.IMappingFile.Format;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

class InternalUtils {
    private static final List<String> ORDER = Arrays.asList("PK:", "CL:", "FD:", "MD:");

    static IMappingFile load(InputStream in) throws IOException {
        return load(in, false);
    }

    static IMappingFile load(InputStream in, boolean reverseClass) throws IOException {
        INamedMappingFile named = loadNamed(in, reverseClass);
        return named.getMap(named.getNames().get(0), named.getNames().get(1));
    }

    static INamedMappingFile loadNamed(InputStream in) throws IOException {
        return loadNamed(in, false);
    }

    static INamedMappingFile loadNamed(InputStream in, boolean reverseClass) throws IOException {
        List<String> lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                .map(InternalUtils::stripComment)
                .filter(l -> !l.isEmpty()) //Remove Empty lines
                .collect(Collectors.toList());


        String firstLine = lines.get(0);
        String test = firstLine.split(" ")[0];

        if ("PK:".equals(test) || "CL:".equals(test) || "FD:".equals(test) || "MD:".equals(test)) //SRG
            return loadSRG(lines).build();
        else if (firstLine.contains(" -> ")) // ProGuard
            return loadProguard(lines).build();
        else if (firstLine.startsWith("v1\t")) // Tiny V1
            return loadTinyV1(lines).build();
        else if (firstLine.startsWith("tiny\t")) // Tiny V2+
            return loadTinyV2(lines).build();
        else if (firstLine.startsWith("tsrg2 ")) // TSRG v2, parameters, and multi-names
            return loadTSrg2(lines).build();
        else { // TSRG/CSRG
            return loadSlimSRG(lines, reverseClass).build();
        }
    }

    private static IMappingBuilder loadSRG(List<String> lines) throws IOException {
        IMappingBuilder ret = IMappingBuilder.create("left", "right");
        Map<String, IMappingBuilder.IClass> classes = new HashMap<>();
        for (String line : lines) {
            String[] pts = line.split(" ");
            switch (pts[0]) {
                case "PK:":
                    ret.addPackage(pts[1], pts[2]);
                    break;
                case "CL:":
                    classes.put(pts[1], ret.addClass(pts[1], pts[2]));
                    break;
                case "FD:":
                    String[] left = rsplit(pts[1], '/', 1);
                    String[] right;
                    if (pts.length == 5) {
                        right = rsplit(pts[3], '/', 1);
                    } else {
                        right = rsplit(pts[2], '/', 1);
                    }
                    classes.computeIfAbsent(left[0], k -> ret.addClass(left[0], right[0])).field(left[1], right[1]).descriptor(pts[2]);
                    break;
                case "MD:":
                    String[] left1 = rsplit(pts[1], '/', 1);
                    String[] right1 = rsplit(pts[3], '/', 1);
                    classes.computeIfAbsent(left1[0], k -> ret.addClass(left1[0], right1[0])).method(pts[2], left1[1], right1[1]);
                    break;
                default:
                    throw new IOException("Invalid SRG file, Unknown type: " + line);
            }
        }
        return ret;
    }

    private static IMappingBuilder loadProguard(List<String> lines) throws IOException {
        IMappingBuilder ret = IMappingBuilder.create("left", "right");

        IMappingBuilder.IClass cls = null;
        for (String line : lines) {
            line = line.replace('.', '/');
            if (!line.startsWith("    ") && line.endsWith(":")) {
                String[] pts = line.replace('.', '/').split(" -> ");
                cls = ret.addClass(pts[0], pts[1].substring(0, pts[1].length() - 1));
            } else if (line.contains("(") && line.contains(")")) {
                if (cls == null)
                    throw new IOException("Invalid PG line, missing class: " + line);

                line = line.trim();
                int start = 0;
                int end = 0;
                if (line.indexOf(':') != -1) {
                    int i = line.indexOf(':');
                    int j = line.indexOf(':', i + 1);
                    start = Integer.parseInt(line.substring(0, i));
                    end = Integer.parseInt(line.substring(i + 1, j));
                    line = line.substring(j + 1);
                }

                String obf = line.split(" -> ")[1];
                String _ret = toDesc(line.split(" ")[0]);
                String name = line.substring(line.indexOf(' ') + 1, line.indexOf('('));
                String[] args = line.substring(line.indexOf('(') + 1, line.indexOf(')')).split(",");

                StringBuilder desc = new StringBuilder();
                desc.append('(');
                for (String arg : args) {
                    if (arg.isEmpty()) break;
                    desc.append(toDesc(arg));
                }
                desc.append(')').append(_ret);
                IMappingBuilder.IMethod mtd = cls.method(desc.toString(), name, obf);
                if (start != 0) mtd.meta("start_line", Integer.toString(start));
                if (end != 0) mtd.meta("end_line", Integer.toString(end));
            } else {
                if (cls == null)
                    throw new IOException("Invalid PG line, missing class: " + line);
                String[] pts = line.trim().split(" ");
                cls.field(pts[1], pts[3]).descriptor(toDesc(pts[0]));
            }
        }

        return ret;
    }

    private static IMappingBuilder loadSlimSRG(List<String> lines, boolean reverseClass) throws IOException {
        IMappingBuilder ret = IMappingBuilder.create("left", "right");
        Map<String, IMappingBuilder.IClass> classes = new HashMap<>();

        lines.stream()
                .filter(l -> l.charAt(0) != '\t')
                .map(l -> l.split(" "))
                .filter(pts -> pts.length == 2)
                .forEach(pts -> {
                    if (pts[0].endsWith("/"))
                        ret.addPackage(pts[0].substring(0, pts[0].length() - 1), pts[1].substring(0, pts[1].length() - 1));
                    else {
                        if (reverseClass) {
                            classes.put(pts[1], ret.addClass(pts[1], pts[0]));
                        } else {
                            classes.put(pts[0], ret.addClass(pts[0], pts[1]));
                        }
                    }
                });

        if (classes.size() == 0) {
            throw new IOException("Invalid TSRG file, missing classes");
        }

        IMappingBuilder.IClass cls = null;
        for (String line : lines) {
            String[] pts = line.split(" ");
            if (pts[0].charAt(0) == '\t') {
                if (cls == null)
                    throw new IOException("Invalid TSRG line, missing class: " + line);
                pts[0] = pts[0].substring(1);
                if (pts.length == 2)
                    cls.field(pts[0], pts[1]);
                else if (pts.length == 3)
                    cls.method(pts[1], pts[0], pts[2]);
                else
                    throw new IOException("Invalid TSRG line, too many parts: " + line);
            } else {
                // if mapping is missing, presuming that it maps to the same name
                if (pts.length == 2) {
                    if (!pts[0].endsWith("/"))
                        cls = classes.get(pts[0]);
                } else if (pts.length == 3)
                    classes.computeIfAbsent(pts[0], key -> ret.addClass(key, key)).field(pts[1], pts[2]);
                else if (pts.length == 4)
                    classes.computeIfAbsent(pts[0], key -> ret.addClass(key, key)).method(pts[2], pts[1], pts[3]);
                else
                    throw new IOException("Invalid CSRG line, too many parts: " + line);
            }
        }

        return ret;
    }

    private static IMappingBuilder loadTSrg2(List<String> lines) throws IOException {
        /*
         *   This is a extended spec of the TSRG format, mainly to allow multiple names
         * for entries, consolidating our files into a single one, parameter names, and
         * optional descriptors for fields {Doesn't really matter in MC modding, but why not..}
         *
         * Multiple names:
         *   The header line defines how many extra names are allowed.
         *   tsrg2 [name ...]
         *
         * Field Descriptors:
         *   If a line would be a method line, but the descriptor does not start with '(' it is a field with a descriptor
         *
         * Methods:
         *   The same as TSRG 1 but with potentially multiple names
         *   \t name1 desc [name..]
         *
         * Parameters:
         *   Tabbed in one extra time below method nodes.
         *   \t\tindex [name ...]
         *
         * Static Identifier:
         *   To allow consumers to know if there is a synthetic 'this' parameter without having it added to every function.
         *   There is a marker under the method node, Any method that has parameters, yet does not have this marker
         *   Can be assume to be non-static and thus have a synthetic 'this' parameter.
         *   \t\ts
         *
         * Things we do not care about:
         *   Local variables:
         *     In theory this would be useful if decompilers were perfect and matched up
         *     things perfectly, but they don't so it's not really worth caring.
         *   Comments:
         *     This format is targeted towards binary files, comments are added else ware.
         *   Line numbers:
         *     I can't see a use for this
         */
        String[] header = lines.get(0).split(" ");
        if (header.length < 3) throw new IOException("Invalid TSrg v2 Header: " + lines.get(0));
        IMappingBuilder ret = IMappingBuilder.create(Arrays.copyOfRange(header, 1, header.length));
        int nameCount = header.length - 1;
        lines.remove(0);

        IMappingBuilder.IClass cls = null;
        IMappingBuilder.IMethod mtd = null;
        for (String line : lines) {
            if (line.length() < 2)
                throw new IOException("Invalid TSRG v2 line, too short: " + line);

            String[] pts = line.split(" ");
            if (line.charAt(0) != '\t') { // Classes or Packages are not tabbed
                if (pts.length != nameCount)
                    throw new IOException("Invalid TSRG v2 line: " + line);
                if (pts[0].charAt(pts[0].length() - 1) == '/') { // Packages
                    for (int x = 0; x < pts.length; x++)
                        pts[x] = pts[x].substring(0, pts[x].length() - 1);
                    ret.addPackage(pts);
                    cls = null;
                } else
                    cls = ret.addClass(pts);
                mtd = null;
            } else if (line.charAt(1) == '\t') {
                if (mtd == null)
                    throw new IOException("Invalid TSRG v2 line, missing method: " + line);
                pts[0] = pts[0].substring(2);

                if (pts.length == 1 && pts[0].equals("static"))
                    mtd.meta("is_static", "true");
                else if (pts.length == nameCount + 1) // Parameter
                    mtd.parameter(Integer.parseInt(pts[0]), Arrays.copyOfRange(pts, 1, pts.length));
                else
                    throw new IOException("Invalid TSRG v2 line, too many parts: " + line);
            } else {
                if (cls == null)
                    throw new IOException("Invalid TSRG v2 line, missing class: " + line);
                pts[0] = pts[0].substring(1);

                if (pts.length == nameCount) // Field without descriptor
                    cls.field(pts);
                else if (pts.length == 1 + nameCount) {
                    swapFirst(pts);
                    if (pts[0].charAt(0) == '(') { // Methods
                        mtd = cls.method(pts[0], Arrays.copyOfRange(pts, 1, pts.length));
                    } else { // Field with Descriptor
                        mtd = null;
                        cls.field(Arrays.copyOfRange(pts, 1, pts.length)).descriptor(pts[0]);
                    }
                } else
                    throw new IOException("Invalid TSRG v2 line, to many parts: " + line);
            }
        }

        return ret;
    }

    private static IMappingBuilder loadTinyV1(List<String> lines) throws IOException {
        /*
         *  The entire file is just a list tab-separated-value lines.
         *  It can have a unlimited number of name steps, The first part of the header is always 'v1'
         *  anything extra tells us the names of mapping stages. So we build a bunch of maps from the first value to the Nth value
         */
        String[] header = lines.get(0).split("\t");
        if (header.length < 3) throw new IOException("Invalid Tiny v1 Header: " + lines.get(0));
        IMappingBuilder ret = IMappingBuilder.create(Arrays.copyOfRange(header, 1, header.length));
        Map<String, IMappingBuilder.IClass> classes = new HashMap<>();
        int nameCount = header.length - 1;

        for (int x = 1; x < lines.size(); x++) {
            String[] line = lines.get(x).split("\t");
            switch (line[0]) {
                case "CLASS": // CLASS Name1 Name2 Name3...
                    if (line.length != nameCount + 1)
                        throw new IOException("Invalid Tiny v1 line: #" + x + ": " + Arrays.toString(line));
                    classes.put(line[1], ret.addClass(Arrays.copyOfRange(line, 1, line.length)));
                    break;
                case "FIELD": // FIELD Owner Desc Name1 Name2 Name3
                    if (line.length != nameCount + 3)
                        throw new IOException("Invalid Tiny v1 line: #" + x + ": " + Arrays.toString(line));
                    classes.computeIfAbsent(line[1], k -> ret.addClass(duplicate(k, nameCount)))
                            .field(Arrays.copyOfRange(line, 3, line.length))
                            .descriptor(line[2]);
                    break;
                case "METHOD": // METHOD Owner Desc Name1 Name2 Name3
                    if (line.length != nameCount + 3)
                        throw new IOException("Invalid Tiny v1 line: #" + x + ": " + Arrays.toString(line));
                    classes.computeIfAbsent(line[1], k -> ret.addClass(duplicate(k, nameCount)))
                            .method(line[2], Arrays.copyOfRange(line, 3, line.length));
                    break;
                default:
                    throw new IOException("Invalid Tiny v1 line: #" + x + ": " + Arrays.toString(line));
            }
        }

        return ret;
    }

    private static IMappingBuilder loadTinyV2(List<String> lines) throws IOException {
        /*
         * This is the only spec I could find on it, so i'm assuming its official:
         * https://github.com/FabricMC/tiny-remapper/issues/9
         */
        String[] header = lines.get(0).split("\t");
        if (header.length < 5) throw new IOException("Invalid Tiny v2 Header: " + lines.get(0));

        try {
            int major = Integer.parseInt(header[1]);
            int minor = Integer.parseInt(header[2]);
            if (major != 2 || minor != 0)
                throw new IOException("Unsupported Tiny v2 version: " + lines.get(0));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Tiny v2 Header: " + lines.get(0));
        }
        IMappingBuilder ret = IMappingBuilder.create(Arrays.copyOfRange(header, 3, header.length));

        int nameCount = header.length - 3;
        boolean escaped = false;
        Map<String, String> properties = new HashMap<>();
        int start;
        for (start = 1; start < lines.size(); start++) {
            String[] line = lines.get(start).split("\t");
            if (!line[0].isEmpty())
                break;

            properties.put(line[1], line.length < 3 ? null : escaped ? unescapeTinyString(line[2]) : line[2]);
            if ("escaped-names".equals(line[1]))
                escaped = true;
        }

        Deque<TinyV2State> stack = new ArrayDeque<>();
        IMappingBuilder.IClass cls = null;
        IMappingBuilder.IField field = null;
        IMappingBuilder.IMethod method = null;
        IMappingBuilder.IParameter param = null;

        for (int x = start; x < lines.size(); x++) {
            String line = lines.get(x);

            int newdepth = 0;
            while (line.charAt(newdepth) == '\t')
                newdepth++;
            if (newdepth != 0)
                line = line.substring(newdepth);

            if (newdepth != stack.size()) {
                while (stack.size() != newdepth) {
                    switch (stack.pop()) {
                        case CLASS:
                            cls = null;
                            break;
                        case FIELD:
                            field = null;
                            break;
                        case METHOD:
                            method = null;
                            break;
                        case PARAMETER:
                            param = null;
                            break;
                        default:
                            break;
                    }
                }
            }

            String[] parts = line.split("\t");
            if (escaped) {
                for (int y = 1; y < parts.length; y++)
                    parts[y] = unescapeTinyString(parts[y]);
            }

            switch (parts[0]) {
                case "c":
                    if (stack.size() == 0) { // Class: c Name1 Name2 Name3
                        if (parts.length != nameCount + 1) {
                            if (parts.length == nameCount) { // Fix for 1.19.1 yarn
                                var newArray = new String[nameCount + 1];
                                System.arraycopy(parts, 0, newArray, 0, parts.length);
                                newArray[newArray.length - 1] = newArray[newArray.length - 2];
                                parts = newArray;
                                System.out.println("WARN: Class without mapping: " + line + ", copying the original name...");
                            } else {
                                throw new IOException("Invalid Tiny v2 line: #" + x + ": " + line);
                            }
                        }

                        cls = ret.addClass(Arrays.copyOfRange(parts, 1, parts.length));
                        stack.push(TinyV2State.CLASS);
                    } else { // Comment
                        String comment = unescapeTinyString(parts[1]);
                        if (method != null) method.meta("comment", comment);
                        else if (field != null) field.meta("comment", comment);
                        else if (cls != null) cls.meta("comment", comment);
                        else if (param != null) param.meta("comment", comment);
                    }
                    break;
                case "f": // Field: f desc Name1 Name2 Name3
                    if (parts.length != nameCount + 2 || stack.peek() != TinyV2State.CLASS)
                        throw new IOException("Invalid Tiny v2 line: #" + x + ": " + line);

                    field = cls.field(Arrays.copyOfRange(parts, 2, parts.length)).descriptor(parts[1]);
                    stack.push(TinyV2State.FIELD);

                    break;

                case "m": // Method: m desc Name1 Name2 Name3
                    if (parts.length != nameCount + 2 || stack.peek() != TinyV2State.CLASS)
                        throw new IOException("Invalid Tiny v2 line: #" + x + ": " + line);

                    method = cls.method(parts[1], Arrays.copyOfRange(parts, 2, parts.length));
                    stack.push(TinyV2State.METHOD);

                    break;

                case "p": // Parameters: p index Name1 Name2 Name3
                    if (parts.length != nameCount + 2 || stack.peek() != TinyV2State.METHOD)
                        throw new IOException("Invalid Tiny v2 line: #" + x + ": " + line);

                    param = method.parameter(Integer.parseInt(parts[1]), Arrays.copyOfRange(parts, 2, parts.length));
                    stack.push(TinyV2State.PARAMETER);

                    break;
                case "v": // Local Variable: v index start Name1 Name2 Name3?
                    break; //TODO: Unsupported, is this used? Should we expose it?
                default:
                    throw new IOException("Invalid Tiny v2 line: #" + x + ": " + line);
            }
        }

        return ret;
    }

    /* <escaped-string> is a string that must not contain <eol> and escapes
     *     \ to \\
     *     "\n" to \n
     *     "\r" to \r
     *     "\t" to \t
     *     "\0" to \0
     * */
    private static String unescapeTinyString(String value) {
        return value.replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\0", "\0");
    }

    static String escapeTinyString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\0", "\\0");
    }

    static String toDesc(String type) {
        if (type.endsWith("[]")) return "[" + toDesc(type.substring(0, type.length() - 2));
        if (type.equals("int")) return "I";
        if (type.equals("void")) return "V";
        if (type.equals("boolean")) return "Z";
        if (type.equals("byte")) return "B";
        if (type.equals("char")) return "C";
        if (type.equals("short")) return "S";
        if (type.equals("double")) return "D";
        if (type.equals("float")) return "F";
        if (type.equals("long")) return "J";
        if (type.contains("/")) return "L" + type + ";";
        throw new RuntimeException("Invalid toDesc input: " + type);
    }

    static String toSource(String desc) {
        char first = desc.charAt(0);
        switch (first) {
            case 'I':
                return "int";
            case 'V':
                return "void";
            case 'Z':
                return "boolean";
            case 'B':
                return "byte";
            case 'C':
                return "char";
            case 'S':
                return "short";
            case 'D':
                return "double";
            case 'F':
                return "float";
            case 'J':
                return "long";
            case '[':
                return toSource(desc.substring(1)) + "[]";
            case 'L':
                return desc.substring(1, desc.length() - 1).replace('/', '.');
            default:
                throw new IllegalArgumentException("Unknown descriptor: " + desc);
        }
    }

    static String toSource(String name, String desc) {
        StringBuilder buf = new StringBuilder();
        int endParams = desc.lastIndexOf(')');
        String ret = desc.substring(endParams + 1);
        buf.append(toSource(ret)).append(' ').append(name).append('(');

        int idx = 1;
        while (idx < endParams) {
            int array = 0;
            char c = desc.charAt(idx);
            if (c == '[') {
                while (desc.charAt(idx) == '[') {
                    array++;
                    idx++;
                }
                c = desc.charAt(idx);
            }
            if (c == 'L') {
                int end = desc.indexOf(';', idx);
                buf.append(toSource(desc.substring(idx, end + 1)));
                idx = end;
            } else {
                buf.append(toSource(c + ""));
            }

            while (array-- > 0)
                buf.append("[]");

            idx++;
            if (idx < endParams)
                buf.append(',');
        }
        buf.append(')');
        return buf.toString();
    }

    private static String[] rsplit(String str, char chr, int count) {
        List<String> pts = new ArrayList<>();
        int idx;
        while ((idx = str.lastIndexOf(chr)) != -1 && count > 0) {
            pts.add(str.substring(idx + 1));
            str = str.substring(0, idx);
            count--;
        }
        pts.add(str);
        Collections.reverse(pts);
        return pts.toArray(new String[0]);
    }

    public static int compareLines(String o1, String o2) {
        String[] pt1 = o1.split(" ");
        String[] pt2 = o2.split(" ");
        if (!pt1[0].equals(pt2[0]))
            return ORDER.indexOf(pt1[0]) - ORDER.lastIndexOf(pt2[0]);

        if ("PK:".equals(pt1[0]))
            return o1.compareTo(o2);
        if ("CL:".equals(pt1[0]))
            return compareCls(pt1[1], pt2[1]);
        if ("FD:".equals(pt1[0]) || "MD:".equals(pt1[0])) {
            String[][] y = {
                    {pt1[1].substring(0, pt1[1].lastIndexOf('/')), pt1[1].substring(pt1[1].lastIndexOf('/') + 1)},
                    {pt2[1].substring(0, pt2[1].lastIndexOf('/')), pt2[1].substring(pt2[1].lastIndexOf('/') + 1)}
            };
            int ret = compareCls(y[0][0], y[1][0]);
            if (ret != 0)
                return ret;
            return y[0][1].compareTo(y[1][1]);
        }
        return o1.compareTo(o2);
    }

    public static int compareCls(String cls1, String cls2) {
        if (cls1.indexOf('/') > 0 && cls2.indexOf('/') > 0)
            return cls1.compareTo(cls2);
        String[][] t = {cls1.split("\\$"), cls2.split("\\$")};
        int max = Math.min(t[0].length, t[1].length);
        for (int i = 0; i < max; i++) {
            if (!t[0][i].equals(t[1][i])) {
                if (t[0][i].length() != t[1][i].length())
                    return t[0][i].length() - t[1][i].length();
                return t[0][i].compareTo(t[1][i]);
            }
        }
        return Integer.compare(t[0].length, t[1].length);
    }

    public static String stripComment(String str) {
        return str.replaceAll(" *#.+$", "");
    }

    private static void swapFirst(String[] values) {
        String tmp = values[0];
        values[0] = values[1];
        values[1] = tmp;
    }

    private static String[] duplicate(String value, int count) {
        String[] ret = new String[count];
        Arrays.fill(ret, value);
        return ret;
    }

    static void writeMeta(Format format, List<String> lines, Element element, Map<String, String> meta) {
        int indent = 0;
        switch (element) {
            case PACKAGE:
            case CLASS:
                indent = 1;
                break;
            case FIELD:
            case METHOD:
                indent = 2;
                break;
            case PARAMETER:
                indent = 3;
                break;
        }

        switch (format) {
            case CSRG:
            case PG:
            case SRG:
            case TINY1:
            case TSRG:
            case XSRG:
                break;
            case TINY:
                String comment = meta.get("comment");
                if (comment != null) {
                    char[] prefix = new char[indent];
                    Arrays.fill(prefix, '\t');
                    lines.add(new String(prefix) + "c\t" + comment);
                }
                break;
            case TSRG2:
                if (meta.containsKey("is_static")) {
                    char[] prefix = new char[indent];
                    Arrays.fill(prefix, '\t');
                    lines.add(new String(prefix) + "static");
                }
                break;
        }
    }

    enum TinyV2State {
        ROOT, CLASS, FIELD, METHOD, PARAMETER
    }

    enum Element {
        PACKAGE, CLASS, FIELD, METHOD, PARAMETER
    }
}

/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.com.sun.glass.ui.gtk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The hand-kept constants headers of the GTK glass sources, every {@code *.h} in {@value #DIRECTORY}, hold what
 * {@code javac -h} writes for the constants of their Java classes, so that the unchanged {@code #include} lines of
 * the GTK sources compile to the same code as with the generated headers, and without {@code jni.h}.
 * <p>
 * The rules are those of {@code JNIWriter} in {@code jdk.compiler}. A header is named for its class: the binary
 * name with {@code '.'} and {@code '$'} replaced by {@code '_'}, so {@code com_sun_glass_ui_Window_Level.h} is
 * {@code com.sun.glass.ui.Window$Level}. Its macros are the static final constants of the class and of its
 * superclasses, each named {@code <class>_<field>} after the header's own class ({@code printStaticDefines}), and
 * a {@code byte}, {@code short} or {@code int} constant is spelled {@code <decimal>L}. Those three are the only
 * types the headers hold, so a macro for a constant of any other type fails as unsupported.
 * <p>
 * A header holds comments, an include guard and {@code #define <macro> <literal>} lines, nothing else: an
 * {@code #include} line fails, which keeps {@code jni.h} out. It may define fewer constants than {@code javac -h}
 * writes, since it carries the ones the GTK sources use, but at least one, and the directory holds at least one
 * header, so that an emptied file or directory cannot pass.
 * <p>
 * The Java side comes from the class files, read with the ClassFile API: a {@code ConstantValue} attribute is the
 * constant value {@code javac -h} writes, and reading it initializes no class.
 */
public class GtkConstantsHeaderTest {

    /** The constants headers, relative to the module directory. */
    private static final String DIRECTORY = "src/main/native-glass/gtk/constants";

    /** A preprocessor directive: its name and the rest of the line. */
    private static final Pattern DIRECTIVE = Pattern.compile("#\\s*(\\w*)\\s*(.*)");

    /** The operand of an object-like {@code #define}: the macro and, unless it is the include guard, one token. */
    private static final Pattern DEFINE = Pattern.compile("([A-Za-z_]\\w*)(?:\\s+(\\S+))?");

    /** How {@code javac -h} spells a {@code byte}, {@code short} or {@code int} constant. */
    private static final Pattern DECIMAL_L = Pattern.compile("-?(?:0|[1-9][0-9]*)L");

    /** The field types whose spelling this test knows, by descriptor: {@code byte}, {@code short}, {@code int}. */
    private static final List<String> DECIMAL_L_TYPES = List.of("B", "S", "I");

    @Test
    public void theDirectoryHoldsConstantsHeaders() {
        assertFalse(headers().isEmpty(), GtkConstantsHeaderTest::noHeaders);
    }

    @TestFactory
    public List<DynamicTest> everyHeaderHoldsWhatJavacWritesForItsClass() {
        List<Path> headers = headers();
        assertFalse(headers.isEmpty(), GtkConstantsHeaderTest::noHeaders);
        return headers.stream()
                .map(header -> DynamicTest.dynamicTest(header.getFileName().toString(), header.toUri(),
                        () -> check(header)))
                .toList();
    }

    private static void check(Path header) throws IOException {
        String file = header.getFileName().toString();
        Header parsed = Header.parse(file, Files.readString(header, StandardCharsets.UTF_8));
        List<Executable> checks = new ArrayList<>();
        for (String problem : parsed.problems()) {
            checks.add(() -> fail(problem));
        }
        if (parsed.defines().isEmpty()) {
            checks.add(() -> fail(problem(file, "no constant #define, so the header checks nothing",
                    "at least one #define <macro> <literal>", "0")));
        }
        String name = file.substring(0, file.length() - ".h".length());
        List<String> candidates = new ArrayList<>();
        binaryNames(name, 0, "", false, candidates);
        List<Class<?>> classes = loadable(candidates);
        if (classes.size() != 1) {
            String actual = classes.isEmpty()
                    ? "none of the " + candidates.size() + " binary names that javac -h turns into " + name
                    : classes.toString();
            checks.add(() -> fail(problem(file, "the header maps to no single class",
                    "the one class whose binary name javac -h turns into " + name, actual)));
        } else {
            JavaConstants javaConstants = JavaConstants.of(classes.getFirst());
            for (Define define : parsed.defines()) {
                checks.add(() -> javaConstants.check(file, define));
            }
        }
        assertAll(file, checks);
    }

    /**
     * Every binary name that {@code javac -h} turns into {@code name}: each {@code '_'} stands for {@code '.'},
     * {@code '$'} or itself, and no {@code '.'} follows a {@code '$'}.
     */
    private static void binaryNames(String name, int from, String prefix, boolean nested, List<String> names) {
        int underscore = name.indexOf('_', from);
        if (underscore < 0) {
            names.add(prefix + name.substring(from));
            return;
        }
        String head = prefix + name.substring(from, underscore);
        binaryNames(name, underscore + 1, head + "_", nested, names);
        binaryNames(name, underscore + 1, head + "$", true, names);
        if (!nested) {
            binaryNames(name, underscore + 1, head + ".", false, names);
        }
    }

    /** The classes the names load, without initializing them; javac -h writes no header for a local class. */
    private static List<Class<?>> loadable(List<String> names) {
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, GtkConstantsHeaderTest.class.getClassLoader());
                if (type.getCanonicalName() != null) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException e) {
                // no class of that name, the answer for all but the class of the header
            }
        }
        return classes;
    }

    private static List<Path> headers() {
        Path directory = moduleDirectory().resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".h"))
                    .filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String noHeaders() {
        return problem(DIRECTORY, "no constants header in " + moduleDirectory().resolve(DIRECTORY),
                "at least one *.h", "0");
    }

    private static Path moduleDirectory() {
        Path dir = Path.of("").toAbsolutePath();
        if (Files.isDirectory(dir.resolve("src").resolve("main"))) {
            return dir;
        }
        return dir.resolve("modules").resolve("javafx.graphics");
    }

    private static String problem(String where, String what, String expected, String actual) {
        return where + ": " + what + "; expected " + expected + ", actual " + actual;
    }

    /** A {@code #define <macro> <literal>} line of a header. */
    private record Define(int line, String macro, String literal) {

        String where(String file) {
            return file + ":" + line + " " + macro;
        }
    }

    /** The constant {@code #define}s of a header, and every line that is not a comment, guard or constant. */
    private record Header(List<Define> defines, List<String> problems) {

        static Header parse(String file, String text) {
            List<Define> defines = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            String[] raw = text.split("\n", -1);
            String[] code = withoutComments(file, text, problems).split("\n", -1);
            String guard = null;
            for (int i = 0; i < code.length; i++) {
                String line = code[i].strip();
                String where = file + ":" + (i + 1);
                String actual = raw[i].strip();
                Matcher directive = DIRECTIVE.matcher(line);
                if (line.isEmpty()) {
                    continue;
                } else if (!directive.matches()) {
                    problems.add(problem(where, "a line that is not a directive or a comment",
                            "only comments, #ifndef, #define and #endif", actual));
                    continue;
                }
                String rest = directive.group(2).strip();
                switch (directive.group(1)) {
                    case "include" -> problems.add(problem(where, "an #include line, which a constants header"
                            + " must not have (it keeps jni.h out)", "no #include", actual));
                    case "ifndef" -> guard = rest;
                    case "endif" -> { }
                    case "define" -> {
                        Matcher define = DEFINE.matcher(rest);
                        if (define.matches() && define.group(2) != null) {
                            defines.add(new Define(i + 1, define.group(1), define.group(2)));
                        } else if (!define.matches() || !define.group(1).equals(guard)) {
                            problems.add(problem(where, "an unparseable #define",
                                    "#define <macro> <literal>, or the include guard after its #ifndef", actual));
                        }
                    }
                    default -> problems.add(problem(where, "an unsupported directive",
                            "#ifndef, #define or #endif", actual));
                }
            }
            return new Header(defines, problems);
        }

        /** The text with every comment blanked out, line breaks kept. */
        private static String withoutComments(String file, String text, List<String> problems) {
            StringBuilder code = new StringBuilder(text.length());
            int i = 0;
            while (i < text.length()) {
                int end;
                if (text.startsWith("/*", i)) {
                    int close = text.indexOf("*/", i + 2);
                    if (close < 0) {
                        problems.add(problem(file, "an unterminated comment", "*/", "the end of the file"));
                    }
                    end = close < 0 ? text.length() : close + 2;
                } else if (text.startsWith("//", i)) {
                    int newline = text.indexOf('\n', i);
                    end = newline < 0 ? text.length() : newline;
                } else {
                    code.append(text.charAt(i++));
                    continue;
                }
                for (; i < end; i++) {
                    code.append(text.charAt(i) == '\n' ? '\n' : ' ');
                }
            }
            return code.toString();
        }
    }

    /** A field of a class file. */
    private record Field(String owner, String name, ClassDesc type, int modifiers, ConstantDesc value) {

        static Field of(String owner, FieldModel field) {
            ConstantDesc value = field.findAttribute(Attributes.constantValue())
                    .map(attribute -> attribute.constant().constantValue()).orElse(null);
            return new Field(owner, field.fieldName().stringValue(), field.fieldTypeSymbol(),
                    field.flags().flagsMask(), value);
        }

        boolean isConstant() {
            return Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && value != null;
        }

        String typeName() {
            if (type.isPrimitive() || type.packageName().isEmpty()) {
                return type.displayName();
            }
            return type.packageName() + "." + type.displayName();
        }

        @Override
        public String toString() {
            String modifierNames = Modifier.toString(modifiers);
            return (modifierNames.isEmpty() ? "" : modifierNames + " ") + typeName() + " " + owner + "." + name
                    + (value == null ? " with no constant value" : " = " + value);
        }
    }

    /** The macros {@code javac -h} writes for a class, and every field of the class and its superclasses. */
    private record JavaConstants(String className, String prefix, Map<String, Field> constants,
            Map<String, Field> fields) {

        static JavaConstants of(Class<?> type) {
            List<Class<?>> hierarchy = new ArrayList<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                hierarchy.add(c);
            }
            // javac writes the superclass constants first, so a constant of a subclass replaces one of the same name
            Collections.reverse(hierarchy);
            String prefix = encodeClass(type.getCanonicalName());
            Map<String, Field> constants = new LinkedHashMap<>();
            Map<String, Field> fields = new LinkedHashMap<>();
            for (Class<?> c : hierarchy) {
                for (FieldModel model : classFile(c).fields()) {
                    Field field = Field.of(c.getName(), model);
                    String macro = prefix + "_" + encodeField(field.name());
                    fields.put(macro, field);
                    if (field.isConstant()) {
                        constants.put(macro, field);
                    }
                }
            }
            return new JavaConstants(type.getName(), prefix, constants, fields);
        }

        void check(String file, Define define) {
            String where = define.where(file);
            if (!define.macro().startsWith(prefix + "_")) {
                fail(problem(where, "a macro of another class than the file's " + className,
                        prefix + "_<field>", define.macro()));
                return;
            }
            Field constant = constants.get(define.macro());
            if (constant == null) {
                Field field = fields.get(define.macro());
                String fieldName = define.macro().substring(prefix.length() + 1);
                if (field == null) {
                    fail(problem(where, "a missing field", "a static final constant " + className + "." + fieldName
                            + " (or one of a superclass)", "no such field"));
                } else {
                    fail(problem(where, "a field that is not a static final constant, for which javac -h writes no"
                            + " macro", "a static final constant", field.toString()));
                }
                return;
            }
            if (!DECIMAL_L_TYPES.contains(constant.type().descriptorString())) {
                fail(problem(where, "an unsupported type: this test knows how javac -h spells byte, short and int"
                        + " constants only (JNIWriter.printStaticDefines has the rest)", "byte, short or int",
                        constant.toString()));
                return;
            }
            String expected = constant.value() + "L";
            String what = DECIMAL_L.matcher(define.literal()).matches()
                    ? "a value other than " + constant
                    : "a literal not spelled the way javac -h spells " + constant.typeName() + " constants, <decimal>L";
            assertEquals(expected, define.literal(), problem(where, what, expected, define.literal()));
        }

        private static ClassModel classFile(Class<?> type) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream in = type.getModule().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("no " + resource + " in " + type.getModule());
                }
                return ClassFile.of().parse(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** {@code JNIWriter.encode(name, EncoderType.CLASS)}. */
        private static String encodeClass(String name) {
            StringBuilder result = new StringBuilder();
            for (char ch : name.toCharArray()) {
                if (isAsciiAlphanumeric(ch)) {
                    result.append(ch);
                } else if (ch == '.' || ch == '_') {
                    result.append('_');
                } else if (ch == '$') {
                    result.append("__");
                } else {
                    result.append(encodeChar(ch));
                }
            }
            return result.toString();
        }

        /** {@code JNIWriter.encode(name, EncoderType.FIELDSTUB)}. */
        private static String encodeField(String name) {
            StringBuilder result = new StringBuilder();
            for (char ch : name.toCharArray()) {
                result.append(isAsciiAlphanumeric(ch) || ch == '_' ? String.valueOf(ch) : encodeChar(ch));
            }
            return result.toString();
        }

        /** {@code JNIWriter.encodeChar}: {@code '_'} and five lower-case hex digits. */
        private static String encodeChar(char ch) {
            return String.format("_%05x", (int) ch);
        }

        private static boolean isAsciiAlphanumeric(char ch) {
            return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
        }
    }
}

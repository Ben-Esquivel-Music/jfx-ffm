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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails when JNI comes back into the tree: the check of US-074 acceptance criterion 7.
 * <p>
 * Run it from the repository root with the JDK source launcher, which needs nothing but the JDK:
 * <pre>
 *     java buildtools/CheckNoJni.java [root]       scan the tree at root (default: the current directory)
 *     java buildtools/CheckNoJni.java --self-test  plant every rule in a temporary directory and prove it fires
 * </pre>
 * It reads every file {@code git ls-files --cached --others --exclude-standard} lists under the root, so tracked
 * files and new files that are not ignored yet. Without git it says so and walks the root, skipping {@code .git}
 * and the {@code target} and {@code build} directories that sit beside a {@code pom.xml}. A file with a NUL byte in
 * its first 8000 bytes is binary and skipped, as git does. Every platform's sources are read on every host:
 * nothing depends on the operating system that runs the check.
 * <p>
 * The rules, one per item of the criterion (each hit prints as {@code path:line:rule:matched text}):
 * <ul>
 *   <li>{@code R-NATIVE}: a {@code native} modifier, {@code @Native} or {@code java.lang.annotation.Native} in
 *       Java code, in the template text of a StringTemplate group (.stg), or declared in the Java a generator
 *       writes;</li>
 *   <li>{@code R-EXPORT}: a JNI entry point - a {@code Java_*} symbol, {@code JNI_OnLoad}, {@code JNI_OnUnload},
 *       {@code JNIEXPORT} or {@code JNICALL} - in any source, export list (.def, .map, version scripts) or
 *       string literal;</li>
 *   <li>{@code R-JNI}: a JNI type, {@code JNI_*} constant or JNI function, including inside string literals, so
 *       that a code generator that emits JNI is caught;</li>
 *   <li>{@code R-INCLUDE}: {@code jni.h}, {@code jni_md.h}, or a JDK header that includes it ({@code jawt.h},
 *       {@code jawt_md.h}, {@code jvmti.h}, {@code jdwpTransport.h}, the Access Bridge headers), in any case;</li>
 *   <li>{@code R-JDKINC}: a JDK include directory or CMake's {@code FindJNI} in a build file or script;</li>
 *   <li>{@code R-JAVAC-H}: {@code javac -h} on one logical line, or within one CMake command; {@code -h} as an
 *       {@code <arg>}, {@code <compilerarg>} or {@code <compilerArguments><h>} of a pom or Ant file; Ant's
 *       {@code nativeheaderdir}, Gradle's {@code headerOutputDirectory}, CMake's {@code GENERATE_NATIVE_HEADERS},
 *       or {@code javah};</li>
 *   <li>{@code R-LOAD}: {@code System.load}, {@code System.loadLibrary} or {@code Runtime.load*} in Java code,
 *       also through a variable or parameter that holds the Runtime and through a static import of
 *       {@code java.lang.System.*}; and the same calls in .stg templates and in the Java a generator writes.
 *       Reflection and method handles are not followed.</li>
 * </ul>
 * Comments never count; string literals do, except for {@code R-NATIVE} and {@code R-LOAD} in Java, which look at
 * code only (a message that names {@code System.loadLibrary} calls nothing). In a generator language (perl, python,
 * ruby, shell, CMake) those two read the comment-free text for the shapes of written Java: a native method or an
 * {@code @Native} constant declared, {@code System.load*(} called. The comment syntax is chosen per file:
 * {@code //} and {@code /* *}{@code /} for Java, C, C++, Objective-C and the other C-like languages (JavaScript
 * with its regular expression literals); {@code #} for perl (and its POD blocks), python, ruby (and {@code =begin}
 * blocks), shell, CMake (and its bracket comments), YAML, properties, Makefiles and export lists; {@code <!-- -->}
 * for XML; {@code ;} for Windows .def files. A template such as {@code config.h.in} or {@code config.h.cmake} is read
 * as the file it becomes. Heredocs and multi-line strings are string content, not comments. Where the lexer could
 * be wrong it keeps text rather than drop it: a {@code #} comment after code is kept when an odd number of quotes
 * precede it on its line. A file of unknown syntax is read whole, comments included. Prose is not code and is not
 * read: markdown, .txt files other than CMakeLists.txt, HTML, and extensionless README, LICENSE and similar files.
 * <p>
 * The allowlist {@code buildtools/CheckNoJni.allowlist} under the root holds the hits that US-074 puts out of
 * scope, one per line as {@code path | rule | text | reason}. An entry covers every hit of that rule in that file
 * whose source line contains the text around the matched token. An entry that covers nothing is stale and fails
 * the check, so the list cannot outlive what it excuses. This program and its allowlist are the only files the scan
 * skips by name: they spell out every token they look for.
 * <p>
 * Exit status: 0 no JNI found; 1 hits; 2 usage error, unreadable file, or a malformed or stale allowlist entry.
 * It uses only JDK APIs and the language level of {@code --release 27}.
 */
public final class CheckNoJni {

    private static final int EXIT_CLEAN = 0;
    private static final int EXIT_HITS = 1;
    private static final int EXIT_USAGE = 2;

    private static final String SELF = "buildtools/CheckNoJni.java";
    private static final String ALLOWLIST = "buildtools/CheckNoJni.allowlist";
    private static final String ALLOWLIST_SEPARATOR = " | ";
    private static final int BINARY_PROBE_BYTES = 8000;
    private static final int MAX_SHOWN_TEXT = 100;

    /** The rules, one per item of US-074 acceptance criterion 7. */
    enum Rule {
        NATIVE("R-NATIVE", "a native method or @Native in Java code, a .stg template, or Java a generator writes"),
        EXPORT("R-EXPORT", "a JNI entry point: a Java_* symbol, JNI_OnLoad/JNI_OnUnload, JNIEXPORT or JNICALL"),
        JNI("R-JNI", "a JNI type, JNI_* constant or JNI function outside comments, string literals included"),
        INCLUDE("R-INCLUDE", "jni.h, jni_md.h or a JDK header that includes them (jawt, jvmti, jdwpTransport,"
                + " Access Bridge), in any case"),
        JDKINC("R-JDKINC", "a JDK include directory (or CMake's FindJNI) in a build file or script"),
        JAVAC_H("R-JAVAC-H", "javac -h (JNI header generation) in a command, pom, Ant or Gradle build,"
                + " CMake's GENERATE_NATIVE_HEADERS, or javah"),
        LOAD("R-LOAD", "System.load*, Runtime.load* (also through a Runtime variable or a static import) in Java"
                + " code, a .stg template or Java a generator writes: the JNI library loader");

        final String id;
        final String explanation;

        Rule(String id, String explanation) {
            this.id = id;
            this.explanation = explanation;
        }

        static Rule byId(String id) {
            for (Rule rule : values()) {
                if (rule.id.equals(id)) {
                    return rule;
                }
            }
            return null;
        }
    }

    /** How a file's comments and strings are recognised; see {@link #classify}. */
    enum Syntax {
        JAVA, C, JS, PERL, SHELL, PYTHON, RUBY, CMAKE, YAML, PROPERTIES, MAKE, XML, DEF, EXPORT_LIST, BATCH, RAW, PROSE
    }

    record Hit(String path, int line, int column, int length, Rule rule, String text, String lineText) {
    }

    record AllowEntry(int lineNumber, String path, Rule rule, String text, String reason) {
    }

    private enum Outcome { SCANNED, BINARY, PROSE, OWN, MISSING }

    private record FileResult(String path, Outcome outcome, List<Hit> hits, String error) {
    }

    private record Report(List<Hit> hits, int allowlisted, List<AllowEntry> stale, List<String> errors,
            int scanned, int binary, int prose, int own, int missing, String listing, long nanos) {
    }

    private CheckNoJni() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length == 1 && args[0].equals("--self-test")) {
            return selfTest();
        }
        if (args.length == 1 && args[0].equals("--help")) {
            usage(System.out);
            return EXIT_CLEAN;
        }
        if (args.length > 1 || (args.length == 1 && args[0].startsWith("-"))) {
            usage(System.err);
            return EXIT_USAGE;
        }
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("ERROR: " + root + " is not a directory");
            usage(System.err);
            return EXIT_USAGE;
        }
        return print(scan(root, true), System.out, System.err);
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java buildtools/CheckNoJni.java [root]");
        out.println("       java buildtools/CheckNoJni.java --self-test");
        out.println("Exit status: 0 no JNI found, 1 hits, 2 usage error or a malformed or stale allowlist entry.");
    }

    // ---- scanning ----

    static Report scan(Path root, boolean useGit) {
        long start = System.nanoTime();
        List<String> errors = new ArrayList<>();
        List<String> paths = useGit ? gitListing(root) : null;
        String listing = paths != null ? "git ls-files" : "a directory walk";
        if (paths == null) {
            if (useGit) {
                System.err.println("WARNING: git ls-files failed under " + root + "; walking the directory instead,"
                        + " which also reads untracked files and skips only .git and Maven build output");
            }
            paths = walkListing(root);
        }
        List<AllowEntry> allowlist = readAllowlist(root.resolve(ALLOWLIST), errors);
        List<FileResult> results = paths.parallelStream().map(path -> scanFile(root, path)).toList();

        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        int binary = 0;
        int prose = 0;
        int own = 0;
        int missing = 0;
        for (FileResult result : results) {
            switch (result.outcome()) {
                case SCANNED -> scanned++;
                case BINARY -> binary++;
                case PROSE -> prose++;
                case OWN -> own++;
                case MISSING -> missing++;
            }
            if (result.error() != null) {
                errors.add(result.path() + ": " + result.error());
            }
            hits.addAll(result.hits());
        }
        hits.sort(Comparator.comparing(Hit::path).thenComparingInt(Hit::line).thenComparingInt(Hit::column));

        Set<AllowEntry> used = new HashSet<>();
        List<Hit> remaining = new ArrayList<>();
        for (Hit hit : hits) {
            AllowEntry entry = allowEntryFor(hit, allowlist);
            if (entry == null) {
                remaining.add(hit);
            } else {
                used.add(entry);
            }
        }
        List<AllowEntry> stale = allowlist.stream().filter(entry -> !used.contains(entry)).toList();
        return new Report(remaining, hits.size() - remaining.size(), stale, errors,
                scanned, binary, prose, own, missing, listing, System.nanoTime() - start);
    }

    private static int print(Report report, PrintStream out, PrintStream err) {
        Set<Rule> fired = EnumSet.noneOf(Rule.class);
        Set<String> files = new TreeSet<>();
        for (Hit hit : report.hits()) {
            out.println(hit.path() + ":" + hit.line() + ":" + hit.rule().id + ":" + hit.text());
            fired.add(hit.rule());
            files.add(hit.path());
        }
        for (String error : report.errors()) {
            err.println("ERROR: " + error);
        }
        for (AllowEntry entry : report.stale()) {
            err.println("ERROR: " + ALLOWLIST + ":" + entry.lineNumber() + ": stale entry, it matches nothing: "
                    + entry.path() + ALLOWLIST_SEPARATOR + entry.rule().id + ALLOWLIST_SEPARATOR + entry.text());
        }
        out.printf(Locale.ROOT, "CheckNoJni: read %d files found by %s in %.1f s (skipped %d binary, %d prose,"
                + " %d of its own, %d listed but missing)%n", report.scanned(), report.listing(),
                report.nanos() / 1e9, report.binary(), report.prose(), report.own(), report.missing());
        if (report.hits().isEmpty()) {
            out.println("CheckNoJni: no JNI found (" + report.allowlisted() + " allowlisted hits)");
        } else {
            out.println("CheckNoJni: " + report.hits().size() + " hits in " + files.size() + " files ("
                    + report.allowlisted() + " more allowlisted). JNI must not come back (US-074):");
            for (Rule rule : fired) {
                out.printf(Locale.ROOT, "  %-10s %s%n", rule.id, rule.explanation);
            }
        }
        if (!report.errors().isEmpty() || !report.stale().isEmpty()) {
            return EXIT_USAGE;
        }
        return report.hits().isEmpty() ? EXIT_CLEAN : EXIT_HITS;
    }

    private static List<String> gitListing(Path root) {
        ProcessBuilder builder = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z",
                "--cached", "--others", "--exclude-standard");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = in.readAllBytes();
            }
            if (process.waitFor() != 0) {
                return null;
            }
            Set<String> paths = new LinkedHashSet<>();
            for (String path : new String(output, StandardCharsets.UTF_8).split("\0")) {
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            }
            return new ArrayList<>(paths);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static List<String> walkListing(Path root) {
        List<String> paths = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (dir.equals(root) || name == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    // .git, and the build output of a Maven project (target or build beside its pom.xml); a source
                    // directory that happens to be named build, such as glib's, is read
                    boolean buildOutput = Set.of("target", "build").contains(name.toString())
                            && Files.isRegularFile(dir.resolveSibling("pom.xml"));
                    return name.toString().equals(".git") || buildOutput
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        paths.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        paths.sort(null);
        return paths;
    }

    private static FileResult scanFile(Path root, String path) {
        if (path.equals(SELF) || path.equals(ALLOWLIST)) {
            return new FileResult(path, Outcome.OWN, List.of(), null);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(root.resolve(path));
        } catch (NoSuchFileException e) {
            return new FileResult(path, Outcome.MISSING, List.of(), null);
        } catch (IOException e) {
            return new FileResult(path, Outcome.SCANNED, List.of(), "cannot read: " + e);
        }
        for (int i = 0; i < Math.min(bytes.length, BINARY_PROBE_BYTES); i++) {
            if (bytes[i] == 0) {
                return new FileResult(path, Outcome.BINARY, List.of(), null);
            }
        }
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        Syntax syntax = classify(path, text);
        if (syntax == Syntax.PROSE) {
            return new FileResult(path, Outcome.PROSE, List.of(), null);
        }
        return new FileResult(path, Outcome.SCANNED, scanText(path, text, syntax), null);
    }

    static List<Hit> scanText(String path, String text, Syntax syntax) {
        char[] noComments = text.toCharArray();
        char[] codeOnly = syntax == Syntax.JAVA ? text.toCharArray() : null;
        Lexers.strip(text, syntax, noComments, codeOnly);
        String code = null;
        String view;
        if (codeOnly == null) {
            view = new String(noComments);
        } else {
            // Java names JNI only inside a string: a symbol it looks up, or C it generates. A Java identifier that
            // shares a JNI name (Monocle's C.NewDirectByteBuffer) is a Java method, and native and System.load* are
            // found in the code view. So the other rules read the string literals of Java, and nothing else.
            char[] strings = noComments;
            for (int i = 0; i < strings.length; i++) {
                if (!Character.isWhitespace(codeOnly[i])) {
                    strings[i] = ' ';
                }
            }
            view = new String(strings);
            code = new String(codeOnly);
        }
        if (path.endsWith(".stg")) {
            code = view; // the template text of a StringTemplate group is the Java it emits (jslc's *Glue.stg)
        }
        String generated = GENERATORS.contains(syntax) ? view : null;
        String cmake = syntax == Syntax.CMAKE ? view : null;
        HitCollector hits = new HitCollector(path, text);
        Tokens.scan(view, hits);
        Tokens.scanEnvCalls(view, hits);
        scanJdkIncludes(view, hits);
        for (PatternRule rule : PATTERN_RULES) {
            String ruleView = switch (rule.scope()) {
                case JAVA_CODE -> code;
                case GENERATED_JAVA -> generated;
                case TEXT -> view;
                case CMAKE -> cmake;
            };
            if (ruleView == null || !rule.triggeredBy(ruleView)) {
                continue;
            }
            Matcher matcher = rule.pattern().matcher(ruleView);
            while (matcher.find()) {
                hits.add(rule.rule(), matcher.start(), matcher.end(), ruleView);
            }
        }
        if (code != null) {
            scanLoadThroughNames(code, hits);
        }
        return hits.sorted();
    }

    /** A name bound to the Runtime: {@code Runtime rt = ...}, {@code var rt = Runtime.getRuntime()}, a parameter. */
    private static final Pattern RUNTIME_NAME = Pattern.compile(
            "\\b(\\w+)\\s*=\\s*(?:java\\s*\\.\\s*lang\\s*\\.\\s*)?Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)"
            + "|\\b(?:java\\s*\\.\\s*lang\\s*\\.\\s*)?Runtime\\s+(\\w+)\\s*[;,)=]");

    private static final Pattern SYSTEM_STATIC_IMPORT =
            Pattern.compile("\\bimport\\s+static\\s+java\\s*\\.\\s*lang\\s*\\.\\s*System\\s*\\.\\s*\\*\\s*;");

    /** load( or loadLibrary( with no receiver: a call, or the declaration of a method of that name. */
    private static final Pattern UNQUALIFIED_LOAD = Pattern.compile("(?<![\\w$.:])(load(?:Library)?)\\s*\\(");

    /** The words that may stand right before a method call; any other word before load( makes it a declaration. */
    private static final Set<String> BEFORE_A_CALL = Set.of("return", "throw", "case", "else", "yield", "assert", "do");

    /**
     * R-LOAD for the routes to the JNI loader that do not spell System or Runtime at the call: a Runtime held in a
     * variable, field or parameter ({@code rt.loadLibrary(name)}), and the unqualified {@code loadLibrary(name)} of
     * a class that imports {@code java.lang.System.*} statically. A class that declares its own load (or
     * loadLibrary) calls that method unqualified, as Java resolves it, so calls of that name are not reported, calls
     * of the other name are; and qualified calls of
     * the fork's own methods ({@code NativeLibLoader.loadLibrary}, Monocle's {@code LinuxSystem.loadLibrary}) never
     * are. Reflection and method handles are not followed.
     */
    private static void scanLoadThroughNames(String code, HitCollector hits) {
        if (code.contains("Runtime")) {
            Set<String> names = new TreeSet<>();
            Matcher binding = RUNTIME_NAME.matcher(code);
            while (binding.find()) {
                names.add(binding.group(1) != null ? binding.group(1) : binding.group(2));
            }
            for (String name : names) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:\\.|::)\\s*load(?:Library)?\\b")
                        .matcher(code);
                while (call.find()) {
                    hits.add(Rule.LOAD, call.start(), call.end(), code);
                }
            }
        }
        if (SYSTEM_STATIC_IMPORT.matcher(code).find()) {
            Set<String> declared = new HashSet<>();
            List<int[]> calls = new ArrayList<>();
            Matcher load = UNQUALIFIED_LOAD.matcher(code);
            while (load.find()) {
                String name = load.group(1);
                int before = Tokens.skipBack(code, load.start() - 1);
                if (before >= 0 && Tokens.isWordChar(code.charAt(before))) {
                    int wordStart = before;
                    while (wordStart > 0 && Tokens.isWordChar(code.charAt(wordStart - 1))) {
                        wordStart--;
                    }
                    if (!BEFORE_A_CALL.contains(code.substring(wordStart, before + 1))) {
                        declared.add(name); // this class declares its own method of that name
                        continue;
                    }
                }
                calls.add(new int[] {load.start(), load.end(), name.length()});
            }
            for (int[] call : calls) {
                if (!declared.contains(code.substring(call[0], call[0] + call[2]))) {
                    hits.add(Rule.LOAD, call[0], call[1], code);
                }
            }
        }
    }

    /**
     * The JDK's home in a variable reference: ${JDK_HOME}, $(JAVA_HOME), %JAVA_HOME%, ${java.home}, $JAVA_HOME,
     * $env:JAVA_HOME, $ENV{JAVA_HOME} (CMake, perl), ${{ env.JAVA_HOME }} (GitHub Actions).
     */
    private static final String JDK_VARIABLE = "(?:\\$\\{\\{\\s*|\\$\\{|\\$\\(|\\$ENV\\{|\\$env:|\\$|%)(?:env\\.)?"
            + "['\"]?[\\w.-]*(?i:java|jdk|jvm)[\\w.-]*['\"]?(?:\\s*\\}\\}|[})%])?\"?";

    /** A JDK directory written out: /usr/lib/jvm/<name>, <...>/Contents/Home, <...>/jdk-27+35. */
    private static final String JDK_LITERAL =
            "(?:[/\\\\]jvm[/\\\\][^\\s\"'/\\\\]+|[/\\\\]Contents[/\\\\]Home|(?i:jdk)[-\\w.+]*)";

    /** Ends at the JDK's home, so that the /include that follows it is the JDK's include directory. */
    private static final Pattern JDK_HOME_BEFORE = Pattern.compile("(?:" + JDK_VARIABLE + "|" + JDK_LITERAL
            + ")[/\\\\]+$");

    /** The directory of jni_md.h for each platform, right after an include directory. */
    private static final Pattern PLATFORM_AFTER =
            Pattern.compile("[/\\\\]+(?:win32|linux|darwin|solaris|freebsd|openbsd|bsd|aix)\\b");

    private static final int JDK_HOME_WINDOW = 200;

    /**
     * R-JDKINC for an include directory under the JDK's home (a variable or a JDK path before it), or for a
     * platform's jni_md.h directory (include/win32, include/linux...) wherever it appears. It looks back from
     * each /include rather than running one pattern over the whole text, which is what keeps it fast.
     */
    private static void scanJdkIncludes(String view, HitCollector hits) {
        for (int at = view.indexOf("include"); at >= 0; at = view.indexOf("include", at + 1)) {
            int end = at + "include".length();
            if (at == 0 || (view.charAt(at - 1) != '/' && view.charAt(at - 1) != '\\')
                    || (end < view.length() && Tokens.isWordChar(view.charAt(end)))) {
                continue;
            }
            int lineStart = view.lastIndexOf('\n', at - 1) + 1;
            Matcher home = JDK_HOME_BEFORE.matcher(view).region(Math.max(lineStart, at - JDK_HOME_WINDOW), at);
            if (home.find()) {
                hits.add(Rule.JDKINC, home.start(), end, view);
            }
            Matcher platform = PLATFORM_AFTER.matcher(view).region(end, view.length());
            if (platform.lookingAt()) {
                hits.add(Rule.JDKINC, at - 1, platform.end(), view);
            }
        }
    }

    /** Collects a file's hits, one per rule and position, with their line and source line. */
    private static final class HitCollector {
        private final String path;
        private final String text;
        private final List<Hit> hits = new ArrayList<>();
        private List<Integer> lineStarts;

        HitCollector(String path, String text) {
            this.path = path;
            this.text = text;
        }

        void add(Rule rule, int start, int end, String view) {
            if (lineStarts == null) {
                lineStarts = new ArrayList<>();
                lineStarts.add(0);
                for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
                    lineStarts.add(i + 1);
                }
            }
            int line = lineIndex(start);
            int lineStart = lineStarts.get(line);
            int lineEnd = line + 1 < lineStarts.size() ? lineStarts.get(line + 1) - 1 : text.length();
            if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') {
                lineEnd--;
            }
            int column = start - lineStart;
            int length = Math.max(1, Math.min(end, lineEnd) - start);
            for (Hit hit : hits) {
                if (hit.rule() == rule && hit.line() == line + 1 && column < hit.column() + hit.length()
                        && hit.column() < column + length) {
                    return;
                }
            }
            String shown = view.substring(start, end).replaceAll("\\s+", " ").strip();
            if (shown.length() > MAX_SHOWN_TEXT) {
                shown = shown.substring(0, MAX_SHOWN_TEXT) + "...";
            }
            hits.add(new Hit(path, line + 1, column, length, rule, shown, text.substring(lineStart, lineEnd)));
        }

        private int lineIndex(int offset) {
            int low = 0;
            int high = lineStarts.size() - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (lineStarts.get(mid) <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low;
        }

        List<Hit> sorted() {
            hits.sort(Comparator.comparingInt(Hit::line).thenComparingInt(Hit::column));
            return hits;
        }
    }

    // ---- the pattern rules ----

    /** The text of a file that a pattern rule reads. */
    private enum Scope {
        /** Java code, without comments, strings or characters; and the template text of a StringTemplate group. */
        JAVA_CODE,
        /** The comment-free text of a generator language (perl, python, ruby, shell, CMake): the Java it writes. */
        GENERATED_JAVA,
        /** The comment-free text of any file; of a Java file, its string literals. */
        TEXT,
        /** The comment-free text of a CMake file. */
        CMAKE
    }

    /** The languages whose string literals and heredocs write other files' text. */
    private static final Set<Syntax> GENERATORS =
            EnumSet.of(Syntax.PERL, Syntax.PYTHON, Syntax.RUBY, Syntax.SHELL, Syntax.CMAKE);

    private record PatternRule(Rule rule, Pattern pattern, Scope scope, boolean ignoreCase, List<String> triggers) {
        boolean triggeredBy(String view) {
            for (String trigger : triggers) {
                if (ignoreCase ? containsIgnoreCase(view, trigger) : view.contains(trigger)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        char lower = Character.toLowerCase(needle.charAt(0));
        char upper = Character.toUpperCase(needle.charAt(0));
        for (int i = 0; i <= text.length() - needle.length(); i++) {
            char c = text.charAt(i);
            if ((c == lower || c == upper) && text.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    private static PatternRule javaCode(Rule rule, String regex, String... triggers) {
        return new PatternRule(rule, Pattern.compile(regex), Scope.JAVA_CODE, false, List.of(triggers));
    }

    private static PatternRule generatedJava(Rule rule, String regex, String... triggers) {
        return new PatternRule(rule, Pattern.compile(regex), Scope.GENERATED_JAVA, false, List.of(triggers));
    }

    private static PatternRule anyFile(Rule rule, String regex, String... triggers) {
        return new PatternRule(rule, Pattern.compile(regex), Scope.TEXT, false, List.of(triggers));
    }

    /** A rule whose triggers are matched in any case; its regex says itself which parts ignore case. */
    private static PatternRule anyFileAnyCase(Rule rule, String regex, String... triggers) {
        return new PatternRule(rule, Pattern.compile(regex), Scope.TEXT, true, List.of(triggers));
    }

    private static PatternRule cmake(Rule rule, String regex, String... triggers) {
        return new PatternRule(rule, Pattern.compile(regex), Scope.CMAKE, false, List.of(triggers));
    }

    /** Java modifiers, in the shapes below that recognise a declaration a generator writes. */
    private static final String MODIFIERS = "(?:(?:public|protected|private|static|final|synchronized|abstract)\\s+)";

    /**
     * A Java type as a generator writes it: a primitive, a capitalised (qualified, generic) name, or a
     * placeholder that becomes one ($type, ${type}, $type$, &lt;type&gt;). Lower-case words are not types,
     * which keeps prose such as "native library failed to load (code 5)" out.
     */
    private static final String GENERATED_TYPE = "(?:void|boolean|byte|char|short|int|long|float|double"
            + "|[A-Z][\\w$.]*(?:<[^<>;()]*>)?|\\$\\{?\\w+\\}?\\$?|<\\w+>)(?:\\s*\\[\\s*\\])*";

    /** jni.h, jni_md.h and the JDK headers that include them; jvmticmlr.h and classfile_constants.h do not. */
    private static final String JNI_HEADERS =
            "jni|jni_md|jawt|jawt_md|jvmti|jdwpTransport|AccessBridgeCallbacks|AccessBridgeCalls|AccessBridgePackages";
    /** A method name as a generator writes it: an identifier, $name$, or a ${name} placeholder. */
    private static final String NAME = "(?:[\\w$]+\\$?|\\$\\{\\w+\\})";

    /** The Java primitive types and void. */
    private static final String PRIMITIVE = "(?:void|boolean|byte|char|short|int|long|float|double)";


    private static final List<PatternRule> PATTERN_RULES = List.of(
            // native is a reserved word, so in Java code (no comments, strings or characters) it is the modifier
            javaCode(Rule.NATIVE, "\\bnative\\b", "native"),
            javaCode(Rule.NATIVE, "@\\s*(?:java\\s*\\.\\s*lang\\s*\\.\\s*annotation\\s*\\.\\s*)?Native\\b", "Native"),
            javaCode(Rule.NATIVE, "\\bjava\\s*\\.\\s*lang\\s*\\.\\s*annotation\\s*\\.\\s*Native\\b", "Native"),
            javaCode(Rule.LOAD, "\\bSystem\\s*(?:\\.|::)\\s*load(?:Library)?\\b", "load"),
            javaCode(Rule.LOAD, "\\bRuntime\\s*(?:\\.|::)\\s*load(?:Library)?\\b", "load"),
            javaCode(Rule.LOAD, "\\bgetRuntime\\s*\\(\\s*\\)\\s*(?:\\.|::)\\s*load(?:Library)?\\b", "load"),
            // the Java a generator writes: a native method or an @Native constant declared, System.load* called
            generatedJava(Rule.NATIVE, "\\b" + MODIFIERS + "+native\\s+" + MODIFIERS + "*(?:[^\\s(;\"']+\\s+" + NAME
                    + "\\s*\\(|[\"'])|\\bnative\\s+" + MODIFIERS + "*" + GENERATED_TYPE + "\\s+" + NAME + "\\s*\\("
                    + "|\\bnative\\s+(?:" + MODIFIERS + "+(?:" + GENERATED_TYPE + "\\s+)?|" + MODIFIERS + "*"
                    + PRIMITIVE + "\\s+)[\"']", "native"),
            generatedJava(Rule.NATIVE, "@\\s*(?:java\\s*\\.\\s*lang\\s*\\.\\s*annotation\\s*\\.\\s*)?Native\\s+"
                    + MODIFIERS + "*" + GENERATED_TYPE + "\\s+(?:[\\w${}]+\\$?\\s*=|[\"'])", "Native"),
            generatedJava(Rule.NATIVE, "\\bimport\\s+java\\s*\\.\\s*lang\\s*\\.\\s*annotation\\s*\\.\\s*Native\\s*;",
                    "Native"),
            generatedJava(Rule.LOAD, "\\b(?:System|getRuntime\\s*\\(\\s*\\))\\s*\\.\\s*load(?:Library)?\\s*\\(",
                    "load"),
            anyFileAnyCase(Rule.INCLUDE, "(?:#[ \\t]*(?:include|import|include_next)[ \\t]*[<\"][^<>\"\\n]*?)?"
                    + "(?<![\\w.-])(?i:(?:" + JNI_HEADERS + ")\\.h)\\b",
                    "jni", "jawt", "jvmti", "jdwp", "AccessBridge"),
            anyFile(Rule.JDKINC, "(?i:\\bfind_package)\\s*\\(\\s*JNI\\b|\\bFindJNI\\b", "JNI"),
            anyFile(Rule.JDKINC, "\\b(?:JAVA|JNI|JDK|JVM)_\\w*INCLUDE\\w*", "INCLUDE"),
            // javac (or JAVAC, javac.exe, Java_JAVAC_EXECUTABLE) followed by -h on the same logical line: lines
            // continued with \ (shells, Makefiles), ^ (cmd) or ` (PowerShell) are one line
            anyFile(Rule.JAVAC_H, "(?i:javac)(?:[\\\\^`]\\r?\\n|[^\\n])*?(?<![\\w-])-h(?![\\w-])", "-h"),
            // in CMake, javac and -h anywhere within the parentheses of one command
            cmake(Rule.JAVAC_H, "(?i:javac)[^()]*?(?<![\\w-])-h(?![\\w-])", "-h"),
            // <arg>-h</arg>, <compilerArg>-h</compilerArg>, <compilerArgument>-h dir</compilerArgument> in a pom or
            // Ant file, also with the -h on a line of its own
            anyFile(Rule.JAVAC_H, "<(\\w*[Aa]rg\\w*)\\b[^>]*>\\s*-h(?:\\s[^<]*)?</\\1\\s*>", "-h"),
            // <compilerarg value="-h"/>, <compilerarg value='-h'/>, <arg line="... -h ..."/>
            anyFile(Rule.JAVAC_H, "<\\w*[Aa]rg\\w*\\b[^>]*?\\b(?:value|line)\\s*=\\s*"
                    + "([\"'])(?:[^\"'>]*?\\s)?-h(?:\\s[^\"'>]*)?\\1", "-h"),
            // maven-compiler-plugin's map form, <compilerArguments><h>dir</h></compilerArguments>
            anyFile(Rule.JAVAC_H, "(?s)<compilerArguments\\b[^>]*>(?:(?!</compilerArguments\\s*>).)*?<h\\s*>",
                    "compilerArguments"),
            // Ant's <javac nativeheaderdir="..."> (Ant attribute names ignore case) and Gradle's header directory
            anyFileAnyCase(Rule.JAVAC_H, "(?i:\\bnativeheaderdir)\\s*=|\\bheaderOutputDirectory\\b",
                    "nativeheaderdir", "headerOutputDirectory"),
            anyFile(Rule.JAVAC_H, "\\bjavah\\b|\\bGENERATE_NATIVE_HEADERS\\b|\\bcreate_javah\\b",
                    "javah", "GENERATE_NATIVE_HEADERS", "create_javah"));

    // ---- JNI tokens ----

    /** The JNI names, matched as whole identifiers in comment-free text. */
    static final class Tokens {
        private static final List<String> PRIMITIVES =
                List.of("Boolean", "Byte", "Char", "Short", "Int", "Long", "Float", "Double");

        /** The types of jni.h and jni_md.h, with their struct tags. */
        static final Set<String> TYPES = typeNames();

        /**
         * The JNI functions whose names are JNI's own, found wherever they appear. The function table also has
         * names that other code uses for something else - WebKit has its own NewObject, Throw and GetVersion, and
         * JavaScriptCore an ExitKind named ExceptionCheck - so those count only when called through a JNIEnv or
         * JavaVM pointer; see {@link #CALL_ONLY}.
         */
        static final Set<String> FUNCTIONS = functionNames();

        static final Set<String> CALL_ONLY = Set.of("GetVersion", "DefineClass", "GetSuperclass",
                "IsAssignableFrom", "Throw", "FatalError", "IsSameObject", "AllocObject", "NewObject", "NewObjectA",
                "NewObjectV", "GetObjectClass", "IsInstanceOf", "NewString", "GetStringLength", "GetArrayLength",
                "MonitorEnter", "MonitorExit", "GetModule", "IsVirtualThread", "ExceptionCheck");

        private static final Set<String> INVOCATION = Set.of("GetEnv", "AttachCurrentThread",
                "AttachCurrentThreadAsDaemon", "DetachCurrentThread", "DestroyJavaVM");

        /**
         * The JNI_* names of jni.h, and JNI_VERSION_* below. Not every JNI_ prefix: the fork's tests have Java
         * constants such as MTLGoldens.JNI_EXPORTS and MacFontGoldens.JNI_LIBRARY that name the JNI-era builds they
         * read, which US-074 leaves alone.
         */
        private static final Set<String> JNI_NAMES = Set.of("JNI_FALSE", "JNI_TRUE", "JNI_OK", "JNI_ERR",
                "JNI_EDETACHED", "JNI_EVERSION", "JNI_ENOMEM", "JNI_EEXIST", "JNI_EINVAL", "JNI_COMMIT", "JNI_ABORT",
                "JNI_GetDefaultJavaVMInitArgs", "JNI_CreateJavaVM", "JNI_GetCreatedJavaVMs");

        private Tokens() {
        }

        private static Set<String> typeNames() {
            Set<String> names = new HashSet<>(List.of("JNIEnv", "JNIEnv_", "JavaVM", "JavaVM_", "JNINativeMethod",
                    "JNINativeInterface_", "JNIInvokeInterface_", "JavaVMInitArgs", "JavaVMOption",
                    "JavaVMAttachArgs", "JNIIMPORT", "jobject", "jclass", "jstring", "jthrowable", "jweak", "jarray",
                    "jobjectArray", "jvalue", "jfieldID", "jmethodID", "jobjectRefType", "jint", "jlong",
                    "jboolean", "jbyte", "jchar", "jshort", "jfloat", "jdouble", "jsize", "JNIInvalidRefType",
                    "JNILocalRefType", "JNIGlobalRefType", "JNIWeakGlobalRefType"));
            for (String tag : List.of("object", "class", "string", "throwable", "array", "objectArray",
                    "fieldID", "methodID")) {
                names.add("_j" + tag);
            }
            for (String primitive : PRIMITIVES) {
                String array = "j" + primitive.toLowerCase(Locale.ROOT) + "Array";
                names.add(array);
                names.add("_" + array);
            }
            return Set.copyOf(names);
        }

        private static Set<String> functionNames() {
            Set<String> names = new HashSet<>(List.of("FindClass", "FromReflectedMethod", "FromReflectedField",
                    "ToReflectedMethod", "ToReflectedField", "ThrowNew", "ExceptionOccurred", "ExceptionDescribe",
                    "ExceptionClear", "PushLocalFrame", "PopLocalFrame", "NewGlobalRef",
                    "DeleteGlobalRef", "NewLocalRef", "DeleteLocalRef", "EnsureLocalCapacity", "NewWeakGlobalRef",
                    "DeleteWeakGlobalRef", "GetMethodID", "GetStaticMethodID", "GetFieldID", "GetStaticFieldID",
                    "NewStringUTF", "GetStringUTFChars", "ReleaseStringUTFChars", "GetStringUTFLength",
                    "GetStringUTFLengthAsLong", "GetStringUTFRegion", "GetStringChars", "ReleaseStringChars",
                    "GetStringRegion", "GetStringCritical", "ReleaseStringCritical", "GetPrimitiveArrayCritical",
                    "ReleasePrimitiveArrayCritical", "NewObjectArray", "GetObjectArrayElement",
                    "SetObjectArrayElement", "RegisterNatives", "UnregisterNatives", "GetJavaVM", "GetEnv",
                    "AttachCurrentThread", "AttachCurrentThreadAsDaemon", "DetachCurrentThread", "DestroyJavaVM",
                    "NewDirectByteBuffer", "GetDirectBufferAddress", "GetDirectBufferCapacity",
                    "GetObjectRefType"));
            List<String> callTypes = new ArrayList<>(List.of("Void", "Object"));
            callTypes.addAll(PRIMITIVES);
            for (String type : callTypes) {
                for (String kind : List.of("", "Static", "Nonvirtual")) {
                    for (String suffix : List.of("", "A", "V")) {
                        names.add("Call" + kind + type + "Method" + suffix);
                    }
                }
            }
            List<String> fieldTypes = new ArrayList<>(List.of("Object"));
            fieldTypes.addAll(PRIMITIVES);
            for (String type : fieldTypes) {
                for (String access : List.of("Get", "Set", "GetStatic", "SetStatic")) {
                    names.add(access + type + "Field");
                }
            }
            for (String primitive : PRIMITIVES) {
                names.add("New" + primitive + "Array");
                names.add("Get" + primitive + "ArrayElements");
                names.add("Release" + primitive + "ArrayElements");
                names.add("Get" + primitive + "ArrayRegion");
                names.add("Set" + primitive + "ArrayRegion");
            }
            return Set.copyOf(names);
        }

        /** The first letters of every name above, to skip all other identifiers without a lookup. */
        private static final String INITIALS = "jJ_ACDEFGNPRSTU";

        /** The rule an identifier breaks, or null. */
        static Rule classify(String identifier) {
            // macOS export lists spell C symbols with a leading underscore (_Java_..., _JNI_OnLoad)
            int underscores = 0;
            while (underscores < identifier.length() && identifier.charAt(underscores) == '_') {
                underscores++;
            }
            String symbol = identifier.substring(underscores);
            if ((symbol.startsWith("Java_") && !isFindJavaVariable(symbol)) || symbol.equals("JNIEXPORT")
                    || symbol.equals("JNICALL") || symbol.startsWith("JNI_OnLoad")
                    || symbol.startsWith("JNI_OnUnload")) {
                return Rule.EXPORT;
            }
            if (TYPES.contains(identifier) || FUNCTIONS.contains(identifier) || JNI_NAMES.contains(identifier)
                    || identifier.startsWith("JNI_VERSION_")) {
                return Rule.JNI;
            }
            return null;
        }

        /**
         * Java_JAVAC_EXECUTABLE, Java_JAR_EXECUTABLE, Java_VERSION...: the variables of CMake's FindJava, in
         * capitals after the prefix. A JNI symbol mangles a package or class name, which has lower case.
         */
        private static boolean isFindJavaVariable(String symbol) {
            for (int i = "Java_".length(); i < symbol.length(); i++) {
                if (Character.isLowerCase(symbol.charAt(i))) {
                    return false;
                }
            }
            return symbol.length() > "Java_".length();
        }

        static void scan(String text, HitCollector hits) {
            int n = text.length();
            int i = 0;
            while (i < n) {
                if (!isWordChar(text.charAt(i))) {
                    i++;
                    continue;
                }
                int start = i;
                while (i < n && isWordChar(text.charAt(i))) {
                    i++;
                }
                // in a generator string, "\njint" is a line break and jint
                int from = start > 0 && text.charAt(start - 1) == '\\' && "ntrfvb".indexOf(text.charAt(start)) >= 0
                        ? start + 1 : start;
                for (int at = start; at <= from; at += Math.max(1, from - start)) {
                    if (i - at >= 4 && INITIALS.indexOf(text.charAt(at)) >= 0) {
                        Rule rule = classify(text.substring(at, i));
                        if (rule != null) {
                            hits.add(rule, at, i, text);
                            break;
                        }
                    }
                }
            }
        }

        /**
         * Calls through a JNIEnv or JavaVM pointer, which it recognises by name (env, jenv, m_env, jvm, g_vm...):
         * {@code (*env)->X} with any X, which is how C calls through the JNIEnv function table, and
         * {@code env->X(} or {@code vm->X(} with X one of the JNI functions whose names are not JNI's own.
         * Any other member call on something named env is not JNI: JavaScriptCore's CellSize.h has
         * {@code env->symbolTable()} on a JSLexicalEnvironment.
         */
        static void scanEnvCalls(String text, HitCollector hits) {
            int n = text.length();
            for (int arrow = text.indexOf("->"); arrow >= 0; arrow = text.indexOf("->", arrow + 2)) {
                int receiverEnd = skipBack(text, arrow - 1);
                boolean cStyle = receiverEnd >= 0 && text.charAt(receiverEnd) == ')';
                if (cStyle) {
                    receiverEnd = skipBack(text, receiverEnd - 1);
                }
                int receiverStart = receiverEnd + 1;
                while (receiverStart > 0 && isWordChar(text.charAt(receiverStart - 1))) {
                    receiverStart--;
                }
                if (receiverStart > receiverEnd) {
                    continue;
                }
                boolean env = endsWithName(text, receiverStart, receiverEnd + 1, "env");
                boolean vm = endsWithName(text, receiverStart, receiverEnd + 1, "vm");
                if (!env && !vm) {
                    continue;
                }
                int matchStart = receiverStart;
                if (cStyle) {
                    int star = skipBack(text, receiverStart - 1);
                    int paren = star >= 0 && text.charAt(star) == '*' ? skipBack(text, star - 1) : -1;
                    if (paren < 0 || text.charAt(paren) != '(') {
                        continue;
                    }
                    matchStart = paren;
                }
                int functionStart = skipForward(text, arrow + 2);
                int functionEnd = functionStart;
                while (functionEnd < n && isWordChar(text.charAt(functionEnd))) {
                    functionEnd++;
                }
                String function = text.substring(functionStart, functionEnd);
                if (function.isEmpty() || FUNCTIONS.contains(function)) {
                    continue; // nothing called, or a JNI function the identifier scan reports by its name
                }
                int after = skipForward(text, functionEnd);
                boolean call = after < n && text.charAt(after) == '(';
                if ((cStyle && env) || (call && env && CALL_ONLY.contains(function))
                        || (call && vm && INVOCATION.contains(function))) {
                    hits.add(Rule.JNI, matchStart, functionEnd, text);
                }
            }
        }

        private static int skipBack(String text, int from) {
            int i = from;
            while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
                i--;
            }
            return i;
        }

        private static int skipForward(String text, int from) {
            int i = from;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return i;
        }

        /** Whether text[start, end), without trailing underscores, ends with suffix in any case. */
        private static boolean endsWithName(String text, int start, int end, String suffix) {
            int last = end;
            while (last > start && text.charAt(last - 1) == '_') {
                last--;
            }
            return last - start >= suffix.length()
                    && text.regionMatches(true, last - suffix.length(), suffix, 0, suffix.length());
        }

        static boolean isWordChar(char c) {
            return c == '_' || (c < 128 && Character.isLetterOrDigit(c));
        }
    }

    // ---- file syntax ----

    private static final Set<String> PROSE_NAMES = Set.of("readme", "license", "licence", "copying", "copyright",
            "authors", "news", "todo", "install", "changes", "changelog", "thanks", "notice", "patents",
            "additional_license_info", "assembly_exception", "features", "contributors", "maintainers");

    private static final Pattern MODULE_DEFINITION =
            Pattern.compile("(?im)^\\s*(?:EXPORTS|LIBRARY|NAME|DESCRIPTION)\\b");

    static Syntax classify(String path, String text) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("cmakelists.txt")) {
            return Syntax.CMAKE;
        }
        if (lower.startsWith("makefile") || lower.equals("gnumakefile")) {
            return Syntax.MAKE;
        }
        if (lower.startsWith("mapfile")) {
            return Syntax.EXPORT_LIST;
        }
        if (lower.endsWith(".in") && lower.length() > 3) {
            // a template: read it in the syntax of the file it becomes (config.h.in, Foo.cmake.in)
            return classify(path.substring(0, path.length() - 3), text);
        }
        if (lower.endsWith(".cmake") && lower.lastIndexOf('.', lower.length() - 7) >= 0) {
            // a configure_file template such as config.h.cmake: read it as the file it becomes
            Syntax inner = classify(path.substring(0, path.length() - ".cmake".length()), text);
            if (inner != Syntax.RAW && inner != Syntax.PROSE) {
                return inner;
            }
        }
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return switch (extension) {
            case "java" -> Syntax.JAVA;
            case "c", "h", "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "inl", "tcc", "inc", "m",
                 "mm" -> Syntax.C;
            case "js", "mjs", "cjs", "ts" -> Syntax.JS;
            case "swift", "idl", "jsl", "hlsl", "metal", "frag", "vert", "glsl", "g4", "stg", "css", "xcconfig",
                 "gradle", "kt", "kts", "groovy", "scala", "cs", "proto" -> Syntax.C;
            case "pl", "pm", "t", "cgi", "perl" -> Syntax.PERL;
            case "sh", "bash", "ksh", "zsh", "ps1", "psm1" -> Syntax.SHELL;
            case "py", "pyw", "pyi" -> Syntax.PYTHON;
            case "rb", "rake", "gemspec" -> Syntax.RUBY;
            case "cmake" -> Syntax.CMAKE;
            case "yml", "yaml" -> Syntax.YAML;
            case "properties", "prefs" -> Syntax.PROPERTIES;
            case "mk", "make", "mak" -> Syntax.MAKE;
            case "xml", "pom", "xsd", "xsl", "xslt", "fxml", "vcxproj", "vcproj", "props", "targets", "filters",
                 "svg", "plist", "wxs", "wxi", "resx", "project", "classpath", "xhtml", "jnlp" -> Syntax.XML;
            // a Windows module definition (EXPORTS, ; comments), or a C X-macro file such as libpas's *.def
            case "def" -> MODULE_DEFINITION.matcher(text).find() ? Syntax.DEF : Syntax.C;
            case "map", "exp", "sym", "syms", "symlst", "lds", "ver", "version", "exports" -> Syntax.EXPORT_LIST;
            case "cmd", "bat" -> Syntax.BATCH;
            case "md", "markdown", "txt", "html", "htm", "rst", "adoc", "asciidoc" -> Syntax.PROSE;
            default -> classifyUnknown(lower, text);
        };
    }

    private static Syntax classifyUnknown(String lowerName, String text) {
        String base = lowerName.split("[.\\-]", 2)[0];
        if (PROSE_NAMES.contains(base)) {
            return Syntax.PROSE;
        }
        if (text.startsWith("#!")) {
            int end = text.indexOf('\n');
            String interpreter = end < 0 ? text : text.substring(0, end);
            if (interpreter.contains("perl")) {
                return Syntax.PERL;
            }
            if (interpreter.contains("python")) {
                return Syntax.PYTHON;
            }
            if (interpreter.contains("ruby")) {
                return Syntax.RUBY;
            }
            if (interpreter.matches("#!.*\\b(?:ba|da|k|z)?sh\\b.*")) {
                return Syntax.SHELL;
            }
        }
        return Syntax.RAW;
    }

    // ---- comment stripping ----

    /**
     * Blanks comments (to spaces, line breaks kept, so offsets and line numbers stay put). For Java, also blanks
     * string, character and text block literals in a second view, for the rules that look at code only.
     */
    static final class Lexers {
        private static final Pattern PERL_HEREDOC =
                Pattern.compile("<<(~?)(?:[ \\t]*\"([^\"\\n]*)\"|[ \\t]*'([^'\\n]*)'|([A-Za-z_]\\w*))");
        private static final Pattern SHELL_HEREDOC =
                Pattern.compile("<<(-?)[ \\t]*(?:\"([^\"\\n]*)\"|'([^'\\n]*)'|\\\\?([A-Za-z_]\\w*))");
        private static final Pattern RUBY_HEREDOC =
                Pattern.compile("<<([~-]?)(?:\"([^\"\\n]*)\"|'([^'\\n]*)'|([A-Z_][A-Z0-9_]*))");
        private static final Set<String> PERL_QUOTE_OPERATORS = Set.of("q", "qq", "qw", "qr", "m", "s", "tr", "y");
        private static final Set<String> PERL_REGEX_AFTER = Set.of("split", "grep", "map", "and", "or", "not", "if",
                "unless", "while", "until", "return", "push", "unshift", "join", "when");

        private Lexers() {
        }

        static void strip(String s, Syntax syntax, char[] noComments, char[] codeOnly) {
            switch (syntax) {
                case JAVA, C, JS -> cFamily(s, syntax, noComments, codeOnly);
                case PERL -> perl(s, noComments);
                case SHELL -> shell(s, noComments);
                case PYTHON -> python(s, noComments);
                case RUBY -> ruby(s, noComments);
                case CMAKE -> cmake(s, noComments);
                case YAML, MAKE -> {
                    hashPerLine(s, noComments, syntax == Syntax.MAKE);
                    keepHeredocBodies(s, noComments);
                }
                case PROPERTIES -> properties(s, noComments);
                case XML -> xml(s, noComments);
                case DEF -> lineComments(s, noComments, ";");
                case EXPORT_LIST -> exportList(s, noComments);
                case BATCH -> batch(s, noComments);
                case RAW, PROSE -> {
                }
            }
        }

        private static void blank(char[] view, int from, int to) {
            if (view == null) {
                return;
            }
            for (int i = from; i < to && i < view.length; i++) {
                if (view[i] != '\n' && view[i] != '\r') {
                    view[i] = ' ';
                }
            }
        }

        private static int lineEnd(String s, int from) {
            int end = s.indexOf('\n', from);
            return end < 0 ? s.length() : end;
        }

        private static int nextLine(String s, int from) {
            int end = s.indexOf('\n', from);
            return end < 0 ? s.length() : end + 1;
        }

        /** The end of a literal opened before from: past its closing quote, or at the line end if stopAtEol. */
        private static int quotedEnd(String s, int from, char quote, boolean stopAtEol) {
            int n = s.length();
            int j = from;
            while (j < n) {
                char c = s.charAt(j);
                if (c == '\\') {
                    j += 2;
                } else if (c == quote) {
                    return j + 1;
                } else if (c == '\n' && stopAtEol) {
                    return j;
                } else {
                    j++;
                }
            }
            return n;
        }

        /** The end of a delimited literal that may span lines and nest when open and close differ. */
        private static int delimitedEnd(String s, int from, char open, char close) {
            int n = s.length();
            int depth = 1;
            int j = from;
            while (j < n) {
                char c = s.charAt(j);
                if (c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return j + 1;
                    }
                } else if (c == open && open != close) {
                    depth++;
                }
                j++;
            }
            return n;
        }

        private static boolean isWord(char c) {
            return c == '_' || (c < 128 && Character.isLetterOrDigit(c));
        }

        // C, C++, Objective-C, Java, JavaScript and the other // and /* */ languages

        private static void cFamily(String s, Syntax syntax, char[] noComments, char[] codeOnly) {
            int n = s.length();
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                int end = -1;
                if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    end = cLineCommentEnd(s, i, syntax == Syntax.C);
                    blank(noComments, i, end);
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    int close = s.indexOf("*/", i + 2);
                    end = close < 0 ? n : close + 2;
                    blank(noComments, i, end);
                } else if (c == '"') {
                    if (syntax == Syntax.JAVA && s.startsWith("\"\"\"", i)) {
                        end = textBlockEnd(s, i + 3);
                    } else if (syntax == Syntax.C && isRawStringStart(s, i)) {
                        end = rawStringEnd(s, i);
                    } else {
                        end = quotedEnd(s, i + 1, '"', true);
                    }
                } else if (c == '\'' && !(syntax == Syntax.C && isDigitSeparator(s, i))) {
                    end = quotedEnd(s, i + 1, '\'', true);
                } else if (c == '`' && syntax == Syntax.JS) {
                    end = quotedEnd(s, i + 1, '`', false);
                } else if (c == '/' && syntax == Syntax.JS && jsRegexMayStart(s, i)) {
                    end = jsRegexEnd(s, i); // -1 when the line has no closing /: a division after all
                }
                if (end < 0) {
                    i++;
                } else {
                    blank(codeOnly, i, end);
                    i = end;
                }
            }
        }

        private static final Set<String> JS_REGEX_AFTER = Set.of("return", "typeof", "case", "do", "else", "in",
                "of", "void", "yield", "await", "delete", "throw", "new");

        /** Whether a / at slash, which starts no comment, starts a JavaScript regular expression literal. */
        private static boolean jsRegexMayStart(String s, int slash) {
            if (slash + 1 < s.length() && (s.charAt(slash + 1) == '/' || s.charAt(slash + 1) == '*')) {
                return false;
            }
            int k = slash - 1;
            while (k >= 0 && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                k--;
            }
            if (k < 0 || s.charAt(k) == '\n' || "(,=:[!&|?{};~+-*%<>^".indexOf(s.charAt(k)) >= 0) {
                return true;
            }
            int start = k;
            while (start > 0 && isWord(s.charAt(start - 1))) {
                start--;
            }
            return isWord(s.charAt(k)) && JS_REGEX_AFTER.contains(s.substring(start, k + 1));
        }

        /** The end of the regular expression literal at slash, with its flags, or -1 if the line does not close it. */
        private static int jsRegexEnd(String s, int slash) {
            boolean inClass = false;
            for (int j = slash + 1; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '\\') {
                    j++;
                } else if (c == '\n') {
                    return -1;
                } else if (c == '[') {
                    inClass = true;
                } else if (c == ']') {
                    inClass = false;
                } else if (c == '/' && !inClass) {
                    int end = j + 1;
                    while (end < s.length() && Character.isLetter(s.charAt(end))) {
                        end++;
                    }
                    return end;
                }
            }
            return -1;
        }

        /** A // comment ends at the line end; in C a backslash at the line end continues it. */
        private static int cLineCommentEnd(String s, int from, boolean continues) {
            int end = lineEnd(s, from);
            while (continues && end < s.length()) {
                int last = end - 1;
                if (last >= 0 && s.charAt(last) == '\r') {
                    last--;
                }
                if (last < from || s.charAt(last) != '\\') {
                    break;
                }
                end = lineEnd(s, end + 1);
            }
            return end;
        }

        private static int textBlockEnd(String s, int from) {
            int n = s.length();
            int j = from;
            while (j < n) {
                if (s.charAt(j) == '\\') {
                    j += 2;
                } else if (s.startsWith("\"\"\"", j)) {
                    return j + 3;
                } else {
                    j++;
                }
            }
            return n;
        }

        /** A C++ raw string: R"delim( ... )delim", also with the L, u, U and u8 prefixes. */
        private static boolean isRawStringStart(String s, int quote) {
            int k = quote;
            while (k > 0 && isWord(s.charAt(k - 1))) {
                k--;
            }
            String prefix = s.substring(k, quote);
            if (!Set.of("R", "LR", "uR", "UR", "u8R").contains(prefix)) {
                return false;
            }
            for (int j = quote + 1; j < s.length() && j <= quote + 17; j++) {
                char c = s.charAt(j);
                if (c == '(') {
                    return true;
                }
                if (c == ' ' || c == '\\' || c == ')' || c == '"' || c == '\n') {
                    return false;
                }
            }
            return false;
        }

        private static int rawStringEnd(String s, int quote) {
            int paren = s.indexOf('(', quote);
            String close = ")" + s.substring(quote + 1, paren) + "\"";
            int end = s.indexOf(close, paren + 1);
            return end < 0 ? s.length() : end + close.length();
        }

        /** The ' of a C++14 number such as 1'000'000, which opens no character literal. */
        private static boolean isDigitSeparator(String s, int quote) {
            int k = quote;
            while (k > 0 && (isWord(s.charAt(k - 1)) || s.charAt(k - 1) == '\'' || s.charAt(k - 1) == '.')) {
                k--;
            }
            return k < quote && Character.isDigit(s.charAt(k)) && quote + 1 < s.length()
                    && Character.digit(s.charAt(quote + 1), 16) >= 0;
        }

        // perl: # comments, POD blocks; strings, quote-like operators, regexes and heredocs are not comments

        private static void perl(String s, char[] noComments) {
            int n = s.length();
            Deque<String[]> heredocs = new ArrayDeque<>();
            boolean lineStart = true;
            int i = 0;
            while (i < n) {
                if (lineStart) {
                    lineStart = false;
                    if (!heredocs.isEmpty()) {
                        i = heredocBodiesEnd(s, i, heredocs);
                        lineStart = true;
                        continue;
                    }
                    if (s.charAt(i) == '=' && i + 1 < n && Character.isLetter(s.charAt(i + 1))) {
                        int end = blockEnd(s, i, "=cut");
                        blank(noComments, i, end);
                        i = end;
                        lineStart = true;
                        continue;
                    }
                    if (s.startsWith("__END__", i) || s.startsWith("__DATA__", i)) {
                        return; // a data section is read as it is
                    }
                }
                char c = s.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                    i++;
                } else if (c == '#') {
                    if (i > 0 && s.charAt(i - 1) == '$') {
                        i++; // $#array
                    } else {
                        i = hashComment(s, i, noComments);
                    }
                } else if (c == '\\') {
                    i += 2;
                } else if (c == '"' || c == '\'' || c == '`') {
                    i = delimitedEnd(s, i + 1, c, c);
                } else if (c == '<' && s.startsWith("<<", i)) {
                    i = heredocStart(s, i, PERL_HEREDOC, heredocs);
                } else if (c == '/' && perlRegexMayStart(s, i)) {
                    i = delimitedEnd(s, i + 1, '/', '/');
                } else if (isWord(c)) {
                    int end = i;
                    while (end < n && isWord(s.charAt(end))) {
                        end++;
                    }
                    // $s, @s, %s, &s, ->s and the file test -s are not the s/// operator
                    boolean variable = i > 0 && "$@%&-".indexOf(s.charAt(i - 1)) >= 0
                            || i > 1 && s.charAt(i - 1) == '>' && s.charAt(i - 2) == '-';
                    int quoted = !variable && PERL_QUOTE_OPERATORS.contains(s.substring(i, end))
                            ? perlQuoteLikeEnd(s, end, s.substring(i, end)) : -1;
                    i = quoted >= 0 ? quoted : end;
                } else {
                    i++;
                }
            }
        }

        /**
         * Blanks the # comment that starts at hash and returns where scanning continues: the line end, either way.
         * A comment after code is kept when an odd number of " or ' precede it on its line: then the # is most
         * likely inside a string the lexer lost track of. Keeping is the safe side - a kept comment can only add a
         * hit, a blanked string of a generator can hide one. The kept text is not lexed: an apostrophe in it
         * (it's) would open a string that does not exist and put every later line out of step.
         */
        private static int hashComment(String s, int hash, char[] noComments) {
            int lineStart = s.lastIndexOf('\n', hash - 1) + 1;
            int doubles = 0;
            int singles = 0;
            for (int i = lineStart; i < hash; i++) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    doubles++;
                } else if (c == '\'') {
                    singles++;
                }
            }
            int end = lineEnd(s, hash);
            if (doubles % 2 == 0 && singles % 2 == 0) {
                blank(noComments, hash, end);
            }
            return end;
        }

        /** Registers the heredoc introduced at from, if any, and returns where scanning continues. */
        private static int heredocStart(String s, int from, Pattern introducer, Deque<String[]> heredocs) {
            Matcher matcher = introducer.matcher(s).region(from, s.length());
            if (!matcher.lookingAt()) {
                return from + 2;
            }
            String terminator = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            heredocs.add(new String[] {terminator, matcher.group(1).isEmpty() ? "" : "indented"});
            return matcher.end();
        }

        /** Skips the bodies of the pending heredocs, which start at from; their text is string content. */
        private static int heredocBodiesEnd(String s, int from, Deque<String[]> heredocs) {
            int i = from;
            while (!heredocs.isEmpty() && i < s.length()) {
                String[] heredoc = heredocs.peekFirst();
                int end = lineEnd(s, i);
                String line = s.substring(i, end);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if ((heredoc[1].isEmpty() ? line : line.strip()).equals(heredoc[0])) {
                    heredocs.removeFirst();
                }
                i = Math.min(s.length(), end + 1);
            }
            heredocs.clear();
            return i;
        }

        /** The end of the block that starts at from and ends with a line starting with terminator. */
        private static int blockEnd(String s, int from, String terminator) {
            int i = nextLine(s, from);
            while (i < s.length()) {
                if (s.startsWith(terminator, i)) {
                    return lineEnd(s, i);
                }
                i = nextLine(s, i);
            }
            return s.length();
        }

        private static boolean perlRegexMayStart(String s, int slash) {
            int k = slash - 1;
            while (k >= 0 && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
                k--;
            }
            if (k < 0 || s.charAt(k) == '\n') {
                return true;
            }
            char before = s.charAt(k);
            // not after } either: $h{k} / 2 is a division (a regex at the start of a line is found above)
            if ("(,=~!&|{;?:[".indexOf(before) >= 0) {
                return true;
            }
            if (!isWord(before)) {
                return false;
            }
            int start = k;
            while (start > 0 && isWord(s.charAt(start - 1))) {
                start--;
            }
            return PERL_REGEX_AFTER.contains(s.substring(start, k + 1));
        }

        /** The end of q{}, qq//, qw(), qr//, m//, s///, tr/// or y/// whose operator ends at from, or -1. */
        private static int perlQuoteLikeEnd(String s, int from, String operator) {
            int n = s.length();
            int j = from;
            while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                j++;
            }
            if (j >= n) {
                return -1;
            }
            char open = s.charAt(j);
            if (isWord(open) || Character.isWhitespace(open) || "=,;)}]>$@%".indexOf(open) >= 0
                    || (open == '#' && j > from)) {
                return -1; // a hash key, a fat comma, a variable or a comment: not a quote-like operator
            }
            char close = closingBracket(open);
            int end = delimitedEnd(s, j + 1, open, close);
            if (operator.equals("s") || operator.equals("tr") || operator.equals("y")) {
                if (open != close) {
                    int k = end;
                    while (k < n && Character.isWhitespace(s.charAt(k))) {
                        k++;
                    }
                    if (k < n) {
                        char secondOpen = s.charAt(k);
                        end = delimitedEnd(s, k + 1, secondOpen, closingBracket(secondOpen));
                    }
                } else {
                    end = delimitedEnd(s, end, open, close);
                }
            }
            while (end < n && Character.isLetter(s.charAt(end))) {
                end++;
            }
            return end;
        }

        private static char closingBracket(char open) {
            return switch (open) {
                case '(' -> ')';
                case '[' -> ']';
                case '{' -> '}';
                case '<' -> '>';
                default -> open;
            };
        }

        // shell (also PowerShell): # starts a comment at the start of a word; quotes and heredocs are content

        private static void shell(String s, char[] noComments) {
            int n = s.length();
            Deque<String[]> heredocs = new ArrayDeque<>();
            boolean lineStart = true;
            int i = 0;
            while (i < n) {
                if (lineStart) {
                    lineStart = false;
                    if (!heredocs.isEmpty()) {
                        i = heredocBodiesEnd(s, i, heredocs);
                        lineStart = true;
                        continue;
                    }
                }
                char c = s.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                    i++;
                } else if (c == '\\') {
                    i += 2;
                } else if (c == '\'') {
                    int close = s.indexOf('\'', i + 1);
                    i = close < 0 ? n : close + 1;
                } else if (c == '"' || c == '`') {
                    i = delimitedEnd(s, i + 1, c, c);
                } else if (c == '#' && (i == 0 || " \t\n;|&()<>".indexOf(s.charAt(i - 1)) >= 0)) {
                    i = hashComment(s, i, noComments);
                } else if (c == '<' && s.startsWith("<<", i) && !s.startsWith("<<<", i)) {
                    i = heredocStart(s, i, SHELL_HEREDOC, heredocs);
                } else {
                    i++;
                }
            }
        }

        // python: # comments; '...', "...", and the multi-line '''...''' and """...""" are content

        private static void python(String s, char[] noComments) {
            int n = s.length();
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '#') {
                    i = hashComment(s, i, noComments);
                } else if (c == '"' || c == '\'') {
                    String triple = String.valueOf(c).repeat(3);
                    if (s.startsWith(triple, i)) {
                        int j = i + 3;
                        while (j < n && !s.startsWith(triple, j)) {
                            j += s.charAt(j) == '\\' ? 2 : 1;
                        }
                        i = Math.min(n, j + 3);
                    } else {
                        i = quotedEnd(s, i + 1, c, true);
                    }
                } else {
                    i++;
                }
            }
        }

        // ruby: # comments and =begin/=end blocks; strings, %-literals, regexes and heredocs are content

        private static void ruby(String s, char[] noComments) {
            int n = s.length();
            Deque<String[]> heredocs = new ArrayDeque<>();
            boolean lineStart = true;
            int i = 0;
            while (i < n) {
                if (lineStart) {
                    lineStart = false;
                    if (!heredocs.isEmpty()) {
                        i = heredocBodiesEnd(s, i, heredocs);
                        lineStart = true;
                        continue;
                    }
                    if (s.startsWith("=begin", i)) {
                        int end = blockEnd(s, i, "=end");
                        blank(noComments, i, end);
                        i = end;
                        lineStart = true;
                        continue;
                    }
                    if (s.startsWith("__END__", i)) {
                        return;
                    }
                }
                char c = s.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                    i++;
                } else if (c == '#') {
                    i = hashComment(s, i, noComments);
                } else if (c == '\\') {
                    i += 2;
                } else if (c == '"' || c == '\'' || c == '`') {
                    i = delimitedEnd(s, i + 1, c, c);
                } else if (c == '<' && s.startsWith("<<", i)) {
                    i = heredocStart(s, i, RUBY_HEREDOC, heredocs);
                } else if (c == '%' && i + 2 < n && rubyPercentLiteral(s, i) > 0) {
                    int open = rubyPercentLiteral(s, i);
                    i = delimitedEnd(s, open + 1, s.charAt(open), closingBracket(s.charAt(open)));
                } else if (c == '/' && perlRegexMayStart(s, i)) {
                    i = delimitedEnd(s, i + 1, '/', '/');
                } else {
                    i++;
                }
            }
        }

        /** The offset of the opening delimiter of a %q{} style literal at percent, or -1. */
        private static int rubyPercentLiteral(String s, int percent) {
            int j = percent + 1;
            if ("qQwWiIrsx".indexOf(s.charAt(j)) >= 0) {
                j++;
            }
            return j < s.length() && "([{<|!/".indexOf(s.charAt(j)) >= 0 ? j : -1;
        }

        // CMake: # comments and #[[ ]] bracket comments; "..." and [[ ]] bracket arguments are content

        private static void cmake(String s, char[] noComments) {
            int n = s.length();
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '#') {
                    int level = bracketLevel(s, i + 1);
                    if (level >= 0) {
                        int end = bracketEnd(s, i + 1, level);
                        blank(noComments, i, end);
                        i = end;
                    } else {
                        i = hashComment(s, i, noComments);
                    }
                } else if (c == '"') {
                    i = delimitedEnd(s, i + 1, '"', '"');
                } else if (c == '[' && bracketLevel(s, i) >= 0) {
                    i = bracketEnd(s, i, bracketLevel(s, i));
                } else if (c == '\\') {
                    i += 2;
                } else {
                    i++;
                }
            }
        }

        /** The number of = in a [==[ opening bracket at from, or -1 if there is none. */
        private static int bracketLevel(String s, int from) {
            if (from >= s.length() || s.charAt(from) != '[') {
                return -1;
            }
            int j = from + 1;
            while (j < s.length() && s.charAt(j) == '=') {
                j++;
            }
            return j < s.length() && s.charAt(j) == '[' ? j - from - 1 : -1;
        }

        private static int bracketEnd(String s, int from, int level) {
            String close = "]" + "=".repeat(level) + "]";
            int end = s.indexOf(close, from + level + 2);
            return end < 0 ? s.length() : end + close.length();
        }

        // YAML and Makefiles: # starts a comment at a line start or after white space, outside quotes

        private static void hashPerLine(String s, char[] noComments, boolean backslashEscapes) {
            int n = s.length();
            int i = 0;
            char quote = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\n') {
                    quote = 0;
                    i++;
                } else if (c == '\\' && (backslashEscapes || quote == '"')) {
                    i += 2;
                } else if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                    i++;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                    i++;
                } else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                    int end = lineEnd(s, i);
                    blank(noComments, i, end);
                    i = end;
                } else {
                    i++;
                }
            }
        }

        private static final Pattern EMBEDDED_HEREDOC =
                Pattern.compile("(?<!<)<<(?!<)-?[ \\t]*[\"']?([A-Za-z_]\\w*)[\"']?");

        /**
         * Puts back the body of a shell heredoc in a YAML run block or a Makefile recipe ({@code cat > x.h <<EOF}):
         * it is file content, so its # lines are not comments. The heredoc is looked for in the comment-free text,
         * so a commented-out {@code <<EOF} opens nothing; the body ends at the line that is the terminator once
         * stripped, as a YAML block indents it.
         */
        private static void keepHeredocBodies(String s, char[] noComments) {
            String terminator = null;
            for (int i = 0; i < s.length(); i = nextLine(s, i)) {
                int end = lineEnd(s, i);
                if (terminator != null) {
                    if (s.substring(i, end).strip().equals(terminator)) {
                        terminator = null;
                    } else {
                        for (int j = i; j < end; j++) {
                            noComments[j] = s.charAt(j);
                        }
                    }
                    continue;
                }
                Matcher heredoc = EMBEDDED_HEREDOC.matcher(new String(noComments, i, end - i));
                if (heredoc.find()) {
                    terminator = heredoc.group(1);
                }
            }
        }

        // properties: a line whose first character is # or ! is a comment, unless it continues a value

        private static void properties(String s, char[] noComments) {
            boolean continuation = false;
            for (int i = 0; i < s.length(); i = nextLine(s, i)) {
                int end = lineEnd(s, i);
                String line = s.substring(i, end).replaceFirst("\r$", "");
                String trimmed = line.stripLeading();
                boolean comment = !continuation && (trimmed.startsWith("#") || trimmed.startsWith("!"));
                if (comment) {
                    blank(noComments, i, end);
                    continuation = false;
                } else {
                    int backslashes = 0;
                    while (backslashes < line.length() && line.charAt(line.length() - 1 - backslashes) == '\\') {
                        backslashes++;
                    }
                    continuation = backslashes % 2 == 1;
                }
            }
        }

        private static void xml(String s, char[] noComments) {
            int i = s.indexOf('<');
            while (i >= 0) {
                if (s.startsWith("<!--", i)) {
                    int close = s.indexOf("-->", i + 4);
                    int end = close < 0 ? s.length() : close + 3;
                    blank(noComments, i, end);
                    i = s.indexOf('<', end);
                } else if (s.startsWith("<![CDATA[", i)) {
                    int close = s.indexOf("]]>", i);
                    i = close < 0 ? -1 : s.indexOf('<', close + 3);
                } else {
                    i = s.indexOf('<', i + 1);
                }
            }
        }

        private static void lineComments(String s, char[] noComments, String marker) {
            for (int i = s.indexOf(marker); i >= 0; i = s.indexOf(marker, i + 1)) {
                int end = lineEnd(s, i);
                blank(noComments, i, end);
                i = end;
            }
        }

        // export lists and linker version scripts: /* */ and # comments

        private static void exportList(String s, char[] noComments) {
            int n = s.length();
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    int close = s.indexOf("*/", i + 2);
                    int end = close < 0 ? n : close + 2;
                    blank(noComments, i, end);
                    i = end;
                } else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                    int end = lineEnd(s, i);
                    blank(noComments, i, end);
                    i = end;
                } else {
                    i++;
                }
            }
        }

        // Windows batch files: REM and :: lines

        private static void batch(String s, char[] noComments) {
            for (int i = 0; i < s.length(); i = nextLine(s, i)) {
                int end = lineEnd(s, i);
                String line = s.substring(i, end).strip().toLowerCase(Locale.ROOT);
                if (line.startsWith("@")) {
                    line = line.substring(1).stripLeading();
                }
                if (line.startsWith("::") || line.equals("rem") || line.startsWith("rem ")
                        || line.startsWith("rem\t")) {
                    blank(noComments, i, end);
                }
            }
        }
    }

    // ---- allowlist ----

    private static List<AllowEntry> readAllowlist(Path file, List<String> errors) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            errors.add(ALLOWLIST + ": cannot read: " + e);
            return List.of();
        }
        List<AllowEntry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(Pattern.quote(ALLOWLIST_SEPARATOR), 4);
            Rule rule = fields.length == 4 ? Rule.byId(fields[1].strip()) : null;
            if (fields.length != 4 || rule == null || fields[0].isBlank() || fields[2].isEmpty()
                    || fields[3].isBlank()) {
                errors.add(ALLOWLIST + ":" + (i + 1) + ": expected 'path | rule | text | reason' with a rule id"
                        + " of this program, found: " + line);
                continue;
            }
            entries.add(new AllowEntry(i + 1, fields[0].strip(), rule, fields[2], fields[3].strip()));
        }
        return entries;
    }

    /** The entry whose text, found in the hit's source line, covers the matched token; or null. */
    private static AllowEntry allowEntryFor(Hit hit, List<AllowEntry> allowlist) {
        for (AllowEntry entry : allowlist) {
            if (entry.rule() != hit.rule() || !entry.path().equals(hit.path())) {
                continue;
            }
            String line = hit.lineText();
            for (int at = line.indexOf(entry.text()); at >= 0; at = line.indexOf(entry.text(), at + 1)) {
                if (at <= hit.column() && hit.column() + hit.length() <= at + entry.text().length()) {
                    return entry;
                }
            }
        }
        return null;
    }

    // ---- self-test ----

    private record SelfTestCase(String name, String file, String content, String allowlist, int expectedExit,
            Rule expectedRule, Map<String, String> moreFiles) {
        SelfTestCase(String name, String file, String content, String allowlist, int expectedExit, Rule expectedRule) {
            this(name, file, content, allowlist, expectedExit, expectedRule, Map.of());
        }
    }

    private static SelfTestCase plant(String name, String file, Rule rule, String content) {
        return new SelfTestCase(name, file, content, null, EXIT_HITS, rule);
    }

    private static SelfTestCase control(String name, String file, String content) {
        return new SelfTestCase(name, file, content, null, EXIT_CLEAN, null);
    }

    /**
     * Plants every rule, each in a file of its own in a fresh temporary directory, and proves that the scan of that
     * directory fails with that rule and no other; then proves that the same tokens in comments, and lookalike
     * names, pass, and that the allowlist both excuses a hit and fails when stale or malformed.
     */
    private static int selfTest() {
        List<SelfTestCase> cases = selfTestCases();
        String format = "%-46s %-24s %-10s %-4s %-24s %s%n";
        System.out.printf(Locale.ROOT, format, "CASE", "FILE", "EXPECT", "EXIT", "GOT", "RESULT");
        int failures = 0;
        for (SelfTestCase test : cases) {
            Path dir = null;
            try {
                dir = Files.createTempDirectory("checknojni-");
                Map<String, String> files = new TreeMap<>(test.moreFiles());
                files.put(test.file(), test.content());
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    Path file = dir.resolve(entry.getKey());
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, entry.getValue(), StandardCharsets.ISO_8859_1);
                }
                if (test.allowlist() != null) {
                    Path allowlist = dir.resolve(ALLOWLIST);
                    Files.createDirectories(allowlist.getParent());
                    Files.writeString(allowlist, test.allowlist(), StandardCharsets.UTF_8);
                }
                Report report = scan(dir, false);
                int exit = print(report, new PrintStream(PrintStream.nullOutputStream()),
                        new PrintStream(PrintStream.nullOutputStream()));
                Set<String> rules = new TreeSet<>();
                report.hits().forEach(hit -> rules.add(hit.rule().id));
                boolean pass = exit == test.expectedExit() && (test.expectedRule() == null ? rules.isEmpty()
                        : rules.equals(Set.of(test.expectedRule().id)));
                failures += pass ? 0 : 1;
                System.out.printf(Locale.ROOT, format, test.name(), test.file(),
                        test.expectedRule() == null ? "pass" : test.expectedRule().id, exit,
                        rules.isEmpty() ? "-" : String.join(",", rules), pass ? "PASS" : "FAIL");
                if (!pass) {
                    report.hits().forEach(hit -> System.out.println("    " + hit.path() + ":" + hit.line() + ":"
                            + hit.rule().id + ":" + hit.text()));
                }
            } catch (IOException e) {
                failures++;
                System.out.printf(Locale.ROOT, format, test.name(), test.file(), "-", "-", e, "FAIL");
            } finally {
                deleteTree(dir);
            }
        }
        int usageExit = run(new String[] {"--no-such-option"});
        boolean usagePass = usageExit == EXIT_USAGE;
        failures += usagePass ? 0 : 1;
        System.out.printf(Locale.ROOT, format, "unknown option is a usage error", "-", "exit 2", usageExit, "-",
                usagePass ? "PASS" : "FAIL");
        System.out.println("CheckNoJni self-test: " + (cases.size() + 1 - failures) + " of " + (cases.size() + 1)
                + " cases passed");
        return failures == 0 ? EXIT_CLEAN : EXIT_HITS;
    }

    private static void deleteTree(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            System.err.println("WARNING: cannot delete " + dir + ": " + e);
        }
    }

    private static List<SelfTestCase> selfTestCases() {
        return List.of(
                plant("native method", "p/NativeMethod.java", Rule.NATIVE, """
                        package p;
                        class NativeMethod {
                            private static native int answer();
                        }
                        """),
                plant("@Native constant and its import", "p/NativeConstant.java", Rule.NATIVE, """
                        package p;
                        import java.lang.annotation.Native;
                        class NativeConstant {
                            @Native static final int ANSWER = 42;
                        }
                        """),
                plant("fully qualified @Native", "p/QualifiedNative.java", Rule.NATIVE, """
                        package p;
                        class QualifiedNative {
                            @java.lang.annotation.Native static final int ANSWER = 42;
                        }
                        """),
                plant("Java_ export in a .def file", "glass.def", Rule.EXPORT, """
                        LIBRARY glass
                        EXPORTS
                            Java_com_sun_glass_ui_win_WinApplication__1init
                        """),
                plant("Java_ glob in a linker version script", "mapfile-vers", Rule.EXPORT, """
                        SUNWprivate_1.0 {
                            global:
                                Java_*;
                            local:
                                *;
                        };
                        """),
                plant("_Java_ in a macOS export list", "exports.exp", Rule.EXPORT, """
                        _Java_com_sun_glass_ui_mac_MacApplication__1init
                        """),
                plant("Java_ function definition in C", "glass.c", Rule.EXPORT, """
                        #include <stdint.h>
                        int32_t Java_com_sun_glass_Foo_bar(void *e, void *c) { return 0; }
                        """),
                plant("JNI_OnLoad in C++", "onload.cpp", Rule.EXPORT, """
                        extern "C" int JNI_OnLoad(void *vm, void *reserved) {
                            return 0x00010008;
                        }
                        """),
                plant("JNIEXPORT and JNICALL in Objective-C", "GlassFoo.m", Rule.EXPORT, """
                        JNIEXPORT void JNICALL glass_foo(void) {}
                        """),
                plant("JNIEXPORT in a perl generator string", "gen/export.pl", Rule.EXPORT, """
                        print OUT "JNIEXPORT void JNICALL\\n";
                        """),
                plant("JNI type in a perl string literal", "gen/type.pl", Rule.JNI, """
                        my $decl = "static jobject cached;";
                        print $decl, "\\n";
                        """),
                plant("JNI type after # in a perl string", "gen/hash.pl", Rule.JNI, """
                        print "# jint is what this used to be\\n";
                        """),
                plant("JNI type in a perl heredoc #-line", "gen/heredoc.pm", Rule.JNI, """
                        print <<"END";
                        #define CACHE(x) static jclass x
                        END
                        1;
                        """),
                plant("JNI type in a perl q{} string", "gen/quote.pl", Rule.JNI, """
                        my $type = q{# jstring};
                        """),
                plant("JNI type in a python string", "gen/type.py", Rule.JNI, """
                        HEADER = "jstring s = NULL;  # not a comment"
                        """),
                plant("JNI type in a python triple-quoted string", "gen/triple.py", Rule.JNI, """
                        TEMPLATE = '''
                        # a line of the template
                        jmethodID mid;
                        '''
                        """),
                plant("JNI type in a ruby string", "gen/type.rb", Rule.JNI, """
                        puts "JavaVM *vm; # not a comment"
                        """),
                plant("JNI type in a shell string", "gen/type.sh", Rule.JNI, """
                        echo "jfieldID fid; # not a comment"
                        """),
                plant("JNI type in a shell heredoc #-line", "gen/heredoc.sh", Rule.JNI, """
                        cat <<EOF > out.h
                        # jthrowable t;
                        EOF
                        """),
                plant("JNI type in a CMake string", "gen/type.cmake", Rule.JNI, """
                        file(WRITE out.h "#define X 1\\njlong v;\\n")
                        """),
                plant("JNI type in a YAML string", "ci.yml", Rule.JNI, """
                        steps:
                          - run: echo "jboolean # not a comment"
                        """),
                plant("JNI type in C", "uses.c", Rule.JNI, """
                        void f(JNIEnv *e) { (void) e; }
                        """),
                plant("JNI function through (*env)->", "call.c", Rule.JNI, """
                        void f(struct S **env) { (*env)->GetVersion(env); }
                        """),
                plant("JNI function through env->", "call.cpp", Rule.JNI, """
                        void f(S *env, void *c) { env->NewObject(c, 0); }
                        """),
                plant("JNI function by its own name", "find.cpp", Rule.JNI, """
                        void f(S *e) { e->FindClass("x"); }
                        """),
                plant("JNI_* constant", "const.c", Rule.JNI, """
                        int ok = JNI_OK;
                        """),
                plant("JNI function name in a Java string", "p/Oracle.java", Rule.JNI, """
                        package p;
                        class Oracle {
                            static final String SYMBOL = "JNI_GetCreatedJavaVMs";
                        }
                        """),
                plant("#include <jni.h> in C", "inc.c", Rule.INCLUDE, """
                        #include <jni.h>
                        """),
                plant("#import of jni_md.h in Objective-C", "inc.m", Rule.INCLUDE, """
                        #import "jni_md.h"
                        """),
                plant("#include <jawt.h> in C++", "awt.cpp", Rule.INCLUDE, """
                        #  include <jawt.h>
                        """),
                plant("#include <jni.h> in a perl generator string", "gen/inc.pl", Rule.INCLUDE, """
                        print "#include <jni.h>\\n";
                        """),
                plant("JDK include directories in CMake", "CMakeLists.txt", Rule.JDKINC, """
                        include_directories(${JDK_HOME}/include ${JDK_HOME}/include/win32)
                        """),
                plant("find_package(JNI) in CMake", "jni/CMakeLists.txt", Rule.JDKINC, """
                        find_package(JNI REQUIRED)
                        include_directories(${JNI_INCLUDE_DIRS})
                        """),
                plant("${java.home}/include in a pom", "pom.xml", Rule.JDKINC, """
                        <project>
                          <arg>-I${java.home}/include</arg>
                        </project>
                        """),
                plant("$(JAVA_HOME)/include in a Makefile", "Makefile", Rule.JDKINC, """
                        CFLAGS += -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux
                        """),
                plant("$JAVA_HOME/include in a workflow", ".github/workflows/x.yml", Rule.JDKINC, """
                        steps:
                          - run: gcc -I"$JAVA_HOME/include" -c x.c
                        """),
                plant("JDK include in a Visual Studio project", "x.vcxproj", Rule.JDKINC, """
                        <Project>
                          <AdditionalIncludeDirectories>$(JAVA_HOME)\\include;%(X)</AdditionalIncludeDirectories>
                        </Project>
                        """),
                plant("javac -h in pom compilerArgs", "h/pom.xml", Rule.JAVAC_H, """
                        <project>
                          <compilerArgs>
                            <arg>-h</arg>
                            <arg>${project.build.directory}/headers</arg>
                          </compilerArgs>
                        </project>
                        """),
                plant("javac -h as an Ant compilerarg", "ant/pom.xml", Rule.JAVAC_H, """
                        <javac srcdir="src" destdir="out">
                          <compilerarg value="-h"/>
                          <compilerarg value="out/headers"/>
                        </javac>
                        """),
                plant("javac -h on a command line", "build.sh", Rule.JAVAC_H, """
                        javac -d out -h out/headers Foo.java
                        """),
                plant("javac -h in a workflow", ".github/workflows/h.yml", Rule.JAVAC_H, """
                        steps:
                          - run: javac -h headers -d classes src/Foo.java
                        """),
                plant("GENERATE_NATIVE_HEADERS in CMake", "jar/CMakeLists.txt", Rule.JAVAC_H, """
                        add_jar(foo Foo.java GENERATE_NATIVE_HEADERS foo-headers)
                        """),
                plant("System.load", "p/Load.java", Rule.LOAD, """
                        package p;
                        class Load {
                            static { System.load("/tmp/libx.so"); }
                        }
                        """),
                plant("System.loadLibrary", "p/LoadLibrary.java", Rule.LOAD, """
                        package p;
                        class LoadLibrary {
                            static { System.loadLibrary("x"); }
                        }
                        """),
                plant("Runtime.getRuntime().loadLibrary", "p/RuntimeLoad.java", Rule.LOAD, """
                        package p;
                        class RuntimeLoad {
                            static { Runtime.getRuntime().loadLibrary("x"); }
                        }
                        """),
                plant("System::loadLibrary reference", "p/LoadReference.java", Rule.LOAD, """
                        package p;
                        class LoadReference {
                            static final java.util.function.Consumer<String> LOAD = System::loadLibrary;
                        }
                        """),
                control("Java comments", "p/Commented.java", """
                        package p;
                        /**
                         * Formerly {@code private native int f();} and {@code @Native}, that is
                         * java.lang.annotation.Native.
                         */
                        class Commented {
                            // System.loadLibrary("glass"); JNIEnv *env; #include <jni.h>
                            /* Java_com_sun_Foo_bar JNI_OnLoad javac -h ${JDK_HOME}/include */
                        }
                        """),
                control("Java strings for the code-only rules", "p/Messages.java", """
                        package p;
                        class Messages {
                            static final String A = "native library failed to load";
                            static final String B = "System.loadLibrary(\\"glass\\") failed";
                            static final char C = '"';
                            static final String D = \"""
                                    native text block; System.load(x) "quoted"
                                    \""";
                        }
                        """),
                control("C comments, continued // comment", "commented.c", """
                        /* #include <jni.h>
                           JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *r) */
                        // (*env)->FindClass(env, "x"); \\
                           still the same comment: jobject
                        int x = 1'000;
                        char q = '\\'';
                        """),
                control("perl comments and POD", "commented.pl", """
                        # JNIEnv *env; #include <jni.h>
                        my $x = 1; # jobject
                        my $n = $#ARGV;

                        =pod

                        JNIEXPORT jint JNICALL Java_com_sun_Foo_bar

                        =cut

                        print "done\\n";
                        """),
                control("CMake comments", "c/CMakeLists.txt", """
                        # include_directories(${JDK_HOME}/include)
                        #[[ find_package(JNI)
                           javac -h out ]]
                        set(X "a#b")  # jint
                        """),
                control("pom comment", "c/pom.xml", """
                        <project>
                          <!-- <compilerArgs><arg>-h</arg></compilerArgs>
                               ${java.home}/include JNIEnv -->
                        </project>
                        """),
                control(".def comment", "c/glass.def", """
                        EXPORTS
                            ; Java_com_sun_glass_Foo was removed
                            glass_foo
                        """),
                control("YAML and shell comments", "c/ci.yml", """
                        jobs:
                          check:
                            steps:
                              # javac -h headers
                              - run: echo "done" # -I$JAVA_HOME/include jclass
                        """),
                control("python comments", "c/gen.py", """
                        # jclass c
                        X = 1  # JNIEnv
                        """),
                control("lookalike names and the fork's own loader", "p/Lookalike.java", """
                        package p;
                        class Lookalike {
                            static final int ERROR_JNI_SEND_PLAYER_MEDIA_ERROR_EVENT = 1;
                            static final int ERROR_BASE_JNI = 2;
                            static final String JNI_LIBRARY = "libjfxwebkit.dylib";
                            int nativeLibraryCount;
                            boolean isNative() { return false; }
                            void load() { NativeLibLoader.loadLibrary("glass"); nativeLoad(); }
                            void nativeLoad() { java.nio.ByteBuffer b = C.NewDirectByteBuffer(0L, 4); }
                        }
                        """),
                control("lookalike C names", "lookalike.c", """
                        int jintValue, myjobject, JavaVMs, ERROR_JNI_FOO, isNative, Java, JNI_EXPAND;
                        void f(struct W *web, struct E *env) { web->NewObject(); env->Run(); }
                        """),
                control("JavaScriptCore's ExitKind ExceptionCheck", "ExitKind.cpp", """
                        case ExitKind::ExceptionCheck: return "ExceptionCheck";
                        """),
                control("JavaScriptCore's env->symbolTable()", "CellSize.h", """
                        auto* env = jsCast<JSLexicalEnvironment*>(cell);
                        return JSLexicalEnvironment::allocationSize(env->symbolTable());
                        """),
                control("CMake's FindJava variables", "java/CMakeLists.txt", """
                        find_package(Java COMPONENTS Development)
                        execute_process(COMMAND ${Java_JAR_EXECUTABLE} cf x.jar)
                        """),
                plant("JNI_VERSION_* constant", "version.c", Rule.JNI, """
                        int version = JNI_VERSION_21;
                        """),
                plant("a shared JNI name through env->", "check.cpp", Rule.JNI, """
                        bool failed(S *jniEnv) { return jniEnv->ExceptionCheck(); }
                        """),
                plant("a platform's jni_md.h directory", "md.sh", Rule.JDKINC, """
                        cc -Ifoo/include -Ifoo/include/darwin -c x.c
                        """),
                plant("a JDK path's include directory", "jvm.sh", Rule.JDKINC, """
                        cc -I/usr/lib/jvm/java-27-openjdk/include -c x.c
                        """),
                control("markdown is prose", "README.md", """
                        JNIEnv, #include <jni.h>, System.loadLibrary, javac -h
                        """),
                control("a clean file", "p/Clean.java", """
                        package p;
                        class Clean {
                        }
                        """),
                plant("Ant javac nativeHeaderDir", "ant/pom.xml", Rule.JAVAC_H, """
                        <target name="compile">
                          <javac srcdir="src" destdir="out" nativeHeaderDir="out/headers"/>
                        </target>
                        """),
                control("Ant nativeheaderdir in a comment", "ant2/pom.xml", """
                        <!-- <javac srcdir="src" nativeheaderdir="out/headers"/> -->
                        <javac srcdir="src" destdir="out"/>
                        """),
                plant("javac and -h on two lines of a CMake command", "jh/CMakeLists.txt", Rule.JAVAC_H, """
                        add_custom_command(OUTPUT headers.stamp
                            COMMAND ${Java_JAVAC_EXECUTABLE} -d classes
                                    -h headers Foo.java)
                        """),
                control("javac and an -h in two CMake commands", "jh2/CMakeLists.txt", """
                        add_custom_command(OUTPUT classes.stamp COMMAND ${Java_JAVAC_EXECUTABLE} -d classes Foo.java)
                        execute_process(COMMAND tool -h)
                        """),
                plant("javac -h across a cmd ^ continuation", "build.cmd", Rule.JAVAC_H, """
                        javac -d out ^
                          -h headers Foo.java
                        """),
                control("javac, then -h in the next command", "build2.sh", """
                        javac -d out Foo.java
                        ls -h out
                        """),
                plant("single-quoted compilerarg -h", "sq/pom.xml", Rule.JAVAC_H, """
                        <javac srcdir="src">
                          <compilerarg value='-h'/>
                          <compilerarg value='out/headers'/>
                        </javac>
                        """),
                control("single-quoted compilerargs, no -h", "sq2/pom.xml", """
                        <javac srcdir="src">
                          <compilerarg value='-implicit:none'/>
                          <compilerarg line='-Xlint:all -help-free'/>
                        </javac>
                        """),
                plant("-h on a line of its own in an <arg>", "arg/pom.xml", Rule.JAVAC_H, """
                        <compilerArgs>
                          <arg>
                            -h
                          </arg>
                        </compilerArgs>
                        """),
                plant("<compilerArguments><h>", "map/pom.xml", Rule.JAVAC_H, """
                        <compilerArguments>
                          <h>${project.build.directory}/headers</h>
                        </compilerArguments>
                        """),
                control("an <h> outside compilerArguments", "map2/pom.xml", """
                        <compilerArguments><Xlint/></compilerArguments>
                        <description><h>Title</h></description>
                        """),
                plant("Gradle headerOutputDirectory", "g/build.gradle", Rule.JAVAC_H, """
                        compileJava { options.headerOutputDirectory = file("build/headers") }
                        """),
                control("Gradle headerOutputDirectory in a comment", "g2/build.gradle", """
                        // options.headerOutputDirectory = file("build/headers")
                        compileJava { options.encoding = "UTF-8" }
                        """),
                plant("$ENV{JAVA_HOME}/include in CMake", "env/CMakeLists.txt", Rule.JDKINC, """
                        include_directories("$ENV{JAVA_HOME}/include")
                        """),
                control("$ENV{HOME}/include in CMake", "env2/CMakeLists.txt", """
                        include_directories("$ENV{HOME}/include")
                        """),
                plant("$ENV{JAVA_HOME}/include in perl", "gen/env.pl", Rule.JDKINC, """
                        my $inc = "-I$ENV{'JAVA_HOME'}/include";
                        """),
                control("$ENV{HOME}/include in perl", "gen/env2.pl", """
                        my $inc = "-I$ENV{HOME}/include";
                        """),
                plant("${{ env.JAVA_HOME }}/include in a workflow", ".github/workflows/env.yml", Rule.JDKINC, """
                        steps:
                          - run: gcc -I${{ env.JAVA_HOME }}/include -c x.c
                        """),
                control("${{ env.HOME }}/include in a workflow", ".github/workflows/env2.yml", """
                        steps:
                          - run: gcc -I${{ env.HOME }}/include -c x.c
                        """),
                plant("loadLibrary through a Runtime variable", "p/RuntimeVariable.java", Rule.LOAD, """
                        package p;
                        class RuntimeVariable {
                            static {
                                Runtime rt = Runtime.getRuntime();
                                rt.loadLibrary("x");
                            }
                        }
                        """),
                plant("load through a var holding the Runtime", "p/RuntimeVar.java", Rule.LOAD, """
                        package p;
                        class RuntimeVar {
                            static {
                                var runtime = Runtime.getRuntime();
                                runtime.load("/tmp/libx.so");
                            }
                        }
                        """),
                plant("loadLibrary through a Runtime parameter", "p/RuntimeParameter.java", Rule.LOAD, """
                        package p;
                        class RuntimeParameter {
                            static void open(java.lang.Runtime target, String name) {
                                target.loadLibrary(name);
                            }
                        }
                        """),
                plant("loadLibrary under import static System.*", "p/StaticImport.java", Rule.LOAD, """
                        package p;
                        import static java.lang.System.*;
                        class StaticImport {
                            static {
                                loadLibrary("x");
                            }
                        }
                        """),
                plant("Runtime.getRuntime()::loadLibrary", "p/RuntimeReference.java", Rule.LOAD, """
                        package p;
                        class RuntimeReference {
                            static final java.util.function.Consumer<String> LOAD = Runtime.getRuntime()::loadLibrary;
                        }
                        """),
                control("a Runtime variable that loads nothing", "p/RuntimeMemory.java", """
                        package p;
                        class RuntimeMemory {
                            long free(FXMLLoader loader) throws Exception {
                                Runtime r = Runtime.getRuntime();
                                loader.load();
                                return r.totalMemory() - r.freeMemory();
                            }
                        }
                        """),
                control("own loadLibrary beside import static System.*", "p/OwnLoader.java", """
                        package p;
                        import static java.lang.System.*;
                        class OwnLoader {
                            static void loadLibrary(String name) {
                            }
                            static {
                                loadLibrary("glass");
                                NativeLibLoader.loadLibrary("glass");
                                LinuxSystem.getLinuxSystem().loadLibrary();
                            }
                        }
                        """),
                plant("perl file test -s before a generator string", "gen/size.pl", Rule.INCLUDE, """
                        my $size = -s "$file";
                        print OUT "#include <jni.h> /* $size */\\n";
                        """),
                control("perl file test -s, then a comment", "gen/size2.pl", """
                        my $size = -s "$file"; # jni.h was included here
                        """),
                plant("perl / after a hash subscript, then a # string", "gen/half.pl", Rule.JNI, """
                        my $half = $count{all} / 2;
                        print OUT "out/dir # JNIEnv *env;\\n";
                        """),
                control("perl / after a hash subscript, then a comment", "gen/half2.pl", """
                        my $half = $count{all} / 2; # JNIEnv
                        my $path = "a/b";
                        """),
                plant("#include <jni.h> in a .h.cmake template", "tpl/config.h.cmake", Rule.INCLUDE, """
                        #cmakedefine HAVE_X
                        #include <jni.h>
                        """),
                control("a commented include in a .h.cmake template", "tpl2/config.h.cmake", """
                        /* #include <jni.h> */
                        #cmakedefine HAVE_X
                        """),
                plant("JavaScript regex literal before a string", "web/re.js", Rule.JNI, """
                        var slashes = /\\/\\//g; var type = "JNIEnv";
                        """),
                control("JavaScript regex literal before a comment", "web/re2.js", """
                        var slashes = /\\/\\//g; // JNIEnv
                        var half = width / 2; // JNIEnv
                        """),
                plant("# after an odd quote stays (lost string)", "gen/char.rb", Rule.JNI, """
                        QUOTE = ?"
                        puts "# JNIEnv *env;"
                        """),
                control("# after even quotes is a comment", "gen/even.rb", """
                        puts "x" # JNIEnv
                        """),
                plant(".stg template writing a native method", "stg/Peer.stg", Rule.NATIVE, """
                        group Peer;

                        peer(name) ::= <<
                        final class $name$ {
                            private static native void filter(int[] dst, int dstw);
                        }
                        >>
                        """),
                plant(".stg template writing System.loadLibrary", "stg/Loader.stg", Rule.LOAD, """
                        group Loader;

                        loader() ::= <<
                        static {
                            System.loadLibrary("decora_sse");
                        }
                        >>
                        """),
                control(".stg template comments", "stg2/Peer.stg", """
                        group Peer;

                        peer() ::= <<
                        // private static native void filter();
                        /* System.loadLibrary("x"); */
                        setDestNativeBounds(w, h);
                        >>
                        """),
                plant("perl generator writing a native method", "gen/nat.pl", Rule.NATIVE, """
                        print OUT "    private static native long create(long ptr);\\n";
                        """),
                plant("perl generator writing an @Native constant", "gen/natconst.pl", Rule.NATIVE, """
                        print OUT "    \\@Native public static final int $name = $value;\\n";
                        """),
                plant("perl generator writing System.loadLibrary", "gen/loadgen.pl", Rule.LOAD, """
                        print OUT "        System.loadLibrary(\\"glass\\");\\n";
                        """),
                plant("python generator writing a native method", "gen/nat.py", Rule.NATIVE, """
                        out.write("    native int answer();\\n")
                        """),
                control("perl that reads native declarations", "gen/reader.pl", """
                        next unless $line =~ /^\\s*native\\s+static\\s+(\\S+)\\s+(\\w+)\\s*\\(/;
                        $mods =~ s/\\bnative[ \\t]+//;
                        die "native library failed to load (code $code)\\n";
                        print "System.loadLibrary is not called here\\n";
                        """),
                plant("a source directory named build is read", "glib/build/win32/x.c", Rule.INCLUDE, """
                        #include <jni.h>
                        """),
                new SelfTestCase("Maven build output is skipped", "pom.xml", """
                        <project/>
                        """, null, EXIT_CLEAN, null, Map.of(
                        "target/gen/Native.java", "class Native { native void f(); }\n",
                        "build/headers/x.h", "#include <jni.h>\n")),
                plant("#include <JNI.H> in upper case", "upper.c", Rule.INCLUDE, """
                        #include <JNI.H>
                        """),
                plant("#include <jvmti.h>", "agent.c", Rule.INCLUDE, """
                        #include <jvmti.h>
                        """),
                plant("#include \"jdwpTransport.h\"", "transport.c", Rule.INCLUDE, """
                        #include "jdwpTransport.h"
                        """),
                plant("#include <AccessBridgeCalls.h>", "bridge.cpp", Rule.INCLUDE, """
                        #include <AccessBridgeCalls.h>
                        """),
                control("headers that do not include jni.h", "headers.c", """
                        #include <jvmticmlr.h>
                        #include "classfile_constants.h"
                        #include "JNIUtility.h"
                        #include "myjni.h"
                        """),
                plant("it's in a kept comment, then a heredoc-like string", "gen/apos.pl", Rule.INCLUDE, """
                        my $what = "the generator's header"; # it's written below
                        my $hdr = '
                        #include <jni.h>
                        ';
                        print OUT $hdr;
                        """),
                plant("a string closing before # it's", "gen/mlclose.pl", Rule.INCLUDE, """
                        my $usage = "usage: gen
                        options"; # it's printed on error
                        my $hdr = '
                        #include <jni.h>
                        ';
                        """),
                control("it's in a kept comment, then a real comment", "gen/apos2.pl", """
                        my $what = "the generator's header"; # it's written below
                        # #include <jni.h> was written here
                        my $done = 1;
                        """),
                plant("perl generator writing native ... ${name}(", "gen/placeholder.pl", Rule.NATIVE, """
                        push(@contentJava, "    native static boolean ${funcName}(long peer);\\n\\n");
                        """),
                plant("perl generator concatenating a native method", "gen/concat.pl", Rule.NATIVE, """
                        $property .= "    native static " . $type . " " . $name . "(long peer);\\n";
                        """),
                plant("python generator concatenating a native method", "gen/concat.py", Rule.NATIVE, """
                        out.write("    private static native " + ret + " " + name + "(long peer);\\n")
                        """),
                plant("perl generator concatenating an @Native constant", "gen/constconcat.pl", Rule.NATIVE, """
                        print OUT "    \\@Native public static final int " . $name . " = " . $value . ";\\n";
                        """),
                control("native as a plain word before a quote", "gen/windows.sh", """
                        echo "Building for native Windows " "$x"
                        echo "native code is optional"
                        """),
                plant("#include <jni.h> in a workflow heredoc", ".github/workflows/heredoc.yml", Rule.INCLUDE, """
                        jobs:
                          probe:
                            steps:
                              - run: |
                                  cat > probe.c <<'END'
                                  #include <jni.h>
                                  int main(void) { return 0; }
                                  END
                                  cc -c probe.c
                        """),
                control("workflow comments around a heredoc", ".github/workflows/heredoc2.yml", """
                        jobs:
                          probe:
                            steps:
                              # cat > probe.c <<EOF would write the probe
                              # jclass is not used here
                              - run: |
                                  cat > probe.c <<'END'
                                  int main(void) { return 0; }
                                  END
                                  # jclass was here
                                  cc -c probe.c
                        """),
                plant("loadLibrary under import static System.* beside own load()", "p/OwnLoad.java", Rule.LOAD,
                        """
                        package p;
                        import static java.lang.System.*;
                        class OwnLoad {
                            void load() {
                            }
                            static {
                                loadLibrary("glass");
                            }
                        }
                        """),
                new SelfTestCase("allowlisted hit passes", "p/Guard.java", """
                        package p;
                        class Guard {
                            static final String EXPORT = "JNI_OnLoad";
                        }
                        """, "p/Guard.java | R-EXPORT | \"JNI_OnLoad\" | a guard\n", EXIT_CLEAN, null),
                new SelfTestCase("stale allowlist entry fails", "p/Clean.java", """
                        package p;
                        class Clean {
                        }
                        """, "p/Clean.java | R-JNI | jint | nothing matches\n", EXIT_USAGE, null),
                new SelfTestCase("malformed allowlist entry fails", "p/Clean.java", """
                        package p;
                        class Clean {
                        }
                        """, "p/Clean.java R-JNI jint\n", EXIT_USAGE, null),
                new SelfTestCase("entry of another rule excuses nothing", "p/Guard.java", """
                        package p;
                        class Guard {
                            static final String EXPORT = "JNI_OnLoad";
                        }
                        """, "p/Guard.java | R-JNI | \"JNI_OnLoad\" | wrong rule\n", EXIT_USAGE, Rule.EXPORT));
    }
}

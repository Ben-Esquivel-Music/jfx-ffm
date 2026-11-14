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
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks that the legal notices of every JavaFX module ship byte for byte in its jar and in the SDK (US-075).
 * <p>
 * Run it with the JDK source launcher, which needs nothing but the JDK:
 * <pre>
 *     java CheckLegalNotices.java jar &lt;module src/main/legal directory&gt; &lt;module jar&gt;
 *     java CheckLegalNotices.java sdk &lt;repository root&gt; &lt;SDK directory&gt;
 * </pre>
 * <b>jar</b> checks one module jar: every file of the module's {@code src/main/legal} directory has to be the
 * entry {@code META-INF/legal/<file name>} exactly once and with the same bytes, and {@code META-INF/legal/}
 * may hold nothing else.
 * <p>
 * <b>sdk</b> checks an assembled SDK against the repository:
 * <ul>
 *   <li>{@code legal/} holds {@code LICENSE}, {@code ADDITIONAL_LICENSE_INFO} and {@code ASSEMBLY_EXCEPTION}
 *       of the repository root and {@code legal/<module>/<file name>} for every file of
 *       {@code modules/<module>/src/main/legal}, all byte for byte, and nothing else;</li>
 *   <li>every jar in {@code lib/} passes the jar check against the notices of its module, which is the empty set
 *       for a module without a {@code src/main/legal} directory. A jar is mapped to {@code modules/<name>} by the
 *       name {@link ModuleFinder} gives its module: the name in its module descriptor, or for an automatic module
 *       the {@code Automatic-Module-Name} of its manifest or else the name derived from the file name
 *       ({@code javafx-swt.jar} is {@code javafx.swt});</li>
 *   <li>every module with notices has a jar in {@code lib/}.</li>
 * </ul>
 * Jar entries are read with {@link ZipInputStream}, which returns every entry of the archive in turn, so that
 * every copy of a name is counted and compared: the lookup by name of {@link java.util.zip.ZipFile}
 * ({@code getEntry}, or {@code getInputStream} of an entry looked up by name) resolves to only one of several
 * entries with the same name.
 * <p>
 * A source file whose name maven-jar-plugin's default excludes drop from every jar, such as {@code .DS_Store} or
 * an editor backup {@code gcc.md~}, is reported as such: the copies keep it, but the jar plugin leaves it out. So is
 * a notice named {@code package.html}, which the plugin's own default excludes drop from the jar of a module that
 * sets no excludes.
 * <p>
 * The program shares no path logic with the build steps that copy the notices; it reads the source directories
 * itself. It prints one line per problem to standard error, naming the notice, the jar or SDK path and the reason,
 * and exits with status 1 after reporting all of them; a run without problems prints what it checked to standard
 * output instead. The root pom runs the jar check in every project that has a
 * {@code src/main/legal} directory and {@code sdk/pom.xml} runs the SDK check, so a module that adds notices is
 * covered without a build change. It uses only JDK APIs and the language level of {@code --release 27}, the
 * oldest JDK that may build JavaFX.
 */
public final class CheckLegalNotices {

    private static final String JAR_LEGAL_DIR = "META-INF/legal/";
    private static final String SOURCE_LEGAL_DIR = "src/main/legal";
    private static final List<String> ROOT_LICENCE_FILES =
            List.of("LICENSE", "ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION");
    private static final int EXIT_PROBLEM = 1;
    private static final int EXIT_USAGE = 2;

    /**
     * The file names that maven-jar-plugin 3.4.2 leaves out of every jar, as its {@code addDefaultExcludes} is
     * true in this build while the Ant copies into the classes directory and the SDK keep every file: the patterns
     * of {@code org.codehaus.plexus.util.AbstractScanner.DEFAULTEXCLUDES} in plexus-utils 4.0.1, the version that
     * plugin resolves, reduced to their last path segment. As in the plugin's scanner, {@code *} matches any run
     * of characters and case matters.
     */
    private static final List<Pattern> JAR_DEFAULT_EXCLUDES = Stream.of(
                    "*~", "#*#", ".#*", "%*%", "._*", "CVS", ".cvsignore", "RCS", "SCCS", "vssver.scc", "project.pj",
                    ".svn", ".arch-ids", ".bzr", ".MySCMServerInfo", ".DS_Store", ".metadata", ".hg", ".git",
                    ".gitignore", "BitKeeper", "ChangeSet", "_darcs", ".darcsrepo", "-darcs-backup*",
                    ".darcs-temp-mail")
            .map(glob -> Pattern.compile(
                    Stream.of(glob.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*"))))
            .toList();

    /**
     * The file name that maven-jar-plugin 3.4.2 leaves out of the jar of a module whose jar configuration sets no
     * {@code excludes}: the plugin's own default excludes, <code>**&#47;package.html</code> in
     * {@code org.apache.maven.plugins.jar.AbstractJarMojo.DEFAULT_EXCLUDES}, take the place of an empty
     * {@code excludes}, whatever {@code addDefaultExcludes} says. A module that sets excludes keeps the file, but a
     * notice of this name is rejected in every module.
     */
    private static final String JAR_PLUGIN_DEFAULT_EXCLUDE = "package.html";

    private CheckLegalNotices() {
    }

    /** One reason for the check to fail: which notice, where, and what is wrong. */
    private record Problem(String notice, Path location, String reason) {

        @Override
        public String toString() {
            return notice + ": " + location + ": " + reason;
        }
    }

    /** What a run found: its problems, and the lines it prints when there are none. */
    private static final class Report {

        private final List<Problem> problems = new ArrayList<>();
        private final List<String> passes = new ArrayList<>();

        void problem(String notice, Path location, String reason) {
            problems.add(new Problem(notice, location, reason));
        }

        void pass(String line) {
            passes.add(line);
        }

        int problemCount() {
            return problems.size();
        }
    }

    public static void main(String[] args) {
        if (args.length != 3 || !(args[0].equals("jar") || args[0].equals("sdk"))) {
            System.err.println("Usage: java CheckLegalNotices.java jar <module src/main/legal directory> <module jar>");
            System.err.println("       java CheckLegalNotices.java sdk <repository root> <SDK directory>");
            System.exit(EXIT_USAGE);
        }
        Report report = new Report();
        if (args[0].equals("jar")) {
            checkModuleJar(Path.of(args[1]), Path.of(args[2]), report);
        } else {
            checkSdk(Path.of(args[1]), Path.of(args[2]), report);
        }
        // A run writes to one stream only: Ant's exec task pumps stdout and stderr on separate threads, which
        // can join a line of one to a line of the other in the build log.
        if (report.problems.isEmpty()) {
            report.passes.forEach(System.out::println);
            return;
        }
        report.problems.forEach(problem -> System.err.println("ERROR: " + problem));
        System.err.println("ERROR: " + report.problemCount() + " legal notice problem(s); every file of a module's "
                + SOURCE_LEGAL_DIR + " must ship byte for byte in its jar and in the SDK (US-075)");
        System.exit(EXIT_PROBLEM);
    }

    private static void checkModuleJar(Path legalDirectory, Path jar, Report report) {
        Map<String, byte[]> notices = readSourceNotices(legalDirectory, report);
        if (checkJar(jar, notices, legalDirectory, report)) {
            report.pass(notices.size() + " legal notice(s) of " + legalDirectory + " are in " + jar
                    + " under " + JAR_LEGAL_DIR + ", byte for byte");
        }
    }

    private static void checkSdk(Path repositoryRoot, Path sdk, Report report) {
        int problemsBefore = report.problemCount();
        Map<String, Map<String, byte[]>> noticesByModule = readAllSourceNotices(repositoryRoot, report);
        Path sdkLegal = sdk.resolve("legal");
        Map<String, Path> expectedSources = new TreeMap<>();
        Set<String> expectedDirectories = new TreeSet<>();
        for (String licenceFile : ROOT_LICENCE_FILES) {
            expectedSources.put(licenceFile, repositoryRoot.resolve(licenceFile));
        }
        noticesByModule.forEach((module, notices) -> {
            expectedDirectories.add(module);
            Path legalDirectory = sourceLegalDirectory(repositoryRoot, module);
            for (String notice : notices.keySet()) {
                expectedSources.put(module + "/" + notice, legalDirectory.resolve(notice));
            }
        });
        checkSdkLegalFiles(sdkLegal, expectedSources, report);
        checkNothingElseInSdkLegal(sdkLegal, expectedSources.keySet(), expectedDirectories, report);
        int jars = checkSdkJars(repositoryRoot, sdk.resolve("lib"), noticesByModule, report);
        if (report.problemCount() == problemsBefore) {
            int notices = noticesByModule.values().stream().mapToInt(Map::size).sum();
            report.pass(sdkLegal + " holds " + ROOT_LICENCE_FILES + " and the " + notices + " legal notice(s)"
                    + " of " + noticesByModule.keySet() + ", byte for byte, and nothing else; the " + jars
                    + " jar(s) of " + sdk.resolve("lib") + " carry exactly the notices of their module");
        }
    }

    /** Reads {@code modules/<module>/src/main/legal} of every module that has one, by module directory name. */
    private static Map<String, Map<String, byte[]>> readAllSourceNotices(Path repositoryRoot, Report report) {
        Map<String, Map<String, byte[]>> noticesByModule = new TreeMap<>();
        Path modules = repositoryRoot.resolve("modules");
        try (Stream<Path> moduleDirectories = Files.list(modules)) {
            for (Path moduleDirectory : moduleDirectories.sorted().toList()) {
                Path legalDirectory = moduleDirectory.resolve(SOURCE_LEGAL_DIR);
                if (Files.isDirectory(legalDirectory)) {
                    noticesByModule.put(moduleDirectory.getFileName().toString(),
                            readSourceNotices(legalDirectory, report));
                }
            }
        } catch (IOException e) {
            report.problem(SOURCE_LEGAL_DIR, modules, "cannot list the module directories: " + e);
        }
        return noticesByModule;
    }

    /** Reads every file of a {@code src/main/legal} directory, by file name. */
    private static Map<String, byte[]> readSourceNotices(Path legalDirectory, Report report) {
        Map<String, byte[]> notices = new TreeMap<>();
        try (Stream<Path> files = Files.list(legalDirectory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file)) {
                    notices.put(name, Files.readAllBytes(file));
                    if (name.equals(JAR_PLUGIN_DEFAULT_EXCLUDE)) {
                        report.problem(name, file, "maven-jar-plugin's own default excludes drop this file name"
                                + " from the jar of a module that sets no excludes; rename it");
                    } else if (isDroppedFromJars(name)) {
                        report.problem(name, file, "maven-jar-plugin's default excludes drop this file name from"
                                + " jars; delete or rename it");
                    }
                } else {
                    report.problem(name, file, "is not a regular file; " + SOURCE_LEGAL_DIR
                            + " may hold only notice files");
                }
            }
        } catch (IOException e) {
            report.problem(SOURCE_LEGAL_DIR, legalDirectory, "cannot read the notices: " + e);
        }
        return notices;
    }

    /**
     * Returns whether maven-jar-plugin 3.4.2 may leave a file of this name out of a module jar: a name that the
     * patterns of {@code org.codehaus.plexus.util.AbstractScanner.DEFAULTEXCLUDES} in plexus-utils 4.0.1 match
     * ({@link #JAR_DEFAULT_EXCLUDES}) is dropped from every jar, and {@code package.html}, by the plugin's own default
     * excludes <code>**&#47;package.html</code> ({@link #JAR_PLUGIN_DEFAULT_EXCLUDE}), from the jar of a module that
     * sets no excludes.
     */
    private static boolean isDroppedFromJars(String fileName) {
        return fileName.equals(JAR_PLUGIN_DEFAULT_EXCLUDE)
                || JAR_DEFAULT_EXCLUDES.stream().anyMatch(pattern -> pattern.matcher(fileName).matches());
    }

    /**
     * Checks that {@code META-INF/legal/} of {@code jar} holds exactly {@code notices}, each once and byte for
     * byte, and returns whether it does.
     */
    private static boolean checkJar(Path jar, Map<String, byte[]> notices, Path source, Report report) {
        int problemsBefore = report.problemCount();
        Map<String, List<byte[]>> entries;
        try {
            entries = readJarNotices(jar);
        } catch (IOException e) {
            for (String notice : notices.keySet()) {
                report.problem(notice, jar, "cannot read the jar: " + e);
            }
            if (notices.isEmpty()) {
                report.problem(JAR_LEGAL_DIR, jar, "cannot read the jar: " + e);
            }
            return false;
        }
        for (Map.Entry<String, byte[]> notice : notices.entrySet()) {
            String name = notice.getKey();
            List<byte[]> copies = entries.getOrDefault(name, List.of());
            if (copies.isEmpty()) {
                if (!isDroppedFromJars(name)) {  // else readSourceNotices has reported why
                    report.problem(name, jar, "missing: the jar has no entry " + JAR_LEGAL_DIR + name);
                }
                continue;
            }
            if (copies.size() > 1) {
                report.problem(name, jar, "the entry " + JAR_LEGAL_DIR + name + " occurs " + copies.size()
                        + " times, expected once");
            }
            for (int i = 0; i < copies.size(); i++) {
                if (!Arrays.equals(copies.get(i), notice.getValue())) {
                    String which = copies.size() > 1 ? "copy " + (i + 1) + " of the entry " : "the entry ";
                    report.problem(name, jar, which + JAR_LEGAL_DIR + name + " differs from "
                            + source.resolve(name));
                }
            }
        }
        for (String name : entries.keySet()) {
            if (!notices.containsKey(name)) {
                report.problem(JAR_LEGAL_DIR + name, jar, "has no source: " + source + " holds no " + name);
            }
        }
        return report.problemCount() == problemsBefore;
    }

    /**
     * Returns the content of every entry under {@code META-INF/legal/} other than that directory itself, by name
     * relative to it, one array per occurrence of the name.
     */
    private static Map<String, List<byte[]>> readJarNotices(Path jar) throws IOException {
        Map<String, List<byte[]>> entries = new TreeMap<>();
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (name.startsWith(JAR_LEGAL_DIR) && name.length() > JAR_LEGAL_DIR.length()) {
                    entries.computeIfAbsent(name.substring(JAR_LEGAL_DIR.length()), key -> new ArrayList<>())
                            .add(zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void checkSdkLegalFiles(Path sdkLegal, Map<String, Path> expectedSources, Report report) {
        for (Map.Entry<String, Path> expected : expectedSources.entrySet()) {
            Path copy = sdkLegal.resolve(expected.getKey());
            Path source = expected.getValue();
            String notice = source.getFileName().toString();
            if (!Files.isRegularFile(copy)) {
                report.problem(notice, copy, "missing from the SDK (source " + source + ")");
                continue;
            }
            try {
                if (!Arrays.equals(Files.readAllBytes(copy), Files.readAllBytes(source))) {
                    report.problem(notice, copy, "differs from " + source);
                }
            } catch (IOException e) {
                report.problem(notice, copy, "cannot compare with " + source + ": " + e);
            }
        }
    }

    private static void checkNothingElseInSdkLegal(Path sdkLegal, Set<String> expectedFiles,
            Set<String> expectedDirectories, Report report) {
        if (!Files.isDirectory(sdkLegal)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sdkLegal)) {
            for (Path path : paths.skip(1).sorted().toList()) {
                String relative = sdkLegal.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    if (!expectedDirectories.contains(relative)) {
                        report.problem(relative + "/", path, "unexpected directory in the SDK: no module"
                                + " has notices of that name");
                    }
                } else if (!expectedFiles.contains(relative)) {
                    report.problem(relative, path, "unexpected file in the SDK: it is neither a licence"
                            + " file of the repository root nor a notice of a module's " + SOURCE_LEGAL_DIR);
                }
            }
        } catch (IOException e) {
            report.problem("legal/", sdkLegal, "cannot list the SDK legal directory: " + e);
        }
    }

    /** Checks the notices in every jar of the SDK's lib directory and returns the number of jars. */
    private static int checkSdkJars(Path repositoryRoot, Path lib, Map<String, Map<String, byte[]>> noticesByModule,
            Report report) {
        List<Path> jars;
        try (Stream<Path> files = Files.list(lib)) {
            jars = files.filter(file -> file.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            report.problem(JAR_LEGAL_DIR, lib, "cannot list the SDK jars: " + e);
            return 0;
        }
        Map<String, List<Path>> jarsByModule = new TreeMap<>();
        for (Path jar : jars) {
            String module;
            try {
                module = moduleName(jar);
            } catch (FindException e) {
                report.problem(JAR_LEGAL_DIR, jar, "cannot tell which module the jar holds: " + e);
                continue;
            }
            jarsByModule.computeIfAbsent(module, key -> new ArrayList<>()).add(jar);
            Path moduleDirectory = repositoryRoot.resolve("modules").resolve(module);
            if (!Files.isDirectory(moduleDirectory)) {
                report.problem(JAR_LEGAL_DIR, jar, "holds module " + module + ", which has no directory "
                        + moduleDirectory);
                continue;
            }
            Map<String, byte[]> notices = noticesByModule.getOrDefault(module, Map.of());
            if (checkJar(jar, notices, sourceLegalDirectory(repositoryRoot, module), report)) {
                report.pass(jar.getFileName() + " holds module " + module + " and carries its "
                        + notices.size() + " legal notice(s) under " + JAR_LEGAL_DIR + ", byte for byte");
            }
        }
        jarsByModule.forEach((module, moduleJars) -> {
            if (moduleJars.size() > 1) {
                report.problem(JAR_LEGAL_DIR, lib, moduleJars.size() + " jars hold module " + module
                        + ": " + moduleJars);
            }
        });
        noticesByModule.forEach((module, notices) -> {
            if (!jarsByModule.containsKey(module)) {
                for (String notice : notices.keySet()) {
                    report.problem(notice, lib, "missing: the SDK holds no jar of module " + module);
                }
            }
        });
        return jars.size();
    }

    /** Returns the module name {@link ModuleFinder} gives the jar, as the module path would. */
    private static String moduleName(Path jar) {
        Set<ModuleReference> modules = ModuleFinder.of(jar).findAll();
        if (modules.size() != 1) {
            throw new FindException(modules.size() + " modules found in " + jar);
        }
        return modules.iterator().next().descriptor().name();
    }

    private static Path sourceLegalDirectory(Path repositoryRoot, String module) {
        return repositoryRoot.resolve("modules").resolve(module).resolve(SOURCE_LEGAL_DIR);
    }
}

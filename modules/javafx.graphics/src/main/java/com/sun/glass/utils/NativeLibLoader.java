/*
 * Copyright (c) 2010, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.glass.utils;

import com.sun.javafx.PlatformUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads the native libraries of JavaFX and resolves their symbols.
 * <p>
 * A library is opened with {@link SymbolLookup#libraryLookup(Path, Arena)} in {@link Arena#global()}, not with
 * {@code System.load}: the JDK opens it through the same {@code JVM_LoadLibrary} and {@code os::dll_load} as
 * {@code System.load} ({@code LoadLibrary} on Windows, {@code dlopen(RTLD_LAZY)} elsewhere), but never looks up or
 * runs a {@code JNI_OnLoad} it exports, and the global arena keeps it loaded until the JVM exits, as a library
 * {@code System.load} loaded for a built-in class loader stays. On macOS a path that does not exist is handed to dyld
 * as it is, through {@link SymbolLookup#libraryLookup(String, Arena)}, as {@code System.load} hands it there. Each step
 * checks native access before it looks for a file, as {@code System.load} and {@code System.loadLibrary} do. The
 * search order and its locations are those of commit {@code 0fd9cb0ef5}, which ended in {@code System.load} and
 * {@code System.loadLibrary}; the last step searches the directories {@code System.loadLibrary} searched, in its
 * order, and fails with its error.
 * <p>
 * The symbols of what was loaded are found through {@link #libraryLookup(String)}, one library, and
 * {@link #loadedLibrariesLookup()}, every library this class has loaded: what {@link SymbolLookup#loaderLookup()}
 * answered while the libraries were loaded with {@code System.load}, and no longer can.
 */
public class NativeLibLoader {

    /** A library this class opened: the canonical path it was opened from and its symbols. */
    private record Library(Path path, SymbolLookup lookup) {
    }

    /** Every library loaded so far, by the name it was loaded under. */
    private static final Map<String, Library> loaded = new ConcurrentHashMap<>();

    /** Every library opened so far, by canonical path: a file is opened once. Guarded by the class lock. */
    private static final Map<String, Library> opened = new HashMap<>();

    /** Every library opened so far, in the order it was opened. */
    private static final List<Library> inLoadOrder = new CopyOnWriteArrayList<>();

    private static final SymbolLookup LOADED_LIBRARIES = NativeLibLoader::findInLoadedLibraries;

    public static synchronized void loadLibrary(String libname) {
        if (!loaded.containsKey(libname)) {
            StackWalker walker = StackWalker.
                getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            Class caller = walker.getCallerClass();
            loaded.put(libname, loadLibraryInternal(libname, null, caller));
        }
    }

    public static synchronized void loadLibrary(String libname, List<String> dependencies) {
        if (!loaded.containsKey(libname)) {
            StackWalker walker = StackWalker.
                getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            Class caller = walker.getCallerClass();
            loaded.put(libname, loadLibraryInternal(libname, dependencies, caller));
        }
    }

    /**
     * Loads {@code libname} from the directories {@code System.loadLibrary(libname)} searches for the calling class
     * - {@code sun.boot.library.path}, then, unless the class belongs to the boot loader, {@code java.library.path}
     * - in that order and with its file names, and fails with its {@link UnsatisfiedLinkError}. It is for a library
     * that is not part of the JavaFX runtime, which its caller loaded with {@code System.loadLibrary} rather than
     * through {@link #loadLibrary(String)} and its search; its symbols are found as those of every other library
     * loaded here.
     *
     * @param libname the library name, without platform prefix, suffix or directory
     * @throws UnsatisfiedLinkError if no directory holds the library, or one does and it cannot be loaded
     */
    public static synchronized void loadLibraryFromSystemPaths(String libname) {
        if (!loaded.containsKey(libname)) {
            StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
            loaded.put(libname, loadFromSystemLibraryPaths(walker.getCallerClass(), libname));
        }
    }

    /**
     * The symbols of the library loaded as {@code libname}, or an empty optional if no library of that name has been
     * loaded.
     */
    public static Optional<SymbolLookup> libraryLookup(String libname) {
        return Optional.ofNullable(loaded.get(libname)).map(Library::lookup);
    }

    /**
     * The symbols of every library this class has loaded, searched in the order they were loaded. The lookup is
     * live: a library loaded after it was obtained is searched as well. It stands where
     * {@link SymbolLookup#loaderLookup()} stood while the libraries were loaded with {@code System.load}, which the
     * loader lookup searched (in no defined order), together with any other library {@code System.load} had loaded
     * for the same class loader; a symbol is found as global memory, as the loader lookup of a built-in class loader
     * returns it.
     */
    public static SymbolLookup loadedLibrariesLookup() {
        return LOADED_LIBRARIES;
    }

    /** The canonical path the library loaded as {@code libname} was opened from; for tests, through a shim. */
    static Optional<Path> libraryPath(String libname) {
        return Optional.ofNullable(loaded.get(libname)).map(Library::path);
    }

    /** How many distinct files this class has opened; for tests, through a shim. */
    static int openedLibraryCount() {
        return inLoadOrder.size();
    }

    private static Optional<MemorySegment> findInLoadedLibraries(String name) {
        Objects.requireNonNull(name);
        for (Library library : inLoadOrder) {
            Optional<MemorySegment> symbol = library.lookup().find(name);
            if (symbol.isPresent()) {
                return symbol;
            }
        }
        return Optional.empty();
    }

    private static boolean verbose = false;

    private static File libDir = null;
    private static String libPrefix = "";
    private static String libSuffix = "";

    static {
        verbose = Boolean.getBoolean("javafx.verbose");
    }

    private static String[] initializePath(String propname) {
        String ldpath = System.getProperty(propname, "");
        String ps = File.pathSeparator;
        int ldlen = ldpath.length();
        int i, j, n;
        // Count the separators in the path
        i = ldpath.indexOf(ps);
        n = 0;
        while (i >= 0) {
            n++;
            i = ldpath.indexOf(ps, i + 1);
        }

        // allocate the array of paths - n :'s = n + 1 path elements
        String[] paths = new String[n + 1];

        // Fill the array with paths from the ldpath
        n = i = 0;
        j = ldpath.indexOf(ps);
        while (j >= 0) {
            if (j - i > 0) {
                paths[n++] = ldpath.substring(i, j);
            } else if (j - i == 0) {
                paths[n++] = ".";
            }
            i = j + 1;
            j = ldpath.indexOf(ps, i);
        }
        paths[n] = ldpath.substring(i, ldlen);
        return paths;
    }

    private static Library loadLibraryInternal(String libraryName, List<String> dependencies, Class caller) {
        // The search order for native library loading is:
        // - try to load the native library from either ${java.home}
        //   (for jlinked javafx modules) or from the same folder as
        //   this jar (if using modular jars)
        // - if the native library comes bundled as a resource it is extracted
        //   and loaded
        // - the java.library.path is searched for the library in definition
        //   order
        // - the directories System#loadLibrary searches are searched, in its
        //   order
        try {
            // FIXME: JIGSAW -- We should eventually remove this legacy path,
            // since it isn't applicable to Jigsaw.
            return loadLibraryFullPath(libraryName);
        } catch (UnsatisfiedLinkError ex) {
            if (verbose) {
                System.err.println("WARNING: " + ex);
            }

            // if the library is available in the jar, copy it to cache and load it from there
            Library fromResource = loadLibraryFromResource(libraryName, dependencies, caller);
            if (fromResource != null) {
                return fromResource;
            }

            // NOTE: First attempt to load the libraries from the java.library.path.
            // This allows FX to find more recent versions of the shared libraries
            // from java.library.path instead of ones that might be part of the JRE
            //
            String [] libPath = initializePath("java.library.path");
            for (int i=0; i<libPath.length; i++) {
                try {
                    String path = libPath[i];
                    if (!path.endsWith(File.separator)) path += File.separator;
                    String fileName = System.mapLibraryName(libraryName);
                    File libFile = new File(path + fileName);
                    Library library = load(libFile.getAbsolutePath());
                    if (verbose) {
                        System.err.println("Loaded " + libFile.getAbsolutePath()
                                + " from java.library.path");
                    }
                    return library;
                } catch (UnsatisfiedLinkError ex3) {
                    // Fail silently and try the next directory in java.library.path
                }
            }

            // Finally we will search where System.loadLibrary would have searched.
            // The verbose message keeps the wording it had when this step called it.
            Library library = loadFromSystemLibraryPaths(NativeLibLoader.class, libraryName);
            if (verbose) {
                System.err.println("System.loadLibrary("
                        + libraryName + ") succeeded");
            }
            return library;
        }
    }

   /**
    * If there is a library with the platform-correct name at the
    * root of the resources in this jar, use that.
    */
    private static Library loadLibraryFromResource(String libraryName, List<String> dependencies, Class caller) {
        // first preload dependencies
        if (dependencies != null) {
            for (String dep: dependencies) {
                boolean hasdep = installLibraryFromResource(dep, caller);
            }
        }
        try {
            String reallib = "/"+System.mapLibraryName(libraryName);
            InputStream is = caller.getResourceAsStream(reallib);
            if (is != null) {
                String fp = cacheLibrary(is, reallib, caller);
                Library library = load(fp);
                if (verbose) {
                    System.err.println("Loaded library " + reallib + " from resource");
                }
                return library;
            }
        } catch (Throwable t) {
            // we should only be here if the resource exists in the module, but
            // for some reasons it can't be loaded.
            System.err.println("Loading library " + libraryName + " from resource failed: " + t);
            t.printStackTrace();
        }
        return null;
    }

   /**
    * If there is a library with the platform-correct name at the
    * root of the resources in this jar, install it without loading it.
    */
    private static boolean installLibraryFromResource(String libraryName, Class caller) {
        try {
            String reallib = "/"+System.mapLibraryName(libraryName);
            InputStream is = caller.getResourceAsStream(reallib);
            if (is != null) {
                cacheLibrary(is, reallib, caller);
                if (verbose) {
                    System.err.println("Unpacked library " + reallib + " from resource");
                }
                return true;
            }
        } catch (Throwable t) {
            // we should only be here if the resource exists in the module, but
            // for some reasons it can't be loaded.
            System.err.println("Loading library " + libraryName + " from resource failed: " + t);
            t.printStackTrace();
        }
        return false;
    }

    private static String cacheLibrary(InputStream is, String name, Class caller) throws IOException {
        String jfxVersion = System.getProperty("javafx.runtime.version", "versionless");
        // This fixes an issue with windows - ":" is not allowed for file on Windows.
        jfxVersion = jfxVersion.replace(":", "-");
        String userCache = System.getProperty("javafx.cachedir", "");
        String arch = System.getProperty("os.arch");
        if (userCache.isEmpty()) {
            userCache = System.getProperty("user.home") + "/.openjfx/cache/" + jfxVersion + "/" + arch;
        }
        File cacheDir = new File(userCache);
        boolean cacheDirOk = true;
        if (cacheDir.exists()) {
            if (!cacheDir.isDirectory()) {
                System.err.println("Cache exists but is not a directory: "+cacheDir);
                cacheDirOk = false;
            }
        } else {
            if (!cacheDir.mkdirs()) {
                System.err.println("Can not create cache at "+cacheDir);
                cacheDirOk = false;
            }
        }
        if (!cacheDir.canRead()) {
            // on some systems, directories in user.home can be written but not read.
            cacheDirOk = false;
        }
        if (!cacheDirOk) {
            String username = System.getProperty("user.name", "anonymous");
            String tmpCache = System.getProperty("java.io.tmpdir") + "/.openjfx_" + username
                    + "/cache/" + jfxVersion + "/" + arch;
            cacheDir = new File(tmpCache);
            if (cacheDir.exists()) {
                if (!cacheDir.isDirectory()) {
                    throw new IOException("Cache exists but is not a directory: "+cacheDir);
                }
            } else {
                if (!cacheDir.mkdirs()) {
                    throw new IOException("Can not create cache at "+cacheDir);
                }
            }
        }
        // we have a cache directory. Add the file here
        File f = new File(cacheDir, name);
        // if it exists, calculate checksum and keep if same as inputstream.
        boolean write = true;
        if (f.exists()) {
            byte[] isHash;
            byte[] fileHash;
            try {
                DigestInputStream dis = new DigestInputStream(is, MessageDigest.getInstance("MD5"));
                dis.getMessageDigest().reset();
                byte[] buffer = new byte[4096];
                while (dis.read(buffer) != -1) { /* empty loop body is intentional */ }
                isHash = dis.getMessageDigest().digest();
                is.close();
                is = caller.getResourceAsStream(name); // mark/reset not supported, we have to reread
            }
            catch (NoSuchAlgorithmException nsa) {
                isHash = new byte[1];
            }
            fileHash = calculateCheckSum(f);
            if (!Arrays.equals(isHash, fileHash)) {
                Files.delete(f.toPath());
            } else {
                // hashes are the same, we already have the file.
                write = false;
            }
        }
        if (write) {
            Path path = f.toPath();
            File lockFile = new File(cacheDir, ".lock");
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(lockFile, "rw");
                 FileChannel fileChannel = randomAccessFile.getChannel();
                 FileLock fileLock = fileChannel.lock()) {
                try {
                    if (!Files.exists(path)) {
                        Files.copy(is, path);
                    }
                } finally {
                    if (fileLock != null) {
                        fileLock.release();
                    }
                }
            } catch (IOException ex) {
                throw new IOException("Error copying library " + path + " to cache: " + ex.getMessage(), ex);
            }
        }

        String fp = f.getAbsolutePath();
        return fp;
    }

    static byte[] calculateCheckSum(File file) {
        try {
                // not looking for security, just a checksum. MD5 should be faster than SHA
                try (final InputStream stream = new FileInputStream(file);
                    final DigestInputStream dis = new DigestInputStream(stream, MessageDigest.getInstance("MD5")); ) {
                    dis.getMessageDigest().reset();
                    byte[] buffer = new byte[4096];
                    while (dis.read(buffer) != -1) { /* empty loop body is intentional */ }
                    return dis.getMessageDigest().digest();
                }

        } catch (IllegalArgumentException | NoSuchAlgorithmException | IOException | SecurityException e) {
            // IOException also covers MalformedURLException
            // SecurityException means some untrusted app

            // Fall through...
        }
        return new byte[0];
    }


    private static File libDirForJRT() {
        String javaHome = System.getProperty("java.home");

        if (javaHome == null || javaHome.isEmpty()) {
            throw new UnsatisfiedLinkError("Cannot find java.home");
        }

        // Set the native directory based on the OS
        String relativeDir = null;
        if (PlatformUtil.isWindows()) {
            relativeDir = "bin/javafx";
        } else if (PlatformUtil.isMac()) {
            relativeDir = "lib";
        } else if (PlatformUtil.isLinux()) {
            relativeDir = "lib";
        }

        // Location of native libraries relative to java.home
        return new File(javaHome + "/" + relativeDir);
    }

    private static File libDirForJarFile(String classUrlString) throws Exception {
        // Strip out the "jar:" and everything after and including the "!"
        String tmpStr = classUrlString.substring(4, classUrlString.lastIndexOf('!'));
        // Strip everything after the last "/" or "\" to get rid of the jar filename
        int lastIndexOfSlash = Math.max(tmpStr.lastIndexOf('/'), tmpStr.lastIndexOf('\\'));

        // Set the native directory based on the OS
        String relativeDir = null;
        if (PlatformUtil.isWindows()) {
            relativeDir = "../bin";
        } else if (PlatformUtil.isMac()) {
            relativeDir = ".";
        } else if (PlatformUtil.isLinux()) {
            relativeDir = ".";
        }

        // Location of native libraries relative to jar file
        String libDirUrlString = tmpStr.substring(0, lastIndexOfSlash)
                + "/" + relativeDir;
        return new File(new URI(libDirUrlString).getPath());
    }

    /**
     * Load the native library either from the same directory as the jar file
     * containing this class, or from the Java runtime.
     */
    private static Library loadLibraryFullPath(String libraryName) {
        try {
            if (libDir == null) {
                // Get the URL for this class, if it is a jar URL, then get the
                // filename associated with it.
                String theClassFile = "NativeLibLoader.class";
                Class theClass = NativeLibLoader.class;
                String classUrlString = theClass.getResource(theClassFile).toString();
                if (classUrlString.startsWith("jrt:")) {
                    libDir = libDirForJRT();
                } else if (classUrlString.startsWith("jar:file:") && classUrlString.indexOf('!') > 0) {
                    libDir = libDirForJarFile(classUrlString);
                } else {
                    throw new UnsatisfiedLinkError("Invalid URL for class: " + classUrlString);
                }

                // Set the lib prefix and suffix based on the OS
                if (PlatformUtil.isWindows()) {
                    libPrefix = "";
                    libSuffix = ".dll";
                } else if (PlatformUtil.isMac()) {
                    libPrefix = "lib";
                    libSuffix = ".dylib";
                } else if (PlatformUtil.isLinux()) {
                    libPrefix = "lib";
                    libSuffix = ".so";
                }
            }

            File libFile = new File(libDir, libPrefix + libraryName + libSuffix);
            String libFileName = libFile.getCanonicalPath();
            try {
                Library library = load(libFileName);
                if (verbose) {
                    System.err.println("Loaded " + libFile.getAbsolutePath()
                            + " from relative path");
                }
                return library;
            } catch(UnsatisfiedLinkError ex) {
                throw ex;
            }
        } catch (Exception e) {
            // Throw UnsatisfiedLinkError for best compatibility with System.loadLibrary()
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError().initCause(e);
        }
    }

    /**
     * Loads the library at {@code filename} as {@code System.load(filename)} did: native access is checked first, an
     * absolute path is required, a file that cannot be found fails with {@code "Can't load library: "} and the file,
     * and a file that exists but cannot be loaded fails as {@link #open} describes.
     */
    private static Library load(String filename) {
        requireNativeAccess();
        File file = new File(filename);
        if (!file.isAbsolute()) {
            throw new UnsatisfiedLinkError("Expecting an absolute path of the library: " + filename);
        }
        Library library = open(file);
        if (library == null) {
            throw new UnsatisfiedLinkError("Can't load library: " + file);
        }
        return library;
    }

    /**
     * Searches {@code sun.boot.library.path} and then, unless {@code fromClass} belongs to the boot loader,
     * {@code java.library.path}, in order, for the file {@code System.mapLibraryName} names - on macOS also for its
     * {@code .jnilib} alternative - and loads the first that exists; the search {@code System.loadLibrary} makes for
     * a class of a built-in class loader ({@code ClassLoader.loadLibrary}, {@code NativeLibraries.loadLibrary}), with
     * its failures. Native access is checked first, as {@code System.loadLibrary} does; it is checked for
     * {@code javafx.graphics}, the module of this class. The two properties are read when the search runs.
     */
    private static Library loadFromSystemLibraryPaths(Class<?> fromClass, String libraryName) {
        requireNativeAccess();
        if (libraryName.indexOf(File.separatorChar) != -1) {
            throw new UnsatisfiedLinkError(
                    "Directory separator should not appear in library name: " + libraryName);
        }
        boolean bootLoader = fromClass.getClassLoader() == null;
        String systemPath = System.getProperty("sun.boot.library.path", "");
        String userPath = System.getProperty("java.library.path", "");
        List<String> directories = new ArrayList<>(Arrays.asList(parsePath(systemPath)));
        if (!bootLoader) {
            directories.addAll(Arrays.asList(parsePath(userPath)));
        }
        for (String directory : directories) {
            File file = new File(directory, System.mapLibraryName(libraryName));
            Library library = open(file);
            if (library == null && PlatformUtil.isMac()) {
                File alternative = jnilibAlternative(file);
                if (alternative != null) {
                    library = open(alternative);
                }
            }
            if (library != null) {
                return library;
            }
        }
        throw new UnsatisfiedLinkError(bootLoader
                ? "no " + libraryName + " in system library path: " + systemPath
                : "no " + libraryName + " in java.library.path: " + userPath);
    }

    /**
     * Whether a file that does not exist is still handed to the operating system loader, as {@code System.load}
     * hands it where {@code ClassLoaderHelper.loadLibraryOnlyIfPresent()} is false: on macOS, where dyld may resolve
     * such a path ({@code DYLD_LIBRARY_PATH} or {@code DYLD_FALLBACK_LIBRARY_PATH} by leaf name, its shared cache).
     * Elsewhere such a file is skipped without a load. A test can switch the rule through a shim.
     */
    private static volatile boolean openAbsentFiles = PlatformUtil.isMac();

    /** Sets {@link #openAbsentFiles} and returns the previous value; for tests, through a shim. */
    static boolean setOpenAbsentFiles(boolean open) {
        boolean previous = openAbsentFiles;
        openAbsentFiles = open;
        return previous;
    }

    /**
     * Opens {@code file} unless a file of the same canonical path is open already, and answers {@code null} when it
     * has no canonical path or does not exist - unless {@link #openAbsentFiles}, which hands an absent file's path to
     * the operating system loader and answers {@code null} only when that fails, as {@code System.load} did there. A
     * file that exists but that the operating system refuses to load fails, as with {@code System.load}, with an
     * {@link UnsatisfiedLinkError} that begins with its canonical path; {@code System.load} went on with the operating
     * system's reason, which {@code java.lang.foreign} does not report ({@code -Xlog:os=info} logs it).
     */
    private static Library open(File file) {
        boolean exists = file.exists();
        if (!exists && !openAbsentFiles) {
            return null;
        }
        String name;
        try {
            name = file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
        Library library = opened.get(name);
        if (library == null) {
            Path path = Path.of(name);
            SymbolLookup lookup;
            try {
                // The Path form resolves the path before the load and so fails on an absent file before any load;
                // the String form hands the path to the operating system loader as it is, as System.load does.
                lookup = exists ? openExisting(path) : openAbsent(name);
            } catch (IllegalArgumentException e) {
                if (!exists) {
                    return null;
                }
                UnsatisfiedLinkError error = new UnsatisfiedLinkError(name
                        + ": the operating system could not load this library (-Xlog:os=info shows why)");
                error.initCause(e);
                throw error;
            }
            library = new Library(path, lookup);
            opened.put(name, library);
            inLoadOrder.add(library);
        }
        return library;
    }

    /**
     * Checks that {@code javafx.graphics} has native access, and does nothing else: the check
     * {@code System.load} and {@code System.loadLibrary} made before they looked for a file. Without it a search that
     * finds no file would never make a restricted call, so under {@code --illegal-native-access=deny} it would end
     * in a not-found error instead of the {@link IllegalCallerException} it ended in, and the warning of the default
     * mode would come later or not at all.
     */
    @SuppressWarnings("restricted")
    private static void requireNativeAccess() {
        MemorySegment.NULL.reinterpret(0);
    }

    /**
     * Opens the library at {@code path}, which exists. Restricted, as {@code System.load} is; the global arena is
     * never closed, so the library is never unloaded.
     */
    @SuppressWarnings("restricted")
    private static SymbolLookup openExisting(Path path) {
        return SymbolLookup.libraryLookup(path, Arena.global());
    }

    /** Hands {@code name}, a path that does not exist, to the operating system loader, as {@code System.load} did. */
    @SuppressWarnings("restricted")
    private static SymbolLookup openAbsent(String name) {
        return SymbolLookup.libraryLookup(name, Arena.global());
    }

    /** {@code ClassLoaderHelper.mapAlternativeName} on macOS: the last extension replaced with {@code .jnilib}. */
    private static File jnilibAlternative(File file) {
        String name = file.toString();
        int index = name.lastIndexOf('.');
        return index < 0 ? null : new File(name.substring(0, index) + ".jnilib");
    }

    /**
     * {@code ClassLoaderHelper.parsePath}: the elements of a path list, an empty one standing for {@code "."}. On
     * Windows a double-quoted part keeps any separator inside it, and the quotes are dropped.
     */
    private static String[] parsePath(String ldPath) {
        char ps = File.pathSeparatorChar;
        if (PlatformUtil.isWindows() && ldPath.indexOf('"') >= 0) {
            // A quoted part is copied without its quotes, and each separator outside one becomes a quote,
            // which then separates the elements.
            StringBuilder buf = new StringBuilder(ldPath.length());
            for (int i = 0; i < ldPath.length(); ++i) {
                char ch = ldPath.charAt(i);
                if (ch == '"') {
                    while (++i < ldPath.length() && (ch = ldPath.charAt(i)) != '"') {
                        buf.append(ch);
                    }
                } else {
                    buf.append(ch == ps ? '"' : ch);
                }
            }
            ldPath = buf.toString();
            ps = '"';
        }
        List<String> paths = new ArrayList<>();
        int pathStart = 0;
        int pathEnd;
        while ((pathEnd = ldPath.indexOf(ps, pathStart)) >= 0) {
            paths.add(pathStart < pathEnd ? ldPath.substring(pathStart, pathEnd) : ".");
            pathStart = pathEnd + 1;
        }
        paths.add(pathStart < ldPath.length() ? ldPath.substring(pathStart) : ".");
        return paths.toArray(new String[0]);
    }

}

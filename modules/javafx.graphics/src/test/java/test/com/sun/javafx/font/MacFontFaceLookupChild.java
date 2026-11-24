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

package test.com.sun.javafx.font;

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.MacFontOracleShim;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CTFactory;
import com.sun.javafx.font.coretext.CTFactoryShim;
import com.sun.javafx.font.coretext.CoreTextPeerShim;
import com.sun.javafx.font.coretext.OSShim;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;
import test.com.sun.javafx.font.MacFontReleaseChild.FaceRun;

/**
 * The child JVM of {@link MacFontFaceLookupTest}: one scenario per check of how layout looks a fallback face up in the
 * file its run font reports, remembers a file without the face while that file stays the same, and shares one lookup
 * among the callers that need the face at once. The files are the scenario's own copies of Ahem and Helvetica.ttc.
 */
public final class MacFontFaceLookupChild {

    static final String MISS_VERSION = "lookup.missVersion";
    static final String CHANGED_FILE = "lookup.changedFile";
    static final String MOVED_FILE = "lookup.movedFile";
    static final String SHARED_FOUND = "lookup.sharedFound";
    static final String SHARED_MISSING = "lookup.sharedMissing";
    static final String SHARED_FAILED = "lookup.sharedFailed";
    static final String LATE_LEADER = "lookup.lateLeader";
    static final String TWO_FILES = "lookup.twoFiles";

    static final String FACE = MacFontReleaseChild.FACE;

    /** Rounds of {@link #TWO_FILES}'s layouts that alternate between two files without the face. */
    static final int ALTERNATIONS = 3;

    /** Layouts of a face its file lacks before the file or the path changes. */
    static final int LAYOUTS = 2;

    /** Invented: no face of Helvetica.ttc has these names. */
    static final String NO_SUCH_FACE = "US-111 No Such Face";
    static final String NO_FILE_FACE = "US-111 No File Face";
    static final String NO_FONT_FACE = "US-111 No Font Face";

    /** The callers of a shared lookup and the prefixes of their keys; the first runs the lookup. */
    static final List<String> CALLERS = List.of("leader", "platform", "virtual");

    /** What the held lookup of {@link #SHARED_FAILED} does once released, and the prefix of its keys. */
    enum Failure {
        UNREADABLE, ERROR
    }

    /** The modification time every file of {@link #MISS_VERSION} starts at: whole seconds, so a reset is exact. */
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** How long the scenario waits for a thread to wait or end; it bounds only a failure. */
    private static final long WAIT_SECONDS = 20;

    /** How long the first read holds its lookup at most: beyond {@link #WAIT_SECONDS}, released before. */
    private static final long HOLD_SECONDS = 60;

    private MacFontFaceLookupChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case MISS_VERSION -> missVersion(out);
            case CHANGED_FILE -> changedFile(out);
            case MOVED_FILE -> movedFile(out);
            case SHARED_FOUND -> sharedLookup(out, SHARED_FOUND, "ahem", null, "");
            case SHARED_MISSING -> sharedLookup(out, SHARED_MISSING, "helvetica", null, "");
            case SHARED_FAILED -> {
                for (Failure failure : Failure.values()) {
                    String prefix = failure.name().toLowerCase(Locale.ROOT) + ".";
                    sharedLookup(out, SHARED_FAILED + "." + prefix, "ahem", failure, prefix);
                }
            }
            case TWO_FILES -> twoFiles(out);
            case LATE_LEADER -> {
                lateLeader(out, "ahem", "found.");
                lateLeader(out, "helvetica", "missing.");
            }
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacFontFaceLookupChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    /**
     * Whether a factory of the scenario's own would look up {@link #NO_SUCH_FACE} again in a copy of Helvetica.ttc
     * once a lookup, of a font made from a second copy, found no face of it there: with the file unchanged, under
     * another case, another path or no path; once only its modification time, only its size or only its file key
     * changed, each remembered again after; once it is gone and once it is back. Each change is written with what it
     * changed of the three. Then a lookup whose read is followed by a change of the file's time, in a second factory;
     * a lookup of {@link #NO_FONT_FACE} in a file that is no font; and one of {@link #NO_FILE_FACE} for a font that
     * reports no file. The other path is a hard link to the file, the same file in the same version.
     */
    private static void missVersion(GoldenMap out) throws Exception {
        Path directory = MacFontReleaseChild.fontDirectory(MISS_VERSION);
        Path file = directory.resolve("face.ttc");
        Path fontFile = directory.resolve("font.ttc");
        Files.copy(MacFontGoldens.pinned("helvetica"), file);
        Files.copy(MacFontGoldens.pinned("helvetica"), fontFile);
        Files.setLastModifiedTime(file, FileTime.from(START));
        Path other = Files.createLink(directory.resolve("other.ttc"), file);
        String path = file.toRealPath().toString();
        String otherPath = other.toRealPath().toString();
        long font = MacFontReleaseChild.fontOfFile(fontFile.toRealPath().toString());
        AtomicInteger reads = new AtomicInteger();
        CTFactory factory = CTFactoryShim.newFactory((face, target, own) -> {
            reads.incrementAndGet();
            return own.call();
        }, new AtomicInteger());
        out.put("before", may(factory, NO_SUCH_FACE, path));
        out.put("made", fontFileOf(factory, NO_SUCH_FACE, path, font));
        out.put("same", may(factory, NO_SUCH_FACE, path));
        out.put("otherCase", may(factory, NO_SUCH_FACE.toUpperCase(Locale.ROOT), path));
        out.put("otherPath", may(factory, NO_SUCH_FACE, otherPath));
        out.put("otherPath.changed", changed(attributes(file), attributes(other)));
        out.put("noPath", may(factory, NO_SUCH_FACE, null));

        BasicFileAttributes before = attributes(file);
        Files.setLastModifiedTime(file, FileTime.from(START.plusSeconds(3600)));
        remembered(out, "time", factory, path, font, before, attributes(file));

        before = attributes(file);
        Files.write(file, new byte[4], StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, before.lastModifiedTime());
        remembered(out, "size", factory, path, font, before, attributes(file));

        before = attributes(file);
        Path swap = directory.resolve("swap.ttc");
        Files.copy(file, swap);
        Files.setLastModifiedTime(swap, before.lastModifiedTime());
        Files.move(swap, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        remembered(out, "key", factory, path, font, before, attributes(file));

        Files.delete(file);
        out.put("gone", may(factory, NO_SUCH_FACE, path));
        Files.copy(MacFontGoldens.pinned("helvetica"), file);
        out.put("back", may(factory, NO_SUCH_FACE, path));
        out.put("reads", Integer.toString(reads.get()));

        CTFactory changing = CTFactoryShim.newFactory((face, target, own) -> {
            int index = own.call();
            Files.setLastModifiedTime(Path.of(target), FileTime.from(START.plusSeconds(7200)));
            return index;
        }, new AtomicInteger());
        out.put("readChanged.made", fontFileOf(changing, NO_SUCH_FACE, path, font));
        out.put("readChanged.remembered",
                Boolean.toString(CTFactoryShim.remembersAMiss(changing, NO_SUCH_FACE, path)));
        out.put("readChanged", may(changing, NO_SUCH_FACE, path));

        Path garbage = directory.resolve("garbage.ttf");
        Files.write(garbage, new byte[64]);
        String garbagePath = garbage.toRealPath().toString();
        out.put("garbage.thrown", thrownBy(factory, NO_FONT_FACE, garbagePath, font));
        out.put("garbage.remembered",
                Boolean.toString(CTFactoryShim.remembersAMiss(factory, NO_FONT_FACE, garbagePath)));
        out.put("garbage", may(factory, NO_FONT_FACE, garbagePath));

        int readsBeforeNoFile = reads.get();
        out.put("noFile.made", fontFileOf(factory, NO_FILE_FACE, null, font));
        out.put("noFile", may(factory, NO_FILE_FACE, null));
        out.put("noFile.again", may(factory, NO_FILE_FACE, null));
        out.put("noFile.withAFile", may(factory, NO_FILE_FACE, path));
        out.put("noFile.reads", Integer.toString(reads.get() - readsBeforeNoFile));
        MacFontOracleShim.release(font);
    }

    /** What a change of the file changed, whether the miss is looked up again, and whether a new lookup keeps it. */
    private static void remembered(GoldenMap out, String key, CTFactory factory, String path, long font,
                                   BasicFileAttributes before, BasicFileAttributes after) throws Exception {
        out.put(key + ".changed", changed(before, after));
        out.put(key, may(factory, NO_SUCH_FACE, path));
        out.put(key + ".made", fontFileOf(factory, NO_SUCH_FACE, path, font));
        out.put(key + ".again", may(factory, NO_SUCH_FACE, path));
    }

    /**
     * Layout's slot for the run in Ahem of {@code MacFontReleaseChild.swappedFace}, {@link #LAYOUTS} times while its
     * file holds Helvetica.ttc, then once Ahem is copied back in its place. With {@code prism.debugfonts} the factory
     * prints each lookup. The preconditions are those of the swapped face, and the file a fresh copy of the run's font
     * reports once Ahem is back.
     */
    private static void changedFile(GoldenMap out) throws Exception {
        FaceRun face = MacFontReleaseChild.swappedFace(out, CHANGED_FILE);
        missingLayouts(out, face);
        Path file = Path.of(out.get("file"));
        Files.delete(file);
        Files.copy(MacFontGoldens.pinned("ahem"), file);
        out.put("file.ofACopyOnceBack", MacFontReleaseChild.fileOfACopy(MacFontReleaseChild.runFont(face.run())));
        found(out, "found", face);
        face.release();
    }

    /**
     * Layout's slot for the run in Ahem of {@code MacFontReleaseChild.swappedFace}, {@link #LAYOUTS} times, then for
     * the run in Ahem of a line of "A" in a second font made from a copy of Ahem in another directory, laid out in the
     * same System font. The preconditions are those of the swapped face, and the file the second font reports, for
     * itself and for a fresh copy of its run's font, and its run font's name.
     */
    private static void movedFile(GoldenMap out) throws Exception {
        FaceRun face = MacFontReleaseChild.swappedFace(out, MOVED_FILE);
        missingLayouts(out, face);
        Path directory = MacFontGoldens.scenarioDirectory(MOVED_FILE).resolve("other");
        FontGoldenRuntime.deleteTree(directory);
        Path file = Files.createDirectories(directory).resolve("face.ttf");
        Files.copy(MacFontGoldens.pinned("ahem"), file);
        String path = file.toRealPath().toString();
        long font = MacFontReleaseChild.fontOfFile(path);
        long line = CoreTextPeerShim.line(font, "A");
        long run = MacFontReleaseChild.firstRun(line);
        out.put("moved.file", path);
        out.put("moved.reported", OSShim.CTFontCopyURLAttribute(font));
        out.put("moved.run.font", MacFontReleaseChild.fontName(MacFontReleaseChild.runFont(run)));
        out.put("moved.file.ofACopy", MacFontReleaseChild.fileOfACopy(MacFontReleaseChild.runFont(run)));
        FaceRun moved = new FaceRun(font, line, run, face.system());
        found(out, "found", moved);
        moved.release();
        face.release();
    }

    private static void missingLayouts(GoldenMap out, FaceRun face) {
        for (int i = 0; i < LAYOUTS; i++) {
            out.put("missing." + i, Integer.toString(MacFontReleaseChild.fallbackSlot(face)));
        }
        out.put("debugFonts", String.valueOf(PrismFontFactory.debugFonts));
    }

    private static void found(GoldenMap out, String key, FaceRun face) {
        int slot = MacFontReleaseChild.fallbackSlot(face);
        out.put(key, Integer.toString(slot));
        out.put(key + ".font", slot < 0 ? null : face.system().getSlotResource(slot).getFullName());
    }

    /**
     * Three callers of layout's lookup of Ahem in the scenario's copy of {@code source}, each with a System font of its
     * own and a font made from a second copy, so that the first can be deleted; the factory's first read of the file
     * the scenario holds: the leader, whose lookup
     * that is, then a platform and a virtual thread, started once the leader is held, the platform one interrupted
     * once both wait. Then the file's reads and the copies of the font handed to the factory, each caller's slot,
     * failure and whether its slot holds the leader's font, the platform caller's interrupt after its call, the
     * lookups left in flight and whether the factory remembers a miss. With a {@code failure} the held read fails,
     * and a fourth call follows, on this thread, once the file holds Ahem.
     */
    private static void sharedLookup(GoldenMap out, String scenario, String source, Failure failure, String prefix)
            throws Exception {
        Path directory = MacFontReleaseChild.fontDirectory(scenario);
        Path file = directory.resolve("face");
        Path fontFile = directory.resolve("font");
        Files.copy(MacFontGoldens.pinned(source), file);
        Files.copy(MacFontGoldens.pinned(source), fontFile);
        String path = file.toRealPath().toString();
        long font = MacFontReleaseChild.fontOfFile(fontFile.toRealPath().toString());
        HeldRead read = new HeldRead(failure == Failure.ERROR);
        AtomicInteger copies = new AtomicInteger();
        CTFactory factory = CTFactoryShim.newFactory(read, copies);
        List<Caller> callers = new ArrayList<>();
        for (int i = 0; i < CALLERS.size(); i++) {
            callers.add(new Caller(factory, path, font));
        }
        List<Thread> threads = new ArrayList<>();
        threads.add(Thread.ofPlatform().name(scenario + ".leader").daemon().start(callers.get(0)));
        out.put(prefix + "leader.held", Boolean.toString(read.inside.await(WAIT_SECONDS, TimeUnit.SECONDS)));
        threads.add(Thread.ofPlatform().name(scenario + ".platform").daemon().start(callers.get(1)));
        threads.add(Thread.ofVirtual().name(scenario + ".virtual").start(callers.get(2)));
        out.put(prefix + "waiting", Boolean.toString(awaitWaiting(threads.get(1), false)
                && awaitWaiting(threads.get(2), false)));
        threads.get(1).interrupt();
        out.put(prefix + "interrupted.waitsOn", Boolean.toString(awaitWaiting(threads.get(1), true)));
        out.put(prefix + "inFlight.during", Integer.toString(CTFactoryShim.lookupsInFlight(factory)));
        if (failure == Failure.UNREADABLE) {
            Files.delete(file);
        }
        read.release.countDown();
        out.put(prefix + "finished", Boolean.toString(joined(threads)));
        out.put(prefix + "reads", Integer.toString(read.reads.get()));
        out.put(prefix + "copies", Integer.toString(copies.get()));
        FontResource leaderFont = callers.get(0).slotFont();
        for (int i = 0; i < CALLERS.size(); i++) {
            Caller caller = callers.get(i);
            String key = prefix + CALLERS.get(i);
            out.put(key + ".slot", caller.slot == Integer.MIN_VALUE ? null : Integer.toString(caller.slot));
            out.put(key + ".failure", caller.failure);
            out.put(key + ".leadersFont", Boolean.toString(leaderFont != null && caller.slotFont() == leaderFont));
        }
        out.put(prefix + "platform.interruptedAfter", Boolean.toString(callers.get(1).interruptedAfter));
        out.put(prefix + "inFlight.after", Integer.toString(CTFactoryShim.lookupsInFlight(factory)));
        out.put(prefix + "remembered", Boolean.toString(CTFactoryShim.remembersAMiss(factory, FACE, path)));
        out.put(prefix + "mayFindFace", may(factory, FACE, path));
        if (failure == Failure.UNREADABLE) {
            Files.copy(MacFontGoldens.pinned("ahem"), file);
        }
        if (failure != null || "helvetica".equals(source)) {
            Caller next = new Caller(factory, path, font);
            Thread nextThread = Thread.ofPlatform().name(scenario + ".next").daemon().start(next);
            out.put(prefix + "next.finished", Boolean.toString(joined(List.of(nextThread))));
            out.put(prefix + "next.slot", Integer.toString(next.slot));
            out.put(prefix + "next.failure", next.failure);
            out.put(prefix + "next.reads", Integer.toString(read.reads.get()));
            out.put(prefix + "next.copies", Integer.toString(copies.get()));
        }
        MacFontOracleShim.release(font);
    }

    /**
     * A caller that takes up a lookup of Ahem once another lookup of it has ended: the late caller, on a thread of its
     * own, is held right after the factory first answered whether it may find the face, until a second caller's whole
     * call, the leader's, has run on another thread; then it goes on. Each caller has a System font of its own, and the
     * font is made from a second copy of {@code source}. Then the reads of the file, the copies of the font handed to
     * the factory, the answer the late caller was held after, and each caller's slot and failure, and whether the late
     * caller's slot holds the leader's font.
     */
    private static void lateLeader(GoldenMap out, String source, String prefix) throws Exception {
        Path directory = MacFontReleaseChild.fontDirectory(LATE_LEADER + "." + prefix);
        Path file = directory.resolve("face");
        Path fontFile = directory.resolve("font");
        Files.copy(MacFontGoldens.pinned(source), file);
        Files.copy(MacFontGoldens.pinned(source), fontFile);
        String path = file.toRealPath().toString();
        long font = MacFontReleaseChild.fontOfFile(fontFile.toRealPath().toString());
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger copies = new AtomicInteger();
        LateQuery query = new LateQuery();
        CTFactory factory = CTFactoryShim.newFactory((face, target, own) -> {
            reads.incrementAndGet();
            return own.call();
        }, query, copies);
        Caller late = new Caller(factory, path, font);
        Caller leader = new Caller(factory, path, font);
        Thread lateThread = Thread.ofPlatform().name(LATE_LEADER + "." + prefix + "late").daemon().unstarted(late);
        query.late = lateThread;
        lateThread.start();
        out.put(prefix + "late.held", Boolean.toString(query.inside.await(WAIT_SECONDS, TimeUnit.SECONDS)));
        Thread leaderThread = Thread.ofPlatform().name(LATE_LEADER + "." + prefix + "leader").daemon().start(leader);
        out.put(prefix + "leader.finished", Boolean.toString(joined(List.of(leaderThread))));
        query.release.countDown();
        out.put(prefix + "late.finished", Boolean.toString(joined(List.of(lateThread))));
        out.put(prefix + "late.firstAnswer", query.firstAnswer);
        out.put(prefix + "reads", Integer.toString(reads.get()));
        out.put(prefix + "copies", Integer.toString(copies.get()));
        for (Caller caller : List.of(leader, late)) {
            String key = prefix + (caller == leader ? "leader" : "late");
            out.put(key + ".slot", caller.slot == Integer.MIN_VALUE ? null : Integer.toString(caller.slot));
            out.put(key + ".failure", caller.failure);
        }
        FontResource leaderFont = leader.slotFont();
        out.put(prefix + "late.leadersFont", Boolean.toString(leaderFont != null && late.slotFont() == leaderFont));
        MacFontOracleShim.release(font);
    }

    /**
     * Ahem in two files of the child's own, one that lacks it (a copy of Helvetica.ttc) and one that holds it (a copy
     * of Ahem), each with a font made from a second copy. With the first file's read held, a caller of the second file
     * runs a lookup of its own and ends while the first is held; then the first is released. Whether each file's miss
     * is remembered. Then, with a factory of its own, layouts of a System font that alternate {@link #ALTERNATIONS}
     * times between two copies of Helvetica.ttc: their reads of a file and their slots.
     */
    private static void twoFiles(GoldenMap out) throws Exception {
        Path directory = MacFontReleaseChild.fontDirectory(TWO_FILES);
        String lacking = copy(directory, "lacking", "helvetica");
        String holding = copy(directory, "holding", "ahem");
        long lackingFont = MacFontReleaseChild.fontOfFile(copy(directory, "lackingFont", "helvetica"));
        long holdingFont = MacFontReleaseChild.fontOfFile(copy(directory, "holdingFont", "ahem"));
        HeldRead read = new HeldRead(false);
        CTFactory factory = CTFactoryShim.newFactory(read, new AtomicInteger());
        Caller lackingCaller = new Caller(factory, lacking, lackingFont);
        Caller holdingCaller = new Caller(factory, holding, holdingFont);
        Thread lackingThread = Thread.ofPlatform().name(TWO_FILES + ".lacking").daemon().start(lackingCaller);
        out.put("lacking.held", Boolean.toString(read.inside.await(WAIT_SECONDS, TimeUnit.SECONDS)));
        Thread holdingThread = Thread.ofPlatform().name(TWO_FILES + ".holding").daemon().start(holdingCaller);
        out.put("holding.finishedWhileHeld", Boolean.toString(joined(List.of(holdingThread))));
        out.put("readsWhileHeld", Integer.toString(read.reads.get()));
        read.release.countDown();
        out.put("lacking.finished", Boolean.toString(joined(List.of(lackingThread, holdingThread))));
        for (Caller caller : List.of(lackingCaller, holdingCaller)) {
            String key = caller == lackingCaller ? "lacking" : "holding";
            out.put(key + ".slot", caller.slot == Integer.MIN_VALUE ? null : Integer.toString(caller.slot));
            out.put(key + ".failure", caller.failure);
            out.put(key + ".remembered", Boolean.toString(CTFactoryShim.remembersAMiss(factory, FACE, caller.file)));
        }
        FontResource holdingFace = holdingCaller.slotFont();
        out.put("holding.font", holdingFace == null ? null : holdingFace.getFullName());

        String first = copy(directory, "first", "helvetica");
        String second = copy(directory, "second", "helvetica");
        AtomicInteger reads = new AtomicInteger();
        CTFactory alternating = CTFactoryShim.newFactory((face, target, own) -> {
            reads.incrementAndGet();
            return own.call();
        }, new AtomicInteger());
        CompositeFontResource system = FontResourceShim.newLogicalFont("System", false, false);
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < ALTERNATIONS; i++) {
            for (String file : List.of(first, second)) {
                slots.add(Integer.toString(CTFactoryShim.addFallbackFont(alternating, system, FACE, file,
                                                                         lackingFont)));
            }
        }
        out.put("alternating.slots", String.join(";", slots));
        out.put("alternating.reads", Integer.toString(reads.get()));
        MacFontOracleShim.release(lackingFont);
        MacFontOracleShim.release(holdingFont);
    }

    /** The real path of a copy of the pinned font {@code source} named {@code name} in {@code directory}. */
    private static String copy(Path directory, String name, String source) throws Exception {
        Path file = directory.resolve(name);
        Files.copy(MacFontGoldens.pinned(source), file);
        return file.toRealPath().toString();
    }

    private static String may(CTFactory factory, String name, String file) {
        return Boolean.toString(CTFactoryShim.mayFindFace(factory, name, file));
    }

    /** The simple class name of the font file the factory makes of a copy of {@code font}, or null for none. */
    private static String fontFileOf(CTFactory factory, String name, String file, long font) throws Exception {
        long copy = OSShim.CTFontCreateCopyWithAttributes(font, 0, null, 0);
        FontResource made = CTFactoryShim.createFontFile(factory, name, file, copy);
        return made == null ? null : made.getClass().getSimpleName();
    }

    /** Which of the file key, the modification time and the size differ, joined by "+"; "none" for none. */
    private static String changed(BasicFileAttributes before, BasicFileAttributes after) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.fileKey(), after.fileKey())) {
            changed.add("key");
        }
        if (!before.lastModifiedTime().equals(after.lastModifiedTime())) {
            changed.add("time");
        }
        if (before.size() != after.size()) {
            changed.add("size");
        }
        return changed.isEmpty() ? "none" : String.join("+", changed);
    }

    /** What making a font file of a copy of {@code font} for {@code file} threw, or null. */
    private static String thrownBy(CTFactory factory, String name, String file, long font) {
        try {
            fontFileOf(factory, name, file, font);
            return null;
        } catch (Exception e) {
            return e.getClass().getName();
        }
    }

    private static BasicFileAttributes attributes(Path file) throws Exception {
        return Files.readAttributes(file, BasicFileAttributes.class);
    }

    /**
     * Whether {@code thread} is seen waiting for a lookup in flight within {@link #WAIT_SECONDS}, with its interrupt
     * status clear where {@code cleared}: the status is read first, so a wait seen after it is one the thread entered
     * once it had cleared the interrupt. False once it has ended.
     */
    private static boolean awaitWaiting(Thread thread, boolean cleared) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            boolean interrupted = thread.isInterrupted();
            if ((!cleared || !interrupted) && isWaitingForALookup(thread)) {
                return true;
            }
            if (!thread.isAlive()) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }

    /** WAITING in the wait for a lookup in flight, {@code CTFactory$FaceLookup.await}. */
    private static boolean isWaitingForALookup(Thread thread) {
        if (thread.getState() != Thread.State.WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("com.sun.javafx.font.coretext.CTFactory$FaceLookup")
                    && frame.getMethodName().equals("await")) {
                return true;
            }
        }
        return false;
    }

    private static boolean joined(List<Thread> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (Thread thread : threads) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !thread.join(Duration.ofNanos(left))) {
                return false;
            }
        }
        return true;
    }

    /** One caller of layout's lookup of Ahem; what it answered or threw is read once its thread has ended. */
    private static final class Caller implements Runnable {

        private final CTFactory factory;
        private final String file;
        private final long font;
        private final CompositeFontResource system = FontResourceShim.newLogicalFont("System", false, false);
        private int slot = Integer.MIN_VALUE;
        private String failure;
        private boolean interruptedAfter;

        Caller(CTFactory factory, String file, long font) {
            this.factory = factory;
            this.file = file;
            this.font = font;
        }

        @Override
        public void run() {
            try {
                slot = CTFactoryShim.addFallbackFont(factory, system, FACE, file, font);
            } catch (Throwable t) {
                failure = t.getClass().getName();
            }
            interruptedAfter = Thread.currentThread().isInterrupted();
        }

        FontResource slotFont() {
            return slot < 0 ? null : system.getSlotResource(slot);
        }
    }

    /** Holds the late caller right after the factory's first answer to it, until the scenario releases it. */
    private static final class LateQuery implements CTFactoryShim.FaceQuery {

        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread late;
        private volatile String firstAnswer;

        @Override
        public boolean mayFindFace(String name, String file, BooleanSupplier answer) {
            boolean may = answer.getAsBoolean();
            if (Thread.currentThread() == late && firstAnswer == null) {
                firstAnswer = Boolean.toString(may);
                inside.countDown();
                try {
                    if (!release.await(HOLD_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the scenario did not release the late caller");
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException("the late caller was interrupted", e);
                }
            }
            return may;
        }
    }

    /**
     * The factory's reads of a font file: the first signals that it is inside and waits for the release, then throws
     * an {@code OutOfMemoryError} where it is to fail, or reads; the others read.
     */
    private static final class HeldRead implements CTFactoryShim.FaceRead {

        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private final boolean fails;

        HeldRead(boolean fails) {
            this.fails = fails;
        }

        @Override
        public int read(String name, String file, Callable<Integer> read) throws Exception {
            if (reads.incrementAndGet() == 1) {
                inside.countDown();
                if (!release.await(HOLD_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the scenario did not release the held read");
                }
                if (fails) {
                    throw new OutOfMemoryError("thrown by the scenario's held read");
                }
            }
            return read.call();
        }
    }
}

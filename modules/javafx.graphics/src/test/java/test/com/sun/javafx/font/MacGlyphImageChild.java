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

import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.Glyph;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.font.coretext.CTFontStrikeShim;
import com.sun.javafx.font.coretext.CTGlyphShim;
import com.sun.javafx.font.coretext.OSShim;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import test.com.sun.javafx.font.FontGoldenHarness.GoldenMap;

/**
 * The child JVM of {@link MacGlyphImageTest}: what {@code CTGlyph} draws and measures while another thread uses it,
 * what a glyph whose measurement threw answers afterwards, and the options it draws a glyph with into its cached
 * context. Every scenario draws or measures, and the cached context lives as long as its JVM, so each runs in a JVM
 * of its own.
 */
public final class MacGlyphImageChild {

    static final String CACHED_DRAW = "glyph.cachedDraw";
    static final String BOUNDS_RACE = "glyph.boundsRace";
    static final String MEASUREMENT_FAILS = "glyph.measurementFails";
    static final String OWN_SETTINGS = "glyph.ownSettings";

    static final String SECOND_BLOCKED = "second blocked";
    static final String SECOND_FINISHED = "second finished";

    /** The message of what the strike throws into the measurement of a glyph in {@link #MEASUREMENT_FAILS}. */
    static final String MEASUREMENT_FAILURE = "the strike's font file cannot be had";

    static final float SIZE = 24f;

    /** The sub-pixel index of half a pixel for a grey Helvetica strike of {@link #SIZE}. */
    static final int HALF_PIXEL = 1;

    /** How long a thread held at its hook polls for the other thread to block or to finish. */
    static final long HOLD_SECONDS = 10;

    /** How long the main thread waits for the first thread to reach its hook. */
    private static final long ARRIVAL_SECONDS = 10;

    /**
     * How long the main thread waits for both threads to end: twice a hold, so that a hold that times out is
     * reported as one, and with the arrival well inside {@link MacFontGoldens#CHILD_TIMEOUT_SECONDS}.
     */
    private static final Duration JOIN_LIMIT = Duration.ofSeconds(2 * HOLD_SECONDS);

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private static final String CT_GLYPH = "com.sun.javafx.font.coretext.CTGlyph";

    private MacGlyphImageChild() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        GoldenMap out = new GoldenMap();
        switch (scenario) {
            case CACHED_DRAW -> cachedDraw(out);
            case BOUNDS_RACE -> boundsRace(out);
            case MEASUREMENT_FAILS -> measurementFails(out);
            case OWN_SETTINGS -> ownSettings(out);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        Files.writeString(Path.of(args[1]), FontGoldens.format(out, List.of("MacGlyphImageChild " + scenario)),
                          StandardCharsets.UTF_8);
        System.exit(0);
    }

    /**
     * Two threads draw two glyphs that fit the cached context. Each is first drawn alone, which also makes the
     * cached context, and then drawn alone once more, into the context the other glyph was drawn into last, as each
     * thread's draw is. Then the first thread is held between its draw and the read of its image until the second
     * thread has blocked in {@code CTGlyph.getImage} or has finished. The hold is the strike's anti-aliasing query
     * that the read makes, the first after the draw has asked for its sub-pixel position. Both draw at sub-pixel
     * index 0, for which the strike answers the position without asking for its anti-aliasing mode.
     */
    private static void cachedDraw(GoldenMap out) throws Exception {
        out.put("cached.before", Long.toString(CTGlyphShim.cachedContext()));
        DrawRace race = new DrawRace();
        FontStrike strike = CTFontStrikeShim.create(helvetica(), SIZE, race);
        Glyph first = strike.getGlyph('W');
        Glyph second = strike.getGlyph('o');
        out.put("serial.first", FontGoldenFormat.bytesSummary(first.getPixelData(0)));
        out.put("serial.second", FontGoldenFormat.bytesSummary(second.getPixelData(0)));
        out.put("box.first", first.getWidth() + "x" + first.getHeight());
        out.put("box.second", second.getWidth() + "x" + second.getHeight());
        out.put("cached.serial", Boolean.toString(CTGlyphShim.cachedContext() != 0));
        out.put("again.first", FontGoldenFormat.bytesSummary(first.getPixelData(0)));
        out.put("again.second", FontGoldenFormat.bytesSummary(second.getPixelData(0)));
        Draw firstDraw = new Draw(first);
        Draw secondDraw = new Draw(second);
        Thread firstThread = Thread.ofPlatform().name(CACHED_DRAW + ".first").daemon().unstarted(firstDraw);
        Thread secondThread = Thread.ofPlatform().name(CACHED_DRAW + ".second").daemon().unstarted(secondDraw);
        race.arm(firstThread, secondThread, secondDraw::returned);
        firstThread.start();
        out.put("firstAtRead", Boolean.toString(race.firstHeld.await(ARRIVAL_SECONDS, TimeUnit.SECONDS)));
        secondThread.start();
        out.put("finished", Boolean.toString(joinBoth(firstThread, secondThread)));
        out.put("hold", race.hold);
        out.put("first.failure", firstDraw.failure);
        out.put("second.failure", secondDraw.failure);
        out.put("first.image", FontGoldenFormat.bytesSummary(firstDraw.image));
        out.put("second.image", FontGoldenFormat.bytesSummary(secondDraw.image));
        Reference.reachabilityFence(strike);
    }

    /**
     * One thread reads a glyph's advance while it is measured, the other its bounds and advances. The first is held
     * while it measures, after the advances and before the box, until the second thread has blocked in
     * {@code CTGlyph} or has finished. The hold is the strike's font-file query that the measurement makes there,
     * its first. The reference is the same glyph of another strike of the same font and size, read alone.
     */
    private static void boundsRace(GoldenMap out) throws Exception {
        FontResource resource = helvetica();
        FontStrike reference = CTFontStrikeShim.create(resource, SIZE, new CTFontStrikeShim.Calls() { });
        out.put("reference", values(reference.getGlyph('W')));
        BoundsRace race = new BoundsRace();
        FontStrike strike = CTFontStrikeShim.create(resource, SIZE, race);
        Glyph glyph = strike.getGlyph('W');
        Read first = new Read(() -> Float.toString(glyph.getPixelXAdvance()));
        Read second = new Read(() -> values(glyph));
        Thread firstThread = Thread.ofPlatform().name(BOUNDS_RACE + ".first").daemon().unstarted(first);
        Thread secondThread = Thread.ofPlatform().name(BOUNDS_RACE + ".second").daemon().unstarted(second);
        race.arm(firstThread, secondThread, second::returned);
        firstThread.start();
        out.put("firstMeasuring", Boolean.toString(race.firstHeld.await(ARRIVAL_SECONDS, TimeUnit.SECONDS)));
        secondThread.start();
        out.put("finished", Boolean.toString(joinBoth(firstThread, secondThread)));
        out.put("hold", race.hold);
        out.put("first.advance", first.value);
        out.put("second.values", second.value);
        out.put("first.failure", first.failure);
        out.put("second.failure", second.failure);
        Reference.reachabilityFence(strike);
        Reference.reachabilityFence(reference);
    }

    /**
     * The strike's font-file query, which the measurement of a glyph makes after the advances and before the box,
     * throws on its first call. The first reader of the glyph gets that exception; then the glyph's box is read.
     */
    private static void measurementFails(GoldenMap out) throws Exception {
        FontStrike strike = CTFontStrikeShim.create(helvetica(), SIZE, new FailingMeasurement());
        Glyph glyph = strike.getGlyph('W');
        String failure = null;
        try {
            glyph.getPixelXAdvance();
        } catch (IllegalStateException e) {
            failure = e.getMessage();
        }
        out.put("first.failure", failure);
        out.put("after", glyph.getWidth() + "x" + glyph.getHeight() + ";" + glyph.getOriginX() + ";"
                + glyph.getOriginY());
        Reference.reachabilityFence(strike);
    }

    /**
     * Glyph 'A' of a strike with sub-pixel positions, drawn half a pixel over: into a context made with its own
     * strike's options, into one made with the options of a strike without sub-pixel positions, and into the cached
     * context after a glyph of that other strike made it. Each is the second draw into its context: the other
     * strike's 'A' is drawn first into each context the probe made, at sub-pixel index 0 and with the options that
     * context has, as it was into the cached context when it made it, and its image is discarded.
     */
    private static void ownSettings(GoldenMap out) throws Exception {
        PrismFontFactory factory = PrismFontFactory.getFontFactory();
        boolean lcd = factory.isLCDTextSupported();
        out.put("lcd", Boolean.toString(lcd));
        out.put("subPixelText",
                Boolean.toString((factory.getSubPixelMode() & PrismFontFactory.SUB_PIXEL_ON) != 0));
        FontResource resource = helvetica();
        FontStrike own = CTFontStrikeShim.create(resource, SIZE, new CTFontStrikeShim.Calls() { });
        FontStrike other = CTFontStrikeShim.withoutSubPixelPositions(resource, SIZE);
        Glyph ownA = own.getGlyph('A');
        Glyph otherA = other.getGlyph('A');
        long ownContext = CTGlyphShim.createContext(ownA, lcd, 256, 256);
        long otherContext = CTGlyphShim.createContext(otherA, lcd, 256, 256);
        boolean made = ownContext != 0 && otherContext != 0;
        out.put("contexts.made", Boolean.toString(made));
        if (made) {
            CTGlyphShim.drawInto(otherA, ownContext, 0);
            out.put("own", FontGoldenFormat.bytesSummary(CTGlyphShim.drawInto(ownA, ownContext, HALF_PIXEL)));
            CTGlyphShim.drawInto(otherA, otherContext, 0);
            out.put("other", FontGoldenFormat.bytesSummary(CTGlyphShim.drawInto(ownA, otherContext, HALF_PIXEL)));
        }
        if (ownContext != 0) {
            OSShim.CGContextRelease(ownContext);
        }
        if (otherContext != 0) {
            OSShim.CGContextRelease(otherContext);
        }
        out.put("cached.before", Long.toString(CTGlyphShim.cachedContext()));
        otherA.getPixelData(0);
        out.put("cached.made", Boolean.toString(CTGlyphShim.cachedContext() != 0));
        out.put("subject", FontGoldenFormat.bytesSummary(ownA.getPixelData(HALF_PIXEL)));
        Reference.reachabilityFence(own);
        Reference.reachabilityFence(other);
    }

    private static FontResource helvetica() throws Exception {
        return FontResourceShim.newUncachedFontFile(MacFontGoldens.pinned("helvetica").toString(), 0);
    }

    /** What a reader takes from a glyph's advances and bounds, in this order. */
    private static String values(Glyph glyph) {
        return glyph.getPixelXAdvance() + ";" + glyph.getPixelYAdvance() + ";" + glyph.getWidth() + ";"
                + glyph.getHeight() + ";" + glyph.getOriginX() + ";" + glyph.getOriginY();
    }

    /** Both threads ended within {@link #JOIN_LIMIT}, counted from the first join. */
    private static boolean joinBoth(Thread first, Thread second) throws InterruptedException {
        long deadline = System.nanoTime() + JOIN_LIMIT.toNanos();
        boolean firstEnded = first.join(Duration.ofNanos(deadline - System.nanoTime()));
        boolean secondEnded = second.join(Duration.ofNanos(deadline - System.nanoTime()));
        return firstEnded && secondEnded;
    }

    /**
     * Holds the first thread at a hook, once, until the second thread is blocked with a top frame that
     * {@link #blockedAt} accepts or has returned, or {@link #HOLD_SECONDS} have passed. A hook on any other thread,
     * or before {@link #arm}, does nothing.
     */
    private abstract static class Race implements CTFontStrikeShim.Calls {

        final CountDownLatch firstHeld = new CountDownLatch(1);
        volatile String hold = "never held";
        private final AtomicBoolean held = new AtomicBoolean();
        private volatile Thread first;
        private volatile Thread second;
        private volatile Returned secondReturned;

        void arm(Thread first, Thread second, Returned secondReturned) {
            this.second = second;
            this.secondReturned = secondReturned;
            this.first = first;
        }

        boolean onFirst() {
            return Thread.currentThread() == first;
        }

        void holdFirst() {
            if (held.compareAndSet(false, true)) {
                firstHeld.countDown();
                hold = awaitSecond();
            }
        }

        abstract boolean blockedAt(StackTraceElement top);

        private String awaitSecond() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_SECONDS);
            while (System.nanoTime() - deadline < 0) {
                if (secondReturned.returned()) {
                    return SECOND_FINISHED;
                }
                if (second.getState() == Thread.State.BLOCKED) {
                    StackTraceElement[] stack = second.getStackTrace();
                    if (stack.length > 0 && blockedAt(stack[0])) {
                        return SECOND_BLOCKED;
                    }
                }
                LockSupport.parkNanos(POLL_NANOS);
            }
            return "timed out after " + HOLD_SECONDS + " s";
        }
    }

    /** Holds the first thread at the read of its image, which asks for the anti-aliasing mode after the draw. */
    private static final class DrawRace extends Race {

        private volatile boolean drawn;

        @Override
        public void subPixelPosition() {
            if (onFirst()) {
                drawn = true;
            }
        }

        @Override
        public void aaModeQuery() {
            if (onFirst() && drawn) {
                holdFirst();
            }
        }

        @Override
        boolean blockedAt(StackTraceElement top) {
            return top.getClassName().equals(CT_GLYPH) && top.getMethodName().equals("getImage");
        }
    }

    /** Holds the first thread where it measures the glyph and asks for the font file, after the advances. */
    private static final class BoundsRace extends Race {

        @Override
        public void fontResourceQuery() {
            if (onFirst()) {
                holdFirst();
            }
        }

        @Override
        boolean blockedAt(StackTraceElement top) {
            return top.getClassName().equals(CT_GLYPH);
        }
    }

    /** Throws from the strike's first font-file query, the one the measurement of a glyph makes. */
    private static final class FailingMeasurement implements CTFontStrikeShim.Calls {

        private boolean failed;

        @Override
        public void fontResourceQuery() {
            if (!failed) {
                failed = true;
                throw new IllegalStateException(MEASUREMENT_FAILURE);
            }
        }
    }

    @FunctionalInterface
    private interface Returned {
        boolean returned();
    }

    /** One thread's draw of a glyph at sub-pixel index 0; what it answered or threw is read once it has ended. */
    private static final class Draw implements Runnable {

        private final Glyph glyph;
        private volatile boolean returned;
        private byte[] image;
        private String failure;

        Draw(Glyph glyph) {
            this.glyph = glyph;
        }

        @Override
        public void run() {
            try {
                image = glyph.getPixelData(0);
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            } finally {
                returned = true;
            }
        }

        boolean returned() {
            return returned;
        }
    }

    /** One thread's read of a glyph; what it answered or threw is read once it has ended. */
    private static final class Read implements Runnable {

        private final ReadOf read;
        private volatile boolean returned;
        private String value;
        private String failure;

        Read(ReadOf read) {
            this.read = read;
        }

        @Override
        public void run() {
            try {
                value = read.value();
            } catch (RuntimeException | Error e) {
                failure = e.toString();
            } finally {
                returned = true;
            }
        }

        boolean returned() {
            return returned;
        }
    }

    @FunctionalInterface
    private interface ReadOf {
        String value();
    }
}

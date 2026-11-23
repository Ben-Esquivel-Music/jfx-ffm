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

import com.sun.javafx.font.CharToGlyphMapper;
import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FallbackResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontResourceShim;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.FontStrikeDesc;
import com.sun.javafx.font.LogicalFont;
import com.sun.javafx.font.PrismFontFactory;
import com.sun.javafx.geom.transform.BaseTransform;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The locks of the factory's font maps and of the slot lists of the composite fonts, {@code LogicalFont} and
 * {@code FallbackResource}. A thread started while the test holds a monitor either blocks on it or ends, so each
 * check decides by the state of a thread against that monitor, never by time. The machine has no font with the
 * names used here, so a factory answers null for each name the test did not store itself. The class-file checks, the
 * reflection check and the store into a factory of no platform run on any OS; the slot tests use the process's
 * factory, whose look-ups reach the platform's native font calls, and run on macOS only.
 */
public class CompositeFontLocksTest {

    private static final String FONT = "com/sun/javafx/font/";
    private static final String PRISM_FONT_FACTORY = FONT + "PrismFontFactory";
    private static final String LOGICAL_FONT = FONT + "LogicalFont";
    private static final String FALLBACK_RESOURCE = FONT + "FallbackResource";

    /** The slot count at which {@code addNativeFallback} refuses another font. */
    private static final int SLOT_CAP = 0x7E;

    private static final String ADDED_UNDER_THE_LOCK = "a font is added to a composite under the composite's monitor";

    /**
     * A font stored while another thread holds the factory's monitor waits for it and is found once the monitor is
     * free: the store writes the map under the lock its readers take. Through a factory of no platform, on any OS:
     * the store and the lookup of the name stored make no native call.
     */
    @Test
    void aFontStoredWhileItsFactoryIsBusyIsFoundOnceItIsFree() throws Exception {
        PrismFontFactory factory = FontResourceShim.newMapOnlyFactory();
        SlotFont stored = new SlotFont("JFX Map Test Stored");
        Started<Void> store;
        synchronized (factory) {
            store = start(() -> {
                FontResourceShim.storeInMap(factory, stored.getFullName(), stored);
                return null;
            });
            assertTrue(blocksOn(store.thread(), factory), "storeInMap writes the font map under the lock its readers"
                    + " take");
        }
        store.result();
        assertSame(stored, factory.getFontResource(stored.getFullName(), null, false), "the font stored");
    }

    /**
     * Every method of the factory, or of a class nested in it, that reads one of the factory's three font maps, or
     * makes a call that uses one (a store, a lookup, the making of a font resource, or a composite, which puts itself
     * in the composite map), holds the factory's monitor throughout. On any OS, from the class files.
     */
    @Test
    void theFontMapsOfTheFactoryAreUsedOnlyUnderItsMonitor() {
        FontCallOrder.assertReachedOnlyUnderTheMonitor(MacFontBindingStructureTest.nest(PRISM_FONT_FACTORY),
                List.of("fontResourceMap", "compResourceMap", "fileNameToFontResourceMap"),
                List.of(PRISM_FONT_FACTORY + " storeInMap", PRISM_FONT_FACTORY + " lookupResource",
                        PRISM_FONT_FACTORY + " createFontResource", PRISM_FONT_FACTORY + " createFontResources",
                        FONT + "PrismCompositeFontResource <init>"),
                "no two threads use one of its maps at once, and a lookup finds every font a finished store stored");
    }

    /** Two fonts added at once to a logical font take one slot each, and each slot holds its font. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void twoFontsAddedAtOnceToALogicalFontTakeTwoSlots() throws Exception {
        CompositeFontResource font = FontResourceShim.newLogicalFont(LogicalFont.SYSTEM, false, false);
        int first = font.getNumSlots();
        requireRoomFor(first, 2);
        SlotFont a = new SlotFont("JFX Slot Test A");
        SlotFont b = new SlotFont("JFX Slot Test B");
        Started<Integer> addA;
        Started<Integer> addB;
        synchronized (font) {
            addA = start(() -> font.addSlotFont(a));
            addB = start(() -> font.addSlotFont(b));
            assertTrue(blocksOn(addA.thread(), font), ADDED_UNDER_THE_LOCK);
            assertTrue(blocksOn(addB.thread(), font), ADDED_UNDER_THE_LOCK);
        }
        int slotA = addA.result();
        int slotB = addB.result();
        assertEquals(List.of(first, first + 1), Stream.of(slotA, slotB).sorted().toList(), "the slots handed out");
        assertSame(a, font.getSlotResource(slotA), "the font at the slot handed out for the first font");
        assertSame(b, font.getSlotResource(slotB), "the font at the slot handed out for the second font");
        assertEquals(first + 2, font.getNumSlots(), "the slots of the logical font");
        assertEquals(slotA, font.getSlotForFont(a.getFullName()), "the slot a later lookup finds");
    }

    /** One font added twice at once to a logical font takes one slot, which both callers are handed. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void oneFontAddedTwiceAtOnceToALogicalFontTakesOneSlot() throws Exception {
        CompositeFontResource font = FontResourceShim.newLogicalFont(LogicalFont.SYSTEM, false, false);
        int first = font.getNumSlots();
        requireRoomFor(first, 1);
        SlotFont a = new SlotFont("JFX Slot Test A");
        Started<Integer> once;
        Started<Integer> twice;
        synchronized (font) {
            once = start(() -> font.addSlotFont(a));
            twice = start(() -> font.addSlotFont(a));
            assertTrue(blocksOn(once.thread(), font), ADDED_UNDER_THE_LOCK);
            assertTrue(blocksOn(twice.thread(), font), ADDED_UNDER_THE_LOCK);
        }
        assertEquals(first, once.result(), "the slot handed out to one caller");
        assertEquals(first, twice.result(), "the slot handed out to the other caller");
        assertEquals(first + 1, font.getNumSlots(), "the slots of the logical font");
        assertSame(a, font.getSlotResource(first), "the font at the slot handed out for it");
    }

    /**
     * A logical font answers its slot count, the slot of a font it has added, that font and its first font while
     * another thread holds its monitor. macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void aLogicalFontAnswersWhatItKnowsWithoutItsLock() throws Exception {
        CompositeFontResource font = FontResourceShim.newLogicalFont(LogicalFont.SYSTEM, false, false);
        requireRoomFor(font.getNumSlots(), 1);
        SlotFont a = new SlotFont("JFX Slot Test A");
        int slot = font.addSlotFont(a);
        int slots = font.getNumSlots();
        FontResource zero = font.getSlotResource(0);
        Started<Reads> read;
        synchronized (font) {
            read = start(() -> new Reads(font.getNumSlots(), font.getSlotForFont(a.getFullName()),
                                         font.getSlotResource(slot), font.getSlotResource(0)));
            assertFalse(blocksOn(read.thread(), font), "a logical font answers what it knows without its lock");
        }
        Reads reads = read.result();
        assertEquals(slots, reads.slots(), "the slot count");
        assertEquals(slot, reads.slot(), "the slot of the font added");
        assertSame(a, reads.added(), "the font at that slot");
        assertSame(zero, reads.zero(), "the font at slot 0");
    }

    /**
     * Two fonts added at once to a fallback list take one slot each, the next two indexes, and each slot holds its
     * font. macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void twoFontsAddedAtOnceToAFallbackResourceTakeTwoSlots() throws Exception {
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(new SlotFont("JFX Slot Test Primary"));
        int first = fallback.getNumSlots();
        requireRoomFor(first, 2);
        SlotFont a = new SlotFont("JFX Slot Test A");
        SlotFont b = new SlotFont("JFX Slot Test B");
        Started<Integer> addA;
        Started<Integer> addB;
        synchronized (fallback) {
            addA = start(() -> fallback.addSlotFont(a));
            addB = start(() -> fallback.addSlotFont(b));
            assertTrue(blocksOn(addA.thread(), fallback), ADDED_UNDER_THE_LOCK);
            assertTrue(blocksOn(addB.thread(), fallback), ADDED_UNDER_THE_LOCK);
        }
        int slotA = addA.result();
        int slotB = addB.result();
        assertEquals(List.of(first, first + 1), Stream.of(slotA, slotB).sorted().toList(), "the slots handed out");
        assertSame(a, fallback.getSlotResource(slotA), "the font at the slot handed out for the first font");
        assertSame(b, fallback.getSlotResource(slotB), "the font at the slot handed out for the second font");
        assertEquals(first + 2, fallback.getNumSlots(), "the slots of the fallback list");
    }

    /** One font added twice at once to a fallback list takes one slot, the next index, for both callers. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void oneFontAddedTwiceAtOnceToAFallbackResourceTakesOneSlot() throws Exception {
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(new SlotFont("JFX Slot Test Primary"));
        int first = fallback.getNumSlots();
        requireRoomFor(first, 1);
        SlotFont a = new SlotFont("JFX Slot Test A");
        Started<Integer> once;
        Started<Integer> twice;
        synchronized (fallback) {
            once = start(() -> fallback.addSlotFont(a));
            twice = start(() -> fallback.addSlotFont(a));
            assertTrue(blocksOn(once.thread(), fallback), ADDED_UNDER_THE_LOCK);
            assertTrue(blocksOn(twice.thread(), fallback), ADDED_UNDER_THE_LOCK);
        }
        assertEquals(first, once.result(), "the slot handed out to one caller");
        assertEquals(first, twice.result(), "the slot handed out to the other caller");
        assertEquals(first + 1, fallback.getNumSlots(), "the slots of the fallback list");
        assertSame(a, fallback.getSlotResource(first), "the font at the slot handed out for it");
    }

    /**
     * A fallback list answers its slot count and the slot of a font it has added while another thread holds its
     * monitor. Its {@code getSlotResource} is synchronized, as it is upstream, so it is not read here. macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void aFallbackResourceAnswersWhatItKnowsWithoutItsLock() throws Exception {
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(new SlotFont("JFX Slot Test Primary"));
        requireRoomFor(fallback.getNumSlots(), 1);
        SlotFont a = new SlotFont("JFX Slot Test A");
        int slot = fallback.addSlotFont(a);
        int slots = fallback.getNumSlots();
        Started<List<Integer>> read;
        synchronized (fallback) {
            read = start(() -> List.of(fallback.getNumSlots(), fallback.getSlotForFont(a.getFullName())));
            assertFalse(blocksOn(read.thread(), fallback), "a fallback list answers what it knows without its lock");
        }
        assertEquals(List.of(slots, slot), read.result(), "the slot count and the slot of the font added");
    }

    /** A font added to a fallback list is at the slot it is handed out for, the list's next index. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void aFontAddedToAFallbackResourceIsAtTheSlotItIsHandedOutFor() {
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(new SlotFont("JFX Slot Test Primary"));
        int first = fallback.getNumSlots();
        requireRoomFor(first, 1);
        SlotFont added = new SlotFont("JFX Slot Test Added");
        int slot = fallback.addSlotFont(added);
        assertEquals(first, slot, "a font added to a fallback list of " + first + " slots is at index " + first);
        assertSame(added, fallback.getSlotResource(slot), "the font at the slot handed out for it");
        assertEquals(slot, fallback.getSlotForFont(added.getFullName()), "the slot a later lookup finds");
    }

    /**
     * A font that the composite of a font file adds, for a name the factory knows, is at the slot the composite hands
     * out for it: the composite numbers its slots one above its fallback list's. The process's factory knows the name
     * because the test stored the font under it; Helvetica's own fallbacks come from CoreText. macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void aFontACompositeAddsIsAtTheSlotItIsHandedOutFor() {
        FontResource helvetica = FontResourceShim.newUncachedFontFile(MacFontGoldens.SYSTEM_FONTS + "/Helvetica.ttc");
        assertNotNull(helvetica, "CoreText opens Helvetica.ttc, which every macOS has");
        CompositeFontResource composite = FontResourceShim.newComposite(helvetica);
        int slots = composite.getNumSlots();
        requireRoomFor(slots - 1, 1);
        SlotFont added = new SlotFont("JFX Slot Test Composite Added");
        FontResourceShim.storeInMap(PrismFontFactory.getFontFactory(), added.getFullName(), added);
        int slot = composite.getSlotForFont(added.getFullName());
        assertEquals(slots, slot, "a font added to a composite of " + slots + " slots is at slot " + slots);
        assertSame(added, composite.getSlotResource(slot), "the font at the slot handed out for it");
        assertEquals(slot, composite.getSlotForFont(added.getFullName()), "the slot a later lookup finds");
    }

    /** Three callers that meet a new logical font at once wait while one of them looks up its fallbacks. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void callersOfANewLogicalFontWaitForOneLookUpOfItsFallbacks() throws Exception {
        CompositeFontResource font = FontResourceShim.newLogicalFont(LogicalFont.SYSTEM, false, false);
        List<Integer> counts = countSlotsAtOnce(font);
        assertEquals(List.of(font.getNumSlots(), font.getNumSlots(), font.getNumSlots()), counts,
                     "the slot counts the three callers were given");
    }

    /** Three callers that meet a new fallback list at once wait while one of them looks it up. macOS. */
    @Test
    @EnabledOnOs(OS.MAC)
    void callersOfANewFallbackResourceWaitForOneLookUpOfItsFallbacks() throws Exception {
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(new SlotFont("JFX Slot Test Primary"));
        List<Integer> counts = countSlotsAtOnce(fallback);
        assertEquals(List.of(fallback.getNumSlots(), fallback.getNumSlots(), fallback.getNumSlots()), counts,
                     "the slot counts the three callers were given");
    }

    /**
     * A new fallback list is looked up once for three callers that meet it at once. The factory reads the primary's
     * style once per look-up, which a control shows on this host before the count is relied on. macOS.
     */
    @Test
    @EnabledOnOs(OS.MAC)
    void aNewFallbackResourceLooksUpItsFallbacksOnceForThreeCallers() throws Exception {
        SlotFont control = new SlotFont("JFX Slot Test Control");
        CompositeFontResource controlFallback = FontResourceShim.newFallbackResource(control);
        int controlBefore = control.boldReads();
        controlFallback.getNumSlots();
        assertEquals(controlBefore + 1, control.boldReads(), "a look-up of a fallback list reads its primary's style"
                + " once: the count this test relies on");
        SlotFont primary = new SlotFont("JFX Slot Test Primary");
        CompositeFontResource fallback = FontResourceShim.newFallbackResource(primary);
        int before = primary.boldReads();
        countSlotsAtOnce(fallback);
        assertEquals(before + 1, primary.boldReads(), "look-ups of one fallback list by three callers");
    }

    /**
     * Each composite looks up its fallbacks only under its monitor and only while they are unknown, and a caller that
     * finds them known takes no lock. On any OS, from the class files.
     */
    @Test
    void eachCompositeLooksUpItsFallbacksOnlyWhileTheyAreUnknownUnderItsLock() {
        for (String type : List.of(LOGICAL_FONT, FALLBACK_RESOURCE)) {
            FontCallOrder.assertCalledOnlyWhileNullUnderLock(type, "getLinkedFonts", "fallbacks",
                    PRISM_FONT_FACTORY + " getFallbacks", "callers that meet a new composite at once wait for one"
                    + " look-up of its fallbacks, and a caller that finds them known takes no lock");
        }
    }

    /**
     * Each composite sets its fallbacks after their names and files, so a caller that finds them set without the
     * lock finds the names and files too. Afterwards only a logical font's {@code getSlotResource} replaces them, with
     * a filled copy of what it read. On any OS, from the class files.
     */
    @Test
    void eachCompositeSetsItsFallbacksAfterTheirNamesAndFiles() {
        List<String> earlier = List.of("linkedFontNames", "linkedFontFiles");
        String protects = "a caller that finds the fallbacks set without the lock finds their names and files set too";
        FontCallOrder.assertStoredLastUnderLock(LOGICAL_FONT, "getLinkedFonts", "fallbacks", earlier,
                List.of("getSlotResource"), protects);
        FontCallOrder.assertStoredLastUnderLock(FALLBACK_RESOURCE, "getLinkedFonts", "fallbacks", earlier, List.of(),
                protects);
    }

    /**
     * A logical font reads slot 0 once, fills it with one store of its final answer, and answers what it read or
     * filled: a second read without a lock may find null where the first found the font. It reads its linked and
     * added slots once per call, and fills a linked slot by publishing a filled copy of the slots it read, never by
     * writing into an array that other threads read. Only the method that fills slot 0 reads its field, only
     * {@code getSlotResource} reads a linked slot, and the other methods that read the linked slots write into no
     * array. On any OS, from the class file.
     */
    @Test
    void aLogicalFontReadsAndFillsEachSlotOnceAndAnswersWhatItReadOrFilled() {
        String protects = "a thread that reads a slot without a lock never answers null for a slot another thread"
                + " filled";
        FontCallOrder.assertReadOnceAndStoredOnce(LOGICAL_FONT, "getSlot0Resource", "slot0FontResource", protects);
        FontCallOrder.assertFieldReadOnlyIn(LOGICAL_FONT, "slot0FontResource", List.of("getSlot0Resource"), protects);
        FontCallOrder.assertFieldReadOnceIn(LOGICAL_FONT, "getSlotResource", "fallbacks", protects);
        FontCallOrder.assertFieldReadOnceIn(LOGICAL_FONT, "getSlotResource", "nativeFallbacks", protects);
        FontCallOrder.assertPublishesOnlyFilledCopies(LOGICAL_FONT, "getSlotResource", "fallbacks", protects);
        FontCallOrder.assertFieldReadOnlyIn(LOGICAL_FONT, "fallbacks",
                List.of("getLinkedFonts", "getNumSlots", "getSlotResource"), protects);
        FontCallOrder.assertStoresNoArrayElement(LOGICAL_FONT, List.of("getLinkedFonts", "getNumSlots"),
                "a font reaches the linked slots that threads read without a lock only through a filled copy");
    }

    /**
     * Each composite publishes a font it adds in a new array that it filled before, and the methods that read the
     * added fonts without the composite's monitor, besides a logical font's {@code getNumSlots} and
     * {@code getSlotResource}, which the test above pins, write into no array. On any OS, from the class files.
     */
    @Test
    void eachCompositePublishesTheFontsItAddsOnlyOnceFilled() {
        String protects = "a thread that reads the added fonts without the lock finds every slot handed out filled";
        for (String type : List.of(LOGICAL_FONT, FALLBACK_RESOURCE)) {
            FontCallOrder.assertPublishesNewArrayOnceFilled(type, "addNativeFallback", "nativeFallbacks", protects);
        }
        FontCallOrder.assertStoresNoArrayElement(LOGICAL_FONT, List.of("getSlotForFontNoCreate"), protects);
        FontCallOrder.assertStoresNoArrayElement(FALLBACK_RESOURCE, List.of("getNumSlots", "getSlotForFontNoCreate"),
                                                 protects);
    }

    /**
     * No reader makes a composite's added fonts again, so a null stored over them would drop every font already given
     * a slot, and {@code getSlotResource} would answer null for those slots: no method of either composite stores null
     * there, which the check above allows. On any OS, from the class files.
     */
    @Test
    void noCompositeDropsTheFontsItAdded() {
        for (String type : List.of(LOGICAL_FONT, FALLBACK_RESOURCE)) {
            FontCallOrder.assertNeverStoresNull(type, "nativeFallbacks", "every font a composite gave a slot keeps"
                    + " it, since no reader makes the added fonts again");
        }
    }

    /**
     * A logical font answers the font of a slot, and fills slot 0 or a linked slot it finds empty, without entering a
     * monitor itself: once its fallbacks are known, a thread that asks for the font of a slot, as layout and drawing
     * do for each glyph, never waits for another that holds the font's monitor. On any OS, from the class file.
     */
    @Test
    void aLogicalFontAnswersAndFillsItsSlotsWithoutTakingAMonitor() {
        for (String method : List.of("getSlotResource", "getSlot0Resource")) {
            FontCallOrder.assertTakesNoMonitor(LOGICAL_FONT, method, "a thread that asks for the font of a slot, as"
                    + " layout and drawing do for each glyph, never waits for another that holds the font's monitor");
        }
    }

    /**
     * The slot lists of each composite, and a logical font's slot-0 font, are volatile: they are read without the
     * lock, after they were set or replaced, and the fonts in them are published through them. On any OS, by
     * reflection.
     */
    @Test
    void theSlotListsOfEachCompositeAreVolatile() throws NoSuchFieldException {
        for (Class<?> type : List.<Class<?>>of(LogicalFont.class, FallbackResource.class)) {
            for (String name : List.of("linkedFontFiles", "linkedFontNames", "fallbacks", "nativeFallbacks")) {
                assertTrue(Modifier.isVolatile(type.getDeclaredField(name).getModifiers()), type.getSimpleName()
                        + "." + name + " is read without the lock, so it must be volatile");
            }
        }
        assertTrue(Modifier.isVolatile(LogicalFont.class.getDeclaredField("slot0FontResource").getModifiers()),
                   "LogicalFont.slot0FontResource is read without a lock, so it must be volatile");
    }

    /**
     * The slot counts of three callers started while this thread holds {@code composite}'s monitor, each of which
     * must block on it.
     */
    private static List<Integer> countSlotsAtOnce(CompositeFontResource composite) throws Exception {
        List<Started<Integer>> callers = new ArrayList<>();
        synchronized (composite) {
            for (int i = 0; i < 3; i++) {
                callers.add(start(composite::getNumSlots));
            }
            for (Started<Integer> caller : callers) {
                assertTrue(blocksOn(caller.thread(), composite), "a caller that meets a new composite waits for its"
                        + " fallbacks under the composite's monitor");
            }
        }
        List<Integer> counts = new ArrayList<>();
        for (Started<Integer> caller : callers) {
            counts.add(caller.result());
        }
        return counts;
    }

    /**
     * Skips, or fails under {@code -Djfx.parity.require=true}, where a composite already has so many slots that
     * {@code addNativeFallback} would refuse {@code added} more: such a host cannot show what the test checks.
     */
    private static void requireRoomFor(int slots, int added) {
        ParityGate.requireOracle(CompositeFontLocksTest.class, slots + added <= SLOT_CAP, ParityGate.required(),
                () -> "this host gives the composite " + slots + " slots; the 0x7E cap leaves no room to add " + added);
    }

    /**
     * Whether {@code thread}, started while this thread holds {@code lock}, blocks on it before it ends: a thread
     * that needs the lock ends up blocked on it, and one that never needs it ends, so the pause between looks
     * decides nothing. Platform threads only: the thread bean gives no lock of a virtual thread.
     */
    private static boolean blocksOn(Thread thread, Object lock) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        while (thread.getState() != Thread.State.TERMINATED) {
            ThreadInfo info = threads.getThreadInfo(thread.threadId());
            LockInfo awaited = info == null ? null : info.getLockInfo();
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && awaited != null
                    && awaited.getIdentityHashCode() == System.identityHashCode(lock)
                    && awaited.getClassName().equals(lock.getClass().getName())) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }

    private static <V> Started<V> start(Callable<V> call) {
        FutureTask<V> task = new FutureTask<>(call);
        return new Started<>(Thread.ofPlatform().daemon().start(task), task);
    }

    /** A daemon platform thread and what it runs. */
    private record Started<V>(Thread thread, FutureTask<V> task) {

        /** What the thread answered, once it has ended. */
        V result() throws Exception {
            thread.join();
            return task.get();
        }
    }

    /** What a logical font answers without its lock: its slot count, a font's slot, that font and slot 0's font. */
    private record Reads(int slots, int slot, FontResource added, FontResource zero) {
    }

    /**
     * A font with a name and nothing else. The slot code reads only its name, styles and anti-aliasing mode; how often
     * its boldness is read counts how often a fallback list was looked up for it.
     */
    private static final class SlotFont implements FontResource {

        private final String name;
        private final AtomicInteger boldReads = new AtomicInteger();

        SlotFont(String name) {
            this.name = name;
        }

        int boldReads() {
            return boldReads.get();
        }

        @Override
        public String getFullName() {
            return name;
        }

        @Override
        public boolean isBold() {
            boldReads.incrementAndGet();
            return false;
        }

        @Override
        public boolean isItalic() {
            return false;
        }

        @Override
        public int getDefaultAAMode() {
            return AA_GREYSCALE;
        }

        @Override
        public String getPSName() {
            throw unused();
        }

        @Override
        public String getFamilyName() {
            throw unused();
        }

        @Override
        public String getFileName() {
            throw unused();
        }

        @Override
        public String getStyleName() {
            throw unused();
        }

        @Override
        public String getLocaleFullName() {
            throw unused();
        }

        @Override
        public String getLocaleFamilyName() {
            throw unused();
        }

        @Override
        public String getLocaleStyleName() {
            throw unused();
        }

        @Override
        public int getFeatures() {
            throw unused();
        }

        @Override
        public float getAdvance(int gc, float size) {
            throw unused();
        }

        @Override
        public float[] getGlyphBoundingBox(int gc, float size, float[] retArr) {
            throw unused();
        }

        @Override
        public CharToGlyphMapper getGlyphMapper() {
            throw unused();
        }

        @Override
        public Map<FontStrikeDesc, WeakReference<FontStrike>> getStrikeMap() {
            throw unused();
        }

        @Override
        public FontStrike getStrike(float size, BaseTransform transform) {
            throw unused();
        }

        @Override
        public FontStrike getStrike(float size, BaseTransform transform, int aaMode) {
            throw unused();
        }

        @Override
        public Object getPeer() {
            throw unused();
        }

        @Override
        public void setPeer(Object peer) {
            throw unused();
        }

        @Override
        public boolean isEmbeddedFont() {
            throw unused();
        }

        @Override
        public boolean isColorGlyph(int gc) {
            throw unused();
        }

        private UnsupportedOperationException unused() {
            return new UnsupportedOperationException(name + " is a slot test font, which the slot code reads only"
                    + " for its name, styles and anti-aliasing mode");
        }
    }
}

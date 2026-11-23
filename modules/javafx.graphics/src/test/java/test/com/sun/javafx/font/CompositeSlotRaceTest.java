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
import com.sun.javafx.font.CompositeGlyphMapper;
import com.sun.javafx.font.CompositeStrike;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.FontSharedStateShim;
import com.sun.javafx.font.FontStrike;
import com.sun.javafx.font.FontStrikeDesc;
import com.sun.javafx.geom.transform.BaseTransform;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A composite's glyph mapper and a composite strike, whose slot arrays layout, render and print threads read without
 * a lock, used by two threads at once: one holds after it read the slot count, as a thread descheduled there would,
 * while the other grows or makes the slots. The composite is a fake of this test, so nothing here reaches a font file,
 * a font factory or a native call: it runs on any OS. The JUnit thread calls the code under test only before it starts
 * the workers and after they have ended, and every wait for a worker is bounded, so a regression that blocks fails
 * the test instead of hanging the fork.
 */
public class CompositeSlotRaceTest {

    /** The code the slot-2 font maps 0x100 to, and the composite answers in slot 2. */
    private static final int SLOT_2_GLYPH_A = 0x200;
    private static final int SLOT_2_GLYPH_B = 0x201;

    /**
     * A mapper that grows its slots from a slot count it read before another thread grew them further answers its
     * glyph and throws nothing: it copies only the slots it read, and publishes the copy once filled.
     */
    @Test
    void aMapperGrowingItsSlotsWhileAnotherGrewThemFurtherThrowsNothing() throws Exception {
        SlotFont empty = new SlotFont(Map.of());
        SlotFont mapped = new SlotFont(Map.of(0x100, SLOT_2_GLYPH_A, 0x101, SLOT_2_GLYPH_B));
        RaceComposite composite = new RaceComposite(new FontResource[] {empty, empty, mapped});
        composite.slots.set(1);
        CompositeGlyphMapper mapper = new CompositeGlyphMapper(composite);
        RaceThread<Integer> stale = new RaceThread<>("slot race: stale count", () -> mapper.getGlyphCode(0x100));
        RaceThread<Integer> further = new RaceThread<>("slot race: grows further", () -> mapper.getGlyphCode(0x101));
        composite.holdInCount = stale;
        composite.countCaller = "getSlotMapper";
        composite.slots.set(2);
        stale.start();
        try {
            stale.awaitHeldOrEnded();
            assertTrue(stale.held(), "the hold needs getSlotMapper to ask for the slot count when it grows its slots");
            composite.slots.set(3);
            further.start();
            assertEquals(0x02000000 | SLOT_2_GLYPH_B, further.end(stale).answered("the mapper that grew further"),
                         "the glyph of 0x101, in slot 2");
        } finally {
            stale.release();
        }
        int staleGlyph = stale.end().answered("a composite's mapper that grew its slots while another thread grew"
                + " them further");
        assertEquals(0x02000000 | SLOT_2_GLYPH_A, staleGlyph, "the glyph of 0x100, in slot 2");
        assertEquals(0x02000000 | SLOT_2_GLYPH_A, mapper.getGlyphCode(0x100), "the glyph of 0x100, asked again");
        assertEquals(0x02000000 | SLOT_2_GLYPH_B, mapper.getGlyphCode(0x101), "the glyph of 0x101, asked again");
    }

    /**
     * A slot strike asked for while another thread made the strike's slots from a stale slot count answers without
     * throwing: each call indexes only the slots it read or made itself. The composite has no font in any slot, so
     * nothing is published and both answer null.
     */
    @Test
    void aSlotStrikeAskedForWhileAnotherThreadMadeTheSlotsAnswersWithoutThrowing() throws Exception {
        RaceComposite composite = new RaceComposite(null);
        CompositeStrike strike = FontSharedStateShim.newCompositeStrike(composite, 12f);
        RaceThread<FontStrike> stale = new RaceThread<>("strike race: stale count", () -> strike.getStrikeSlot(1));
        RaceThread<FontStrike> last = new RaceThread<>("strike race: last slot", () -> strike.getStrikeSlot(40));
        composite.holdInCount = stale;
        composite.countCaller = "getStrikeSlot";
        composite.holdInSlot = last;
        composite.heldSlot = 40;
        composite.slots.set(2);
        stale.start();
        try {
            stale.awaitHeldOrEnded();
            assertTrue(stale.held(), "the hold needs getStrikeSlot to ask for the slot count when it first makes its"
                    + " slots");
            composite.slots.set(41);
            last.start();
            last.awaitHeldOrEnded(stale);
            assertTrue(last.held(), "the hold needs getStrikeSlot to ask the composite for slot 40");
            stale.release();
            assertNull(stale.end(last).answered("a slot strike asked for with a stale slot count"),
                       "the strike of a slot with no font");
        } finally {
            stale.release();
            last.release();
        }
        assertNull(last.end().answered("a slot strike asked for while another thread made the strike's slots"),
                   "the strike of a slot with no font");
    }

    /**
     * A composite whose slot count the test sets, which holds one armed thread once in {@code getNumSlots} when a
     * named method of the code under test asks, and one in {@code getSlotResource} for one slot. Its slot fonts are
     * given, or none at all.
     */
    private static final class RaceComposite extends UnusedFont implements CompositeFontResource {

        final AtomicInteger slots = new AtomicInteger();
        private final FontResource[] fonts;
        volatile RaceThread<?> holdInCount;
        volatile String countCaller;
        volatile RaceThread<?> holdInSlot;
        volatile int heldSlot;

        RaceComposite(FontResource[] fonts) {
            this.fonts = fonts;
        }

        @Override
        public int getNumSlots() {
            int read = slots.get();
            RaceThread<?> armed = holdInCount;
            if (armed != null && armed.isCurrent() && !armed.held() && calledFrom(countCaller)) {
                armed.hold();
            }
            return read;
        }

        @Override
        public FontResource getSlotResource(int slot) {
            RaceThread<?> armed = holdInSlot;
            if (armed != null && armed.isCurrent() && !armed.held() && slot == heldSlot) {
                armed.hold();
            }
            return fonts == null ? null : fonts[slot];
        }

        @Override
        public int getSlotForFont(String fontName) {
            throw unused();
        }

        /**
         * Whether the nearest caller of this composite in the mapper's or the strike's class is {@code method}: the
         * first frame of either class, wherever the walk starts.
         */
        private static boolean calledFrom(String method) {
            Optional<String> caller = StackWalker.getInstance().walk(frames -> frames
                    .filter(frame -> frame.getClassName().equals(CompositeGlyphMapper.class.getName())
                            || frame.getClassName().equals(CompositeStrike.class.getName()))
                    .findFirst().map(StackWalker.StackFrame::getMethodName));
            return caller.filter(method::equals).isPresent();
        }
    }

    /** A slot font whose mapper knows only the codes it is given; every other code is the missing glyph, 0. */
    private static final class SlotFont extends UnusedFont {

        private final CharToGlyphMapper mapper;

        SlotFont(Map<Integer, Integer> glyphs) {
            mapper = new CharToGlyphMapper() {
                @Override
                public int getGlyphCode(int charCode) {
                    return glyphs.getOrDefault(charCode, 0);
                }
            };
        }

        @Override
        public CharToGlyphMapper getGlyphMapper() {
            return mapper;
        }
    }

    /** A font resource of which this test uses nothing it does not override. */
    private abstract static class UnusedFont implements FontResource {

        @Override
        public String getFullName() {
            throw unused();
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
        public boolean isBold() {
            throw unused();
        }

        @Override
        public boolean isItalic() {
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
        public int getDefaultAAMode() {
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

        static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("a slot race fake answers only its slot count, slot fonts and"
                    + " their glyph mappers");
        }
    }
}

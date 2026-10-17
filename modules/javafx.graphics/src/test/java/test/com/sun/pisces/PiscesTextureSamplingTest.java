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

package test.com.sun.pisces;

import com.sun.pisces.JavaSurface;
import com.sun.pisces.PiscesRenderer;
import com.sun.pisces.RendererBase;
import com.sun.pisces.Transform6;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a texture paint ({@code genTexturePaintTarget} in {@code native-prism-sw/PiscesPaint.c}) samples, driven
 * through {@link PiscesRenderer} on a heap surface as {@link PiscesTexturePaintEdgeTest} does. Three properties
 * of the GPU pipelines that the software pipeline has to share:
 * <ul>
 * <li>Nearest-neighbour sampling takes the texel under the <em>centre</em> of the device pixel. A 7 x 5 texture
 * with one distinct opaque colour per texel is drawn without filtering, axis-aligned at scales 1, 1.5 and 2.5 and
 * under a 90-degree rotation at scales 1 and 2, and the texel found in every fully covered pixel is compared
 * with the texel under the pixel centre, computed in exact integer arithmetic from the placement alone. A
 * centre that lies exactly on a texel boundary is a tie, where the GPU itself is not consistent, and is
 * skipped. The rotated draw at scale 1 maps texels onto pixels one to one, so every texel appears exactly
 * once.</li>
 * <li>The edge rule of a minified draw does not depend on the transform branch. The same draw at scales 0.5 and
 * 0.3 (and 2) goes once axis-aligned ({@code TEXTURE_TRANSFORM_SCALE_TRANSLATE}) and once each rotated by 90
 * degrees and mirrored in either diagonal ({@code TEXTURE_TRANSFORM_GENERIC}), onto placements that map the
 * pixels of the one draw onto the pixels of the other, and each pair of frames has to agree, fringe pixels
 * included. The rule itself is pinned on the axis-aligned draw: a partly covered pixel whose centre lies outside
 * the drawn quad is its coverage times the nearest edge texel for clamp to edge and for nearest-neighbour clamp
 * to zero, and transparent for a linearly filtered clamp-to-zero texture whose footprint lies outside the
 * content (US-013).</li>
 * <li>Under a rotation that is not a multiple of 90 degrees the bounding box of the quad, which
 * {@code SWGraphics.drawTexture} fills, has corners outside the quad. The rotated branch paints a pixel there
 * transparent once the texture coordinate of its centre lies more than half a texel from the drawn
 * sub-rectangle; nearer, it samples as the other branches do. The draw is checked at 10, 30 and 45 degrees and
 * at scales 1 and 4 against that band, on both sides of its edge.</li>
 * </ul>
 * US-059: nearest-neighbour sampling took the pixel's top-left corner, which moved a rotated unscaled image by a
 * pixel and lost a texel row; and the rotated branch painted a pixel whose sample fell outside the drawn
 * sub-rectangle transparent where the axis-aligned branches clamped it to the edge texel. It now clamps as they
 * do within the band of the third point. Under a quarter turn or a mirror diagonal the band widens to half the
 * pixel's extent in texture space, so that it holds every pixel that touches the quad.
 */
public class PiscesTextureSamplingTest {

    private static final int SURFACE_W = 64;
    private static final int SURFACE_H = 48;

    /** The story's probe image. */
    private static final int W = 7;
    private static final int H = 5;

    private static final int EDGE = RendererBase.WRAP_CLAMP_TO_EDGE;
    private static final int REPEAT = RendererBase.WRAP_REPEAT;
    private static final int ZERO = RendererBase.WRAP_CLAMP_TO_ZERO;

    private static final int KEEP = RendererBase.IMAGE_FRAC_EDGE_KEEP;

    /** Red and green encode the texel column and row; blue marks a texel colour. */
    private static final int COLUMN_STEP = 32;
    private static final int ROW_STEP = 48;
    private static final int MARKER = 0x7F;
    /** Opaque, and not a texel colour: its blue is not {@link #MARKER}. */
    private static final int SENTINEL = 0xFFDEAD00;

    /** Scale 1 at a fractional position: the TRANSLATE branch. */
    private static final Placement AXIS_1_QUARTER = Placement.axis(1, 1, 10.25f, 6.75f);
    private static final Placement AXIS_1_5 = Placement.axis(3, 2, 10f, 6f);
    private static final Placement AXIS_1_5_QUARTER = Placement.axis(3, 2, 10.25f, 6.75f);
    private static final Placement AXIS_2_5_QUARTER = Placement.axis(5, 2, 8.25f, 5.75f);
    /** Pixel centres in columns x = 13, 18 and 23 and in rows y = 10 and 15 map exactly onto texel boundaries: ties. */
    private static final Placement AXIS_2_5_HALF = Placement.axis(5, 2, 8.5f, 5.5f);
    private static final Placement ROTATE_1 = Placement.rotate90(1, 1, 20f, 6f);
    private static final Placement ROTATE_1_QUARTER = Placement.rotate90(1, 1, 20.25f, 6.75f);
    private static final Placement ROTATE_2 = Placement.rotate90(2, 1, 24f, 6f);
    private static final Placement ROTATE_2_QUARTER = Placement.rotate90(2, 1, 24.25f, 6.75f);

    /**
     * The minified draws: a quarter of the first and last pixel column and row is covered, and those pixels'
     * centres lie outside the quad, by half a texel at scale 0.5 and by 0.83 texel at scale 0.3.
     */
    private static final Placement MINIFY_HALF = Placement.axis(1, 2, 10.75f, 6.75f);
    private static final Placement MINIFY_0_3 = Placement.axis(3, 10, 10.75f, 6.75f);
    private static final Placement MAGNIFY_2 = Placement.axis(2, 1, 10.75f, 6.75f);

    /**
     * The twins of a {@link #MINIFY_HALF}-style placement at (tx, ty) ({@link Placement#twin}): rotated by 90
     * degrees it is at (TWIN_X - ty, tx + TWIN_Y), which maps axis pixel (x, y) onto twin pixel
     * (TWIN_X - 1 - y, x + TWIN_Y); mirrored in the diagonal it is at (TWIN_X + ty, tx + TWIN_Y), pixel
     * (TWIN_X + y, x + TWIN_Y); mirrored in the anti-diagonal it is at (TWIN_X - ty, TWIN_X - tx), pixel
     * (TWIN_X - 1 - y, TWIN_X - 1 - x). The offsets are integers, so pixel edges stay pixel edges.
     */
    private static final int TWIN_X = 31;
    private static final int TWIN_Y = -2;

    /**
     * Where the band cases put the centre of the rotated texture: no pixel centre of the bounding box of any case
     * then lies within the 16.16 error bound of a band edge, so no pixel is skipped; the closest lies 7.8e-4 texel
     * beyond the bound (rotate 30 x4).
     */
    private static final double BAND_CENTRE_X = 28.34375;
    private static final double BAND_CENTRE_Y = 20.5;

    @BeforeAll
    static void requireNatives() {
        PiscesNatives.require();
    }

    static Stream<Arguments> centreCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Placement placement : List.of(AXIS_1_QUARTER, AXIS_1_5, AXIS_1_5_QUARTER, AXIS_2_5_QUARTER, AXIS_2_5_HALF,
                ROTATE_1, ROTATE_1_QUARTER, ROTATE_2, ROTATE_2_QUARTER)) {
            cases.add(Arguments.of(Entry.DRAW_IMAGE, placement, EDGE));
        }
        cases.add(Arguments.of(Entry.SET_TEXTURE, AXIS_1_5_QUARTER, EDGE));
        cases.add(Arguments.of(Entry.SET_TEXTURE, ROTATE_2_QUARTER, EDGE));
        cases.add(Arguments.of(Entry.DRAW_IMAGE, AXIS_1_5_QUARTER, ZERO));
        cases.add(Arguments.of(Entry.DRAW_IMAGE, ROTATE_2, ZERO));
        cases.add(Arguments.of(Entry.DRAW_IMAGE, AXIS_1_5_QUARTER, REPEAT));
        cases.add(Arguments.of(Entry.DRAW_IMAGE, ROTATE_2_QUARTER, REPEAT));
        return cases.stream();
    }

    /**
     * Every fully covered pixel holds the texel under its centre. REPEAT draws 2 x 2 tiles, so the texel index
     * wraps; the other modes draw the content, whose fully covered pixels never sample outside it.
     */
    @ParameterizedTest(name = "{0} {1} wrapMode={2}")
    @MethodSource("centreCases")
    void nearestNeighbourSamplesTheTexelUnderThePixelCentre(Entry entry, Placement placement, int wrapMode) {
        int tiles = wrapMode == REPEAT ? 2 : 1;
        Area area = placement.area(tiles * W, tiles * H);
        int[] frame = render(entry, placement.transform(), area, wrapMode, false);
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        int ties = 0;
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                if (!area.fullyCovers(x, y)) {
                    continue;
                }
                int[] centre = placement.texelUnderCentre(x, y);
                if (centre == null) {
                    ties++;
                    continue;
                }
                checked++;
                int expected = texelColour(Math.floorMod(centre[0], W), Math.floorMod(centre[1], H));
                int actual = frame[y * SURFACE_W + x];
                if (actual != expected) {
                    wrong.add(String.format("(%d,%d)=0x%08X %s, the centre is over texel (%d,%d) 0x%08X", x, y,
                            actual, describe(actual), centre[0], centre[1], expected));
                }
            }
        }
        assertTrue(checked >= tiles * tiles * W * H / 2, "only " + checked + " fully covered pixels were checked");
        assertTrue(wrong.isEmpty(), String.format("%s %s wrapMode=%d: %d of %d fully covered pixels (%d ties"
                + " skipped) do not hold the texel under their centre, first: %s", entry, placement, wrapMode,
                wrong.size(), checked, ties, wrong.subList(0, Math.min(4, wrong.size()))));
    }

    /** The ties of {@link #AXIS_2_5_HALF} are real: the tie rule has to skip some pixels, not all of them. */
    @Test
    void theHalfPixelPlacementAtScaleTwoAndAHalfHasTiesInThreeColumnsAndTwoRows() {
        Area area = AXIS_2_5_HALF.area(W, H);
        List<Integer> tieColumns = new ArrayList<>();
        List<Integer> tieRows = new ArrayList<>();
        for (int x = 0; x < SURFACE_W; x++) {
            if (area.fullyCovers(x, 9) && AXIS_2_5_HALF.texelUnderCentre(x, 9) == null) {
                tieColumns.add(x);
            }
        }
        for (int y = 0; y < SURFACE_H; y++) {
            if (area.fullyCovers(9, y) && AXIS_2_5_HALF.texelUnderCentre(9, y) == null) {
                tieRows.add(y);
            }
        }
        assertEquals(List.of(13, 18, 23), tieColumns, "columns of row 9 whose centre lies on a texel boundary");
        assertEquals(List.of(10, 15), tieRows, "rows of column 9 whose centre lies on a texel boundary");
    }

    /**
     * Rotated by 90 degrees at scale 1 on the pixel grid, every texel lands on exactly one pixel. The story:
     * the corner rule left the first device column transparent and never drew texel row 0.
     */
    @Test
    void rotatedUnscaledDrawPaintsEveryTexelExactlyOnce() {
        Area area = ROTATE_1.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, ROTATE_1.transform(), area, EDGE, false);
        int[] count = new int[W * H];
        List<String> foreign = new ArrayList<>();
        for (int i = 0; i < frame.length; i++) {
            int p = frame[i];
            if (p == 0) {
                continue;
            }
            int texel = texelIndex(p);
            if (texel < 0) {
                foreign.add(String.format("(%d,%d)=0x%08X", i % SURFACE_W, i / SURFACE_W, p));
            } else {
                count[texel]++;
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < count.length; i++) {
            if (count[i] == 0) {
                missing.add("(" + i % W + "," + i / W + ")");
            } else if (count[i] > 1) {
                repeated.add("(" + i % W + "," + i / W + ") x" + count[i]);
            }
        }
        assertTrue(foreign.isEmpty() && missing.isEmpty() && repeated.isEmpty(), String.format("%s: %d pixels"
                + " are not a texel colour %s; texels never drawn %s; texels drawn more than once %s", ROTATE_1,
                foreign.size(), foreign.subList(0, Math.min(4, foreign.size())), missing, repeated));
        assertEquals(W * H, Arrays.stream(frame).filter(p -> p != 0).count(), "painted pixels");
    }

    static Stream<Arguments> branchCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Kind twinKind : List.of(Kind.ROTATE_90, Kind.MIRROR_DIAGONAL, Kind.MIRROR_ANTI_DIAGONAL)) {
            for (Placement placement : List.of(MINIFY_HALF, MINIFY_0_3, MAGNIFY_2)) {
                // a mirror case names its twin in the display name
                Object shown = twinKind == Kind.ROTATE_90 ? placement
                        : Named.of(placement + " against " + placement.twin(twinKind), placement);
                for (int wrapMode : new int[] {EDGE, ZERO}) {
                    cases.add(Arguments.of(shown, wrapMode, true, twinKind));
                    cases.add(Arguments.of(shown, wrapMode, false, twinKind));
                }
            }
        }
        return cases.stream();
    }

    /**
     * The axis-aligned draw ({@code SCALE_TRANSLATE}) and its twins ({@code GENERIC}), rotated by 90 degrees and
     * mirrored in either diagonal, paint the same pixels, exactly, so the edge rule cannot depend on the branch.
     * That holds at scale 0.3 as well, whose inverse is not exact in 16.16.
     */
    @ParameterizedTest(name = "{0} wrapMode={1} linear={2}")
    @MethodSource("branchCases")
    void minifiedEdgeDoesNotDependOnTheTransformBranch(Placement placement, int wrapMode, boolean linear,
            Kind twinKind) {
        Placement twin = placement.twin(twinKind);
        int[] axis = render(Entry.DRAW_IMAGE, placement.transform(), placement.area(W, H), wrapMode, linear);
        int[] twinned = render(Entry.DRAW_IMAGE, twin.transform(), twin.area(W, H), wrapMode, linear);
        assertTwinAgrees(twinKind, axis, twinned,
                placement + " against " + twin + " wrapMode=" + wrapMode + " linear=" + linear);
    }

    static Stream<Arguments> fringeCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Placement placement : List.of(MINIFY_HALF, MINIFY_0_3)) {
            for (int wrapMode : new int[] {EDGE, ZERO}) {
                cases.add(Arguments.of(placement, wrapMode, true));
                cases.add(Arguments.of(placement, wrapMode, false));
            }
        }
        return cases.stream();
    }

    /**
     * The edge rule of a minified draw, on the axis-aligned branch: a partly covered pixel whose centre lies
     * outside the quad is its coverage (the pixel's alpha) times the texture sampled at its centre with the
     * coordinate clamped to the content - the edge texel for nearest-neighbour sampling in both clamp modes,
     * the bilinear sample of the clamped coordinate for linear filtering - except that a linearly filtered
     * clamp-to-zero texture, whose footprint there lies wholly outside the content, is transparent (the US-013
     * footprint rule).
     */
    @ParameterizedTest(name = "{0} wrapMode={1} linear={2}")
    @MethodSource("fringeCases")
    void minifiedFringeIsTheCoverageTimesTheEdgeTexel(Placement placement, int wrapMode, boolean linear) {
        Area area = placement.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, placement.transform(), area, wrapMode, linear);
        boolean transparent = wrapMode == ZERO && linear;
        List<String> wrong = new ArrayList<>();
        int fringe = 0;
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                if (!area.touches(x, y) || area.fullyCovers(x, y) || area.containsCentre(x, y)) {
                    continue;
                }
                fringe++;
                int actual = frame[y * SURFACE_W + x];
                if (transparent) {
                    if (actual != 0) {
                        wrong.add(String.format("(%d,%d)=0x%08X is not transparent", x, y, actual));
                    }
                    continue;
                }
                double[] sample = placement.clampedSample(x, y, linear);
                int alpha = actual >>> 24;
                if (alpha == 0 || alpha == 255 || !isCoverageTimes(sample, alpha, actual)) {
                    wrong.add(String.format("(%d,%d)=0x%08X is not its coverage times the edge sample"
                            + " (%.1f, %.1f, %.1f)", x, y, actual, sample[0], sample[1], sample[2]));
                }
            }
        }
        assertTrue(fringe >= 4, "only " + fringe + " fringe pixels");
        assertTrue(wrong.isEmpty(), String.format("%s wrapMode=%d linear=%b: %d of %d fringe pixels break the"
                + " edge rule, first: %s", placement, wrapMode, linear, wrong.size(), fringe,
                wrong.subList(0, Math.min(4, wrong.size()))));
    }

    static Stream<Arguments> bandCases() {
        List<Arguments> cases = new ArrayList<>();
        for (int degrees : new int[] {10, 30, 45}) {
            for (int scale : new int[] {1, 4}) {
                for (int wrapMode : new int[] {EDGE, ZERO}) {
                    cases.add(Arguments.of(new Rotation(degrees, scale), wrapMode, true));
                    cases.add(Arguments.of(new Rotation(degrees, scale), wrapMode, false));
                }
            }
        }
        return cases.stream();
    }

    /**
     * Under a rotation that is not a multiple of 90 degrees, {@code SWGraphics.drawTexture} fills the axis-aligned
     * bounding box of the quad at full coverage, so the box has corners outside the quad, and the rotated branch
     * ({@code GENERIC}) keeps them clear: it samples a pixel, bounded as the other branches do, only while the
     * texture coordinate of the pixel centre lies within half a texel of the drawn sub-rectangle on both axes,
     * and paints it transparent further out. Both sides of the band edge are checked on every pixel of the box:
     * outside the band the pixel is transparent, inside it the pixel is painted (alpha above 0). The exception is
     * a linearly filtered clamp-to-zero texture, whose footprint rule (US-013) fades the fringe out: there only a
     * pixel at least half a texel inside the quad has to be painted. The band is a width in texels, not in device
     * pixels: at scale 4 half a texel is 2 pixels. Quarter turns and mirror diagonals, whose bounding box is the
     * quad, widen the band to half the pixel's extent in texture space; the twins of
     * {@link #minifiedEdgeDoesNotDependOnTheTransformBranch} cover a quarter turn and both mirror diagonals.
     * <p>
     * The oracle maps every pixel centre through the inverse of the transform the test passes, in doubles. The
     * renderer's 16.16 texture coordinate of pixel (x, y) lies within (|x| + |y| + 3) / 65536 texel of it, so a
     * pixel whose centre lies that close to a band edge is skipped; {@link #BAND_CENTRE_X} and
     * {@link #BAND_CENTRE_Y} keep that count, which the test prints, at 0.
     */
    @ParameterizedTest(name = "{0} wrapMode={1} linear={2}")
    @MethodSource("bandCases")
    void rotatedDrawPaintsTheBandAroundTheQuadAndNothingOutsideIt(Rotation rotation, int wrapMode,
            boolean linear) {
        Area box = rotation.boundingBox();
        int[] frame = render(Entry.DRAW_IMAGE, rotation.transform(), box, wrapMode, linear);
        double[] inverse = rotation.inverse();
        // the drawn sub-rectangle is the whole texture: txMin..txMax = 0..W-1, tyMin..tyMax = 0..H-1
        double band = 0.5;
        double[] uBand = {-band, W + band};
        double[] vBand = {-band, H + band};
        boolean fadesOut = wrapMode == ZERO && linear;
        List<String> wrong = new ArrayList<>();
        int paintedOutside = 0;
        int blankInside = 0;
        int outside = 0;
        int fringe = 0;
        int inside = 0;
        int skipped = 0;
        double closest = Double.MAX_VALUE;
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                if (!box.touches(x, y)) {
                    continue;
                }
                double cx = x + 0.5;
                double cy = y + 0.5;
                double u = inverse[0] * cx + inverse[1] * cy + inverse[2];
                double v = inverse[3] * cx + inverse[4] * cy + inverse[5];
                double toEdge = Math.min(Math.min(Math.abs(u - uBand[0]), Math.abs(u - uBand[1])),
                        Math.min(Math.abs(v - vBand[0]), Math.abs(v - vBand[1])));
                double errorBound = (Math.abs(x) + Math.abs(y) + 3) / 65536.0;
                if (toEdge < errorBound) {
                    skipped++;
                    continue;
                }
                closest = Math.min(closest, toEdge - errorBound);
                int actual = frame[y * SURFACE_W + x];
                if (u < uBand[0] || u >= uBand[1] || v < vBand[0] || v >= vBand[1]) {
                    outside++;
                    if (actual != 0) {
                        paintedOutside++;
                        wrong.add(String.format("(%d,%d)=0x%08X at (u,v)=(%.3f,%.3f) lies outside the band and is"
                                + " painted", x, y, actual, u, v));
                    }
                    continue;
                }
                boolean deepInside = u >= 0.5 && u <= W - 0.5 && v >= 0.5 && v <= H - 0.5;
                if (deepInside) {
                    inside++;
                } else if (u < 0 || u >= W || v < 0 || v >= H) {
                    fringe++;
                }
                if ((actual >>> 24) == 0 && (deepInside || !fadesOut)) {
                    blankInside++;
                    wrong.add(String.format("(%d,%d) at (u,v)=(%.3f,%.3f) lies inside the band and is"
                            + " transparent", x, y, u, v));
                }
            }
        }
        String label = String.format("%s wrapMode=%d linear=%b", rotation, wrapMode, linear);
        System.out.printf("%s: band %.1f texel; box pixels: %d outside the band, %d inside it and outside the quad,"
                + " %d at least half a texel inside the quad, %d skipped within the 16.16 error bound of a band"
                + " edge, the closest other centre %.5f texel beyond it%n", label, band, outside, fringe, inside,
                skipped, closest);
        assertTrue(outside > 0, label + ": no pixel of the bounding box lies outside the band");
        assertTrue(fringe > 0, label + ": no pixel of the bounding box lies inside the band and outside the quad");
        assertTrue(inside > 0, label + ": no pixel lies inside the quad");
        assertTrue(wrong.isEmpty(), String.format("%s: %d box pixels outside the band are painted and %d inside it"
                + " are transparent (%d outside, %d inside and outside the quad, %d inside the quad, %d skipped),"
                + " first: %s", label, paintedOutside, blankInside, outside, fringe, inside, skipped,
                wrong.subList(0, Math.min(4, wrong.size()))));
    }

    /**
     * A source of zero width at the right edge of the content, {@code txMin = W} and {@code txMax = W - 1}, as
     * {@code SWGraphics.drawTexture} passes it for a source rectangle of zero width at x = W, and as the range
     * check of {@code drawImage} allows it. Nearest-neighbour sampling reads column {@code txMax} and never
     * column {@code txMin}, which lies outside the content: the texture's stride is W + 1, column W holds a
     * {@link #SENTINEL} that no pixel may show, and the box straddles the content's right edge in u.
     */
    @Test
    void zeroWidthSourceAtTheRightEdgeNeverReadsPastTheContent() {
        int stride = W + 1;
        int[] texels = new int[stride * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < stride; x++) {
                texels[y * stride + x] = x < W ? texelColour(x, y) : SENTINEL;
            }
        }
        // device x = 100 u - 685, device y = v + 6: the box [10, 20) x [6, 11) samples u in [6.95, 7.05)
        Transform6 transform = new Transform6(toS(100f), 0, 0, toS(1f), toS(-685f), toS(6f));
        int[] pixels = new int[SURFACE_W * SURFACE_H];
        PiscesRenderer pr = new PiscesRenderer(
                new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, SURFACE_W, SURFACE_H));
        pr.setClip(0, 0, SURFACE_W, SURFACE_H);
        pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
        pr.drawImage(RendererBase.TYPE_INT_ARGB_PRE, RendererBase.IMAGE_MODE_NORMAL, texels, W, H, 0, stride,
                transform, EDGE, false, toS(10f), toS(6f), toS(10f), toS(5f), KEEP, KEEP, KEEP, KEEP,
                W, 0, W - 1, H - 1, false);
        long painted = Arrays.stream(pixels).filter(p -> p != 0).count();
        long sentinels = Arrays.stream(pixels).filter(p -> p == SENTINEL).count();
        assertTrue(painted > 0, "the zero-width source painted nothing");
        assertEquals(0, sentinels, "pixels that show column W, past the content, of " + painted + " painted");
    }

    /** The two ways the software pipeline hands a texture to the paint. */
    enum Entry {
        /** {@code SWGraphics.drawTexture}, and the {@code IMAGE_PATTERN} branch of {@code SWGraphics.fillRect}. */
        DRAW_IMAGE,
        /** {@code SWPaint.setPaintBeforeDraw} for an {@code ImagePattern}: the C copies the texels first. */
        SET_TEXTURE
    }

    enum Kind {
        /** device = (s u + tx, s v + ty) */
        AXIS,
        /** device = (-s v + tx, s u + ty) */
        ROTATE_90,
        /** device = (s v + tx, s u + ty): mirrored in the diagonal */
        MIRROR_DIAGONAL,
        /** device = (-s v + tx, -s u + ty): mirrored in the anti-diagonal */
        MIRROR_ANTI_DIAGONAL
    }

    /**
     * A placement of the texture at the scale {@code scaleNum / scaleDen}, with a translation in quarter pixels,
     * so that the texel under a pixel centre follows from integer arithmetic alone: the centre (x + 1/2, y + 1/2)
     * maps to u = (x + 1/2 - tx) / s for an axis-aligned draw, with every term a multiple of 1/4.
     */
    record Placement(Kind kind, int scaleNum, int scaleDen, float tx, float ty) {

        Placement {
            if (tx * 4 != Math.rint(tx * 4) || ty * 4 != Math.rint(ty * 4)) {
                throw new IllegalArgumentException("the translation must be a multiple of a quarter pixel");
            }
        }

        static Placement axis(int scaleNum, int scaleDen, float tx, float ty) {
            return new Placement(Kind.AXIS, scaleNum, scaleDen, tx, ty);
        }

        static Placement rotate90(int scaleNum, int scaleDen, float tx, float ty) {
            return new Placement(Kind.ROTATE_90, scaleNum, scaleDen, tx, ty);
        }

        float scale() {
            return (float) scaleNum / scaleDen;
        }

        float m00() {
            return kind == Kind.AXIS ? scale() : 0f;
        }

        float m01() {
            return switch (kind) {
                case AXIS -> 0f;
                case ROTATE_90, MIRROR_ANTI_DIAGONAL -> -scale();
                case MIRROR_DIAGONAL -> scale();
            };
        }

        float m10() {
            return switch (kind) {
                case AXIS -> 0f;
                case ROTATE_90, MIRROR_DIAGONAL -> scale();
                case MIRROR_ANTI_DIAGONAL -> -scale();
            };
        }

        float m11() {
            return kind == Kind.AXIS ? scale() : 0f;
        }

        /** The texture-to-device transform the renderer inverts. */
        Transform6 transform() {
            return new Transform6(toS(m00()), toS(m01()), toS(m10()), toS(m11()), toS(tx), toS(ty));
        }

        /**
         * The device rectangle of the texture coordinates [0, w) x [0, h). A negative extent puts the left (top) edge
         * at the translation less the extent as {@link #toS} truncates it, so that the right (bottom) edge is the
         * translation in 16.16 and the box of a mirrored twin is the exact image of the axis-aligned box.
         */
        Area area(float w, float h) {
            int x = toS(tx) + toS(Math.min(0f, m00() * w)) + toS(Math.min(0f, m01() * h));
            int y = toS(ty) + toS(Math.min(0f, m10() * w)) + toS(Math.min(0f, m11() * h));
            return new Area(x / 65536f, y / 65536f, Math.abs(m00() * w) + Math.abs(m01() * h),
                    Math.abs(m10() * w) + Math.abs(m11() * h));
        }

        /**
         * The draw of kind {@code twinKind} whose pixel {@link #twinPixel twinPixel(twinKind, x, y)} is the pixel
         * (x, y) of this axis-aligned draw: the same coverage, the same texels.
         */
        Placement twin(Kind twinKind) {
            assertEquals(Kind.AXIS, kind, "only an axis-aligned placement has a twin");
            return switch (twinKind) {
                case AXIS -> this;
                case ROTATE_90 -> rotate90(scaleNum, scaleDen, TWIN_X - ty, tx + TWIN_Y);
                case MIRROR_DIAGONAL -> new Placement(twinKind, scaleNum, scaleDen, TWIN_X + ty, tx + TWIN_Y);
                case MIRROR_ANTI_DIAGONAL -> new Placement(twinKind, scaleNum, scaleDen, TWIN_X - ty, TWIN_X - tx);
            };
        }

        /** The pixel of the draw of kind {@code twinKind} that is the pixel (x, y) of the axis-aligned draw. */
        static int[] twinPixel(Kind twinKind, int x, int y) {
            return switch (twinKind) {
                case AXIS -> new int[] {x, y};
                case ROTATE_90 -> new int[] {TWIN_X - 1 - y, x + TWIN_Y};
                case MIRROR_DIAGONAL -> new int[] {TWIN_X + y, x + TWIN_Y};
                case MIRROR_ANTI_DIAGONAL -> new int[] {TWIN_X - 1 - y, TWIN_X - 1 - x};
            };
        }

        /**
         * The texel under the centre of pixel (x, y), or null when the centre lies exactly on a texel boundary.
         * All in integers: pixel centres and translations are multiples of a quarter pixel, the scale is a
         * ratio.
         */
        int[] texelUnderCentre(int x, int y) {
            long[] q = centreNumerators(x, y);
            long den = 4L * scaleNum;
            if (q[0] % den == 0 || q[1] % den == 0) {
                return null;
            }
            return new int[] {(int) Math.floorDiv(q[0], den), (int) Math.floorDiv(q[1], den)};
        }

        /**
         * The texture sampled at the centre of pixel (x, y) with the coordinate clamped to the content, as red,
         * green and blue: the texel under the centre for nearest-neighbour sampling, the bilinear sample of the
         * centre, half a texel back and clamped to the texel centres [0, W - 1] x [0, H - 1], for linear
         * filtering. Clamping the coordinate is what clamp-to-edge's duplicated edge texels do.
         */
        double[] clampedSample(int x, int y, boolean linear) {
            long[] q = centreNumerators(x, y);
            double den = 4.0 * scaleNum;
            if (!linear) {
                int[] texel = {clamp((int) Math.floorDiv(q[0], (long) den), W),
                               clamp((int) Math.floorDiv(q[1], (long) den), H)};
                return channels(texelColour(texel[0], texel[1]));
            }
            double u = Math.max(0, Math.min(W - 1, q[0] / den - 0.5));
            double v = Math.max(0, Math.min(H - 1, q[1] / den - 0.5));
            int u0 = (int) Math.floor(u);
            int v0 = (int) Math.floor(v);
            double fu = u - u0;
            double fv = v - v0;
            double[] sample = new double[3];
            for (int c = 0; c < 3; c++) {
                double top = (1 - fu) * channels(texelColour(u0, v0))[c]
                        + fu * channels(texelColour(clamp(u0 + 1, W), v0))[c];
                double bottom = (1 - fu) * channels(texelColour(u0, clamp(v0 + 1, H)))[c]
                        + fu * channels(texelColour(clamp(u0 + 1, W), clamp(v0 + 1, H)))[c];
                sample[c] = (1 - fv) * top + fv * bottom;
            }
            return sample;
        }

        /** u and v of the pixel centre as numerators over {@code 4 * scaleNum}. */
        private long[] centreNumerators(int x, int y) {
            long cx = 4L * x + 2;
            long cy = 4L * y + 2;
            long txQ = Math.round(tx * 4);
            long tyQ = Math.round(ty * 4);
            return switch (kind) {
                case AXIS -> new long[] {(cx - txQ) * scaleDen, (cy - tyQ) * scaleDen};
                case ROTATE_90 -> new long[] {(cy - tyQ) * scaleDen, (txQ - cx) * scaleDen};
                case MIRROR_DIAGONAL -> new long[] {(cy - tyQ) * scaleDen, (cx - txQ) * scaleDen};
                case MIRROR_ANTI_DIAGONAL -> new long[] {(tyQ - cy) * scaleDen, (txQ - cx) * scaleDen};
            };
        }

        @Override
        public String toString() {
            String name = switch (kind) {
                case AXIS -> "scale";
                case ROTATE_90 -> "rotate90";
                case MIRROR_DIAGONAL -> "mirror diagonal";
                case MIRROR_ANTI_DIAGONAL -> "mirror anti-diagonal";
            };
            return name + " x" + scale() + " at (" + tx + "," + ty + ")";
        }
    }

    /**
     * The texture rotated by {@code degrees} and scaled by {@code scale}, its centre on ({@link #BAND_CENTRE_X},
     * {@link #BAND_CENTRE_Y}).
     */
    record Rotation(int degrees, int scale) {

        /** The texture-to-device matrix m00, m01, m02, m10, m11, m12 in doubles. */
        private double[] matrix() {
            double radians = Math.toRadians(degrees);
            double c = scale * Math.cos(radians);
            double s = scale * Math.sin(radians);
            return new double[] {c, -s, BAND_CENTRE_X - (c * W - s * H) / 2,
                                 s, c, BAND_CENTRE_Y - (s * W + c * H) / 2};
        }

        /** The texture-to-device transform the renderer inverts. */
        Transform6 transform() {
            double[] m = matrix();
            return new Transform6(toS((float) m[0]), toS((float) m[1]), toS((float) m[3]), toS((float) m[4]),
                    toS((float) m[2]), toS((float) m[5]));
        }

        /** The inverse of {@link #transform()}, device to texture, as m00, m01, m02, m10, m11, m12 in doubles. */
        double[] inverse() {
            Transform6 t = transform();
            double m00 = t.m00 / 65536.0;
            double m01 = t.m01 / 65536.0;
            double m02 = t.m02 / 65536.0;
            double m10 = t.m10 / 65536.0;
            double m11 = t.m11 / 65536.0;
            double m12 = t.m12 / 65536.0;
            double det = m00 * m11 - m01 * m10;
            return new double[] {m11 / det, -m01 / det, (m01 * m12 - m02 * m11) / det,
                                 -m10 / det, m00 / det, (m02 * m10 - m00 * m12) / det};
        }

        /**
         * The axis-aligned bounds of the quad in float, as {@code SWGraphics.drawTexture} passes them: the
         * bounding box of the transformed source rectangle.
         */
        Area boundingBox() {
            double[] m = matrix();
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int[] corner : new int[][] {{0, 0}, {W, 0}, {0, H}, {W, H}}) {
                float x = (float) (m[0] * corner[0] + m[1] * corner[1] + m[2]);
                float y = (float) (m[3] * corner[0] + m[4] * corner[1] + m[5]);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            return new Area(minX, minY, maxX - minX, maxY - minY);
        }

        @Override
        public String toString() {
            return "rotate " + degrees + " x" + scale;
        }
    }

    record Area(float x, float y, float w, float h) {

        boolean fullyCovers(int px, int py) {
            return px >= x && px + 1 <= x + w && py >= y && py + 1 <= y + h;
        }

        boolean touches(int px, int py) {
            return px + 1 > x && px < x + w && py + 1 > y && py < y + h;
        }

        boolean containsCentre(int px, int py) {
            float cx = px + 0.5f;
            float cy = py + 0.5f;
            return cx > x && cx < x + w && cy > y && cy < y + h;
        }
    }

    private static int[] render(Entry entry, Transform6 transform, Area area, int wrapMode, boolean linear) {
        int[] pixels = new int[SURFACE_W * SURFACE_H];
        PiscesRenderer pr = new PiscesRenderer(
                new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, SURFACE_W, SURFACE_H));
        pr.setClip(0, 0, SURFACE_W, SURFACE_H);
        pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
        int[] texels = texels();
        if (entry == Entry.SET_TEXTURE) {
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, texels, W, H, W, transform, wrapMode, linear, false);
            pr.fillRect(toS(area.x()), toS(area.y()), toS(area.w()), toS(area.h()));
        } else {
            pr.drawImage(RendererBase.TYPE_INT_ARGB_PRE, RendererBase.IMAGE_MODE_NORMAL, texels, W, H, 0, W,
                    transform, wrapMode, linear, toS(area.x()), toS(area.y()), toS(area.w()), toS(area.h()),
                    KEEP, KEEP, KEEP, KEEP, 0, 0, W - 1, H - 1, false);
        }
        return pixels;
    }

    private static int toS(float v) {
        return (int) (v * 65536f);
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(size - 1, i));
    }

    /** One distinct opaque colour per texel. */
    private static int[] texels() {
        int[] texels = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                texels[y * W + x] = texelColour(x, y);
            }
        }
        return texels;
    }

    private static int texelColour(int x, int y) {
        return 0xFF000000 | ((16 + x * COLUMN_STEP) << 16) | ((16 + y * ROW_STEP) << 8) | MARKER;
    }

    /** The index {@code y * W + x} of the texel with this colour, or -1 if no texel has it. */
    private static int texelIndex(int colour) {
        if ((colour >>> 24) != 0xFF || (colour & 0xFF) != MARKER) {
            return -1;
        }
        int r = (colour >> 16) & 0xFF;
        int g = (colour >> 8) & 0xFF;
        if (r < 16 || (r - 16) % COLUMN_STEP != 0 || g < 16 || (g - 16) % ROW_STEP != 0) {
            return -1;
        }
        int x = (r - 16) / COLUMN_STEP;
        int y = (g - 16) / ROW_STEP;
        return x < W && y < H ? y * W + x : -1;
    }

    private static String describe(int colour) {
        int texel = texelIndex(colour);
        return texel < 0 ? "is not a texel colour" : "is texel (" + texel % W + "," + texel / W + ")";
    }

    /** Premultiplied: every colour channel is the sample's channel times alpha / 255, within one. */
    private static boolean isCoverageTimes(double[] sample, int alpha, int actual) {
        double[] channels = channels(actual);
        for (int c = 0; c < 3; c++) {
            if (Math.abs(Math.round(sample[c] * alpha / 255.0) - channels[c]) > 1) {
                return false;
            }
        }
        return true;
    }

    /** Red, green and blue of a colour. */
    private static double[] channels(int colour) {
        return new double[] {(colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF};
    }

    /** Pixel (x, y) of the axis-aligned frame against pixel {@link Placement#twinPixel} of the twin frame. */
    private static void assertTwinAgrees(Kind twinKind, int[] axis, int[] twin, String label) {
        assertTrue(Arrays.stream(axis).anyMatch(p -> p != 0), label + ": the axis-aligned draw painted nothing");
        List<String> differing = new ArrayList<>();
        int worst = 0;
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                int[] at = Placement.twinPixel(twinKind, x, y);
                int qx = at[0];
                int qy = at[1];
                if (qx < 0 || qx >= SURFACE_W || qy < 0 || qy >= SURFACE_H) {
                    continue;
                }
                int p = axis[y * SURFACE_W + x];
                int q = twin[qy * SURFACE_W + qx];
                int diff = channelDiff(p, q);
                worst = Math.max(worst, diff);
                if (diff > 0) {
                    differing.add(String.format("axis (%d,%d)=0x%08X, twin (%d,%d)=0x%08X", x, y, p, qx, qy, q));
                }
            }
        }
        assertTrue(differing.isEmpty(), String.format("%s: %d pixels differ between the axis-aligned and the"
                + " twin draw (worst %d per channel), first: %s", label, differing.size(), worst,
                differing.subList(0, Math.min(4, differing.size()))));
        assertEquals(Arrays.stream(axis).filter(p -> p != 0).count(),
                Arrays.stream(twin).filter(p -> p != 0).count(), label + ": painted pixel counts");
    }

    private static int channelDiff(int p, int q) {
        int worst = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            worst = Math.max(worst, Math.abs(((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF)));
        }
        return worst;
    }
}

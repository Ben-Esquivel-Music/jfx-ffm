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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The edges of a linear-filtered texture paint ({@code genTexturePaintTarget} in
 * {@code native-prism-sw/PiscesPaint.c}), driven through {@link PiscesRenderer} on a heap surface. Both entry
 * points are covered - {@code drawImage}, and {@code setTexture} followed by {@code fillRect} - and every
 * interpolating branch: {@code TEXTURE_TRANSFORM_TRANSLATE} (scale 1, fractional translation),
 * {@code TEXTURE_TRANSFORM_SCALE_TRANSLATE} (scales 1.5, 2 and 3, and a mirror) and
 * {@code TEXTURE_TRANSFORM_GENERIC} (a 90-degree rotation), each with and without alpha.
 * <p>
 * The main oracle is padding, which is how GPU Prism simulates a wrap mode the hardware lacks. A {@code W x H}
 * texture drawn with clamp-to-edge has to equal, bit for bit, the same texture padded by one duplicated edge
 * texel on every side and drawn as its inner sub-rectangle ({@code txMin = 1}, {@code txMax = W}, ...) with the
 * transform shifted by one texel. Every texel the padded draw interpolates is a real texel inside the array, so
 * the oracle never takes the edge path under test. The clamp-to-zero twin pads with transparent texels, the
 * REPEAT twin compares with the texture pre-tiled and drawn as its inner tiles. The wrap mode applies at the
 * content edge only, so a sub-rectangle draw and a pooled texture with cleared slack are unchanged by it.
 * <p>
 * The transforms are chosen so that {@code pisces_transform_invert} is exact in float: scales 1, 1.5, 2, 3 and 4,
 * translations that are multiples of the scale or dyadic. The shifted transform then inverts to exactly
 * {@code 0x10000} more per texel of shift, both draws sample the same points, and no product rounds, so FMA
 * contraction cannot move a frame between platforms.
 * <p>
 * US-013: the first device column or row that samples left of or above the first texel centre was interpolated
 * between texels 0 and 1 with the weights meant for -1 and 0, and the neighbour read went to the next row or
 * column of the array - past its end for a texture one texel tall or wide.
 */
public class PiscesTexturePaintEdgeTest {

    private static final int SURFACE_W = 96;
    private static final int SURFACE_H = 80;

    /** The size of the story's border and stripe images. */
    private static final int W = 16;
    private static final int H = 10;

    private static final int GREEN = 0xFF00FF00;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;

    /** Fills the array behind a texture, so that a read past the texture shows up as red. */
    private static final int SENTINEL = RED;

    private static final int KEEP = RendererBase.IMAGE_FRAC_EDGE_KEEP;
    private static final int PAD = RendererBase.IMAGE_FRAC_EDGE_PAD;
    private static final int TRIM = RendererBase.IMAGE_FRAC_EDGE_TRIM;

    private static final int EDGE = RendererBase.WRAP_CLAMP_TO_EDGE;
    private static final int REPEAT = RendererBase.WRAP_REPEAT;
    private static final int ZERO = RendererBase.WRAP_CLAMP_TO_ZERO;

    /** How many pixels next to each side of a drawn area count as its edge in a failure message. */
    private static final int EDGE_BAND = 3;

    private static final Placement TRANSLATE = Placement.translate(10.25f, 6.75f);
    private static final Placement TRANSLATE_QUARTER = Placement.translate(10.25f, 6.25f);
    private static final Placement TRANSLATE_HALF = Placement.translate(10.5f, 6.5f);
    private static final Placement SCALE_1_5 = Placement.scale(1.5f, 6f, 4.5f);
    private static final Placement SCALE_2 = Placement.scale(2f, 8f, 6f);
    private static final Placement SCALE_3 = Placement.scale(3f, 6f, 3f);
    private static final Placement SCALE_4 = Placement.scale(4f, 8f, 8f);
    /** Scale 0.25: the left and bottom, or the right and top, edge pixels sample beyond texel -1 or W. */
    private static final Placement MINIFY_LEFT_BOTTOM = Placement.scale(0.25f, 10.9375f, 6.5625f);
    private static final Placement MINIFY_RIGHT_TOP = Placement.scale(0.25f, 10.0625f, 6.9375f);
    private static final Placement MIRROR_2 = new Placement("mirror x2", -2f, 0f, 0f, 2f, 40f, 6f);
    private static final Placement ROTATE_2 = new Placement("rotate90 x2", 0f, -2f, 2f, 0f, 28f, 6f);
    /** Nearest-neighbour sampling hits texel -1 only from a fractional position. */
    private static final Placement ROTATE_2_FRACTIONAL = new Placement("rotate90 x2 at (28.5,6.5)", 0f, -2f, 2f, 0f,
            28.5f, 6.5f);
    private static final Placement ROTATE_4 = new Placement("rotate90 x4", 0f, -4f, 4f, 0f, 24f, 8f);

    /** The texture is the only texture in its array, or sits at the start of an array of sentinels. */
    private static final Shape ROW_4X1 = new Shape("4x1", 4, 1, 4);
    private static final Shape COLUMN_1X4 = new Shape("1x4 stride 1", 1, 4, 1);
    private static final Shape SINGLE_1X1 = new Shape("1x1", 1, 1, 1);

    /**
     * SHA-256 of the frames of the sub-rectangle draws, captured from the unfixed C at commit {@code dc521e99b6}.
     * A sub-rectangle that ends inside the texture interpolates with the real texel beyond its edge, as a GPU
     * does, so the US-013 fix must not move any of them.
     */
    private static final Map<String, String> SUB_RECTANGLE_FRAMES = Map.of(
            "viewport translate(10.25,6.75) hasAlpha=true",
                    "f4d037ebbc062270991fedd57b9971848ef3a7851a45132e781f364b2192146e",
            "viewport translate(10.25,6.75) hasAlpha=false",
                    "7de44bc723d83dc7b6ddb981a97f641dd46265aece70fe619d8cfcd0b26bea4a",
            "viewport scale x2.0 hasAlpha=true",
                    "6f8a002d033d099119d50fa9de93f7c771cfe99e6b60ec525082f16e1efa6fcd",
            "viewport scale x2.0 hasAlpha=false",
                    "15430a7e39163391ae331e2d85fcd24ae8a2c3218ae1a0bcb7859e2764a068b2",
            "viewport rotate90 x2 hasAlpha=true",
                    "7636f65ab2c85353274315cf45b565972d1ee546d47d7cbcefedbc6aa886a1fc",
            "viewport rotate90 x2 hasAlpha=false",
                    "195058fd7733daf1ad0b7e8ef3e706b3525fa92634e61777b32b17078f3db1b4",
            "drawTexture3SliceH shape",
                    "78b18568cd6e164a4e01806e0368c8209292f367a4e93252a4ff33104a8f8c33");

    @BeforeAll
    static void requireNatives() {
        PiscesNatives.require();
    }

    static Stream<Arguments> interpolatingCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Placement placement : List.of(TRANSLATE, SCALE_2, SCALE_1_5, SCALE_3, MIRROR_2, ROTATE_2)) {
                cases.add(Arguments.of(entry, placement, true));
                cases.add(Arguments.of(entry, placement, false));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} hasAlpha={2}")
    @MethodSource("interpolatingCases")
    void clampToEdgeEqualsTheEdgePaddedTexture(Entry entry, Placement placement, boolean hasAlpha) {
        int[] texels = texels(hasAlpha, W, H);
        Area area = placement.area(W, H);
        int[] expected = render(Entry.DRAW_IMAGE, edgePadded(texels, W, H), placement.transform(1, 1), area,
                EDGE, true, hasAlpha);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area,
                EDGE, true, hasAlpha);
        assertSameFrame(expected, actual, area, entry + " " + placement + " clamp to edge hasAlpha=" + hasAlpha);
    }

    /**
     * CLAMP_TO_ZERO: the texture padded by one transparent texel on every side. The padded texture has alpha,
     * so the oracle always takes the alpha interpolators; so does the zero mode, which must not fade an opaque
     * texture to opaque black.
     */
    @ParameterizedTest(name = "{0} {1} hasAlpha={2}")
    @MethodSource("interpolatingCases")
    void clampToZeroEqualsTheZeroPaddedTexture(Entry entry, Placement placement, boolean hasAlpha) {
        int[] texels = texels(hasAlpha, W, H);
        Area area = placement.area(W, H);
        int[] expected = render(Entry.DRAW_IMAGE, zeroPadded(texels, W, H), placement.transform(1, 1), area,
                EDGE, true, true);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area,
                ZERO, true, hasAlpha);
        assertSameFrame(expected, actual, area, entry + " " + placement + " clamp to zero hasAlpha=" + hasAlpha);
    }

    static Stream<Arguments> minifiedCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Placement placement : List.of(MINIFY_LEFT_BOTTOM, MINIFY_RIGHT_TOP)) {
                cases.add(Arguments.of(entry, placement, true));
                cases.add(Arguments.of(entry, placement, false));
            }
        }
        return cases.stream();
    }

    /**
     * CLAMP_TO_ZERO at scale 0.25: the partly covered first or last pixel samples two or more texels outside the
     * content, where both texels of the pair are transparent. The oracle pads with four transparent texels and draws
     * the whole padded texture, so that no sample of it is clamped at all.
     */
    @ParameterizedTest(name = "{0} {1} hasAlpha={2}")
    @MethodSource("minifiedCases")
    void minifiedClampToZeroEqualsTheZeroPaddedTexture(Entry entry, Placement placement, boolean hasAlpha) {
        int[] texels = texels(hasAlpha, W, H);
        Area area = placement.area(W, H);
        int[] expected = render(Entry.DRAW_IMAGE, zeroPaddedWhole(texels, W, H, 4), placement.transform(4, 4), area,
                EDGE, true, true);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area,
                ZERO, true, hasAlpha);
        assertSameFrame(expected, actual, area, entry + " " + placement + " clamp to zero hasAlpha=" + hasAlpha);
    }

    static Stream<Arguments> allocationCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Placement placement : List.of(TRANSLATE, SCALE_2, SCALE_1_5, SCALE_3, MIRROR_2, ROTATE_2)) {
            cases.add(Arguments.of(placement, true));
            cases.add(Arguments.of(placement, false));
        }
        return cases.stream();
    }

    /**
     * A pooled Decora texture ({@code ImagePool.checkOut}) is larger than its content and cleared, and
     * {@code SWGraphics.drawTexture} passes the pool size as the texture size with {@code txMax} at the drawn
     * size. Clamp-to-zero gives the same four edges as from an exact-size texture.
     */
    @ParameterizedTest(name = "{0} hasAlpha={1}")
    @MethodSource("allocationCases")
    void clampToZeroDoesNotDependOnTheTextureAllocation(Placement placement, boolean hasAlpha) {
        int[] texels = texels(hasAlpha, W, H);
        Area area = placement.area(W, H);
        int[] exact = render(Entry.DRAW_IMAGE, Tex.whole(texels, W, H), placement.transform(0, 0), area,
                ZERO, true, hasAlpha);
        int[] pooled = render(Entry.DRAW_IMAGE, pooled(texels, W, H, W + 5, H + 3), placement.transform(0, 0), area,
                ZERO, true, hasAlpha);
        assertSameFrame(exact, pooled, area, placement + " clamp to zero, pooled 21x13 against exact 16x10 hasAlpha="
                + hasAlpha);
    }

    static Stream<Arguments> nearestNeighbourCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Placement placement : List.of(TRANSLATE, SCALE_1_5, ROTATE_2_FRACTIONAL)) {
                cases.add(Arguments.of(entry, placement, EDGE));
                cases.add(Arguments.of(entry, placement, ZERO));
            }
        }
        return cases.stream();
    }

    /**
     * Nearest-neighbour sampling takes the edge texel in both clamp modes: these draws sample the device pixel's
     * corner, which lies up to a pixel outside the content for a partly covered first column or row.
     */
    @ParameterizedTest(name = "{0} {1} wrapMode={2}")
    @MethodSource("nearestNeighbourCases")
    void nearestNeighbourEqualsTheEdgePaddedTexture(Entry entry, Placement placement, int wrapMode) {
        int[] texels = translucentTexels(W, H);
        Area area = placement.area(W, H);
        int[] expected = render(Entry.DRAW_IMAGE, edgePadded(texels, W, H), placement.transform(1, 1), area,
                EDGE, false, true);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area, wrapMode, false,
                true);
        assertSameFrame(expected, actual, area, entry + " " + placement + " nearest wrapMode=" + wrapMode);
    }

    static Stream<Arguments> nearestNeighbourRepeatCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Placement placement : List.of(TRANSLATE, SCALE_1_5, ROTATE_2_FRACTIONAL)) {
                cases.add(Arguments.of(entry, placement));
            }
        }
        return cases.stream();
    }

    /**
     * Nearest-neighbour REPEAT: a corner that lies left of or above the first texel samples tile texel -1, which
     * is texel W - 1. The oracle draws the whole pre-tiled texture, so that no sample comes near its edges.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nearestNeighbourRepeatCases")
    void nearestNeighbourRepeatEqualsThePreTiledTexture(Entry entry, Placement placement) {
        int[] texels = translucentTexels(W, H);
        Area area = placement.area(W, H);
        Tex tiled = preTiled(texels, W, H, 3);
        Tex wholeTiled = new Tex(tiled.data(), tiled.w(), tiled.h(), 0, tiled.stride(), 0, 0, tiled.w() - 1,
                tiled.h() - 1);
        int[] expected = render(Entry.DRAW_IMAGE, wholeTiled, placement.transform(W, H), area, EDGE, false, true);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area, REPEAT, false, true);
        assertSameFrame(expected, actual, area, entry + " " + placement + " nearest repeat");
    }

    /**
     * The story's alpha ramp (outer texel alpha 64, then 160, then 255) at scale 2, against D3D: an image texture
     * (clamp to edge) starts with 64 on every side, a Decora texture (clamp to zero) with 48 = 0.75 x 64.
     */
    @ParameterizedTest(name = "wrapMode={0}")
    @ValueSource(ints = {RendererBase.WRAP_CLAMP_TO_EDGE, RendererBase.WRAP_CLAMP_TO_ZERO})
    void alphaRampEdgesMatchTheGpuForTheWrapMode(int wrapMode) {
        Area area = SCALE_2.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, Tex.whole(rampTexels(), W, H), SCALE_2.transform(0, 0), area,
                wrapMode, true, true);
        int edge = wrapMode == ZERO ? 48 : 64;
        int x0 = (int) area.x();
        int y0 = (int) area.y();
        int x1 = x0 + (int) area.w() - 1;
        int y1 = y0 + (int) area.h() - 1;
        int midX = x0 + (int) area.w() / 2;
        int midY = y0 + (int) area.h() / 2;
        int[] expected = {edge, 88, 88, edge, edge, 88, 88, edge};
        int[] actual = {
            frame[midY * SURFACE_W + x0] >>> 24, frame[midY * SURFACE_W + x0 + 1] >>> 24,
            frame[midY * SURFACE_W + x1 - 1] >>> 24, frame[midY * SURFACE_W + x1] >>> 24,
            frame[y0 * SURFACE_W + midX] >>> 24, frame[(y0 + 1) * SURFACE_W + midX] >>> 24,
            frame[(y1 - 1) * SURFACE_W + midX] >>> 24, frame[y1 * SURFACE_W + midX] >>> 24,
        };
        assertWithinOne(expected, actual, "alpha of the first two and last two pixels of the middle row, then of"
                + " the middle column (left, top, right and bottom edges)");
    }

    static Stream<Arguments> repeatCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (boolean hasAlpha : new boolean[] {true, false}) {
                cases.add(Arguments.of(entry, TRANSLATE, 1, hasAlpha));
                cases.add(Arguments.of(entry, SCALE_1_5, 1, hasAlpha));
                cases.add(Arguments.of(entry, ROTATE_2, 1, hasAlpha));
                cases.add(Arguments.of(entry, SCALE_2, 2, hasAlpha));
            }
        }
        return cases.stream();
    }

    /** REPEAT: texel -1 of a tile is its texel W - 1, over one tile and over 2 x 2 tiles. */
    @ParameterizedTest(name = "{0} {1} tiles={2}x{2} hasAlpha={3}")
    @MethodSource("repeatCases")
    void repeatEqualsThePreTiledTexture(Entry entry, Placement placement, int tiles, boolean hasAlpha) {
        int[] texels = texels(hasAlpha, W, H);
        Area area = placement.area(tiles * W, tiles * H);
        int[] expected = render(Entry.DRAW_IMAGE, preTiled(texels, W, H, tiles + 2), placement.transform(W, H),
                area, EDGE, true, hasAlpha);
        int[] actual = render(entry, Tex.whole(texels, W, H), placement.transform(0, 0), area, REPEAT, true,
                hasAlpha);
        assertSameFrame(expected, actual, area, entry + " " + placement + " repeat " + tiles + "x" + tiles
                + " hasAlpha=" + hasAlpha);
    }

    static Stream<Arguments> thinTileCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Shape shape : List.of(ROW_4X1, COLUMN_1X4)) {
            for (Placement placement : List.of(TRANSLATE, SCALE_4, ROTATE_4)) {
                cases.add(Arguments.of(shape, placement, true));
                cases.add(Arguments.of(shape, placement, false));
            }
        }
        return cases.stream();
    }

    /**
     * REPEAT with a one-row or one-column tile at the start of an array of sentinels: the tile's neighbour row or
     * column is the tile itself, never the next row of the array ({@code getPointsToInterpolateRepeat}).
     */
    @ParameterizedTest(name = "{0} {1} hasAlpha={2}")
    @MethodSource("thinTileCases")
    void repeatOfAOneTexelTallOrWideTileEqualsThePreTiledTexture(Shape shape, Placement placement,
            boolean hasAlpha) {
        int[] texels = texels(hasAlpha, shape.w(), shape.h());
        Area area = placement.area(shape.w(), shape.h());
        int[] expected = render(Entry.DRAW_IMAGE, preTiled(texels, shape.w(), shape.h(), 3),
                placement.transform(shape.w(), shape.h()), area, EDGE, true, hasAlpha);
        int[] actual = render(Entry.DRAW_IMAGE, withSentinelTail(texels, shape), placement.transform(0, 0), area,
                REPEAT, true, hasAlpha);
        assertSameFrame(expected, actual, area, shape + " " + placement + " repeat hasAlpha=" + hasAlpha);
    }

    static Stream<Arguments> outOfBoundsCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Shape shape : List.of(ROW_4X1, COLUMN_1X4, SINGLE_1X1)) {
            for (Placement placement : List.of(TRANSLATE_HALF, SCALE_4, ROTATE_4)) {
                for (int wrapMode : new int[] {EDGE, ZERO}) {
                    cases.add(Arguments.of(shape, placement, wrapMode, true));
                    cases.add(Arguments.of(shape, placement, wrapMode, false));
                }
            }
        }
        return cases.stream();
    }

    /**
     * An opaque green texture one texel tall, one texel wide, or both, at the start of an array whose tail is
     * opaque red. Interpolating green texels can only give premultiplied green, so a red or blue component means
     * the paint read past the texture. With clamp to edge a fully covered pixel is the texel colour exactly;
     * clamp to zero fades the edge pixels, but only ever towards transparent.
     */
    @ParameterizedTest(name = "{0} {1} wrapMode={2} hasAlpha={3}")
    @MethodSource("outOfBoundsCases")
    void aOneTexelTallOrWideTextureNeverReadsPastItsEnd(Shape shape, Placement placement, int wrapMode,
            boolean hasAlpha) {
        int[] texels = new int[shape.w() * shape.h()];
        Arrays.fill(texels, GREEN);
        Area area = placement.area(shape.w(), shape.h());
        int[] frame = render(Entry.DRAW_IMAGE, withSentinelTail(texels, shape), placement.transform(0, 0), area,
                wrapMode, true, hasAlpha);
        assertOnlyPremultipliedGreen(frame, wrapMode == EDGE ? area : null,
                shape + " " + placement + " wrapMode=" + wrapMode + " hasAlpha=" + hasAlpha);
    }

    /** The border image is symmetric, so its scaled draw is: each first column and row mirrors the last one. */
    @ParameterizedTest(name = "scale {0}")
    @ValueSource(floats = {2f, 1.5f, 3f})
    void scaledBorderImageIsMirrorSymmetric(float scale) {
        Placement placement = Map.of(2f, SCALE_2, 1.5f, SCALE_1_5, 3f, SCALE_3).get(scale);
        Area area = placement.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, Tex.whole(borderTexels(), W, H), placement.transform(0, 0), area,
                EDGE, true, true);
        // the 16.16 m00 of scale 1.5 and 3 is not exactly 2/3 or 1/3, so mirrored sample points differ by an ulp of u
        assertMirrorSymmetric(frame, area, scale == 2f ? 0 : 1, "border image " + placement);
    }

    static Stream<Placement> centroidCases() {
        return Stream.of(SCALE_2, SCALE_1_5, SCALE_3, TRANSLATE_QUARTER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("centroidCases")
    void borderImageAlphaCentroidIsTheGeometricCentre(Placement placement) {
        Area area = placement.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, Tex.whole(borderTexels(), W, H), placement.transform(0, 0), area,
                EDGE, true, true);
        double[] centroid = alphaCentroid(frame);
        double cx = area.x() + area.w() / 2.0;
        double cy = area.y() + area.h() / 2.0;
        assertTrue(Math.abs(centroid[0] - cx) <= 0.01 && Math.abs(centroid[1] - cy) <= 0.01,
                String.format("alpha centroid (%.4f, %.4f), geometric centre (%.4f, %.4f)",
                        centroid[0], centroid[1], cx, cy));
    }

    /**
     * Translate (0.25, 0.25): the first and last columns differ in coverage, so no mirror applies; the middle row
     * and column are those D3D and ES2 draw (story US-013, measured 2026-09-26).
     */
    @Test
    void quarterTexelTranslatedBorderImageMatchesTheGpuEdge() {
        Area area = TRANSLATE_QUARTER.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, Tex.whole(borderTexels(), W, H), TRANSLATE_QUARTER.transform(0, 0),
                area, EDGE, true, true);
        int[] gpuRow = {0, 191, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 64, 0};
        int[] gpuColumn = {0, 191, 255, 255, 255, 255, 255, 255, 255, 64, 0};
        int[] row = new int[gpuRow.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = frame[11 * SURFACE_W + 10 + i] >>> 24;
        }
        int[] column = new int[gpuColumn.length];
        for (int i = 0; i < column.length; i++) {
            column[i] = frame[(6 + i) * SURFACE_W + 18] >>> 24;
        }
        assertWithinOne(gpuRow, row, "alpha of row y=11, x=10..26");
        assertWithinOne(gpuColumn, column, "alpha of column x=18, y=6..16");
    }

    /** Opaque red, blue and green columns and rows: the first device column and row are pure edge colour. */
    @ParameterizedTest(name = "hasAlpha={0}")
    @ValueSource(booleans = {true, false})
    void stripeImageEdgeColumnsAndRowsArePureEdgeColour(boolean hasAlpha) {
        Area area = SCALE_2.area(W, H);
        int[] frame = render(Entry.DRAW_IMAGE, Tex.whole(stripeTexels(), W, H), SCALE_2.transform(0, 0), area,
                EDGE, true, hasAlpha);
        int x0 = (int) area.x();
        int y0 = (int) area.y();
        int x1 = x0 + (int) area.w() - 1;
        int y1 = y0 + (int) area.h() - 1;
        List<String> wrong = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x : new int[] {x0, x1}) {
                expectPixel(frame, x, y, RED, wrong);
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int y : new int[] {y0, y1}) {
                expectPixel(frame, x, y, RED, wrong);
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " edge pixels are not the edge colour 0xFFFF0000, first: "
                + wrong.subList(0, Math.min(4, wrong.size())));
    }

    static Stream<Arguments> subRectangleCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Placement placement : List.of(TRANSLATE, SCALE_2, ROTATE_2)) {
            for (boolean hasAlpha : new boolean[] {true, false}) {
                String name = "viewport " + placement + " hasAlpha=" + hasAlpha;
                Supplier<int[]> edge = () -> viewport(placement, hasAlpha, EDGE);
                Supplier<int[]> zero = () -> viewport(placement, hasAlpha, ZERO);
                cases.add(Arguments.of(name, "clamp to edge", edge));
                cases.add(Arguments.of(name, "clamp to zero", zero));
            }
        }
        cases.add(Arguments.of("drawTexture3SliceH shape", "clamp to edge",
                (Supplier<int[]>) PiscesTexturePaintEdgeTest::threeSliceH));
        return cases.stream();
    }

    /**
     * {@code txMin > 0}: the texel left of the sub-rectangle is real and is interpolated, before and after the
     * US-013 fix. A texel inside the content is read as it is in every wrap mode, so clamp to zero draws a
     * sub-rectangle with real texels on all four sides exactly as clamp to edge does.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("subRectangleCases")
    void subRectangleDrawsAreUnchanged(String name, String wrapMode, Supplier<int[]> draw) {
        int[] frame = draw.get();
        assertTrue(Arrays.stream(frame).anyMatch(p -> p != 0), name + ": nothing was drawn");
        assertEquals(SUB_RECTANGLE_FRAMES.get(name), sha256(frame), name + " " + wrapMode + ": frame SHA-256");
    }

    /** The 8 x 6 texels at (4, 2) of the 16 x 10 texture: a sub-rectangle with real texels on all four sides. */
    private static int[] viewport(Placement placement, boolean hasAlpha, int wrapMode) {
        Tex tex = new Tex(texels(hasAlpha, W, H), W, H, 0, W, 4, 2, 11, 7);
        return render(Entry.DRAW_IMAGE, tex, placement.transform(4, 2), placement.area(8, 6), wrapMode, true,
                hasAlpha);
    }

    /**
     * The three {@code drawImage} calls of {@code SWGraphics.drawTexture3SliceH}, with its edge modes: the end
     * slices at scale 1 (the right one at a half-pixel position), the middle slice stretched by 2.3125.
     */
    private static int[] threeSliceH() {
        int[] pixels = new int[SURFACE_W * SURFACE_H];
        PiscesRenderer pr = renderer(pixels);
        int[] texels = translucentTexels(W, H);
        drawSlice(pr, texels, 0, 4, 8f, 12f, KEEP, PAD);
        drawSlice(pr, texels, 4, 12, 12f, 30.5f, TRIM, PAD);
        drawSlice(pr, texels, 12, 16, 30.5f, 34.5f, TRIM, KEEP);
        return pixels;
    }

    private static void drawSlice(PiscesRenderer pr, int[] texels, int sx1, int sx2, float dx1, float dx2,
            int lEdge, int rEdge) {
        Placement placement = new Placement("slice", (dx2 - dx1) / (sx2 - sx1), 0f, 0f, 1f, dx1, 6f);
        Tex tex = new Tex(texels, W, H, 0, W, sx1, 0, sx2 - 1, H - 1);
        drawImage(pr, tex, placement.transform(sx1, 0), placement.area(sx2 - sx1, H), EDGE, true, true,
                lEdge, rEdge);
    }

    /** The two ways the software pipeline hands a texture to the paint. */
    enum Entry {
        /** {@code SWGraphics.drawTexture}, and the {@code IMAGE_PATTERN} branch of {@code SWGraphics.fillRect}. */
        DRAW_IMAGE,
        /** {@code SWPaint.setPaintBeforeDraw} for an {@code ImagePattern}: the C copies the texels first. */
        SET_TEXTURE
    }

    /**
     * device = m * ((u, v) - shift) + t for texture coordinates (u, v): the texture-to-device transform that the
     * renderer inverts. Every placement keeps the pixel grid, so the drawn area is an axis-aligned rectangle.
     */
    record Placement(String name, float m00, float m01, float m10, float m11, float tx, float ty) {

        static Placement translate(float tx, float ty) {
            return new Placement("translate(" + tx + "," + ty + ")", 1f, 0f, 0f, 1f, tx, ty);
        }

        static Placement scale(float s, float tx, float ty) {
            return new Placement("scale x" + s, s, 0f, 0f, s, tx, ty);
        }

        Transform6 transform(float shiftU, float shiftV) {
            return new Transform6(toS(m00), toS(m01), toS(m10), toS(m11),
                    toS(tx - (m00 * shiftU + m01 * shiftV)), toS(ty - (m10 * shiftU + m11 * shiftV)));
        }

        /** The device rectangle of the texture coordinates [0, w) x [0, h). */
        Area area(float w, float h) {
            float x = tx + Math.min(0f, m00 * w) + Math.min(0f, m01 * h);
            float y = ty + Math.min(0f, m10 * w) + Math.min(0f, m11 * h);
            return new Area(x, y, Math.abs(m00 * w) + Math.abs(m01 * h), Math.abs(m10 * w) + Math.abs(m11 * h));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record Area(float x, float y, float w, float h) {

        boolean fullyCovers(int px, int py) {
            return px >= x && px + 1 <= x + w && py >= y && py + 1 <= y + h;
        }
    }

    /** {@code w x h} texels at {@code offset} with {@code stride}; [txMin..txMax] x [tyMin..tyMax] is drawn. */
    record Tex(int[] data, int w, int h, int offset, int stride, int txMin, int tyMin, int txMax, int tyMax) {

        static Tex whole(int[] texels, int w, int h) {
            return new Tex(texels, w, h, 0, w, 0, 0, w - 1, h - 1);
        }

        boolean isWhole() {
            return offset == 0 && txMin == 0 && tyMin == 0 && txMax == w - 1 && tyMax == h - 1;
        }
    }

    record Shape(String name, int w, int h, int stride) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static int[] render(Entry entry, Tex tex, Transform6 transform, Area area, int wrapMode,
            boolean linear, boolean hasAlpha) {
        int[] pixels = new int[SURFACE_W * SURFACE_H];
        PiscesRenderer pr = renderer(pixels);
        if (entry == Entry.SET_TEXTURE) {
            assertTrue(tex.isWhole(), "setTexture draws whole textures only");
            pr.setTexture(RendererBase.TYPE_INT_ARGB_PRE, tex.data(), tex.w(), tex.h(), tex.stride(), transform,
                    wrapMode, linear, hasAlpha);
            pr.fillRect(toS(area.x()), toS(area.y()), toS(area.w()), toS(area.h()));
        } else {
            drawImage(pr, tex, transform, area, wrapMode, linear, hasAlpha, KEEP, KEEP);
        }
        return pixels;
    }

    private static PiscesRenderer renderer(int[] pixels) {
        PiscesRenderer pr = new PiscesRenderer(
                new JavaSurface(pixels, RendererBase.TYPE_INT_ARGB_PRE, SURFACE_W, SURFACE_H));
        pr.setClip(0, 0, SURFACE_W, SURFACE_H);
        pr.setCompositeRule(RendererBase.COMPOSITE_SRC_OVER);
        return pr;
    }

    private static void drawImage(PiscesRenderer pr, Tex tex, Transform6 transform, Area area, int wrapMode,
            boolean linear, boolean hasAlpha, int lEdge, int rEdge) {
        pr.drawImage(RendererBase.TYPE_INT_ARGB_PRE, RendererBase.IMAGE_MODE_NORMAL, tex.data(), tex.w(), tex.h(),
                tex.offset(), tex.stride(), transform, wrapMode, linear,
                toS(area.x()), toS(area.y()), toS(area.w()), toS(area.h()), lEdge, rEdge, KEEP, KEEP,
                tex.txMin(), tex.tyMin(), tex.txMax(), tex.tyMax(), hasAlpha);
    }

    private static int toS(float v) {
        return (int) (v * 65536f);
    }

    /** The texture with one duplicated edge texel on every side; its inner sub-rectangle is the texture. */
    private static Tex edgePadded(int[] texels, int w, int h) {
        int pw = w + 2;
        int ph = h + 2;
        int[] padded = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                padded[y * pw + x] = texels[clamp(y - 1, h) * w + clamp(x - 1, w)];
            }
        }
        return new Tex(padded, pw, ph, 0, pw, 1, 1, w, h);
    }

    private static int clamp(int i, int size) {
        return Math.max(0, Math.min(size - 1, i));
    }

    /** The texture with one transparent texel on every side; its inner sub-rectangle is the texture. */
    private static Tex zeroPadded(int[] texels, int w, int h) {
        int pw = w + 2;
        int[] padded = new int[pw * (h + 2)];
        for (int y = 0; y < h; y++) {
            System.arraycopy(texels, y * w, padded, (y + 1) * pw + 1, w);
        }
        return new Tex(padded, pw, h + 2, 0, pw, 1, 1, w, h);
    }

    /** The texture with {@code pad} transparent texels on every side, drawn whole. */
    private static Tex zeroPaddedWhole(int[] texels, int w, int h, int pad) {
        int pw = w + 2 * pad;
        int ph = h + 2 * pad;
        int[] padded = new int[pw * ph];
        for (int y = 0; y < h; y++) {
            System.arraycopy(texels, y * w, padded, (y + pad) * pw + pad, w);
        }
        return Tex.whole(padded, pw, ph);
    }

    /**
     * The {@code ImagePool} shape: the texels in the top-left corner of a cleared {@code poolW x poolH} texture
     * whose content size is the pool size, drawn over {@code [0, w - 1] x [0, h - 1]}.
     */
    private static Tex pooled(int[] texels, int w, int h, int poolW, int poolH) {
        int[] pool = new int[poolW * poolH];
        for (int y = 0; y < h; y++) {
            System.arraycopy(texels, y * w, pool, y * poolW, w);
        }
        return new Tex(pool, poolW, poolH, 0, poolW, 0, 0, w - 1, h - 1);
    }

    /** The texture tiled {@code n x n} times; the sub-rectangle drawn is everything but the outer ring of tiles. */
    private static Tex preTiled(int[] texels, int w, int h, int n) {
        int pw = n * w;
        int ph = n * h;
        int[] tiled = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                tiled[y * pw + x] = texels[(y % h) * w + (x % w)];
            }
        }
        return new Tex(tiled, pw, ph, 0, pw, w, h, (n - 1) * w - 1, (n - 1) * h - 1);
    }

    /** The texture at the start of an array with the given stride; every other element is {@link #SENTINEL}. */
    private static Tex withSentinelTail(int[] texels, Shape shape) {
        int[] data = new int[shape.stride() * (shape.h() + 1) + shape.w() + 8];
        Arrays.fill(data, SENTINEL);
        for (int y = 0; y < shape.h(); y++) {
            System.arraycopy(texels, y * shape.w(), data, y * shape.stride(), shape.w());
        }
        return new Tex(data, shape.w(), shape.h(), 0, shape.stride(), 0, 0, shape.w() - 1, shape.h() - 1);
    }

    private static int[] texels(boolean hasAlpha, int w, int h) {
        return hasAlpha ? translucentTexels(w, h) : opaqueTexels(w, h);
    }

    /** Premultiplied, alpha from 30 to 255, every texel different. */
    private static int[] translucentTexels(int w, int h) {
        int[] texels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = 255 - (x * 12 + y * 5) % 226;
                int r = a * x / Math.max(1, w - 1);
                int g = a * y / Math.max(1, h - 1);
                int b = a / 2;
                texels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return texels;
    }

    /** Opaque, every texel different. */
    private static int[] opaqueTexels(int w, int h) {
        int[] texels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / Math.max(1, w - 1);
                int g = y * 255 / Math.max(1, h - 1);
                int b = (x * 37 + y * 101) & 0xFF;
                texels[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return texels;
    }

    /** The story's 16 x 10 image: a transparent one-texel border around an opaque grey interior. */
    private static int[] borderTexels() {
        return ringTexels(0x00000000, 0xFF808080, 0xFF808080);
    }

    /** The story's alpha ramp, premultiplied white: alpha 64 on the outer ring, 160 on the next, 255 inside. */
    private static int[] rampTexels() {
        return ringTexels(0x40404040, 0xA0A0A0A0, 0xFFFFFFFF);
    }

    /** Opaque: a red outer ring, a blue ring inside it, a green interior. */
    private static int[] stripeTexels() {
        return ringTexels(RED, BLUE, GREEN);
    }

    private static int[] ringTexels(int outer, int second, int interior) {
        int[] texels = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int ring = Math.min(Math.min(x, W - 1 - x), Math.min(y, H - 1 - y));
                texels[y * W + x] = ring == 0 ? outer : ring == 1 ? second : interior;
            }
        }
        return texels;
    }

    /** Fails with the number of differing pixels per edge of the drawn area, and the first of them. */
    private static void assertSameFrame(int[] expected, int[] actual, Area area, String label) {
        assertTrue(Arrays.stream(expected).anyMatch(p -> p != 0), label + ": the oracle drew nothing");
        int first = -1;
        Map<String, Integer> differing = new TreeMap<>();
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                if (first < 0) {
                    first = i;
                }
                differing.merge(where(area, i % SURFACE_W, i / SURFACE_W), 1, Integer::sum);
            }
        }
        if (first >= 0) {
            int x = first % SURFACE_W;
            int y = first / SURFACE_W;
            fail(String.format("%s: pixels differ from the oracle %s; first (%d,%d) on the %s, expected 0x%08X but"
                    + " was 0x%08X", label, differing, x, y, where(area, x, y), expected[first], actual[first]));
        }
    }

    /**
     * Which edge of the drawn area a pixel belongs to: within {@value #EDGE_BAND} pixels of a side (a texel at
     * scale 3 covers three), the interior, or outside the area.
     */
    private static String where(Area area, int x, int y) {
        int x0 = (int) Math.floor(area.x());
        int x1 = (int) Math.ceil(area.x() + area.w()) - 1;
        int y0 = (int) Math.floor(area.y());
        int y1 = (int) Math.ceil(area.y() + area.h()) - 1;
        if (x < x0 || x > x1 || y < y0 || y > y1) {
            return "outside";
        }
        List<String> sides = new ArrayList<>();
        if (x - x0 < EDGE_BAND) {
            sides.add("left");
        }
        if (x1 - x < EDGE_BAND) {
            sides.add("right");
        }
        if (y - y0 < EDGE_BAND) {
            sides.add("top");
        }
        if (y1 - y < EDGE_BAND) {
            sides.add("bottom");
        }
        return sides.isEmpty() ? "interior" : String.join("+", sides) + " edge";
    }

    /** {@code area} null: no pixel has to be the texel colour exactly. */
    private static void assertOnlyPremultipliedGreen(int[] frame, Area area, String label) {
        List<String> foreign = new ArrayList<>();
        List<String> notExact = new ArrayList<>();
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                int p = frame[y * SURFACE_W + x];
                int a = p >>> 24;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                String at = String.format("(%d,%d)=0x%08X", x, y, p);
                if (r != 0 || b != 0 || g != a) {
                    foreign.add(at);
                }
                if (area != null && area.fullyCovers(x, y) && p != GREEN) {
                    notExact.add(at);
                }
            }
        }
        assertTrue(foreign.isEmpty() && notExact.isEmpty(), String.format("%s: %d pixels are not premultiplied"
                + " green, so the paint read past the texture %s; %d fully covered pixels are not 0xFF00FF00 %s",
                label, foreign.size(),
                foreign.subList(0, Math.min(4, foreign.size())), notExact.size(),
                notExact.subList(0, Math.min(4, notExact.size()))));
    }

    /** Every pixel the area touches against its mirror image about the area's centre, per channel. */
    private static void assertMirrorSymmetric(int[] frame, Area area, int tolerance, String label) {
        int x0 = (int) Math.floor(area.x());
        int x1 = (int) Math.ceil(area.x() + area.w());
        int y0 = (int) Math.floor(area.y());
        int y1 = (int) Math.ceil(area.y() + area.h());
        int xMirror = Math.round(2 * area.x() + area.w()) - 1;
        int yMirror = Math.round(2 * area.y() + area.h()) - 1;
        List<String> asymmetric = new ArrayList<>();
        int worst = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = frame[y * SURFACE_W + x];
                int horizontal = frame[y * SURFACE_W + (xMirror - x)];
                int vertical = frame[(yMirror - y) * SURFACE_W + x];
                int diff = Math.max(channelDiff(p, horizontal), channelDiff(p, vertical));
                worst = Math.max(worst, diff);
                if (diff > tolerance) {
                    asymmetric.add(String.format("(%d,%d)=0x%08X mirrors (%d,%d)=0x%08X and (%d,%d)=0x%08X", x, y, p,
                            xMirror - x, y, horizontal, x, yMirror - y, vertical));
                }
            }
        }
        assertTrue(asymmetric.isEmpty(), String.format("%s: %d pixels differ from their mirror image by more than"
                + " %d (worst %d), first: %s", label, asymmetric.size(), tolerance, worst,
                asymmetric.subList(0, Math.min(3, asymmetric.size()))));
    }

    private static int channelDiff(int p, int q) {
        int worst = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            worst = Math.max(worst, Math.abs(((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF)));
        }
        return worst;
    }

    /** Alpha-weighted centre of the pixel centres. */
    private static double[] alphaCentroid(int[] frame) {
        double sum = 0;
        double sx = 0;
        double sy = 0;
        for (int y = 0; y < SURFACE_H; y++) {
            for (int x = 0; x < SURFACE_W; x++) {
                int a = frame[y * SURFACE_W + x] >>> 24;
                sum += a;
                sx += a * (x + 0.5);
                sy += a * (y + 0.5);
            }
        }
        assertTrue(sum > 0, "nothing was drawn");
        return new double[] {sx / sum, sy / sum};
    }

    private static void assertWithinOne(int[] expected, int[] actual, String label) {
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > 1) {
                fail(label + ": expected " + Arrays.toString(expected) + " within 1 but was "
                        + Arrays.toString(actual));
            }
        }
    }

    private static void expectPixel(int[] frame, int x, int y, int expected, List<String> wrong) {
        int p = frame[y * SURFACE_W + x];
        if (p != expected) {
            wrong.add(String.format("(%d,%d)=0x%08X", x, y, p));
        }
    }

    private static String sha256(int[] frame) {
        ByteBuffer bytes = ByteBuffer.allocate(frame.length * Integer.BYTES);
        bytes.asIntBuffer().put(frame);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}

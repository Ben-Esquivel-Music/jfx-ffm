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

package test.com.sun.scenario.effect;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.Affine2D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.GaussianRenderState;
import com.sun.scenario.effect.impl.sw.RendererDelegate;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolvePeer;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static test.com.sun.scenario.effect.DecoraCorpus.ONE_STEP;
import static test.com.sun.scenario.effect.DecoraCorpus.PRIMARY_SEED;
import static test.com.sun.scenario.effect.DecoraCorpus.SHADOW_TINT;
import static test.com.sun.scenario.effect.DecoraCorpus.channelDelta;

/**
 * A two-pass Gaussian blur or shadow rendered through an output clip has to reproduce, inside the clip, the same kernel
 * rendered without the clip, also when its vertical radius is larger than its horizontal one.
 * <p>
 * Pass 0 blurs horizontally and pass 1 vertically, and production hands both passes the same output clip
 * ({@code LinearConvolveCoreEffect.filterImageDatas}, which {@link DecoraBackend#gaussian} mirrors). Pass 0 therefore
 * has to keep the rows beyond the clip that pass 1 reads: {@code GaussianRenderState.getPassResultBounds} grows the
 * pass-0 clip along the pass-1 sample vector by the pass-1 radius, which is {@code ceil(radiusY)} rows for an
 * untransformed kernel, as {@code BoxRenderState} grows it by half the pass-1 kernel
 * ({@code getInputKernelSize(1) / 2}). It used to grow it by {@code ceil(radiusX)}, so for
 * {@code ceil(radiusX) < ceil(radiusY)} pass 1 read transparent pixels for the missing rows and faded the result next
 * to the clip's top and bottom edges. The Java peers ({@code JSWLinearConvolvePeer},
 * {@code JSWLinearConvolveShadowPeer}) and the GPU peers ({@code PPSLinearConvolvePeer},
 * {@code PPSLinearConvolveShadowPeer}) all take their pass bounds from the render state.
 * <p>
 * Every render uses a fresh {@link DecoraBackend}, so the pool images, whose physical size the peers scale texture
 * coordinates by, do not depend on an earlier render. The clipped render may differ from the unclipped one by one step
 * per channel on any row, and the geometry below is what makes that bound hold. Pass 0, where it runs, runs
 * {@code filterHV} in both renders once its clip grows by {@code ceil(radiusY)} rows, and computes every row it keeps
 * from the same source row, so those rows are bit-identical. Once pass 0 keeps every row pass 1 reads, pass 1 filters
 * the same pixels in both renders. Under a bottom cut it runs {@code filterHV} in both, and the clipped render is
 * bit-identical inside the clip. Under a top cut the clipped pass 1 runs {@code filterVector}, which rounds the same
 * weighted sum differently: for the blur both loops truncate, {@code filterVector} over bilinear samples taken at
 * pixel centres; for the shadow {@code filterHV} truncates {@code c * round(sum)} and {@code filterVector}, which
 * takes the nearest pixel, truncates {@code c * sum}, with {@code c <= 1}. Either way they differ by at most one step
 * per channel. The controls pass before the fix too.
 * <p>
 * The clips leave the left and right edges alone and cut a 64x64 source at row 48 and, unless the source is cut as
 * below, at row {@code ceil(radiusY)}. The bottom cut keeps the result origin, so both passes run the {@code filterHV}
 * loops, and the grown pass-0 clip still cuts the source there. A top cut moves the pass-1 result origin, which sends
 * pass 1 to the {@code filterVector} loop (JDK-8092042), while pass 0 keeps {@code filterHV} because its clip, grown
 * by {@code ceil(radiusY)}, reaches the source top. A deeper top cut would send pass 0 to {@code filterVector} as well.
 * The two loops round differently, so two {@code filterVector} passes differ from two {@code filterHV} passes by as
 * much as two steps for the blur and three for the shadow here, whatever the pass-0 clip. The Decora golden shows the
 * same for the native peers: its shadow clipped 8 rows into the top differs from its unclipped render by three steps
 * at radius 3, and by one at radius 12.3, whose grown pass-0 clip reaches the source top.
 * <p>
 * Production does not usually hand the effect the whole source: {@code FilterEffect.filter} asks its input for
 * {@code GaussianRenderState.getInputClip} of the clip, for an untransformed kernel the clip grown by
 * {@code ceil(radiusX)} columns and {@code ceil(radiusY)} rows, and {@code NodeEffectInput} renders the node only over
 * that rectangle unless it has a larger image cached. The grown pass-0 clip then reaches the top of the cut source,
 * however deep the top cut is, so pass 0 keeps {@code filterHV}. The cut-source cases render a source cut that way
 * through the same clips, except that a top cut starts at row 20, against the whole source rendered unclipped. The
 * render-state tests cover rotated filter transforms, which {@link DecoraBackend} does not render.
 */
public class GaussianPassClipGrowthTest {

    private static final int SIZE = 64;
    /** The row the clips that cut the bottom end before. */
    private static final int CLIP_BOTTOM = 48;
    /** The row the clips that cut the top start at in the cut-source cases, deeper than {@code ceil(radiusY)}. */
    private static final int CUT_SOURCE_CLIP_TOP = 20;
    /** How far a clip reaches beyond a source edge it leaves alone. */
    private static final int OUTSIDE = 100;
    /** A premultiplied opaque colour. */
    private static final int OPAQUE = 0xFF2060A0;
    /** Below {@code LinearConvolveRenderState.MIN_EFFECT_RADIUS} (1/256), which makes a pass a no-op. */
    private static final float BELOW_MIN_EFFECT_RADIUS = 0.002f;

    /** {@code ceil(radiusX) < ceil(radiusY)}: pass 0 grew its clip by fewer rows than pass 1 reads. */
    private static final List<Radii> ANISOTROPIC = List.of(new Radii(2f, 8f), new Radii(1.5f, 12f));

    /**
     * Radii the pass-0 growth never cut short: isotropic, {@code radiusX != radiusY} with equal ceilings,
     * {@code radiusX > radiusY} (the growth shrinks to what pass 1 reads), and a horizontal radius that makes pass 0 a
     * no-op, so that pass 1 filters the source.
     */
    private static final List<Radii> CONTROLS = List.of(new Radii(5f, 5f), new Radii(4.2f, 4.8f),
            new Radii(10f, 1f), new Radii(BELOW_MIN_EFFECT_RADIUS, 10f));

    /** The blur kernel runs the {@code LinearConvolve} peer; the shadows run the {@code LinearConvolveShadow} peer. */
    private static final List<Kernel> KERNELS = List.of(
            new Kernel("blur", false, null, 0f),
            new Kernel("shadow black spread=0.0", true, Color4f.BLACK, 0f),
            new Kernel("shadow black spread=0.5", true, Color4f.BLACK, 0.5f),
            new Kernel("shadow tinted spread=0.0", true, SHADOW_TINT, 0f),
            new Kernel("shadow tinted spread=0.5", true, SHADOW_TINT, 0.5f));

    /** The kernels of the cut-source cases: the blur, and the shadow with a colour and a spread. */
    private static final List<Kernel> CUT_SOURCE_KERNELS = List.of(KERNELS.get(0), KERNELS.get(4));

    /** The loops each pass of a render on a {@link #recordingBackend()} ran, in order; removed after each render. */
    private static final ThreadLocal<List<String>> LOOPS = ThreadLocal.withInitial(ArrayList::new);

    record Radii(float x, float y) {

        /** The row a clip that cuts the top of the whole source starts at. */
        int top() {
            return (int) Math.ceil(y);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "radiusX=%s radiusY=%s", x, y);
        }
    }

    record Kernel(String name, boolean shadow, Color4f color, float spread) {

        String peer() {
            return shadow ? JSWLinearConvolveShadowPeer.class.getName() : JSWLinearConvolvePeer.class.getName();
        }
    }

    enum Cut {
        TOP_AND_BOTTOM("top+bottom"), TOP("top only"), BOTTOM("bottom only");

        private final String label;

        Cut(String label) {
            this.label = label;
        }

        boolean top() {
            return this != BOTTOM;
        }

        /** The clip, starting at row {@code topRow} if it cuts the top. */
        Rectangle clip(int topRow) {
            int top = top() ? topRow : -OUTSIDE;
            int bottom = this == TOP ? SIZE + OUTSIDE : CLIP_BOTTOM;
            return new Rectangle(-OUTSIDE, top, SIZE + 2 * OUTSIDE, bottom - top);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Source {
        /** The Decora corpus input: opaque and transparent frames, a red diagonal band and noise. */
        PATTERN,
        /**
         * An opaque block over rows 2 to 61 and columns 8 to 55, inside the source on every side. Every cut passes
         * through it except the top cut at row 1 of the {@code radiusY=1} control.
         */
        OPAQUE_BLOCK;

        int[] pixels() {
            if (this == PATTERN) {
                return DecoraCorpus.pattern(SIZE, SIZE, PRIMARY_SEED);
            }
            int[] pixels = new int[SIZE * SIZE];
            for (int y = 2; y < SIZE - 2; y++) {
                for (int x = 8; x < SIZE - 8; x++) {
                    pixels[y * SIZE + x] = OPAQUE;
                }
            }
            return pixels;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /**
     * One kernel over one source, rendered through one clip. With {@code cutSource}, the clipped render filters only
     * the part of the source production would render for that clip, and a top cut starts at row
     * {@code CUT_SOURCE_CLIP_TOP}; otherwise it filters the whole source and a top cut starts at row
     * {@code ceil(radiusY)}. The unclipped render always filters the whole source.
     */
    record Case(Kernel kernel, Radii radii, Cut cut, Source source, boolean cutSource) {

        Case(Kernel kernel, Radii radii, Cut cut, Source source) {
            this(kernel, radii, cut, source, false);
        }

        Rectangle clip() {
            return cut.clip(cutSource ? CUT_SOURCE_CLIP_TOP : radii.top());
        }

        GaussianRenderState state() {
            return new GaussianRenderState(radii.x(), radii.y(), kernel.spread(), kernel.shadow(), kernel.color(),
                    BaseTransform.IDENTITY_TRANSFORM);
        }

        /**
         * The source bounds the render filters: the whole source, or for a clipped cut-source render the source
         * intersected with {@code getInputClip(0, clip)}, as {@code FilterEffect.filter} and {@code NodeEffectInput}
         * cut it.
         */
        Rectangle sourceBounds(Rectangle clip) {
            Rectangle bounds = new Rectangle(0, 0, SIZE, SIZE);
            if (cutSource && clip != null) {
                bounds.intersectWith(state().getInputClip(0, clip));
            }
            return bounds;
        }

        Result render(DecoraBackend backend, Rectangle clip) {
            Rectangle bounds = sourceBounds(clip);
            int[] whole = source.pixels();
            int[] pixels = new int[bounds.width * bounds.height];
            for (int y = 0; y < bounds.height; y++) {
                System.arraycopy(whole, (bounds.y + y) * SIZE + bounds.x, pixels, y * bounds.width, bounds.width);
            }
            ImageData input = backend.data(Image.of(bounds.width, bounds.height, pixels), bounds);
            return backend.gaussian(input, radii.x(), radii.y(), kernel.spread(), kernel.shadow(), kernel.color(),
                    clip);
        }

        @Override
        public String toString() {
            String input = cutSource ? " cut to the input clip" : "";
            return kernel.name() + " " + radii + " clip " + cut + " " + source + input;
        }
    }

    static Stream<Arguments> anisotropicCases() {
        return cases(ANISOTROPIC);
    }

    static Stream<Arguments> controlCases() {
        return cases(CONTROLS);
    }

    private static Stream<Arguments> cases(List<Radii> radii) {
        List<Arguments> args = new ArrayList<>();
        for (Source source : Source.values()) {
            for (Radii r : radii) {
                for (Cut cut : Cut.values()) {
                    for (Kernel kernel : KERNELS) {
                        Case c = new Case(kernel, r, cut, source);
                        args.add(Arguments.of(c.toString(), c));
                    }
                }
            }
        }
        return args.stream();
    }

    /** {@code ceil(radiusX) < ceil(radiusY)}: pass 0 has to keep {@code ceil(radiusY)} rows beyond the clip. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("anisotropicCases")
    void anisotropicClippedRenderMatchesUnclipped(String name, Case c) {
        assertClippedRenderMatchesUnclipped(c);
    }

    /** Radii whose pass-0 clip already kept every row pass 1 reads. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("controlCases")
    void controlClippedRenderMatchesUnclipped(String name, Case c) {
        assertClippedRenderMatchesUnclipped(c);
    }

    static Stream<Arguments> cutSourceCases() {
        List<Arguments> args = new ArrayList<>();
        for (Radii r : ANISOTROPIC) {
            for (Cut cut : List.of(Cut.TOP_AND_BOTTOM, Cut.BOTTOM)) {
                for (Kernel kernel : CUT_SOURCE_KERNELS) {
                    Case c = new Case(kernel, r, cut, Source.PATTERN, true);
                    args.add(Arguments.of(c.toString(), c));
                }
            }
        }
        return args.stream();
    }

    /**
     * {@code ceil(radiusX) < ceil(radiusY)} over the part of the source production renders for the clip: the cut
     * source ends {@code ceil(radiusY)} rows beyond the clip, and pass 0 has to keep all of them.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cutSourceCases")
    void cutSourceClippedRenderMatchesUnclipped(String name, Case c) {
        Rectangle source = new Rectangle(0, 0, SIZE, SIZE);
        Rectangle cut = c.sourceBounds(c.clip());
        assertNotEquals(source, cut, () -> c + ": the input clip does not cut the source");
        if (c.cut().top()) {
            assertTrue(cut.y > 0, () -> c + ": the input clip does not cut the source top: " + cut);
        }
        assertClippedRenderMatchesUnclipped(c);
    }

    private static void assertClippedRenderMatchesUnclipped(Case c) {
        DecoraBackend backend = DecoraBackend.java();
        Result clipped = c.render(backend, c.clip());
        assertEquals(Set.of(c.kernel().peer()), backend.ranPeers(), () -> c + ": peers that ran");
        Result full = c.render(DecoraBackend.java(), null);
        Rectangle fullBounds = new Rectangle(full.x(), full.y(), full.width(), full.height());
        Rectangle expected = new Rectangle(fullBounds);
        expected.intersectWith(c.clip());
        Rectangle actual = new Rectangle(clipped.x(), clipped.y(), clipped.width(), clipped.height());
        assertEquals(expected, actual, () -> c + ": clipped result bounds");
        assertNotEquals(fullBounds, actual, () -> c + ": the clip does not cut the result");

        Result cropped = DecoraCorpus.crop(full, clipped);
        List<String> rows = new ArrayList<>();
        int over = 0;
        int worst = -1;
        int worstDelta = 0;
        for (int y = 0; y < clipped.height(); y++) {
            int rowDelta = 0;
            for (int x = 0; x < clipped.width(); x++) {
                int i = y * clipped.width() + x;
                int delta = channelDelta(clipped.pixels()[i], cropped.pixels()[i]);
                rowDelta = Math.max(rowDelta, delta);
                if (delta > ONE_STEP) {
                    over++;
                    if (delta > worstDelta) {
                        worst = i;
                        worstDelta = delta;
                    }
                }
            }
            if (rowDelta > ONE_STEP) {
                rows.add(String.format(Locale.ROOT, "%d (%d/%d): %d", clipped.y() + y, y, clipped.height() - 1 - y,
                        rowDelta));
            }
        }
        if (over > 0) {
            int w = clipped.width();
            fail(String.format(Locale.ROOT, "%s: the clipped render differs from the unclipped one by more than %d step"
                    + " on %d of %d pixels, at most %d at (%d,%d): clipped %08x, unclipped %08x. Rows above the bound,"
                    + " as y (rows from the result's top/bottom edge): max delta: %s", c, ONE_STEP, over,
                    clipped.pixels().length, worstDelta, clipped.x() + worst % w, clipped.y() + worst / w,
                    clipped.pixels()[worst], cropped.pixels()[worst], String.join(", ", rows)));
        }
    }

    static Stream<Arguments> loopCases() {
        List<Arguments> args = new ArrayList<>();
        List<Radii> radii = new ArrayList<>(ANISOTROPIC);
        radii.addAll(CONTROLS);
        for (Radii r : radii) {
            for (Cut cut : Cut.values()) {
                for (Kernel kernel : List.of(KERNELS.get(0), KERNELS.get(2))) {
                    Case c = new Case(kernel, r, cut, Source.PATTERN);
                    args.add(Arguments.of(c.toString(), c));
                }
            }
        }
        cutSourceCases().forEach(args::add);
        return args.stream();
    }

    /**
     * The loops the cases above run: {@code filterHV} for pass 0, whose grown clip keeps the result origin, and for
     * pass 1 unless the clip cuts the top, which moves the pass-1 origin and takes {@code filterVector}. A pass-0 clip
     * grown by fewer rows than pass 1 reads cut the source top as well and sent pass 0 to {@code filterVector}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("loopCases")
    void loopsFollowTheClip(String name, Case c) {
        List<String> expected = new ArrayList<>();
        if (c.radii().x() != BELOW_MIN_EFFECT_RADIUS) {
            expected.add("pass 0 filterHV");
        }
        expected.add(c.cut().top() ? "pass 1 filterVector" : "pass 1 filterHV");
        List<String> loops;
        try {
            c.render(recordingBackend(), c.clip());
            loops = List.copyOf(LOOPS.get());
        } finally {
            LOOPS.remove();
        }
        assertEquals(expected, loops, () -> c + ": loops");
    }

    static Stream<Arguments> renderStateRadii() {
        return Stream.of(
                Arguments.of("ceil(radiusX) < ceil(radiusY)", new Radii(2f, 8f)),
                Arguments.of("ceil(radiusX) < ceil(radiusY)", new Radii(1.5f, 12f)),
                Arguments.of("isotropic", new Radii(5f, 5f)),
                Arguments.of("equal ceilings", new Radii(4.2f, 4.8f)),
                Arguments.of("radiusX > radiusY", new Radii(10f, 1f)));
    }

    /**
     * Pass 0 keeps {@code ceil(radiusY)} rows above and below the clip, within the rows the source has, and nothing
     * beyond the clip horizontally; pass 1 returns the clip. For {@code radiusX > radiusY} pass 0 used to keep
     * {@code ceil(radiusX)} rows, more than pass 1 reads.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("renderStateRadii")
    void passZeroKeepsTheRowsPassOneReads(String kind, Radii radii) {
        GaussianRenderState state = new GaussianRenderState(radii.x(), radii.y(), 0f, false, null,
                BaseTransform.IDENTITY_TRANSFORM);
        DecoraBackend backend = DecoraBackend.java();
        Rectangle source = new Rectangle(0, 0, SIZE, SIZE);
        ImageData input = backend.data(Image.of(SIZE, SIZE, new int[SIZE * SIZE]), source);
        int pad = radii.top();
        for (int clipTop : new int[] {20, 4}) {
            Rectangle clip = new Rectangle(10, clipTop, 30, 16);
            int passZeroTop = Math.max(0, clipTop - pad);
            Rectangle passZeroExpected = new Rectangle(10, passZeroTop, 30, clipTop + 16 + pad - passZeroTop);

            state.validatePassInput(input, 0);
            Rectangle passZero = state.getPassResultBounds(source, clip);
            assertEquals(passZeroExpected, passZero, () -> radii + " clip " + clip + ": pass-0 result bounds");

            ImageData passZeroOutput = backend.data(Image.of(passZero.width, passZero.height,
                    new int[passZero.width * passZero.height]), passZero);
            state.validatePassInput(passZeroOutput, 1);
            assertEquals(clip, state.getPassResultBounds(passZero, clip), () -> radii + " clip " + clip
                    + ": pass-1 result bounds");
        }
    }

    static Stream<Arguments> rotatedFilterTransforms() {
        return Stream.of(
                Arguments.of("rotate 90", new Affine2D(0, 1, -1, 0, 0, 0), new Rectangle(2, 20, 46, 16)),
                Arguments.of("rotate 53.13 (3-4-5)", new Affine2D(0.6, 0.8, -0.8, 0.6, 0, 0),
                        new Rectangle(3, 15, 44, 26)));
    }

    /**
     * Under a rotated filter transform pass 1 runs along the rotated Y axis, and pass 0 keeps the clip grown along that
     * vector by {@code radiusY}: {@code ceil(|vx * radiusY|)} columns and {@code ceil(|vy * radiusY|)} rows. The
     * transforms are exact matrices ({@code Affine2D(mxx, myx, mxy, myy, mxt, myt)}), so no rounding residue decides a
     * ceiling. With {@code radiusX=2 radiusY=8} and the clip (10,20,30,16):
     * <ul>
     * <li>rotate 90: pass 1 runs along (-1, 0), so pass 0 keeps 8 columns and no rows, (2,20,46,16). Growing by
     * {@code ceil(radiusX)} along that vector, as before the fix, gives (8,20,34,16); growing only the rows by
     * {@code ceil(radiusY)} gives (10,12,30,32).</li>
     * <li>the 3-4-5 rotation: pass 1 runs along (-0.8, 0.6), so pass 0 keeps 7 columns (6.4) and 5 rows (4.8),
     * (3,15,44,26). Growing only along the longer component gives (3,20,44,16).</li>
     * </ul>
     * Pass 1 returns the clip.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rotatedFilterTransforms")
    void passZeroGrowsAlongThePassOneVector(String name, BaseTransform filterTransform, Rectangle passZeroExpected) {
        GaussianRenderState state = new GaussianRenderState(2f, 8f, 0f, false, null, filterTransform);
        DecoraBackend backend = DecoraBackend.java();
        Rectangle source = new Rectangle(0, 0, SIZE, SIZE);
        Rectangle clip = new Rectangle(10, 20, 30, 16);

        state.validatePassInput(backend.data(Image.of(SIZE, SIZE, new int[SIZE * SIZE]), source), 0);
        Rectangle passZero = state.getPassResultBounds(source, clip);
        assertEquals(passZeroExpected, passZero, () -> name + " clip " + clip + ": pass-0 result bounds");

        ImageData passZeroOutput = backend.data(Image.of(passZero.width, passZero.height,
                new int[passZero.width * passZero.height]), passZero);
        state.validatePassInput(passZeroOutput, 1);
        assertEquals(clip, state.getPassResultBounds(passZero, clip), () -> name + " clip " + clip
                + ": pass-1 result bounds");
    }

    /**
     * The single-pass {@code MotionBlur} state has no pass 1 and no vertical radius: its pass-0 result is its input
     * grown along the blur vector, cut to the clip without growing it.
     */
    @Test
    void motionBlurKeepsItsClip() {
        DecoraBackend backend = DecoraBackend.java();
        Rectangle source = new Rectangle(0, 0, SIZE, SIZE);
        ImageData input = backend.data(Image.of(SIZE, SIZE, new int[SIZE * SIZE]), source);
        Rectangle clip = new Rectangle(10, 20, 30, 16);
        Rectangle beyond = new Rectangle(-OUTSIDE, -OUTSIDE, SIZE + 2 * OUTSIDE, SIZE + 2 * OUTSIDE);
        // Horizontal, vertical and 20 degrees: radius 10 along (0.940, 0.342) grows the input by 10 and 4.
        float[][] vectors = {{1f, 0f, 10, 0}, {0f, 1f, 0, 10},
            {(float) Math.cos(Math.toRadians(20)), (float) Math.sin(Math.toRadians(20)), 10, 4}};
        for (float[] v : vectors) {
            String motion = "motion radius 10 along (" + v[0] + ", " + v[1] + ")";
            GaussianRenderState state = new GaussianRenderState(10f, v[0], v[1], BaseTransform.IDENTITY_TRANSFORM);
            state.validatePassInput(input, 0);
            assertEquals(clip, state.getPassResultBounds(source, clip), () -> motion + " clip " + clip);
            Rectangle grown = new Rectangle(source);
            grown.grow((int) v[2], (int) v[3]);
            assertEquals(grown, state.getPassResultBounds(source, beyond), () -> motion + " clip " + beyond);
            state.validatePassInput(input, 1);
            assertTrue(state.isPassNop(), () -> motion + ": pass 1 is not a no-op");
        }
    }

    /** A Java backend whose {@code LinearConvolve} peers record the loop each pass runs in {@link #LOOPS}. */
    private static DecoraBackend recordingBackend() {
        return DecoraBackend.javaWith(new RendererDelegate() {
            @Override
            public AccelType getAccelType() {
                return AccelType.NONE;
            }

            @Override
            public String getPlatformPeerName(String name, int unrollCount) {
                return switch (name) {
                    case "LinearConvolve" -> RecordingLinearConvolvePeer.class.getName();
                    case "LinearConvolveShadow" -> RecordingLinearConvolveShadowPeer.class.getName();
                    default -> throw new IllegalArgumentException("unexpected peer " + name);
                };
            }
        });
    }

    /** {@code JSWLinearConvolvePeer} recording which loop each pass runs. */
    public static final class RecordingLinearConvolvePeer extends JSWLinearConvolvePeer {

        public RecordingLinearConvolvePeer(FilterContext fctx, Renderer r, String uniqueName) {
            super(fctx, r, uniqueName);
        }

        @Override
        protected void filterVector(int[] dstPixels, int dstw, int dsth, int dstscan, int[] srcPixels, int srcw,
                                    int srch, int srcscan, float[] weights, int count, float srcx0, float srcy0,
                                    float offsetx, float offsety, float deltax, float deltay, float dxcol,
                                    float dycol, float dxrow, float dyrow) {
            LOOPS.get().add("pass " + getPass() + " filterVector");
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            LOOPS.get().add("pass " + getPass() + " filterHV");
            super.filterHV(dstPixels, dstcols, dstrows, dcolinc, drowinc, srcPixels, srccols, srcrows, scolinc,
                    srowinc, weights);
        }
    }

    /** {@code JSWLinearConvolveShadowPeer} recording which loop each pass runs. */
    public static final class RecordingLinearConvolveShadowPeer extends JSWLinearConvolveShadowPeer {

        public RecordingLinearConvolveShadowPeer(FilterContext fctx, Renderer r, String uniqueName) {
            super(fctx, r, uniqueName);
        }

        @Override
        protected void filterVector(int[] dstPixels, int dstw, int dsth, int dstscan, int[] srcPixels, int srcw,
                                    int srch, int srcscan, float[] weights, int count, float srcx0, float srcy0,
                                    float offsetx, float offsety, float deltax, float deltay, float dxcol,
                                    float dycol, float dxrow, float dyrow) {
            LOOPS.get().add("pass " + getPass() + " filterVector");
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            LOOPS.get().add("pass " + getPass() + " filterHV");
            super.filterHV(dstPixels, dstcols, dstrows, dcolinc, drowinc, srcPixels, srccols, srcrows, scolinc,
                    srowinc, weights);
        }
    }
}

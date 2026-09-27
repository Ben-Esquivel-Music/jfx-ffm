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
import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.GaussianRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import com.sun.scenario.effect.impl.state.RenderState.EffectCoordinateSpace;
import com.sun.scenario.effect.impl.sw.RendererDelegate;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolvePeer;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import test.com.sun.scenario.effect.DecoraBackend.Image;
import test.com.sun.scenario.effect.DecoraBackend.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static test.com.sun.scenario.effect.DecoraCorpus.ONE_STEP;
import static test.com.sun.scenario.effect.DecoraCorpus.SHADOW_TINT;
import static test.com.sun.scenario.effect.DecoraCorpus.channelDelta;

/**
 * A Gaussian kernel rendered through an output clip has to be given all the input its two passes read for the pixels
 * inside the clip: {@code GaussianRenderState.getInputClip} has to grow the clip by the absolute distances the kernel
 * samples, also when a filter transform or a {@code MotionBlur} direction makes them negative.
 * <p>
 * {@code FilterEffect.filter} asks its input for {@code getInputClip(0, clip)}, and {@code NodeEffectInput} renders the
 * node only over that rectangle unless it has a larger image cached. Pass 0 samples up to {@code (dx0, dy0)}, its
 * sample vector times {@code inputRadiusX}, on either side of a pixel, and pass 1 up to {@code (dx1, dy1)}, its vector
 * times {@code inputRadiusY}, so a pixel of the result samples the input at most {@code |dx0| + |dx1|} columns and
 * {@code |dy0| + |dy1|} rows away, and the clip has to grow by {@code ceil(|dx0| + |dx1|)} and
 * {@code ceil(|dy0| + |dy1|)}, the integer bound of the Minkowski sum of the two sampling segments. Off axis, the
 * blur's bilinear samples also touch the pixel beyond each segment's end, at a weight too small to move a result by a
 * step. In the render-space branches of the constructors the sample vectors carry the filter transform's directions
 * and signs: the transformed unit axes {@code (mxx, myx)} and {@code (mxy, myy)}, normalised, for a two-dimensional
 * kernel, and the transformed direction for {@code MotionBlur}. At commit eb6cc1182f {@code getInputClip} grew the
 * clip by the signed sums {@code ceil(dx0 + dx1)} and {@code ceil(dy0 + dy1)}: a negative sum made
 * {@code Rectangle.grow} shrink the clip, and opposite signs cancelled. The fix takes the absolute values, which
 * {@code getPassResultBounds} and {@code MotionBlurState.getHPad}/{@code getVPad} already did.
 * <p>
 * The render-state cases grow a 100x100 clip. Their expected pads are derived by hand from the sample vectors and
 * radii, noted with each case, never from the formula under test. The transforms are exact matrices
 * ({@code Affine2D(mxx, myx, mxy, myy, mxt, myt)}): the axis-aligned distances are exact, and the off-axis sums lie at
 * least 0.02 away from an integer, so no rounding residue decides a ceiling. Rotating by 45 degrees at radius 10 pins
 * the form: the sample distances are {@code (7.07, 7.07)} and {@code (-7.07, 7.07)}, whose absolute sum 14.14 grows the
 * clip by 15 on both axes, where the signed sum gives 0 columns, {@code |dx0 + dx1|} 0 and
 * {@code ceil(|dx0|) + ceil(|dx1|)} 16. Anisotropic radii under the quarter turns, where each pass runs along one axis,
 * tell the two radii apart. The controls pass before the fix too: identity and a positive scale, a kernel whose device
 * radius exceeds {@code GaussianRenderState.MAX_RADIUS} (the scaled {@code CustomSpace} branch, whose sample vectors
 * are the unit axes whatever the transform), and {@code MotionBlur} at a positive angle.
 * <p>
 * {@code MAX_RADIUS} is {@code (MAX_KERNEL_SIZE - 1) / 2}, and {@code LinearConvolveRenderState.MAX_KERNEL_SIZE} is
 * {@code decora.maxLinearConvolveKernelSize}, at most 128, by default 128, or 64 on an embedded platform: 63 or 31. A
 * device radius above {@code MAX_RADIUS} is clamped to it. The scaled control derives its pads from
 * {@code MAX_RADIUS}, and its device radius of 80 exceeds any value {@code MAX_RADIUS} can take. Every other case has
 * device radii of at most 30, which keep it in the render-space branch wherever {@code MAX_RADIUS} is at least 30, and
 * is skipped where a smaller kernel size makes {@code MAX_RADIUS} less than 30.
 * <p>
 * The pixel cases render a 72x72 source, an opaque checkerboard, through the production pass protocol
 * ({@link DecoraBackend#convolve}, as {@code LinearConvolveCoreEffect.filterImageDatas} runs it) under a clip that cuts
 * it at 20 and 52 on the sides each case names. The clipped render filters only the source cut to
 * {@code getInputClip(0, clip)}, as {@code FilterEffect.filter} and {@code NodeEffectInput} cut it (compare
 * {@code GaussianPassClipGrowthTest.Case.sourceBounds}), and is compared, inside the clip, with the whole source
 * rendered without a clip. Each render uses a fresh {@link DecoraBackend}, and the sources are images of exactly their
 * size, so no pool image has slack beyond its content: a band the clip loses on the right or at the bottom shows as
 * plainly as one on the left or at the top. The kernels are a blur of radius 10 ({@code GaussianBlur}), a tinted shadow
 * of radii 10 and 6 ({@code DropShadow} with {@code BlurType.GAUSSIAN}), which run the {@code LinearConvolve} and
 * {@code LinearConvolveShadow} peers, and {@code MotionBlur} of radius 10.
 * <p>
 * The two renders may differ by one step per channel, and the clips are chosen so that each pass runs the same loop in
 * both. A pass runs {@code filterHV} only for a pass-0 vector of {@code (1, 0)} or a pass-1 vector of {@code (0, 1)},
 * and only while its clipped result keeps the origin of its unclipped result; otherwise it runs {@code filterVector}
 * (JDK-8092042). With the source cut to the input clip, a left cut therefore sends a horizontal pass 0 to
 * {@code filterVector}, and a top cut a vertical pass 1. The rotations and the {@code MotionBlur} directions other than
 * {@code (1, 0)} run {@code filterVector} in every pass of both renders and are cut on all four sides. The mirrors keep
 * one axis-aligned pass and are cut on the three sides that do not move its origin, which include both sides of the
 * mirrored axis; the identity control is cut on the right and at the bottom, the {@code MotionBlur} control everywhere
 * but on the left. {@link #bothRendersRunTheSameLoops} records the loops. Two {@code filterHV} passes over the same
 * pixels are bit-identical. The {@code filterVector} sample positions and steps are computed from each render's own
 * image, whose size and origin differ between the renders, so they can differ by float rounding. The shadow takes the
 * pixel under its sample position, which that rounding cannot change here: the kernels sample at pixel centres or,
 * rotated by 45 degrees, at least 0.03 pixels away from a pixel edge. The blur samples bilinearly and mixes that
 * rounding's fraction of a neighbour into a sample, which moves a sum far less than a step but can carry its truncation
 * across an integer: one step. A second pass averages such inputs with weights that sum to one, which keeps the
 * difference of its sums below one step before it truncates. Measured, the renders are bit-identical in every case but
 * the blur rotated by 45 degrees, where 8 of the 1024 pixels in the clip differ by one step. The pixel cases show that
 * the input clip is enough; they do not pin it. The Gaussian's weight in the corners of the Minkowski sum is so small
 * that at 45 degrees the input can lose four more columns, and on the axes the shadow one more, without a result moving
 * by more than a step. The render-state cases pin the pad.
 * <p>
 * At commit eb6cc1182f every render-state case other than the controls failed. So did every clipped-versus-unclipped
 * comparison other than the controls (the loop checks pass either way): under the quarter turns, the mirrors and
 * {@code MotionBlur} along {@code (-1, 0)} and {@code (0, -1)} the input clip kept only 12 or 20 of the clip's 32
 * columns or rows, and the renders differed by 203 steps (the shadow) to 255 steps across most of the clip; rotated by
 * 45 degrees the blur differed by 113 steps in bands 9 pixels deep and the shadow by 21 steps in bands 4 pixels deep,
 * on the left and on the right; {@code MotionBlur} at -15 degrees differed by 243 steps in bands 4 pixels deep at the
 * top and at the bottom.
 */
public class GaussianInputClipTest {

    /** The clip the render-state cases grow. */
    private static final Rectangle CLIP = new Rectangle(20, 30, 100, 100);

    /** {@code cos(45 degrees) = sin(45 degrees)}. */
    private static final double HALF_SQRT2 = Math.sqrt(0.5);

    private static final BaseTransform IDENTITY = BaseTransform.IDENTITY_TRANSFORM;
    private static final BaseTransform ROTATE_90 = new Affine2D(0, 1, -1, 0, 0, 0);
    private static final BaseTransform ROTATE_180 = new Affine2D(-1, 0, 0, -1, 0, 0);
    private static final BaseTransform ROTATE_270 = new Affine2D(0, -1, 1, 0, 0, 0);
    private static final BaseTransform ROTATE_45 = new Affine2D(HALF_SQRT2, HALF_SQRT2, -HALF_SQRT2, HALF_SQRT2, 0, 0);
    /** The rotation by atan(4/3), about 53.13 degrees, whose sine and cosine are exact in decimal. */
    private static final BaseTransform ROTATE_3_4_5 = new Affine2D(0.6, 0.8, -0.8, 0.6, 0, 0);
    private static final BaseTransform MIRROR_X = new Affine2D(-1, 0, 0, 1, 0, 0);
    private static final BaseTransform MIRROR_Y = new Affine2D(1, 0, 0, -1, 0, 0);
    /** {@code x' = x - 0.35 y}, {@code y' = y - 0.25 x}. */
    private static final BaseTransform SHEAR = new Affine2D(1, -0.25, -0.35, 1, 0, 0);
    private static final BaseTransform SCALE = new Affine2D(2, 0, 0, 0.5, 0, 0);
    private static final BaseTransform ROTATE_90_SCALE_2 = new Affine2D(0, 2, -2, 0, 0, 0);

    /** The size of the pixel cases' source. */
    private static final int SIZE = 72;
    /** Where a pixel case's clip cuts the source on the sides it cuts. */
    private static final int CLIP_MIN = 20;
    private static final int CLIP_MAX = 52;
    /** How far a clip reaches beyond a source edge it leaves alone. */
    private static final int OUTSIDE = 100;
    /** The checkerboard cell size, which the clip edges do not fall on. */
    private static final int CELL = 6;
    /** Two premultiplied opaque colours. */
    private static final int COLOR_A = 0xFF2060A0;
    private static final int COLOR_B = 0xFFE0B040;

    private static final String HV = "filterHV";
    private static final String VECTOR = "filterVector";

    /** The loops each pass of a render on a {@link #recordingBackend()} ran, in order; removed after each render. */
    private static final ThreadLocal<List<String>> LOOPS = ThreadLocal.withInitial(ArrayList::new);

    /**
     * The largest device radius of any case but the scaled control, that of {@code MotionBlur} of radius 30; a case
     * with a larger one has to raise it.
     */
    private static final float LARGEST_DEVICE_RADIUS = 30f;

    /**
     * Skips a case where the configured kernel size makes {@code GaussianRenderState.MAX_RADIUS} smaller than
     * {@link #LARGEST_DEVICE_RADIUS}: the pads and loops of the cases are derived for the render-space branch, which a
     * device radius above {@code MAX_RADIUS} leaves for the scaled one.
     */
    private static void assumeRenderSpaceRadii() {
        assumeTrue(LARGEST_DEVICE_RADIUS <= GaussianRenderState.MAX_RADIUS, () -> String.format(Locale.ROOT,
                "MAX_KERNEL_SIZE %d (decora.maxLinearConvolveKernelSize) makes MAX_RADIUS %.0f, below the largest"
                + " device radius of the cases, %.0f", LinearConvolveRenderState.MAX_KERNEL_SIZE,
                GaussianRenderState.MAX_RADIUS, LARGEST_DEVICE_RADIUS));
    }

    private static GaussianRenderState blur(float xradius, float yradius, BaseTransform filterTransform) {
        return new GaussianRenderState(xradius, yradius, 0f, false, null, filterTransform);
    }

    private static GaussianRenderState motion(float radius, float dx, float dy, BaseTransform filterTransform) {
        return new GaussianRenderState(radius, dx, dy, filterTransform);
    }

    /** {@code MotionBlur} at {@code degrees}: the state {@code MotionBlurState.getRenderState} builds for it. */
    private static GaussianRenderState motionAt(float radius, double degrees) {
        float angle = (float) Math.toRadians(degrees);
        return motion(radius, (float) Math.cos(angle), (float) Math.sin(angle), IDENTITY);
    }

    private static Arguments clipCase(String name, GaussianRenderState state, int padx, int pady) {
        return Arguments.of(name, state, padx, pady);
    }

    /**
     * The pads each case expects, derived from the sample vectors (the transformed axes or direction, normalised) and
     * the device radii (the effect's radii times the transform's scale along the vectors).
     */
    static Stream<Arguments> inputClipCases() {
        return Stream.of(
                // Controls. Vectors (1, 0), (0, 1): 10 and 10.
                clipCase("identity, radius 10", blur(10f, 10f, IDENTITY), 10, 10),
                // Vectors (1, 0), (0, 1), device radii 4 * 2 = 8 and 6 * 0.5 = 3.
                clipCase("scale (2, 0.5), radii 4 and 6", blur(4f, 6f, SCALE), 8, 3),
                // (cos 20, sin 20) * 10 = (9.40, 3.42).
                clipCase("motion radius 10 at 20 degrees", motionAt(10f, 20), 10, 4),

                // Vectors (0, 1), (-1, 0): distances (0, 10) and (-4, 0). Signed: -4 columns.
                clipCase("rotate 90, radii 10 and 4", blur(10f, 4f, ROTATE_90), 4, 10),
                // Vectors (-1, 0), (0, -1): distances (-10, 0) and (0, -4). Signed: -10 and -4.
                clipCase("rotate 180, radii 10 and 4", blur(10f, 4f, ROTATE_180), 10, 4),
                // Vectors (0, -1), (1, 0): distances (0, -10) and (4, 0). Signed: -10 rows.
                clipCase("rotate 270, radii 10 and 4", blur(10f, 4f, ROTATE_270), 4, 10),
                // Vectors (0.7071, 0.7071), (-0.7071, 0.7071): distances (7.071, 7.071) and (-7.071, 7.071), sums
                // 14.142. Signed: 0 columns; per pass: 8 + 8 = 16.
                clipCase("rotate 45, radius 10", blur(10f, 10f, ROTATE_45), 15, 15),
                // Distances (7.071, 7.071) and (-2.828, 2.828), sums 9.899. Signed: 5 columns; per pass: 8 + 3 = 11.
                clipCase("rotate 45, radii 10 and 4", blur(10f, 4f, ROTATE_45), 10, 10),
                // Vectors (0.6, 0.8), (-0.8, 0.6): distances (6, 8) and (-3.2, 2.4), sums 9.2 and 10.4. Signed: 3
                // columns.
                clipCase("rotate atan(4/3), radii 10 and 4", blur(10f, 4f, ROTATE_3_4_5), 10, 11),
                // Vectors (-1, 0), (0, 1). Signed: -10 columns.
                clipCase("scale (-1, 1), radius 10", blur(10f, 10f, MIRROR_X), 10, 10),
                // Vectors (1, 0), (0, -1). Signed: -10 rows.
                clipCase("scale (1, -1), radius 10", blur(10f, 10f, MIRROR_Y), 10, 10),
                // Axes (1, -0.25) and (-0.35, 1), lengths 1.0308 and 1.0595, device radii 4.742 and 6.675: distances
                // (4.6, -1.15) and (-2.205, 6.3), sums 6.805 and 7.45. Signed: 2.395 and 5.15, 3 and 6.
                clipCase("shear (-0.35, -0.25), radii 4.6 and 6.3", blur(4.6f, 6.3f, SHEAR), 7, 8),
                // Vector (-1, 0): 10 columns. Signed: -10.
                clipCase("motion radius 10 along (-1, 0)", motion(10f, -1f, 0f, IDENTITY), 10, 0),
                // Vector (0, -1): 10 rows. Signed: -10.
                clipCase("motion radius 10 along (0, -1)", motion(10f, 0f, -1f, IDENTITY), 0, 10),
                // The MotionBlur javadoc example: (cos -15, sin -15) * 30 = (28.98, -7.76). Signed: -7 rows.
                clipCase("motion radius 30 at -15 degrees", motionAt(30f, -15), 29, 8),
                // Direction (1, 0) turned to (-1, 0). Signed: -10 columns.
                clipCase("motion radius 10 along (1, 0), rotate 180", motion(10f, 1f, 0f, ROTATE_180), 10, 0),
                // Direction (0, 1) mirrored to (0, -1). Signed: -10 rows.
                clipCase("motion radius 10 along (0, 1), scale (1, -1)", motion(10f, 0f, 1f, MIRROR_Y), 0, 10));
    }

    /** The input clip is the clip grown by the absolute sample distances of both passes, rounded up per axis. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inputClipCases")
    void inputClipGrowsByTheAbsoluteSampleDistances(String name, GaussianRenderState state, int padx, int pady) {
        assumeRenderSpaceRadii();
        assertInputClip(name, state, padx, pady);
    }

    /**
     * A control: radii 40 and 10 under a rotation by 90 degrees scaled by 2, device radii 80 and 20. 80 exceeds
     * {@code MAX_RADIUS}, so the state asks for its input scaled down to that radius along x and samples along
     * {@code (1, 0)} and {@code (0, 1)}, leaving the rotation to the result transform: each pad is its device radius
     * clamped to {@code MAX_RADIUS}. Wherever {@code MAX_RADIUS} exceeds 20, as it does by default, that is
     * {@code MAX_RADIUS} and 20, which the rotated sample vectors would swap.
     */
    @Test
    void scaledBranchGrowsByTheClampedRadii() {
        GaussianRenderState state = blur(40f, 10f, ROTATE_90_SCALE_2);
        assertEquals(EffectCoordinateSpace.CustomSpace, state.getEffectTransformSpace(),
                () -> "device radius 80 against MAX_RADIUS " + GaussianRenderState.MAX_RADIUS);
        assertInputClip("rotate 90 scaled by 2, radii 40 and 10", state, clampedPad(80f), clampedPad(20f));
    }

    /** The pad of a pass sampling along an axis: its device radius clamped to {@code MAX_RADIUS}, rounded up. */
    private static int clampedPad(float deviceRadius) {
        return (int) Math.ceil(Math.min(deviceRadius, GaussianRenderState.MAX_RADIUS));
    }

    private static void assertInputClip(String name, GaussianRenderState state, int padx, int pady) {
        Rectangle clip = new Rectangle(CLIP);
        Rectangle actual = state.getInputClip(0, clip);
        assertEquals(CLIP, clip, () -> name + ": getInputClip changed its argument");
        Rectangle expected = new Rectangle(CLIP.x - padx, CLIP.y - pady, CLIP.width + 2 * padx,
                CLIP.height + 2 * pady);
        System.out.printf(Locale.ROOT, "%s: input clip %s, grown by (%d, %d), expected (%d, %d)%n", name,
                format(actual), CLIP.x - actual.x, CLIP.y - actual.y, padx, pady);
        assertEquals(expected, actual, () -> String.format(Locale.ROOT, "%s: the input clip %s of the clip %s is not"
                + " the clip grown by (%d, %d)", name, format(actual), format(CLIP), padx, pady));
    }

    /** Without a clip there is nothing to grow. */
    @Test
    void noClipStaysUnclipped() {
        assertNull(blur(10f, 4f, ROTATE_180).getInputClip(0, null), "rotate 180");
        assertNull(motion(10f, -1f, 0f, IDENTITY).getInputClip(0, null), "motion along (-1, 0)");
    }

    enum Side {
        LEFT, TOP, RIGHT, BOTTOM;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A kernel under a filter transform, and the peer it runs. */
    record Kernel(String name, String peer, Function<BaseTransform, GaussianRenderState> state) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Kernel BLUR = new Kernel("blur radius 10", JSWLinearConvolvePeer.class.getName(),
            tx -> blur(10f, 10f, tx));
    private static final Kernel SHADOW = new Kernel("shadow radii 10 and 6",
            JSWLinearConvolveShadowPeer.class.getName(),
            tx -> new GaussianRenderState(10f, 6f, 0f, true, SHADOW_TINT, tx));

    private static Kernel motionKernel(String direction, float dx, float dy) {
        return new Kernel("motion radius 10 " + direction, JSWLinearConvolvePeer.class.getName(),
                tx -> motion(10f, dx, dy, tx));
    }

    /**
     * One kernel under one filter transform, rendered through a clip that cuts the source on the given sides, and the
     * loop each pass runs in both renders.
     */
    record PixelCase(Kernel kernel, String transformName, BaseTransform transform, Set<Side> cut,
                     List<String> loops) {

        Rectangle clip() {
            int left = cut.contains(Side.LEFT) ? CLIP_MIN : -OUTSIDE;
            int top = cut.contains(Side.TOP) ? CLIP_MIN : -OUTSIDE;
            int right = cut.contains(Side.RIGHT) ? CLIP_MAX : SIZE + OUTSIDE;
            int bottom = cut.contains(Side.BOTTOM) ? CLIP_MAX : SIZE + OUTSIDE;
            return new Rectangle(left, top, right - left, bottom - top);
        }

        /**
         * The source bounds the render filters: the whole source, or for a clipped render the source intersected
         * with {@code getInputClip(0, clip)}, as {@code FilterEffect.filter} and {@code NodeEffectInput} cut it.
         */
        Rectangle sourceBounds(Rectangle clip) {
            Rectangle bounds = new Rectangle(0, 0, SIZE, SIZE);
            if (clip != null) {
                bounds.intersectWith(kernel.state().apply(transform).getInputClip(0, clip));
            }
            return bounds;
        }

        Result render(DecoraBackend backend, Rectangle clip) {
            Rectangle bounds = sourceBounds(clip);
            int[] pixels = new int[bounds.width * bounds.height];
            for (int y = 0; y < bounds.height; y++) {
                for (int x = 0; x < bounds.width; x++) {
                    pixels[y * bounds.width + x] = source(bounds.x + x, bounds.y + y);
                }
            }
            ImageData input = backend.data(Image.of(bounds.width, bounds.height, pixels), bounds);
            return backend.convolve(input, kernel.state().apply(transform), clip);
        }

        @Override
        public String toString() {
            return kernel + ", " + transformName + ", clip cuts " + String.join(" ",
                    cut.stream().map(Side::toString).toList());
        }
    }

    /** The source pixel at {@code (x, y)}: the opaque checkerboard. */
    private static int source(int x, int y) {
        return ((x / CELL + y / CELL) & 1) == 0 ? COLOR_A : COLOR_B;
    }

    static Stream<Arguments> pixelCases() {
        Set<Side> all = EnumSet.allOf(Side.class);
        Set<Side> notLeft = EnumSet.of(Side.TOP, Side.RIGHT, Side.BOTTOM);
        Set<Side> notTop = EnumSet.of(Side.LEFT, Side.RIGHT, Side.BOTTOM);
        List<PixelCase> cases = new ArrayList<>();
        for (Kernel kernel : List.of(BLUR, SHADOW)) {
            cases.add(new PixelCase(kernel, "identity", IDENTITY, EnumSet.of(Side.RIGHT, Side.BOTTOM),
                    List.of(HV, HV)));
            cases.add(new PixelCase(kernel, "rotate 90", ROTATE_90, all, List.of(VECTOR, VECTOR)));
            cases.add(new PixelCase(kernel, "rotate 180", ROTATE_180, all, List.of(VECTOR, VECTOR)));
            cases.add(new PixelCase(kernel, "rotate 270", ROTATE_270, all, List.of(VECTOR, VECTOR)));
            cases.add(new PixelCase(kernel, "rotate 45", ROTATE_45, all, List.of(VECTOR, VECTOR)));
            cases.add(new PixelCase(kernel, "scale (-1, 1)", MIRROR_X, notTop, List.of(VECTOR, HV)));
            cases.add(new PixelCase(kernel, "scale (1, -1)", MIRROR_Y, notLeft, List.of(HV, VECTOR)));
        }
        float angle = (float) Math.toRadians(-15);
        cases.add(new PixelCase(motionKernel("along (1, 0)", 1f, 0f), "identity", IDENTITY, notLeft, List.of(HV)));
        cases.add(new PixelCase(motionKernel("along (-1, 0)", -1f, 0f), "identity", IDENTITY, all, List.of(VECTOR)));
        cases.add(new PixelCase(motionKernel("along (0, -1)", 0f, -1f), "identity", IDENTITY, all, List.of(VECTOR)));
        cases.add(new PixelCase(motionKernel("at -15 degrees", (float) Math.cos(angle), (float) Math.sin(angle)),
                "identity", IDENTITY, all, List.of(VECTOR)));
        return cases.stream().map(c -> Arguments.of(c.toString(), c));
    }

    /**
     * Inside the clip, the kernel rendered over the source cut to the input clip matches the kernel rendered over the
     * whole source without a clip, within one step per channel.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pixelCases")
    void clippedRenderMatchesUnclipped(String name, PixelCase c) {
        assumeRenderSpaceRadii();
        Rectangle clip = c.clip();
        Rectangle cut = c.sourceBounds(clip);
        for (Side side : c.cut()) {
            boolean inside = switch (side) {
                case LEFT -> cut.x > 0;
                case TOP -> cut.y > 0;
                case RIGHT -> cut.x + cut.width < SIZE;
                case BOTTOM -> cut.y + cut.height < SIZE;
            };
            assertTrue(inside, () -> c + ": the input clip does not cut the source on the " + side + ": "
                    + format(cut));
        }

        DecoraBackend backend = DecoraBackend.java();
        Result clipped = c.render(backend, clip);
        assertEquals(Set.of(c.kernel().peer()), backend.ranPeers(), () -> c + ": peers that ran");
        Result full = c.render(DecoraBackend.java(), null);
        Rectangle fullBounds = new Rectangle(full.x(), full.y(), full.width(), full.height());
        Rectangle expected = new Rectangle(fullBounds);
        expected.intersectWith(clip);
        assertNotEquals(fullBounds, expected, () -> c + ": the clip does not cut the result");
        Rectangle actual = new Rectangle(clipped.x(), clipped.y(), clipped.width(), clipped.height());

        // Compare the whole expected area; a pixel the clipped result does not cover counts as transparent. A band's
        // depth is measured from its edge over the middle third of that edge, away from the corners.
        int w = expected.width;
        int h = expected.height;
        int differ = 0;
        int over = 0;
        int maxDelta = 0;
        int worstX = 0;
        int worstY = 0;
        int[] depth = new int[Side.values().length];
        for (int v = 0; v < h; v++) {
            for (int u = 0; u < w; u++) {
                int x = expected.x + u;
                int y = expected.y + v;
                int want = full.pixels()[(y - full.y()) * full.width() + (x - full.x())];
                int got = actual.contains(x, y)
                        ? clipped.pixels()[(y - clipped.y()) * clipped.width() + (x - clipped.x())] : 0;
                int delta = channelDelta(got, want);
                differ += delta > 0 ? 1 : 0;
                if (delta > maxDelta) {
                    maxDelta = delta;
                    worstX = x;
                    worstY = y;
                }
                if (delta > ONE_STEP) {
                    over++;
                    if (v >= h / 3 && v < h - h / 3) {
                        Side side = u < w / 2 ? Side.LEFT : Side.RIGHT;
                        depth[side.ordinal()] = Math.max(depth[side.ordinal()], u < w / 2 ? u + 1 : w - u);
                    }
                    if (u >= w / 3 && u < w - w / 3) {
                        Side side = v < h / 2 ? Side.TOP : Side.BOTTOM;
                        depth[side.ordinal()] = Math.max(depth[side.ordinal()], v < h / 2 ? v + 1 : h - v);
                    }
                }
            }
        }
        List<String> bands = new ArrayList<>();
        for (Side side : Side.values()) {
            if (depth[side.ordinal()] > 0) {
                bands.add(side + " " + depth[side.ordinal()] + " px");
            }
        }
        String worst = maxDelta == 0 ? "" : String.format(Locale.ROOT, " at (%d,%d)", worstX, worstY);
        String summary = String.format(Locale.ROOT, "%s: source cut to %s; max delta %d%s, %d of %d pixels differ,"
                + " %d above %d step, bands: %s; result %s, expected %s", c, format(cut), maxDelta, worst, differ,
                w * h, over, ONE_STEP, bands.isEmpty() ? "none" : String.join(", ", bands), format(actual),
                format(expected));
        System.out.println(summary);
        if (over > 0 || !expected.equals(actual)) {
            fail("the clipped render differs from the unclipped one: " + summary);
        }
    }

    /**
     * Each pass runs the same loop in the clipped and in the unclipped render, the one the case names, which is what
     * keeps the renders within one step.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pixelCases")
    void bothRendersRunTheSameLoops(String name, PixelCase c) {
        assumeRenderSpaceRadii();
        List<String> expected = new ArrayList<>();
        for (int pass = 0; pass < c.loops().size(); pass++) {
            expected.add("pass " + pass + " " + c.loops().get(pass));
        }
        List<String> clipped = loops(c, c.clip());
        List<String> unclipped = loops(c, null);
        System.out.printf(Locale.ROOT, "%s: loops clipped %s, unclipped %s%n", c, clipped, unclipped);
        assertEquals(expected, unclipped, () -> c + ": loops of the unclipped render");
        assertEquals(expected, clipped, () -> c + ": loops of the clipped render");
    }

    private static List<String> loops(PixelCase c, Rectangle clip) {
        try {
            c.render(recordingBackend(), clip);
            return List.copyOf(LOOPS.get());
        } finally {
            LOOPS.remove();
        }
    }

    private static String format(Rectangle r) {
        return String.format(Locale.ROOT, "(%d,%d %dx%d)", r.x, r.y, r.width, r.height);
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
            LOOPS.get().add("pass " + getPass() + " " + VECTOR);
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            LOOPS.get().add("pass " + getPass() + " " + HV);
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
            LOOPS.get().add("pass " + getPass() + " " + VECTOR);
            super.filterVector(dstPixels, dstw, dsth, dstscan, srcPixels, srcw, srch, srcscan, weights, count, srcx0,
                    srcy0, offsetx, offsety, deltax, deltay, dxcol, dycol, dxrow, dyrow);
        }

        @Override
        protected void filterHV(int[] dstPixels, int dstcols, int dstrows, int dcolinc, int drowinc, int[] srcPixels,
                                int srccols, int srcrows, int scolinc, int srowinc, float[] weights) {
            LOOPS.get().add("pass " + getPass() + " " + HV);
            super.filterHV(dstPixels, dstcols, dstrows, dcolinc, drowinc, srcPixels, srccols, srcrows, scolinc,
                    srowinc, weights);
        }
    }
}

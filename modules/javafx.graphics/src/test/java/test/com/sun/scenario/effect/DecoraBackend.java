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
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.FilterContext;
import com.sun.scenario.effect.Filterable;
import com.sun.scenario.effect.ImageData;
import com.sun.scenario.effect.impl.EffectPeer;
import com.sun.scenario.effect.impl.HeapImage;
import com.sun.scenario.effect.impl.ImagePool;
import com.sun.scenario.effect.impl.PoolFilterable;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.state.BoxRenderState;
import com.sun.scenario.effect.impl.state.GaussianRenderState;
import com.sun.scenario.effect.impl.state.LinearConvolveRenderState;
import com.sun.scenario.effect.impl.state.RenderState;
import com.sun.scenario.effect.impl.sw.RendererDelegate;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolvePeer;
import com.sun.scenario.effect.impl.sw.java.JSWLinearConvolveShadowPeer;
import com.sun.scenario.effect.impl.sw.java.JSWRendererDelegate;
import com.sun.scenario.effect.impl.sw.sse.SSELinearConvolvePeer;
import com.sun.scenario.effect.impl.sw.sse.SSELinearConvolveShadowPeer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Drives the Decora software peers of one backend - the native {@code decora_sse} peers
 * ({@code com.sun.scenario.effect.impl.sw.sse}) or the pure-Java peers
 * ({@code com.sun.scenario.effect.impl.sw.java}) - directly, without Prism.
 * <p>
 * Production reaches these peers through {@code PSWRenderer}, which needs a Prism {@code Screen} or
 * {@code ResourceFactory}. This class replaces {@code PSWRenderer} with a minimal {@link Renderer}
 * that hands out plain heap images and creates peers exactly the way {@code PSWRenderer} does: the
 * {@link RendererDelegate} names the class ({@code SSE<Name>Peer} or {@code JSW<Name>Peer}) and the
 * {@code (FilterContext, Renderer, String)} constructor is invoked reflectively. Because the delegate
 * is the only thing that differs between the two backends, one JVM can hold both and feed them the
 * same inputs.
 * <p>
 * Multi-pass effects (box and Gaussian blurs and shadows) are run through the same per-pass protocol
 * {@code LinearConvolveCoreEffect.filterImageDatas} uses in production:
 * {@code validatePassInput -> getPassPeer -> setPass -> filter} for pass 0 and pass 1. Single-pass
 * generated peers are invoked with {@link RenderState#RenderSpaceRenderState}, which is what their
 * effects return from {@code getRenderState}.
 * <p>
 * Every image handed out has a scanline stride wider than its width ({@link #STRIDE_PAD}) so that a
 * stride bug in either backend shows up as a pixel difference rather than being masked.
 */
public final class DecoraBackend {

    /** Extra ints per scanline beyond the image width, deliberately odd. */
    public static final int STRIDE_PAD = 5;

    private final String name;
    private final RendererDelegate delegate;
    private final DirectRenderer renderer;
    private final FilterContext fctx = new FilterContext(new Object()) {
    };

    private DecoraBackend(String name, RendererDelegate delegate) {
        this.name = name;
        this.delegate = delegate;
        this.renderer = new DirectRenderer(delegate);
    }

    /** The pure-Java backend: {@code JSWRendererDelegate} and the {@code JSW*Peer} classes. */
    public static DecoraBackend java() {
        return new DecoraBackend("Java", new JSWRendererDelegate());
    }

    /**
     * The native backend: {@code SSERendererDelegate} (which loads {@code decora_sse}) and the
     * {@code SSE*Peer} classes.
     *
     * @throws AssertionError if the library is reachable and unusable, see {@link DecoraNatives}
     */
    public static DecoraBackend sse() {
        return new DecoraBackend("SSE", DecoraNatives.requireSse());
    }

    public String name() {
        return name;
    }

    public AccelType accelType() {
        return delegate.getAccelType();
    }

    /** The class name the delegate maps the given peer name to, for reporting. */
    public String peerClassName(String peerName) {
        return delegate.getPlatformPeerName(peerName, -1);
    }

    /**
     * The cached peer instance for a peer name and unroll count, created on first use the way
     * {@code Renderer.getPeerInstance} does for production; lets a test call a peer's loops directly.
     */
    public EffectPeer<?> peer(String peerName, int unrollCount) {
        return renderer.getPeerInstance(fctx, peerName, unrollCount);
    }

    /** The peer size {@code LinearConvolveRenderState.getPassPeer} passes as unroll count for a kernel. */
    public static int peerSize(int kernelSize) {
        return LinearConvolveRenderState.getPeerSize(kernelSize);
    }

    /**
     * Runs this backend's {@code filterHV} blur loop directly, with the {@code count * 2} duplicated
     * weights the peers build for a centered pass. SSE: the package-private native loop
     * {@code SSELinearConvolvePeer} runs for {@code HORIZONTAL_CENTERED}/{@code VERTICAL_CENTERED} passes
     * (reached reflectively, hence {@code --add-opens ...impl.sw.sse} in {@code src/test/addExports}).
     * Java: the protected {@code JSWLinearConvolvePeer.filterHV}, which production never reaches because
     * {@code filter} forces {@code GENERAL_VECTOR}.
     */
    public void filterHV(int[] dst, int dstcols, int dstrows, int dcolinc, int drowinc, int[] src, int srccols,
                         int srcrows, int scolinc, int srowinc, float[] weights) {
        if (delegate instanceof JSWRendererDelegate) {
            javaHv().hv(dst, dstcols, dstrows, dcolinc, drowinc, src, srccols, srcrows, scolinc, srowinc, weights);
            return;
        }
        try {
            sseHv().invoke(peer("LinearConvolve", peerSize(weights.length / 2)), dst, dstcols, dstrows, dcolinc,
                    drowinc, src, srccols, srcrows, scolinc, srowinc, weights);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SSELinearConvolvePeer.filterHV not callable", e);
        }
    }

    /**
     * Runs this backend's {@code filterHV} shadow loop directly. SSE: the private static native of
     * {@code SSELinearConvolveShadowPeer} that takes the premultiplied shadow colour explicitly. Java: the
     * protected {@code JSWLinearConvolveShadowPeer.filterHV}, which reads the colour from its render state,
     * so the probe carries a Gaussian state validated for pass 1 (pass 0 of a shadow is always black).
     */
    public void filterHVShadow(int[] dst, int dstcols, int dstrows, int dcolinc, int drowinc, int[] src,
                               int srccols, int srcrows, int scolinc, int srowinc, float[] weights,
                               Color4f shadowColor) {
        if (delegate instanceof JSWRendererDelegate) {
            javaShadowHv(shadowColor).hv(dst, dstcols, dstrows, dcolinc, drowinc, src, srccols, srcrows, scolinc,
                    srowinc, weights);
            return;
        }
        try {
            sseShadowHv().invoke(null, dst, dstcols, dstrows, dcolinc, drowinc, src, srccols, srcrows, scolinc,
                    srowinc, weights, shadowColor.getPremultipliedRGBComponents());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SSELinearConvolveShadowPeer.filterHV not callable", e);
        }
    }

    private HvProbe javaHvProbe;
    private HvShadowProbe javaShadowHvProbe;
    private Color4f javaShadowHvColor;
    private Method sseHvMethod;
    private Method sseShadowHvMethod;

    private HvProbe javaHv() {
        if (javaHvProbe == null) {
            javaHvProbe = new HvProbe(fctx, renderer);
        }
        return javaHvProbe;
    }

    private HvShadowProbe javaShadowHv(Color4f shadowColor) {
        if (javaShadowHvProbe == null || !shadowColor.equals(javaShadowHvColor)) {
            GaussianRenderState state = new GaussianRenderState(1f, 1f, 0f, true, shadowColor,
                    BaseTransform.IDENTITY_TRANSFORM);
            state.validatePassInput(data(new Image(1, 1), 0, 0), 1);
            javaShadowHvProbe = new HvShadowProbe(fctx, renderer, state);
            javaShadowHvColor = shadowColor;
        }
        return javaShadowHvProbe;
    }

    private Method sseHv() throws NoSuchMethodException {
        if (sseHvMethod == null) {
            Method m = SSELinearConvolvePeer.class.getDeclaredMethod("filterHV", int[].class, int.class, int.class,
                    int.class, int.class, int[].class, int.class, int.class, int.class, int.class, float[].class);
            m.setAccessible(true);
            sseHvMethod = m;
        }
        return sseHvMethod;
    }

    private Method sseShadowHv() throws NoSuchMethodException {
        if (sseShadowHvMethod == null) {
            Method m = SSELinearConvolveShadowPeer.class.getDeclaredMethod("filterHV", int[].class, int.class,
                    int.class, int.class, int.class, int[].class, int.class, int.class, int.class, int.class,
                    float[].class, float[].class);
            m.setAccessible(true);
            sseShadowHvMethod = m;
        }
        return sseShadowHvMethod;
    }

    /** Exposes the protected Java {@code filterHV} blur loop. */
    private static final class HvProbe extends JSWLinearConvolvePeer {

        HvProbe(FilterContext fctx, Renderer renderer) {
            super(fctx, renderer, "LinearConvolve");
        }

        void hv(int[] dst, int dstcols, int dstrows, int dcolinc, int drowinc, int[] src, int srccols, int srcrows,
                int scolinc, int srowinc, float[] weights) {
            filterHV(dst, dstcols, dstrows, dcolinc, drowinc, src, srccols, srcrows, scolinc, srowinc, weights);
        }
    }

    /** Exposes the protected Java {@code filterHV} shadow loop, which reads the shadow colour from the state. */
    private static final class HvShadowProbe extends JSWLinearConvolveShadowPeer {

        HvShadowProbe(FilterContext fctx, Renderer renderer, LinearConvolveRenderState state) {
            super(fctx, renderer, "LinearConvolveShadow");
            setRenderState(state);
        }

        void hv(int[] dst, int dstcols, int dstrows, int dcolinc, int drowinc, int[] src, int srccols, int srcrows,
                int scolinc, int srowinc, float[] weights) {
            filterHV(dst, dstcols, dstrows, dcolinc, drowinc, src, srccols, srcrows, scolinc, srowinc, weights);
        }
    }

    /** Wraps an image as an {@code ImageData} whose filter-space bounds start at {@code (x, y)}. */
    public ImageData data(Image image, int x, int y) {
        return new ImageData(fctx, image, new Rectangle(x, y, image.getPhysicalWidth(), image.getPhysicalHeight()));
    }

    /**
     * Runs a box kernel: {@code BoxBlur}/{@code BoxShadow} peers when {@code spread == 0} and the input
     * is untransformed, otherwise the {@code LinearConvolve}/{@code LinearConvolveShadow} peers - the
     * same selection {@code BoxRenderState.getPassPeer} makes in production.
     */
    public Result box(ImageData src, float hsize, float vsize, int passes, float spread, boolean shadow,
                      Color4f shadowColor, Rectangle clip) {
        BoxRenderState state = new BoxRenderState(hsize, vsize, passes, spread, shadow, shadowColor,
                BaseTransform.IDENTITY_TRANSFORM);
        return convolve(src, state, clip);
    }

    /**
     * Runs a two-pass Gaussian kernel through the {@code LinearConvolve} or {@code LinearConvolveShadow}
     * peers. With an untransformed input the passes are {@code HORIZONTAL_CENTERED} and
     * {@code VERTICAL_CENTERED}: the SSE peer takes its {@code filterHV} loop for those, the Java peer
     * always takes {@code filterVector}.
     */
    public Result gaussian(ImageData src, float xradius, float yradius, float spread, boolean shadow,
                           Color4f shadowColor, Rectangle clip) {
        GaussianRenderState state = new GaussianRenderState(xradius, yradius, spread, shadow, shadowColor,
                BaseTransform.IDENTITY_TRANSFORM);
        return convolve(src, state, clip);
    }

    /**
     * Runs a single directional Gaussian pass (the {@code MotionBlur} kernel) along {@code (dx, dy)}:
     * a {@code GENERAL_VECTOR} pass, so both backends take their {@code filterVector} loop.
     */
    public Result motion(ImageData src, float radius, float dx, float dy, Rectangle clip) {
        GaussianRenderState state = new GaussianRenderState(radius, dx, dy, BaseTransform.IDENTITY_TRANSFORM);
        return convolve(src, state, clip);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Result convolve(ImageData src, LinearConvolveRenderState state, Rectangle clip) {
        ImageData data = src;
        if (state.isNop()) {
            return toResult(data);
        }
        for (int pass = 0; pass < 2; pass++) {
            data = state.validatePassInput(data, pass);
            EffectPeer peer = state.getPassPeer(renderer, fctx);
            if (peer != null) {
                peer.setPass(pass);
                data = peer.filter(null, state, BaseTransform.IDENTITY_TRANSFORM, clip, data);
            }
        }
        return toResult(data);
    }

    /**
     * Runs one generated single-pass peer.
     *
     * @param effect the effect whose parameters the peer reads through {@code getEffect()}; its class
     *        has to match the peer ({@code ColorAdjust} for {@code ColorAdjust}, ...)
     * @param peerName the peer key the effect registers with {@code updatePeerKey}, for example
     *        {@code "Blend_ADD"} or {@code "PhongLighting_POINT"}
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Result generated(Effect effect, String peerName, Rectangle clip, ImageData... inputs) {
        EffectPeer peer = renderer.getPeerInstance(fctx, peerName, -1);
        ImageData out = peer.filter(effect, RenderState.RenderSpaceRenderState, BaseTransform.IDENTITY_TRANSFORM,
                clip, inputs);
        return toResult(out);
    }

    /**
     * Returns the result's image to this backend's pool, the way {@code ImageData.unref} does in
     * production, unless the result is one of the given source images (a no-op pass returns its input).
     */
    public void release(Result result, Image... sources) {
        for (Image source : sources) {
            if (result.image() == source) {
                return;
            }
        }
        renderer.releaseCompatibleImage(result.image());
    }

    private static Result toResult(ImageData data) {
        Rectangle bounds = data.getUntransformedBounds();
        HeapImage image = (HeapImage) data.getUntransformedImage();
        int scan = image.getScanlineStride();
        int[] source = image.getPixelArray();
        int[] pixels = new int[bounds.width * bounds.height];
        for (int y = 0; y < bounds.height; y++) {
            System.arraycopy(source, y * scan, pixels, y * bounds.width, bounds.width);
        }
        return new Result(bounds.x, bounds.y, bounds.width, bounds.height, pixels, image);
    }

    /**
     * The output of one run: its bounds in filter space and its pixels compacted to
     * {@code width * height} row-major ARGB-pre ints.
     */
    public record Result(int x, int y, int width, int height, int[] pixels, HeapImage image) {

        public int pixel(int col, int row) {
            return pixels[row * width + col];
        }
    }

    /** An ARGB-pre heap image with a padded stride; sources and pool images alike. */
    public static final class Image implements HeapImage, PoolFilterable {

        private final int width;
        private final int height;
        private final int scan;
        private final int[] pixels;
        private int contentWidth;
        private int contentHeight;
        private ImagePool pool;

        Image(int width, int height) {
            this.width = width;
            this.height = height;
            this.scan = width + STRIDE_PAD;
            this.pixels = new int[scan * height];
            this.contentWidth = width;
            this.contentHeight = height;
        }

        /** A source image holding the given compact {@code width * height} row-major ARGB-pre pixels. */
        public static Image of(int width, int height, int[] argbPre) {
            if (argbPre.length != width * height) {
                throw new IllegalArgumentException(argbPre.length + " pixels for " + width + "x" + height);
            }
            Image image = new Image(width, height);
            for (int y = 0; y < height; y++) {
                System.arraycopy(argbPre, y * width, image.pixels, y * image.scan, width);
            }
            return image;
        }

        @Override
        public int getScanlineStride() {
            return scan;
        }

        @Override
        public int[] getPixelArray() {
            return pixels;
        }

        @Override
        public Object getData() {
            return pixels;
        }

        @Override
        public int getContentWidth() {
            return contentWidth;
        }

        @Override
        public int getContentHeight() {
            return contentHeight;
        }

        @Override
        public void setContentWidth(int contentW) {
            contentWidth = contentW;
        }

        @Override
        public void setContentHeight(int contentH) {
            contentHeight = contentH;
        }

        @Override
        public int getMaxContentWidth() {
            return width;
        }

        @Override
        public int getMaxContentHeight() {
            return height;
        }

        @Override
        public int getPhysicalWidth() {
            return width;
        }

        @Override
        public int getPhysicalHeight() {
            return height;
        }

        @Override
        public float getPixelScale() {
            return 1f;
        }

        @Override
        public void flush() {
        }

        @Override
        public void lock() {
        }

        @Override
        public void unlock() {
        }

        @Override
        public boolean isLost() {
            return false;
        }

        @Override
        public void setImagePool(ImagePool pool) {
            this.pool = pool;
        }

        @Override
        public ImagePool getImagePool() {
            return pool;
        }

        @Override
        public String toString() {
            return "Image[" + width + "x" + height + ", scan=" + scan + "]";
        }
    }

    /**
     * {@code PSWRenderer} without Prism: heap images from {@link Image}, peers from the delegate's class
     * names. Transforms are not supported - every input this harness builds is untransformed.
     */
    private static final class DirectRenderer extends Renderer {

        private final RendererDelegate delegate;

        DirectRenderer(RendererDelegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public AccelType getAccelType() {
            return delegate.getAccelType();
        }

        @Override
        public int getCompatibleWidth(int w) {
            return w;
        }

        @Override
        public int getCompatibleHeight(int h) {
            return h;
        }

        @Override
        public PoolFilterable createCompatibleImage(int w, int h) {
            return new Image(w, h);
        }

        @Override
        public void clearImage(Filterable image) {
            Arrays.fill(((Image) image).pixels, 0);
        }

        @Override
        public ImageData createImageData(FilterContext fctx, Filterable src) {
            throw new UnsupportedOperationException("sources are wrapped by DecoraBackend.data");
        }

        @Override
        public Filterable transform(FilterContext fctx, Filterable original, BaseTransform transform,
                                    Rectangle origBounds, Rectangle xformBounds) {
            throw new UnsupportedOperationException("identity transforms only");
        }

        @Override
        public ImageData transform(FilterContext fctx, ImageData original, BaseTransform transform,
                                   Rectangle origBounds, Rectangle xformBounds) {
            throw new UnsupportedOperationException("identity transforms only");
        }

        @Override
        public RendererState getRendererState() {
            return RendererState.OK;
        }

        @Override
        protected Renderer getBackupRenderer() {
            return this;
        }

        @Override
        public boolean isImageDataCompatible(ImageData id) {
            return id.getUntransformedImage() instanceof Image;
        }

        @Override
        protected EffectPeer<?> createPeer(FilterContext fctx, String name, int unrollCount) {
            String className = delegate.getPlatformPeerName(name, unrollCount);
            try {
                Class<?> klass = Class.forName(className);
                Constructor<?> ctor = klass.getConstructor(FilterContext.class, Renderer.class, String.class);
                return (EffectPeer<?>) ctor.newInstance(fctx, this, name);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot create peer " + className + " for " + name, e);
            }
        }
    }
}

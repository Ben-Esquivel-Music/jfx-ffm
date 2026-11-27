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

package test.com.sun.javafx.webkit.prism;

import com.sun.glass.ui.Screen;
import com.sun.javafx.application.PlatformImpl;
import com.sun.javafx.webkit.prism.PrismGraphicsManager;
import com.sun.javafx.webkit.prism.PrismInvokerShim;
import com.sun.javafx.webkit.prism.RTImageShim;
import com.sun.prism.CompositeMode;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.ResourceFactoryListener;
import com.sun.prism.Texture;
import com.sun.prism.impl.TextureResourcePool;
import com.sun.prism.j2d.J2DPipeline;
import com.sun.prism.j2d.PrismPrintGraphics;
import com.sun.prism.paint.Color;
import com.sun.webkit.graphics.GraphicsDecoder;
import com.sun.webkit.graphics.WCGraphicsContext;
import com.sun.webkit.graphics.WCGraphicsManager;
import com.sun.webkit.graphics.WCImage;
import com.sun.webkit.graphics.WCPath;
import com.sun.webkit.graphics.WCRenderQueue;
import com.sun.webkit.graphics.WCTransform;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The child JVM of {@link WebPrismGpuOffscreenTest}: {@code main(workDirectory, pipeline)}. It records the
 * pipeline it got and one result per scenario in {@value #RESULTS_FILE}, and appends each scenario's name to
 * {@value #PROGRESS_FILE} before running it. Prism work runs on the render thread, as in production; the
 * prints draw from the main thread, as a printer job does from a thread of its own.
 * <p>
 * The child ends with {@code System.exit} and never exits the platform or disposes the pipeline: Metal's
 * release path over-releases (audit item L1 of {@code backlog/US-001b-prism-mtl-audit.md}).
 */
public final class WebPrismGpuOffscreenChild {

    /** The GPU pipelines the suite runs on, by the classes of {@code javafx.graphics} that it names. */
    enum Pipeline {
        METAL("Metal", "mtl", "com.sun.prism.mtl.MTLPipeline", "com.sun.prism.mtl.MTLRTTexture", "prism_mtl"),
        ES2("ES2", "es2", "com.sun.prism.es2.ES2Pipeline", "com.sun.prism.es2.ES2RTTexture", "prism_es2");

        private final String displayName;
        private final String order;
        private final String pipelineClass;
        private final String renderTextureClass;
        private final String library;

        Pipeline(String displayName, String order, String pipelineClass, String renderTextureClass,
                String library) {
            this.displayName = displayName;
            this.order = order;
            this.pipelineClass = pipelineClass;
            this.renderTextureClass = renderTextureClass;
            this.library = library;
        }

        /** The name {@code -Dprism.order} gives this pipeline. */
        String order() {
            return order;
        }

        String pipelineClass() {
            return pipelineClass;
        }

        String renderTextureClass() {
            return renderTextureClass;
        }

        /** The name of the pipeline's native library, as {@code System.mapLibraryName} takes it. */
        String library() {
            return library;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    static final String RESULTS_FILE = "results.properties";
    static final String PROGRESS_FILE = "progress.txt";
    static final String PIPELINE_KEY = "pipeline";
    static final String PIPELINE_INIT_KEY = "pipeline.init";
    static final String PIPELINE_ERROR_KEY = "pipeline.error";
    static final String SCENARIO_PREFIX = "scenario.";
    static final String PASS = "PASS";

    static final String READ_CONTENT = "readContentReadsTheWholeContentOfAGpuTextureAsDrawn";
    static final String PRINT_SCALES = "aPrintOfAGpuImageOfScaleOneAndTwoHasAllItsPixels";
    static final String PRINT_PART = "aPartOfAGpuImagePrintsThatPart";
    static final String JAVA2D_COPY = "aGpuImageDrawnIntoJava2DGraphicsIsCopied";
    static final String PROJECTED_FILL = "aProjectedFillLandsWhereTDotPPutsIt";
    static final String PROJECTED_IMAGE = "aProjectedImageLandsWhereTDotPPutsIt";
    static final String SCALED_FOLD = "atScaleTwoTheFoldStillPlacesTheFill";
    static final String SCALED_IMAGE_FOLD = "atScaleTwoTheFoldStillPlacesTheImage";
    static final String LAYERED_FOLD =
            "aProjectedDrawInsideAClipLayerAndATransparencyLayerLandsWhereThePerspectivePutsIt";
    static final String NESTED_DECODE = "aNestedDecodeDoesNotLeaveItsPerspectiveForTheNextDraw";
    static final String NESTED_DECODE_AT_START = "aNestedDecodeAtTheStartOfABufferDrawsWithItsOwnPerspective";
    static final String IDENTITY_LEFT = "aDecodeAndADisposeLeaveTheIdentityInTheDevice";
    static final String REUSED_CLEARED = "aReusedTextureIsClearedOfWhatItHeld";
    static final String CACHE_DISPOSAL = "disposingCachedTexturesFreesThem";
    static final String IMAGE_REUSE = "aDisposedImagesTextureIsReusedClearedAfterAPoolSweep";
    static final String PIXEL_BUFFER = "aPixelBufferDrawnOverAPendingFillReplacesIt";
    static final String ROTATED_EDGE = "aRotatedImageShowsNothingOfItsOppositeEdges";

    private static final long STARTUP_SECONDS = 20;

    private static final int OPAQUE_RED = 0xffff0000;
    private static final int OPAQUE_BLUE = 0xff0000ff;
    private static final int OPAQUE_BLACK = 0xff000000;
    private static final int TRANSPARENT = 0;
    private static final int HALF_GREEN = 0x80008000;
    /** Per channel, for a colour of alpha 0.5, which Prism may round either way. */
    private static final int TRANSLUCENT_BOUND = 1;

    private static final int PAGE_WIDTH = 40;
    private static final int PAGE_HEIGHT = 20;

    /** A command buffer's projection, whose w is 1 + x / 1024. */
    private static final float[] COMMAND_PROJECTION = {1, 0, 0, 0.0009765625f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    /** The projection of the fold scenarios, whose w is 1 + 0.01 x. */
    private static final float[] PROJECTION = {1, 0, 0, 0.01f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    private record Scenario(String name, Check check) {
    }

    private static Pipeline expected;

    private WebPrismGpuOffscreenChild() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        expected = Pipeline.valueOf(args[1]);
        Properties results = new Properties();
        CountDownLatch started = new CountDownLatch(1);
        PlatformImpl.startup(started::countDown);
        if (!started.await(STARTUP_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the toolkit did not start within " + STARTUP_SECONDS + " s");
        }
        GraphicsPipeline pipeline = GraphicsPipeline.getPipeline();
        String pipelineName = pipeline == null ? "null" : pipeline.getClass().getName();
        results.setProperty(PIPELINE_KEY, pipelineName);
        if (!expected.pipelineClass().equals(pipelineName)) {
            diagnoseThePipeline(results);
            write(work, results);
            System.exit(0);
        }
        // A WCTransform or WCRenderQueue takes its id from the manager, so it must exist before any of them.
        WCGraphicsManager.setGraphicsManager(new PrismGraphicsManager());
        for (Scenario scenario : scenarios()) {
            Files.writeString(work.resolve(PROGRESS_FILE), scenario.name() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            results.setProperty(SCENARIO_PREFIX + scenario.name(), outcome(work, scenario));
            write(work, results);
        }
        System.exit(0);
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario(READ_CONTENT, WebPrismGpuOffscreenChild::readContentReadsTheWholeContent),
                new Scenario(PRINT_SCALES, WebPrismGpuOffscreenChild::aPrintOfScaleOneAndTwoHasAllItsPixels),
                new Scenario(PRINT_PART, WebPrismGpuOffscreenChild::aPartOfAnImagePrintsThatPart),
                new Scenario(JAVA2D_COPY, WebPrismGpuOffscreenChild::anImageDrawnIntoJava2DGraphicsIsCopied),
                new Scenario(PROJECTED_FILL, WebPrismGpuOffscreenChild::aProjectedFillLandsWhereTDotPPutsIt),
                new Scenario(PROJECTED_IMAGE, WebPrismGpuOffscreenChild::aProjectedImageLandsWhereTDotPPutsIt),
                new Scenario(SCALED_FOLD, WebPrismGpuOffscreenChild::atScaleTwoTheFoldStillPlacesTheFill),
                new Scenario(SCALED_IMAGE_FOLD, WebPrismGpuOffscreenChild::atScaleTwoTheFoldStillPlacesTheImage),
                new Scenario(LAYERED_FOLD, WebPrismGpuOffscreenChild::aProjectedDrawInsideALayerLandsWhereItShould),
                new Scenario(NESTED_DECODE, () -> nestedDecode(false)),
                new Scenario(NESTED_DECODE_AT_START, () -> nestedDecode(true)),
                new Scenario(IDENTITY_LEFT, WebPrismGpuOffscreenChild::aDecodeAndADisposeLeaveTheIdentity),
                new Scenario(REUSED_CLEARED, WebPrismGpuOffscreenChild::aReusedTextureIsClearedOfWhatItHeld),
                new Scenario(CACHE_DISPOSAL, WebPrismGpuOffscreenChild::disposingCachedTexturesFreesThem),
                new Scenario(IMAGE_REUSE, WebPrismGpuOffscreenChild::aDisposedImagesTextureIsReusedCleared),
                new Scenario(PIXEL_BUFFER, WebPrismGpuOffscreenChild::aPixelBufferDrawnOverAPendingFillReplacesIt),
                new Scenario(ROTATED_EDGE, WebPrismGpuOffscreenChild::aRotatedImageShowsNothingOfItsOppositeEdges));
    }

    private static String outcome(Path work, Scenario scenario) throws IOException {
        try {
            scenario.check().run();
            return PASS;
        } catch (Throwable t) {
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            Files.writeString(work.resolve(scenario.name() + ".trace"), trace.toString(), StandardCharsets.UTF_8);
            return "FAIL " + String.valueOf(t).replace('\r', ' ').replace('\n', ' ');
        }
    }

    /** ES2's getInstance() answers null where its library loaded and no OpenGL device could be set up. */
    private static void diagnoseThePipeline(Properties results) {
        Throwable[] failure = new Throwable[1];
        boolean[] initialized = new boolean[1];
        PrismInvokerShim.runOnRenderThread(() -> {
            try {
                GraphicsPipeline instance = (GraphicsPipeline) Class.forName(expected.pipelineClass())
                        .getMethod("getInstance").invoke(null);
                initialized[0] = instance != null && instance.init();
            } catch (InvocationTargetException e) {
                failure[0] = e.getCause();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) {
            results.setProperty(PIPELINE_ERROR_KEY, String.valueOf(failure[0]).replace('\n', ' '));
        } else {
            results.setProperty(PIPELINE_INIT_KEY, Boolean.toString(initialized[0]));
        }
    }

    private static void write(Path work, Properties results) throws IOException {
        try (Writer out = Files.newBufferedWriter(work.resolve(RESULTS_FILE), StandardCharsets.UTF_8)) {
            results.store(out, null);
        }
    }

    /*
     * Claim 1: the read-back of a GPU render texture, which a print and a draw into another pipeline's
     * graphics make. These scenarios read pixels only through the production read-back.
     */

    private static void readContentReadsTheWholeContent() throws Exception {
        onRenderThread(() -> {
            RTTexture texture = factory().createRTTexture(37, 23, Texture.WrapMode.CLAMP_NOT_NEEDED);
            try {
                expectClass(expected.renderTextureClass(), texture);
                Graphics g = texture.createGraphics();
                g.clear();
                g.setPaint(Color.RED);
                g.fillRect(0, 0, 18, 23);
                g.setPaint(Color.BLUE);
                g.fillRect(18, 0, 19, 23);
                g.setCompositeMode(CompositeMode.SRC);
                g.setPaint(new Color(0, 1, 0, 0.5f));
                g.fillRect(0, 0, 37, 4);

                IntBuffer content = RTImageShim.readContent(readsOnlyIntoTheWholeContent(texture));
                if (!content.isDirect() || content.order() != ByteOrder.nativeOrder() || content.capacity() != 851) {
                    throw new AssertionError("the read-back buffer is direct " + content.isDirect() + ", of order "
                            + content.order() + " and capacity " + content.capacity());
                }
                int[] read = new int[content.capacity()];
                content.get(0, read);
                Frame readBack = new Frame("the read-back", read, 37);
                Samples samples = new Samples();
                samples.expect(readBack, OPAQUE_RED, 5, 10);
                samples.expect(readBack, OPAQUE_BLUE, 30, 10);
                samples.expectNear(readBack, HALF_GREEN, TRANSLUCENT_BOUND, 5, 1, 30, 1);
                if (samples.failed()) {
                    Frame afterwards = Frame.of("a read afterwards", texture);
                    Samples flushed = new Samples();
                    flushed.expect(afterwards, OPAQUE_RED, 5, 10);
                    flushed.expect(afterwards, OPAQUE_BLUE, 30, 10);
                    flushed.expectNear(afterwards, HALF_GREEN, TRANSLUCENT_BOUND, 5, 1, 30, 1);
                    samples.verify("; " + (flushed.failed() ? "a read afterwards does not show them either: "
                            + flushed.report() : "a read afterwards shows them all: "
                            + afterwards.describe(5, 10, 30, 10, 5, 1, 30, 1)));
                }
            } finally {
                texture.dispose();
            }
        });
    }

    /** The texture, which refuses a read into anything but a direct buffer of exactly its whole content. */
    private static RTTexture readsOnlyIntoTheWholeContent(RTTexture texture) {
        return (RTTexture) Proxy.newProxyInstance(RTTexture.class.getClassLoader(), new Class<?>[] {RTTexture.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readPixels")) {
                        Buffer buffer = (Buffer) args[0];
                        int whole = texture.getContentWidth() * texture.getContentHeight();
                        if (args.length != 1 || !buffer.isDirect() || buffer.capacity() != whole) {
                            throw new AssertionError("a read-back into " + buffer + " with " + args.length
                                    + " arguments, where only a direct buffer of " + whole + " ints may be read");
                        }
                    }
                    try {
                        return method.invoke(texture, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void aPrintOfScaleOneAndTwoHasAllItsPixels() {
        Samples samples = new Samples();
        for (float scale : new float[] {1, 2}) {
            WCImage image = redAndBlueHalves(scale);
            try {
                Frame printed = Frame.of("the print at scale " + scale,
                        print(g -> RTImageShim.draw(image, g, 0, 0, PAGE_WIDTH, PAGE_HEIGHT, 0, 0, PAGE_WIDTH,
                                PAGE_HEIGHT)));
                samples.expect(printed, OPAQUE_RED, 10, 10);
                samples.expect(printed, OPAQUE_BLUE, 30, 10);
            } finally {
                RTImageShim.dispose(image);
            }
        }
        samples.verify("");
    }

    private static void aPartOfAnImagePrintsThatPart() {
        WCImage image = redAndBlueHalves(1);
        try {
            Frame printed = Frame.of("the print of the right half",
                    print(g -> RTImageShim.draw(image, g, 0, 0, 20, 20, 20, 0, PAGE_WIDTH, PAGE_HEIGHT)));
            Samples samples = new Samples();
            samples.expect(printed, OPAQUE_BLUE, 10, 10);
            samples.verify("");
        } finally {
            RTImageShim.dispose(image);
        }
    }

    private static void anImageDrawnIntoJava2DGraphicsIsCopied() {
        WCImage image = redAndBlueHalves(1);
        ResourceFactory java2D = J2DPipeline.getInstance().getResourceFactory(Screen.getMainScreen());
        RTTexture target = java2D.createRTTexture(PAGE_WIDTH, PAGE_HEIGHT, Texture.WrapMode.CLAMP_NOT_NEEDED);
        try {
            RTImageShim.draw(image, target.createGraphics(), 0, 0, PAGE_WIDTH, PAGE_HEIGHT, 0, 0, PAGE_WIDTH,
                    PAGE_HEIGHT);
            Frame drawn = new Frame("the Java 2D target", target.getPixels().clone(), target.getPhysicalWidth());
            Samples samples = new Samples();
            samples.expect(drawn, OPAQUE_RED, 10, 10);
            samples.expect(drawn, OPAQUE_BLUE, 30, 10);
            samples.verify("");
        } finally {
            target.dispose();
            RTImageShim.dispose(image);
        }
    }

    /** An image whose physical left half is red and right half blue. */
    private static WCImage redAndBlueHalves(float pixelScale) {
        WCImage image = RTImageShim.create(PAGE_WIDTH, PAGE_HEIGHT, pixelScale);
        int physicalWidth = (int) Math.ceil(PAGE_WIDTH * pixelScale);
        int physicalHeight = (int) Math.ceil(PAGE_HEIGHT * pixelScale);
        RTImageShim.fillPhysical(image, 0, 0, physicalWidth / 2, physicalHeight, Color.RED);
        RTImageShim.fillPhysical(image, physicalWidth / 2, 0, physicalWidth / 2, physicalHeight, Color.BLUE);
        return image;
    }

    private static BufferedImage print(Consumer<Graphics> drawing) {
        BufferedImage page = new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = page.createGraphics();
        try {
            drawing.accept(new PrismPrintGraphics(g2d, PAGE_WIDTH, PAGE_HEIGHT));
        } finally {
            g2d.dispose();
        }
        return page;
    }

    /*
     * Claim 2: the perspective folded into Prism's device context, on a real shader graphics. With
     * translate(30, 0) and w = 1 + 0.01 x, a point (x, y) lands at (x / w + 30, y / w); the fill of
     * (0, 10, 40, 20) spans x' from 30 to 58.57; at x' = 32.5 it spans y' from 9.75 to 29.25, at x' = 56.5
     * from 7.35 to 22.05.
     */

    private static void aProjectedFillLandsWhereTDotPPutsIt() throws Exception {
        Frame drawn = projectedOnto100By40(context -> context.fillRect(0, 10, 40, 20, Color.BLUE));
        expectTheProjectedQuad(drawn);
    }

    private static void aProjectedImageLandsWhereTDotPPutsIt() throws Exception {
        Frame drawn = projectedOnto100By40(context -> {
            WCImage image = RTImageShim.create(40, 20, 1);
            try {
                RTImageShim.fillPhysical(image, 0, 0, 40, 20, Color.BLUE);
                context.drawImage(image, 0, 10, 40, 20, 0, 0, 40, 20);
                context.flush();
            } finally {
                RTImageShim.dispose(image);
            }
        });
        expectTheProjectedQuad(drawn);
    }

    private static void expectTheProjectedQuad(Frame drawn) {
        Samples samples = new Samples();
        samples.expect(drawn, OPAQUE_BLUE, 32, 20, 50, 20, 56, 20, 32, 25);
        samples.expect(drawn, TRANSPARENT, 25, 20, 27, 20, 61, 20, 56, 25);
        samples.verify("");
    }

    private static Frame projectedOnto100By40(Consumer<WCGraphicsContext> draw) throws Exception {
        Frame[] drawn = new Frame[1];
        onRenderThread(() -> {
            RTTexture target = factory().createRTTexture(100, 40, Texture.WrapMode.CLAMP_NOT_NEEDED);
            WCGraphicsContext context = null;
            try {
                Graphics g = target.createGraphics();
                g.clear();
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(g);
                context.translate(30, 0);
                context.setPerspectiveTransform(transform(PROJECTION));
                draw.accept(context);
                context.flush();
                drawn[0] = Frame.of("the 100 x 40 target", target);
            } finally {
                release(context, target);
            }
        });
        return drawn[0];
    }

    /**
     * No regression only: the context's base transform is the pixel scale an image's graphics get. A point
     * lands at (2 (x / w + 15), 2 y / w), so the fill spans x' from 30 to 87.14.
     */
    private static void atScaleTwoTheFoldStillPlacesTheFill() throws Exception {
        onRenderThread(() -> {
            RTTexture target = factory().createRTTexture(120, 80, Texture.WrapMode.CLAMP_NOT_NEEDED);
            WCGraphicsContext context = null;
            try {
                Graphics g = target.createGraphics();
                g.clear();
                g.scale(2, 2);
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(g);
                context.translate(15, 0);
                context.setPerspectiveTransform(transform(PROJECTION));
                context.fillRect(0, 10, 40, 20, Color.BLUE);
                context.flush();
                Frame drawn = Frame.of("the 120 x 80 target", target);
                Samples samples = new Samples();
                samples.expect(drawn, OPAQUE_BLUE, 32, 40, 85, 40, 40, 22, 84, 17);
                samples.expect(drawn, TRANSPARENT, 28, 40, 89, 40, 40, 17);
                samples.verify("");
            } finally {
                release(context, target);
            }
        });
    }

    /**
     * No regression only, as the fill above: the image of (0, 10, 40, 20) lands at (2 (x / w + 15), 2 y / w), as
     * the fill does. Under a transform that is not a translation, Prism hands the whole transform to the shader,
     * which applies it after the perspective, so the drawn pixels are the same folded or not.
     */
    private static void atScaleTwoTheFoldStillPlacesTheImage() throws Exception {
        onRenderThread(() -> {
            RTTexture target = factory().createRTTexture(120, 80, Texture.WrapMode.CLAMP_NOT_NEEDED);
            WCGraphicsContext context = null;
            WCImage image = RTImageShim.create(40, 20, 1);
            try {
                RTImageShim.fillPhysical(image, 0, 0, 40, 20, Color.BLUE);
                Graphics g = target.createGraphics();
                g.clear();
                g.scale(2, 2);
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(g);
                context.translate(15, 0);
                context.setPerspectiveTransform(transform(PROJECTION));
                context.drawImage(image, 0, 10, 40, 20, 0, 0, 40, 20);
                context.flush();
                Frame drawn = Frame.of("the 120 x 80 target", target);
                Samples samples = new Samples();
                samples.expect(drawn, OPAQUE_BLUE, 32, 40, 85, 40, 40, 22, 84, 17);
                samples.expect(drawn, TRANSPARENT, 28, 40, 89, 40, 40, 17);
                samples.verify("");
            } finally {
                RTImageShim.dispose(image);
                release(context, target);
            }
        });
    }

    /*
     * Inside a layer the state transform is the translation to the layer's origin, and the layer's graphics are
     * shader graphics. With w = 1 + 0.01 x and no transform, the fill of (100, 100, 80, 60) spans x' from 50 to
     * 64.29; its left edge spans y' from 50 to 80, its right edge from 35.71 to 57.14. A layer opened under the
     * clip (30, 40, 200, 100) has the clip's bounds, so it holds nothing above row 40, and below it what the
     * target holds with neither clip nor layer. A layer whose offset reached the vertices before the perspective
     * would put the fill at x' from 71.2 to 90 (P (p - (30, 40)) + (30, 40)).
     */
    private static void aProjectedDrawInsideALayerLandsWhereItShould() throws Exception {
        Frame unlayered = projected("neither clip nor layer", context -> { }, context -> { });
        Frame transparency = projected("a transparency layer", context -> {
            context.setClip(30, 40, 200, 100);
            context.beginTransparencyLayer(1f);
        }, WCGraphicsContext::endTransparencyLayer);
        Frame clipLayer = projected("a clip layer", context -> {
            context.setClip(30, 40, 200, 100);
            context.saveState();
            WCPath path = RTImageShim.createPath();
            path.addRect(30, 40, 200, 100);
            context.setClip(path, false);
        }, WCGraphicsContext::restoreState);
        Samples samples = new Samples();
        for (Frame frame : List.of(unlayered, transparency, clipLayer)) {
            System.err.println(frame.name() + ": " + frame.describeOpaque());
            samples.expect(frame, OPAQUE_BLUE, 55, 60, 62, 42);
            samples.expect(frame, TRANSPARENT, 40, 60, 55, 75);
        }
        List<String> unexpected = new ArrayList<>();
        if (unlayered.countOpaqueAbove(40) == 0) {
            unexpected.add("the frame with " + unlayered.name() + " has no opaque pixel above row 40");
        }
        for (Frame layered : List.of(transparency, clipLayer)) {
            for (int y = 0; y < 200; y++) {
                for (int x = 0; x < 300; x++) {
                    boolean expected = y < 40 ? layered.at(x, y) == TRANSPARENT
                            : y < 41 || layered.at(x, y) == unlayered.at(x, y);
                    if (!expected && unexpected.size() < 8) {
                        unexpected.add(layered.describe(x, y) + " where " + unlayered.describe(x, y));
                    }
                }
            }
        }
        if (samples.failed() || !unexpected.isEmpty()) {
            throw new AssertionError(samples.report() + "; first pixels that differ: " + unexpected);
        }
    }

    private static Frame projected(String name, Consumer<WCGraphicsContext> open, Consumer<WCGraphicsContext> close)
            throws Exception {
        Frame[] drawn = new Frame[1];
        onRenderThread(() -> {
            RTTexture target = clearedTexture(300, 200);
            WCGraphicsContext context = null;
            try {
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(target.createGraphics());
                open.accept(context);
                context.setPerspectiveTransform(transform(PROJECTION));
                context.fillRect(100, 100, 80, 60, Color.BLUE);
                close.accept(context);
                context.flush();
                drawn[0] = Frame.of("the 300 x 200 target with " + name, target);
            } finally {
                release(context, target);
            }
        });
        return drawn[0];
    }

    /*
     * Claim 3: the perspective that Prism's device context shares between every graphics. The outer context
     * is translated by (30, 40), and its command projection has w = 1 + x / 1024, so its fill of
     * (0, 0, 50, 50) lands at x' from 30 to 77.67 and y' from 40 to 40 + 50 / w (89.88 at x' = 32.5, 87.78
     * at x' = 75.5). The tile's context draws its own fill of (0, 0, 50, 50) with the identity.
     */

    private static void nestedDecode(boolean warmTile) throws Exception {
        onRenderThread(() -> {
            RTTexture outer = clearedTexture(200, 150);
            RTTexture tile = clearedTexture(100, 100);
            WCGraphicsContext outerContext = null;
            WCGraphicsContext tileContext = null;
            WCRenderQueue tileQueue = null;
            try {
                outerContext = WCGraphicsManager.getGraphicsManager().createGraphicsContext(outer.createGraphics());
                tileContext = WCGraphicsManager.getGraphicsManager().createGraphicsContext(tile.createGraphics());
                if (warmTile) {
                    WCRenderQueue earlierFrame = new HeapBufferQueue(tileContext);
                    earlierFrame.addBuffer(commands(buffer -> putFillRect(buffer, 60, 60, 30, 30)));
                    earlierFrame.decode();
                }
                tileQueue = new HeapBufferQueue(tileContext);
                tileQueue.ref();
                tileQueue.addBuffer(commands(buffer -> putFillRect(buffer, 0, 0, 50, 50)));
                int tileQueueId = tileQueue.getID();
                WCRenderQueue queue = new HeapBufferQueue(outerContext);
                queue.addBuffer(commands(buffer -> putProjection(buffer, COMMAND_PROJECTION)));
                queue.addBuffer(commands(buffer -> {
                    putDecodeTile(buffer, tileQueueId);
                    putFillRect(buffer, 0, 0, 50, 50);
                }));
                outerContext.translate(30, 40);
                queue.decode();

                Samples samples = new Samples();
                Frame outerDrawn = Frame.of("the outer target", outer);
                samples.expect(outerDrawn, OPAQUE_BLACK, 32, 42, 75, 80, 32, 87);
                samples.expect(outerDrawn, TRANSPARENT, 10, 10, 79, 60, 75, 89);
                Frame tileDrawn = Frame.of("the tile", tile);
                samples.expect(tileDrawn, OPAQUE_BLACK, 25, 25, 48, 48);
                samples.expect(tileDrawn, TRANSPARENT, 55, 25, 70, 45);
                samples.verify("");
            } finally {
                if (tileQueue != null) {
                    tileQueue.deref();
                }
                release(tileContext, tile);
                release(outerContext, outer);
            }
        });
    }

    /** With w = 1 + 0.01 x left in the device, the probe's fill of (0, 10, 40, 20) would end at x' = 28.6. */
    private static void aDecodeAndADisposeLeaveTheIdentity() throws Exception {
        onRenderThread(() -> {
            RTTexture target = clearedTexture(100, 40);
            WCGraphicsContext context = null;
            try {
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(target.createGraphics());
                WCRenderQueue queue = new HeapBufferQueue(context);
                queue.addBuffer(commands(buffer -> {
                    putProjection(buffer, PROJECTION);
                    putFillRect(buffer, 0, 10, 40, 20);
                }));
                queue.decode();
                Samples samples = new Samples();
                expectAnUnprojectedProbe(samples, "after the decode");

                context.setPerspectiveTransform(transform(PROJECTION));
                context.getPlatformGraphics();
                context.dispose();
                context = null;
                expectAnUnprojectedProbe(samples, "after the dispose");
                samples.verify("");
            } finally {
                release(context, target);
            }
        });
    }

    private static void expectAnUnprojectedProbe(Samples samples, String when) {
        RTTexture probe = clearedTexture(100, 40);
        try {
            Graphics g = probe.createGraphics();
            g.setPaint(Color.BLACK);
            g.fillRect(0, 10, 40, 20);
            Frame drawn = Frame.of("a fresh graphics " + when, probe);
            samples.expect(drawn, OPAQUE_BLACK, 5, 20, 38, 20);
            samples.expect(drawn, TRANSPARENT, 42, 20);
        } finally {
            probe.dispose();
        }
    }

    /*
     * Claim 4: the reuse of the render textures of disposed images, on real GPU textures. A private cache
     * runs on a clock that never moves and never purges; the images' textures go through the shared one. A scenario
     * that leaves a draw pending first empties the shared cache, for on ES2 the disposal of an older cached texture
     * still bound to a texture unit would flush that draw.
     */

    /** The red is read back before the reuse, so nothing of it is pending and only the clear can remove it. */
    private static void aReusedTextureIsClearedOfWhatItHeld() throws Exception {
        onRenderThread(() -> {
            ResourceFactory f = factory();
            ResourceFactoryListener cache = privateCache();
            RTTexture first = takeAsAnImage(cache, f, 64, 48);
            try {
                Graphics g = first.createGraphics();
                g.setPaint(Color.RED);
                g.fillRect(0, 0, 64, 48);
                Frame held = Frame.of("the texture before its reuse", first);
                if (held.count(OPAQUE_RED) != held.argb().length) {
                    throw new AssertionError("before the reuse only " + held.count(OPAQUE_RED) + " of the "
                            + held.argb().length + " pixels are red; " + held.describeCornersAndCentre());
                }
                giveBackAsAnImage(cache, f, first);

                int reusedBefore = RTImageShim.texturesReused(cache);
                RTTexture second = RTImageShim.obtain(cache, f, 64, 48);
                if (second != first || RTImageShim.texturesReused(cache) != reusedBefore + 1
                        || second.isSurfaceLost()) {
                    throw new AssertionError("the cache answered " + second + " for " + first + ", reused "
                            + RTImageShim.texturesReused(cache) + " after " + reusedBefore + ", surface lost "
                            + second.isSurfaceLost());
                }
                expectAllTransparent(Frame.of("the reused texture", second));
            } finally {
                first.dispose();
            }
        });
    }

    private static void disposingCachedTexturesFreesThem() throws Exception {
        onRenderThread(() -> {
            ResourceFactory f = factory();
            TextureResourcePool pool = f.getTextureResourcePool();
            long usedBefore = pool.used();
            ResourceFactoryListener cache = privateCache();
            List<RTTexture> cached = new ArrayList<>();
            for (int[] size : new int[][] {{16, 16}, {32, 24}, {64, 48}}) {
                RTTexture texture = takeAsAnImage(cache, f, size[0], size[1]);
                cached.add(texture);
                giveBackAsAnImage(cache, f, texture);
            }
            cache.factoryReset();
            List<RTTexture> kept = cached.stream().filter(texture -> !texture.isSurfaceLost()).toList();
            if (!kept.isEmpty() || pool.used() != usedBefore) {
                throw new AssertionError("after the reset the textures " + kept + " are not lost, and the pool uses "
                        + pool.used() + " bytes where it used " + usedBefore);
            }
        });
    }

    /**
     * The images' own path: the first image's red is still pending when its texture is reused, and the sweep of an
     * errored frame, which unlocks every texture that is not permanent, runs while the texture is cached.
     */
    private static void aDisposedImagesTextureIsReusedCleared() throws Exception {
        RTImageShim.emptySharedCache();
        int reusedBefore = RTImageShim.texturesReused();
        WCImage first = RTImageShim.create(64, 48, 1);
        RTImageShim.fillPhysical(first, 0, 0, 64, 48, Color.RED);
        RTImageShim.dispose(first);
        PrismInvokerShim.runOnRenderThread(() -> { });
        PrismInvokerShim.runOnRenderThread(
                () -> factory().getTextureResourcePool().freeDisposalRequestedAndCheckResources(true));
        WCImage second = RTImageShim.create(64, 48, 1);
        try {
            RTImageShim.fillPhysical(second, 5, 7, 1, 1, Color.BLUE);
            int reused = RTImageShim.texturesReused() - reusedBefore;
            IntBuffer ints = second.getPixelBuffer().duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            int[] pixels = new int[ints.remaining()];
            ints.get(pixels);
            Frame read = new Frame("the second image's pixel buffer", pixels, 64);
            List<String> unexpected = new ArrayList<>();
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 64; x++) {
                    int expected = x == 5 && y == 7 ? OPAQUE_BLUE : TRANSPARENT;
                    if (read.at(x, y) != expected && unexpected.size() < 8) {
                        unexpected.add(read.describe(x, y) + " where " + hex(expected) + " was expected");
                    }
                }
            }
            if (reused != 1 || !unexpected.isEmpty()) {
                throw new AssertionError("textures reused " + reused + "; " + read.count(OPAQUE_RED)
                        + " pixels are red; first unexpected pixels: " + unexpected);
            }
        } finally {
            RTImageShim.dispose(second);
        }
    }

    /**
     * WebKit draws an image's pixel buffer back into its texture after writing the buffer. Here the buffer is all
     * transparent and a red fill of the texture is still pending, its texture the current target.
     */
    private static void aPixelBufferDrawnOverAPendingFillReplacesIt() throws Exception {
        RTImageShim.emptySharedCache();
        WCImage image = RTImageShim.create(48, 32, 1);
        try {
            ByteBuffer buffer = image.getPixelBuffer();
            buffer.put(0, new byte[buffer.capacity()]);
            onRenderThread(() -> {
                RTImageShim.fillPhysical(image, 0, 0, 48, 32, Color.RED);
                RTImageShim.drawPixelBuffer(image);
            });
            Frame[] drawn = new Frame[1];
            onRenderThread(() -> drawn[0] = Frame.of("the image's texture", RTImageShim.renderTexture(image)));
            expectAllTransparent(drawn[0]);
        } finally {
            RTImageShim.dispose(image);
        }
    }

    private static ResourceFactoryListener privateCache() {
        return RTImageShim.createCache(() -> 0L, (purge, delayNanos) -> { });
    }

    /** What RTImage does with a texture it gets. */
    private static RTTexture takeAsAnImage(ResourceFactoryListener cache, ResourceFactory f, int width, int height) {
        RTTexture texture = RTImageShim.obtain(cache, f, width, height);
        texture.contentsUseful();
        texture.makePermanent();
        return texture;
    }

    /** What a disposed RTImage does with its texture. */
    private static void giveBackAsAnImage(ResourceFactoryListener cache, ResourceFactory f, RTTexture texture) {
        texture.contentsNotUseful();
        RTImageShim.offer(cache, f, texture);
    }

    private static void expectAllTransparent(Frame frame) {
        int painted = frame.argb().length - frame.count(TRANSPARENT);
        if (painted != 0) {
            throw new AssertionError(painted + " of the " + frame.argb().length + " pixels of " + frame.name()
                    + " are not transparent, " + frame.count(OPAQUE_RED) + " of them red; "
                    + frame.describeCornersAndCentre());
        }
    }

    /*
     * Claim 5: what a linearly filtered draw samples at the edges of a render texture. The top-left quarter of a
     * 150 x 150 image, (0, 0) to (75, 75), is red and the rest transparent, and it is drawn rotated by 45 degrees
     * about the point (148, 112) of the target: (x, y) lands at (148, 112) + R ((x, y) - (75, 75)). A pixel centre
     * q maps back to u = 75 + c dx + s dy, v = 75 - s dx + c dy, with (dx, dy) = q - (148, 112) and
     * c = s = cos 45. A sample at (u, v) reads only the texels whose centres lie within one texel of it, so where
     * u > 78 or v > 78, more than two texels right of or below the red quarter, nothing red may show, unless a
     * sample past the right edge wraps to the red left edge or one past the bottom edge wraps to the red top
     * edge. The red quarter holds about 74 x 74 opaque pixels; fewer than 5,000 means it is not where the
     * mapping puts it.
     */

    private static void aRotatedImageShowsNothingOfItsOppositeEdges() throws Exception {
        onRenderThread(() -> {
            RTTexture target = clearedTexture(300, 230);
            WCGraphicsContext context = null;
            WCImage image = RTImageShim.create(150, 150, 1);
            try {
                RTImageShim.fillPhysical(image, 0, 0, 75, 75, Color.RED);
                double c = Math.cos(Math.PI / 4);
                double s = Math.sin(Math.PI / 4);
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(target.createGraphics());
                context.concatTransform(new WCTransform(c, s, -s, c, 148 - 75 * (c - s), 112 - 75 * (s + c)));
                context.drawImage(image, 0, 0, 150, 150, 0, 0, 150, 150);
                context.flush();
                Frame drawn = Frame.of("the 300 x 230 target", target);
                int fringe = 0;
                int fringeBeyondU = 0;
                int fringeBeyondV = 0;
                int maxFringeRed = 0;
                int redInside = 0;
                List<String> firstFringe = new ArrayList<>();
                for (int y = 0; y < 230; y++) {
                    for (int x = 0; x < 300; x++) {
                        double dx = x + 0.5 - 148;
                        double dy = y + 0.5 - 112;
                        double u = 75 + c * dx + s * dy;
                        double v = 75 - s * dx + c * dy;
                        int red = (drawn.at(x, y) >>> 16) & 0xff;
                        if ((u > 78 || v > 78) && red > 2) {
                            fringe++;
                            if (u > 78) {
                                fringeBeyondU++;
                            }
                            if (v > 78) {
                                fringeBeyondV++;
                            }
                            maxFringeRed = Math.max(maxFringeRed, red);
                            if (firstFringe.size() < 8) {
                                firstFringe.add(drawn.describe(x, y) + String.format(" at u %.2f v %.2f", u, v));
                            }
                        }
                        if (u >= 0 && u <= 75 && v >= 0 && v <= 75 && drawn.at(x, y) == OPAQUE_RED) {
                            redInside++;
                        }
                    }
                }
                String fringeCounts = fringe + " red pixels beyond u 78 or v 78 (" + fringeBeyondU + " beyond u, "
                        + fringeBeyondV + " beyond v), the reddest " + maxFringeRed;
                System.err.println("rotated image: " + fringeCounts + "; " + redInside
                        + " opaque red pixels inside the red quarter");
                if (fringe != 0 || redInside <= 5_000) {
                    throw new AssertionError(fringeCounts + ", and " + redInside
                            + " pixels inside the red quarter are opaque red; first: " + firstFringe);
                }
            } finally {
                RTImageShim.dispose(image);
                release(context, target);
            }
        });
    }

    /*
     * Helpers. Edges are antialiased, with a ramp of half a pixel, so every sample is a pixel whose centre is
     * at least 1.4 pixels of the target from the nearest edge of what was drawn (the least is 1.44).
     */

    private static void onRenderThread(Check check) throws Exception {
        Throwable[] failure = new Throwable[1];
        boolean[] ran = new boolean[1];
        PrismInvokerShim.runOnRenderThread(() -> {
            try {
                check.run();
                ran[0] = true;
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] instanceof Exception exception) {
            throw exception;
        }
        if (!ran[0]) {
            throw new AssertionError("the check did not run on the render thread");
        }
    }

    private static ResourceFactory factory() {
        return GraphicsPipeline.getDefaultResourceFactory();
    }

    private static RTTexture clearedTexture(int width, int height) {
        RTTexture texture = factory().createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED);
        texture.createGraphics().clear();
        return texture;
    }

    /** Disposes the context, which resets the device perspective it set, then resets it whatever was left. */
    private static void release(WCGraphicsContext context, RTTexture target) {
        try {
            if (context != null) {
                context.dispose();
            }
        } finally {
            target.createGraphics().setPerspectiveTransform(null);
            target.dispose();
        }
    }

    private static void expectClass(String expected, Object object) {
        if (!expected.equals(object.getClass().getName())) {
            throw new AssertionError("expected a " + expected + " but got a " + object.getClass().getName());
        }
    }

    private static WCTransform transform(float[] m) {
        return new WCTransform(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]);
    }

    private static String hex(int argb) {
        return String.format("%08x", argb);
    }

    /** A queue of heap buffers, which the native release must never see. */
    private static final class HeapBufferQueue extends WCRenderQueue {
        private HeapBufferQueue(WCGraphicsContext context) {
            super(context);
        }

        @Override
        protected void flush() {
        }

        @Override
        protected void disposeGraphics() {
        }

        @Override
        public void dispose() {
        }
    }

    private static ByteBuffer commands(Consumer<ByteBuffer> writer) {
        ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        writer.accept(buffer);
        buffer.flip();
        return buffer;
    }

    private static void putProjection(ByteBuffer buffer, float[] projection) {
        buffer.putInt(GraphicsDecoder.SET_PERSPECTIVE_TRANSFORM);
        for (float value : projection) {
            buffer.putFloat(value);
        }
    }

    private static void putDecodeTile(ByteBuffer buffer, int tileQueueId) {
        buffer.putInt(GraphicsDecoder.DECODERQ);
        buffer.putInt(tileQueueId);
    }

    private static void putFillRect(ByteBuffer buffer, float x, float y, float width, float height) {
        buffer.putInt(GraphicsDecoder.FILLRECT_FFFF);
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(width);
        buffer.putFloat(height);
    }

    /** Premultiplied ARGB pixels, row by row. */
    private record Frame(String name, int[] argb, int width) {

        /** ES2's getPixels() answers null; its readPixels() flushes the pending draws first, as Metal's getPixels(). */
        static Frame of(String name, RTTexture texture) {
            int[] pixels = texture.getPixels();
            if (pixels == null) {
                pixels = new int[texture.getContentWidth() * texture.getContentHeight()];
                if (!texture.readPixels(IntBuffer.wrap(pixels))) {
                    throw new AssertionError("could not read " + name);
                }
            }
            return new Frame(name, pixels.clone(), texture.getContentWidth());
        }

        static Frame of(String name, BufferedImage image) {
            return new Frame(name, image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                    image.getWidth());
        }

        int at(int x, int y) {
            return argb[y * width + x];
        }

        int count(int value) {
            int count = 0;
            for (int pixel : argb) {
                if (pixel == value) {
                    count++;
                }
            }
            return count;
        }

        /** The count of the opaque pixels and their bounding box. */
        String describeOpaque() {
            int height = argb.length / width;
            int count = 0;
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (at(x, y) >>> 24 == 0xff) {
                        count++;
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            return count + " opaque pixels in x " + minX + ".." + maxX + ", y " + minY + ".." + maxY;
        }

        int countOpaqueAbove(int row) {
            int count = 0;
            for (int i = 0; i < row * width; i++) {
                if (argb[i] >>> 24 == 0xff) {
                    count++;
                }
            }
            return count;
        }

        String describeCornersAndCentre() {
            int height = argb.length / width;
            return describe(0, 0, width - 1, 0, width / 2, height / 2, 0, height - 1, width - 1, height - 1);
        }

        String describe(int... xy) {
            List<String> pixels = new ArrayList<>();
            for (int i = 0; i < xy.length; i += 2) {
                pixels.add("(" + xy[i] + "," + xy[i + 1] + ") = " + hex(at(xy[i], xy[i + 1])));
            }
            return name + " " + String.join(", ", pixels);
        }
    }

    /** Every sample that differs, so that a failure shows all the pixels it saw. */
    private static final class Samples {
        private final List<String> mismatches = new ArrayList<>();

        void expect(Frame frame, int expected, int... xy) {
            for (int i = 0; i < xy.length; i += 2) {
                if (frame.at(xy[i], xy[i + 1]) != expected) {
                    mismatches.add(frame.describe(xy[i], xy[i + 1]) + " where " + hex(expected) + " was expected");
                }
            }
        }

        void expectNear(Frame frame, int expected, int bound, int... xy) {
            for (int i = 0; i < xy.length; i += 2) {
                int actual = frame.at(xy[i], xy[i + 1]);
                for (int shift = 0; shift < 32; shift += 8) {
                    if (Math.abs(((actual >>> shift) & 0xff) - ((expected >>> shift) & 0xff)) > bound) {
                        mismatches.add(frame.describe(xy[i], xy[i + 1]) + " where " + hex(expected) + " +-" + bound
                                + " per channel was expected");
                        break;
                    }
                }
            }
        }

        boolean failed() {
            return !mismatches.isEmpty();
        }

        String report() {
            return String.join("; ", mismatches);
        }

        void verify(String context) {
            if (failed()) {
                throw new AssertionError(report() + context);
            }
        }
    }
}

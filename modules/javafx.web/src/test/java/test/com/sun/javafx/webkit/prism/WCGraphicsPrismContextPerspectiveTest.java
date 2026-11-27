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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.sun.javafx.geom.transform.Affine3D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.webkit.prism.PrismInvokerShim;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.RTTexture;
import com.sun.prism.ReadbackGraphics;
import com.sun.prism.RectShadowGraphics;
import com.sun.prism.Texture;
import com.sun.prism.paint.Color;
import com.sun.webkit.graphics.GraphicsDecoder;
import com.sun.webkit.graphics.WCGraphicsContext;
import com.sun.webkit.graphics.WCGraphicsManager;
import com.sun.webkit.graphics.WCRenderQueue;
import com.sun.webkit.graphics.WCTransform;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import test.javafx.scene.web.TestBase;

/**
 * What Prism receives from the web graphics context when WebKit sets a perspective transform. The
 * shader of every 3D-capable pipeline computes {@code projection x transform x perspective}, but Prism
 * adds a translate-only transform to the vertices before the perspective is applied, so the context
 * has to hand over the state transform folded into the perspective, with an identity transform.
 * <p>
 * Software and Java 2D graphics, printer graphics among them, ignore the perspective; there the context
 * must keep handing over the transform as it always did. The context tells the two kinds apart by
 * {@link RectShadowGraphics}, which exactly the shader graphics implement (through {@code BaseGraphics},
 * the class that keeps the perspective).
 * <p>
 * The context draws into a real software render target through a {@link Proxy} that records the
 * transform and perspective calls and forwards every call. Presented as {@code SWGraphics} presents
 * itself, with {@link Graphics} and {@link ReadbackGraphics}, it stands for a graphics that ignores the
 * perspective; with {@link RectShadowGraphics} added, for a shader graphics. Software Prism ignores the
 * perspective, so the recording is the only place it can be observed. Layers draw into software
 * graphics of their own, which the context returns as its platform graphics.
 */
public class WCGraphicsPrismContextPerspectiveTest extends TestBase {

    private static final double TOLERANCE = 1e-12;
    private static final List<String> TRANSFORM_CALLS =
            List.of("setTransform", "setPerspectiveTransform", "translate", "scale", "transform");

    /*
     * A WCTransform takes an id from the graphics manager, which exists only once a WebView has been
     * made, so the transforms are made in the tests, never when the class is initialized.
     */

    /** A true projection: {@code w} depends on {@code x}. */
    private static WCTransform projection() {
        return new WCTransform(1, 0, 0, 0.001, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
    }

    private static WCTransform identity() {
        return new WCTransform(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
    }

    /** Prism's device context, which holds one perspective transform for every graphics drawing through it. */
    private static final class DeviceContext {
        private GeneralTransform3D perspective = new GeneralTransform3D();
    }

    private static final class RecordingGraphics implements InvocationHandler {
        private final Graphics target;
        private final DeviceContext device;
        private final List<String> calls = new ArrayList<>();
        private final List<GeneralTransform3D> devicePerspectivesAtFills = new ArrayList<>();
        private BaseTransform transform = BaseTransform.IDENTITY_TRANSFORM;
        private GeneralTransform3D perspective = new GeneralTransform3D();
        private boolean refuseFills = false;

        private RecordingGraphics(Graphics target) {
            this(target, new DeviceContext());
        }

        private RecordingGraphics(Graphics target, DeviceContext device) {
            this.target = target;
            this.device = device;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "setTransform" -> transform = args[0] == null
                        ? BaseTransform.IDENTITY_TRANSFORM : new Affine3D((BaseTransform) args[0]);
                case "setPerspectiveTransform" -> {
                    perspective = args[0] == null
                            ? new GeneralTransform3D() : new GeneralTransform3D().set((GeneralTransform3D) args[0]);
                    device.perspective = perspective;
                }
                default -> { }
            }
            if (method.getName().startsWith("fill")) {
                devicePerspectivesAtFills.add(new GeneralTransform3D().set(device.perspective));
            }
            calls.add(method.getName());
            if (refuseFills && method.getName().startsWith("fill")) {
                throw new RefusedFill();
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** An Error, as an OutOfMemoryError would be, which a decode does not catch. */
    private static final class RefusedFill extends Error {
    }

    private record Context(WCGraphicsContext context, RecordingGraphics recording, Graphics graphics) {
    }

    private static final boolean APPLIES_PERSPECTIVE = true;
    private static final boolean IGNORES_PERSPECTIVE = false;

    @Test
    public void withoutPerspectiveTheStateTransformGoesToPrismAsItIs() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().translate(10, 20);
            assertSame(target.graphics(), target.context().getPlatformGraphics());
            assertTransform(BaseTransform.getTranslateInstance(10, 20), target.recording().transform);
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);

            target.recording().calls.clear();
            target.context().translate(1, 2);
            target.context().scale(2, 2);
            assertEquals(List.of("translate", "scale"), target.recording().calls,
                    "the cached graphics follows the state transform call by call");
        });
    }

    @Test
    public void aTranslateOnlyStateTransformIsFoldedIntoThePerspective() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().translate(30, 40);
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            assertTransform(BaseTransform.IDENTITY_TRANSFORM, target.recording().transform);
            assertPerspective(folded(BaseTransform.getTranslateInstance(30, 40)), target.recording().perspective);
        });
    }

    @Test
    public void aScalingStateTransformIsFoldedIntoThePerspective() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().scale(2, 3);
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            assertTransform(BaseTransform.IDENTITY_TRANSFORM, target.recording().transform);
            assertPerspective(folded(BaseTransform.getScaleInstance(2, 3)), target.recording().perspective);
        });
    }

    @Test
    public void aTranslateUnderAPerspectiveIsFoldedAgain() {
        Affine3D state = new Affine3D(BaseTransform.getTranslateInstance(35, 46));
        assertChangeUnderAPerspectiveIsFoldedAgain(context -> context.translate(5, 6), state);
    }

    @Test
    public void aScaleUnderAPerspectiveIsFoldedAgain() {
        Affine3D state = new Affine3D(BaseTransform.getTranslateInstance(30, 40));
        state.scale(2, 3);
        assertChangeUnderAPerspectiveIsFoldedAgain(context -> context.scale(2, 3), state);
    }

    @Test
    public void aRotationUnderAPerspectiveIsFoldedAgain() {
        Affine3D state = new Affine3D(BaseTransform.getTranslateInstance(30, 40));
        state.rotate(0.5);
        assertChangeUnderAPerspectiveIsFoldedAgain(context -> context.rotate(0.5f), state);
    }

    /**
     * Under a folded perspective the cached graphics holds the identity, so a change of the transform
     * must not reach it incrementally; it is folded again when the graphics is next asked for.
     */
    private static void assertChangeUnderAPerspectiveIsFoldedAgain(Consumer<WCGraphicsContext> change,
            BaseTransform state) {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().translate(30, 40);
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            target.recording().calls.clear();
            change.accept(target.context());
            target.context().getPlatformGraphics();
            assertEquals(List.of("setTransform", "setPerspectiveTransform"),
                    target.recording().calls.stream().filter(TRANSFORM_CALLS::contains).toList(),
                    "the transform and perspective calls after the change");
            assertTransform(BaseTransform.IDENTITY_TRANSFORM, target.graphics().getTransformNoClone());
            assertPerspective(folded(state), target.recording().perspective);
        });
    }

    /** A graphics that ignores the perspective would draw a projected quad unplaced at its origin. */
    @Test
    public void aProjectedDrawIsSkippedOnAGraphicsThatIgnoresThePerspective() {
        onRenderTarget(IGNORES_PERSPECTIVE, target -> {
            target.context().setPerspectiveTransform(projection());
            target.context().fillRect(0, 0, 50, 50, Color.RED);
            assertEquals(List.of(), drawCalls(target), "the calls that draw, under a projection");

            target.context().setPerspectiveTransform(identity());
            target.context().fillRect(0, 0, 50, 50, Color.RED);
            assertTrue(!drawCalls(target).isEmpty(), "the same fill without a projection drew nothing");
        });
    }

    @Test
    public void disposingAContextLeavesTheIdentityPerspectiveInPrism() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            assertTrue(!target.recording().perspective.isIdentity(), "the perspective reached Prism");
            target.context().dispose();
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);
        });
    }

    /** A decode that an Error ends inside a perspective sequence must still end with a flush. */
    @Test
    public void aDecodeThatFailsLeavesTheIdentityPerspectiveInPrism() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            WCRenderQueue queue = new WCRenderQueue(target.context()) {
                @Override
                protected void flush() {
                }

                @Override
                protected void disposeGraphics() {
                }
            };
            ByteBuffer commands = ByteBuffer.allocate(4 + 16 * 4 + 4 + 4 * 4).order(ByteOrder.nativeOrder());
            commands.putInt(GraphicsDecoder.SET_PERSPECTIVE_TRANSFORM);
            for (float value : new float[] {1, 0, 0, 0.001f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}) {
                commands.putFloat(value);
            }
            commands.putInt(GraphicsDecoder.FILLRECT_FFFF);
            for (float value : new float[] {0, 0, 50, 50}) {
                commands.putFloat(value);
            }
            commands.flip();
            queue.addBuffer(commands);
            target.recording().refuseFills = true;
            assertThrows(RefusedFill.class, queue::decode, "the fill was not refused");
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);
        });
    }

    /**
     * The decoder asks for the context's graphics at the start of every command buffer, which folds the
     * perspective into Prism's device context. A tile's queue decoded inline by DECODERQ then gives the
     * same device context its own perspective, so the context must fold its own again before it draws.
     */
    @Test
    public void aNestedDecodeDoesNotLeaveItsPerspectiveForTheNextDraw() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            Fills fills = decodeWithATile(target, FRESH_TILE, List.of(
                    (buffer, tile) -> putProjection(buffer),
                    (buffer, tile) -> {
                        putDecodeTile(buffer, tile);
                        putFillRect(buffer);
                    }));
            assertEquals(1, fills.tile().size(), "fills of the tile's queue");
            assertEquals(1, fills.outer().size(), "fills of the outer queue");
            assertPerspective(foldedCommandProjection(), fills.outer().get(0));
        });
    }

    /**
     * Restoring the state drops a folded perspective from the context, but not from Prism's device context,
     * so a tile's queue decoded next must set its own there before it draws, though the tile's context has
     * decoded before.
     */
    @Test
    public void aNestedDecodeAfterARestoredPerspectiveDrawsWithItsOwn() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            Fills fills = decodeWithATile(target, WARM_TILE, List.of((buffer, tile) -> {
                buffer.putInt(GraphicsDecoder.SAVESTATE);
                putProjection(buffer);
                putFillRect(buffer);
                buffer.putInt(GraphicsDecoder.RESTORESTATE);
                putDecodeTile(buffer, tile);
            }));
            assertEquals(1, fills.outer().size(), "fills of the outer queue");
            assertPerspective(foldedCommandProjection(), fills.outer().get(0));
            assertEquals(1, fills.tile().size(), "fills of the tile's queue");
            assertPerspective(new GeneralTransform3D(), fills.tile().get(0));
        });
    }

    /** The outer context folds its perspective at the start of the buffer, before the tile is decoded. */
    @Test
    public void aNestedDecodeAtTheStartOfABufferDrawsWithItsOwnPerspective() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            Fills fills = decodeWithATile(target, WARM_TILE, List.of(
                    (buffer, tile) -> putProjection(buffer),
                    (buffer, tile) -> {
                        putDecodeTile(buffer, tile);
                        putFillRect(buffer);
                    }));
            assertEquals(1, fills.tile().size(), "fills of the tile's queue");
            assertPerspective(new GeneralTransform3D(), fills.tile().get(0));
            assertEquals(1, fills.outer().size(), "fills of the outer queue");
            assertPerspective(foldedCommandProjection(), fills.outer().get(0));
        });
    }

    private static final float[] COMMAND_PROJECTION = {1, 0, 0, 0.0009765625f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    private static final boolean WARM_TILE = true;
    private static final boolean FRESH_TILE = false;

    /** The perspectives in Prism's device context at the fills of the tile's queue and of the outer queue. */
    private record Fills(List<GeneralTransform3D> tile, List<GeneralTransform3D> outer) {
    }

    /**
     * Decodes the buffers into the target translated by (30, 40), with a tile's queue of one fill that they
     * name by its ID. The tile's context draws through the target's device context; a warm tile's context has
     * decoded a queue of an earlier frame.
     */
    private static Fills decodeWithATile(Context target, boolean warm, List<ObjIntConsumer<ByteBuffer>> buffers) {
        RTTexture tileTexture = GraphicsPipeline.getDefaultResourceFactory()
                .createRTTexture(100, 100, Texture.WrapMode.CLAMP_NOT_NEEDED);
        RecordingGraphics tileRecording = new RecordingGraphics(tileTexture.createGraphics(),
                target.recording().device);
        WCGraphicsContext tileContext = WCGraphicsManager.getGraphicsManager()
                .createGraphicsContext(present(tileRecording, APPLIES_PERSPECTIVE));
        WCRenderQueue tileQueue = new HeapBufferQueue(tileContext);
        tileQueue.ref();
        try {
            if (warm) {
                WCRenderQueue earlierFrame = new HeapBufferQueue(tileContext);
                earlierFrame.addBuffer(commands(WCGraphicsPrismContextPerspectiveTest::putFillRect));
                earlierFrame.decode();
                tileRecording.devicePerspectivesAtFills.clear();
            }
            tileQueue.addBuffer(commands(WCGraphicsPrismContextPerspectiveTest::putFillRect));
            WCRenderQueue queue = new HeapBufferQueue(target.context());
            for (ObjIntConsumer<ByteBuffer> buffer : buffers) {
                queue.addBuffer(commands(writer -> buffer.accept(writer, tileQueue.getID())));
            }
            target.context().translate(30, 40);
            queue.decode();
        } finally {
            tileQueue.deref();
            tileContext.dispose();
            tileTexture.dispose();
        }
        return new Fills(List.copyOf(tileRecording.devicePerspectivesAtFills),
                List.copyOf(target.recording().devicePerspectivesAtFills));
    }

    private static GeneralTransform3D foldedCommandProjection() {
        return new GeneralTransform3D()
                .mul(BaseTransform.getTranslateInstance(30, 40))
                .mul(new GeneralTransform3D().set(perspective(COMMAND_PROJECTION).getMatrix()));
    }

    private static void putProjection(ByteBuffer buffer) {
        buffer.putInt(GraphicsDecoder.SET_PERSPECTIVE_TRANSFORM);
        for (float value : COMMAND_PROJECTION) {
            buffer.putFloat(value);
        }
    }

    private static void putDecodeTile(ByteBuffer buffer, int tileQueueId) {
        buffer.putInt(GraphicsDecoder.DECODERQ);
        buffer.putInt(tileQueueId);
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

    private static void putFillRect(ByteBuffer buffer) {
        buffer.putInt(GraphicsDecoder.FILLRECT_FFFF);
        for (float value : new float[] {0, 0, 50, 50}) {
            buffer.putFloat(value);
        }
    }

    private static WCTransform perspective(float[] m) {
        return new WCTransform(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]);
    }

    private static List<String> drawCalls(Context target) {
        return target.recording().calls.stream()
                .filter(call -> call.startsWith("fill") || call.startsWith("draw")).toList();
    }

    /**
     * A layer's state transform is the translation to the layer's origin. Here the layer's graphics are
     * software graphics, which ignore the perspective, so the transform must reach them unchanged under a
     * perspective too. Clip layers and transparency layers are opened the same way; a transparency layer
     * needs no path.
     */
    @Test
    public void insideALayerOfSoftwareGraphicsTheLayerOffsetStaysTheTransform() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().setClip(30, 40, 200, 100);
            target.context().beginTransparencyLayer(1f);
            Graphics layer = (Graphics) target.context().getPlatformGraphics();
            assumeFalse(layer instanceof RectShadowGraphics, "on a GPU pipeline a layer's graphics are shader"
                    + " graphics, where the context folds; the GPU offscreen suite covers the fold inside layers");
            assertNotSame(target.graphics(), layer, "the layer's own graphics");
            assertTransform(BaseTransform.getTranslateInstance(-30, -40), layer.getTransformNoClone());

            target.context().saveState();
            target.context().setPerspectiveTransform(projection());
            assertSame(layer, target.context().getPlatformGraphics());
            assertTransform(BaseTransform.getTranslateInstance(-30, -40), layer.getTransformNoClone());
            target.context().restoreState();

            target.context().getPlatformGraphics();
            assertTransform(BaseTransform.getTranslateInstance(-30, -40), layer.getTransformNoClone());
            target.context().endTransparencyLayer();
        });
    }

    /**
     * Prism keeps the perspective in the device context that every graphics shares, and restoring the
     * web state only drops the cached graphics; a decode ends with a flush, which must leave the
     * identity there.
     */
    @Test
    public void aDecodeEndsWithTheIdentityPerspectiveInPrism() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().saveState();
            target.context().translate(30, 40);
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            assertTrue(!target.recording().perspective.isIdentity(), "the perspective reached Prism");
            target.context().restoreState();
            target.context().flush();
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);
        });
    }

    @Test
    public void aDecodeThatEndsInsideAPerspectiveSequenceStillLeavesTheIdentity() {
        onRenderTarget(APPLIES_PERSPECTIVE, target -> {
            target.context().saveState();
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            target.context().flush();
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);

            target.context().getPlatformGraphics();
            assertPerspective(folded(BaseTransform.IDENTITY_TRANSFORM), target.recording().perspective);
            target.context().setPerspectiveTransform(identity());
            target.context().restoreState();
            target.context().flush();
            assertPerspective(new GeneralTransform3D(), target.recording().perspective);
        });
    }

    /**
     * The calls a graphics that ignores the perspective received before the fold existed: the state
     * transform and the perspective as they are, incremental updates of the cached graphics, and nothing
     * from the flush that ends a decode.
     */
    @Test
    public void aGraphicsThatIgnoresThePerspectiveGetsTheTransformAndPerspectiveAsBefore() {
        onRenderTarget(IGNORES_PERSPECTIVE, target -> {
            target.context().translate(30, 40);
            target.context().setPerspectiveTransform(projection());
            target.context().getPlatformGraphics();
            assertTransform(BaseTransform.getTranslateInstance(30, 40), target.recording().transform);
            assertPerspective(new GeneralTransform3D().set(projection().getMatrix()), target.recording().perspective);

            target.context().translate(5, 6);
            target.context().scale(2, 2);
            target.context().rotate(0.5f);
            target.context().flush();
            assertEquals(List.of("setTransform", "setPerspectiveTransform", "translate", "scale", "setTransform"),
                    target.recording().calls.stream().filter(TRANSFORM_CALLS::contains).toList(),
                    "the transform and perspective calls, in order");
            Affine3D state = new Affine3D(BaseTransform.getTranslateInstance(35, 46));
            state.scale(2, 2);
            state.rotate(0.5);
            assertTransform(state, target.recording().transform);
        });
    }

    private static GeneralTransform3D folded(BaseTransform state) {
        GeneralTransform3D projection = new GeneralTransform3D().set(projection().getMatrix());
        return new GeneralTransform3D().mul(state).mul(projection);
    }

    private static void assertTransform(BaseTransform expected, BaseTransform actual) {
        GeneralTransform3D expected3D = new GeneralTransform3D().mul(expected);
        GeneralTransform3D actual3D = new GeneralTransform3D().mul(actual);
        assertArrayEquals(expected3D.get((double[]) null), actual3D.get((double[]) null), TOLERANCE,
                "the transform Prism received");
    }

    private static void assertPerspective(GeneralTransform3D expected, GeneralTransform3D actual) {
        assertArrayEquals(expected.get((double[]) null), actual.get((double[]) null), TOLERANCE,
                "the perspective transform Prism received");
    }

    /**
     * Runs the check on the render thread against a fresh 400 x 300 software render target, presented as
     * a shader graphics or as a graphics that ignores the perspective.
     */
    private static void onRenderTarget(boolean appliesPerspective, Consumer<Context> check) {
        Throwable[] failure = new Throwable[1];
        boolean[] ran = new boolean[1];
        PrismInvokerShim.runOnRenderThread(() -> {
            RTTexture texture = GraphicsPipeline.getDefaultResourceFactory()
                    .createRTTexture(400, 300, Texture.WrapMode.CLAMP_NOT_NEEDED);
            WCGraphicsContext context = null;
            try {
                RecordingGraphics recording = new RecordingGraphics(texture.createGraphics());
                Graphics recorded = present(recording, appliesPerspective);
                context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(recorded);
                check.accept(new Context(context, recording, recorded));
                ran[0] = true;
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                if (context != null) {
                    context.dispose();
                }
                texture.dispose();
            }
        });
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] instanceof TestAbortedException aborted) {
            throw aborted;
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        assertTrue(ran[0], "the check did not run on the render thread");
    }

    private static Graphics present(RecordingGraphics recording, boolean appliesPerspective) {
        Class<?>[] kinds = appliesPerspective
                ? new Class<?>[] {Graphics.class, ReadbackGraphics.class, RectShadowGraphics.class}
                : new Class<?>[] {Graphics.class, ReadbackGraphics.class};
        return (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(), kinds, recording);
    }
}

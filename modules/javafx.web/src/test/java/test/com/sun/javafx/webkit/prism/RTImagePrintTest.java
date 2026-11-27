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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.glass.ui.Screen;
import com.sun.javafx.webkit.prism.RTImageShim;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.j2d.J2DPipeline;
import com.sun.prism.j2d.PrismPrintGraphics;
import com.sun.prism.paint.Color;
import com.sun.webkit.graphics.WCGraphicsContext;
import com.sun.webkit.graphics.WCGraphicsManager;
import com.sun.webkit.graphics.WCImage;
import com.sun.webkit.graphics.WCPath;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import test.javafx.scene.web.TestBase;

/**
 * A printer's Java 2D graphics cannot draw a render texture of the screen pipeline, nor can the graphics
 * of a clip layer opened on a print context, so an image is read back and drawn as a texture of the
 * printer's own. The render texture of an image has the image's pixel scale, the screen's highest in
 * production, so it is larger than the image wherever that scale is not 1. The prints run on the test
 * thread, as a printer job's do on a thread of its own.
 */
public class RTImagePrintTest extends TestBase {

    private static final int WIDTH = 40;
    private static final int HEIGHT = 20;
    private static final int OPAQUE_RED = 0xffff0000;
    private static final int OPAQUE_BLUE = 0xff0000ff;

    @Test
    public void anImageOfPixelScaleTwoPrintsAllItsPhysicalPixels() {
        WCImage image = redAndBlueHalves(2);
        try {
            BufferedImage printed = print(g -> RTImageShim.draw(image, g, 0, 0, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT));
            assertEquals(OPAQUE_RED, printed.getRGB(10, 10), "the left half");
            assertEquals(OPAQUE_BLUE, printed.getRGB(30, 10), "the right half");
        } finally {
            RTImageShim.dispose(image);
        }
    }

    @Test
    public void aPartOfAnImagePrintsThatPart() {
        WCImage image = redAndBlueHalves(1);
        try {
            BufferedImage printed = print(g -> RTImageShim.draw(image, g, 0, 0, 20, 20, 20, 0, WIDTH, HEIGHT));
            assertEquals(OPAQUE_BLUE, printed.getRGB(10, 10), "the right half drawn on its own");
        } finally {
            RTImageShim.dispose(image);
        }
    }

    @Test
    public void anImageDrawnIntoAClipLayerOfAPrintContextPrintsInsideTheClip() {
        WCImage image = RTImageShim.create(WIDTH, HEIGHT, 1);
        try {
            RTImageShim.fillPhysical(image, 0, 0, WIDTH, HEIGHT, Color.RED);
            BufferedImage printed = print(g -> {
                WCGraphicsContext context = WCGraphicsManager.getGraphicsManager().createGraphicsContext(g);
                try {
                    context.saveState();
                    WCPath clip = RTImageShim.createPath();
                    clip.addEllipse(0, 0, WIDTH, HEIGHT);
                    context.setClip(clip, false);
                    context.drawImage(image, 0, 0, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT);
                    context.restoreState();
                    context.flush();
                } finally {
                    context.dispose();
                }
            });
            assertEquals(OPAQUE_RED, printed.getRGB(20, 10), "the middle of the clip");
            assertEquals(0, printed.getRGB(0, 0), "a corner outside the clip");
        } finally {
            RTImageShim.dispose(image);
        }
    }

    /**
     * Other Java 2D graphics cannot draw a render texture of another pipeline either. A print context
     * never meets them, because its clip layers draw through the printer's own resource factory, whose
     * graphics are printer graphics; the Java 2D pipeline's graphics stand in for them.
     */
    @Test
    public void anImageDrawnIntoJava2DGraphicsThatAreNotAPrintersIsCopied() {
        WCImage image = redAndBlueHalves(1);
        ResourceFactory java2D = J2DPipeline.getInstance().getResourceFactory(Screen.getMainScreen());
        RTTexture target = java2D.createRTTexture(WIDTH, HEIGHT, Texture.WrapMode.CLAMP_NOT_NEEDED);
        try {
            RTImageShim.draw(image, target.createGraphics(), 0, 0, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT);
            int[] pixels = target.getPixels();
            assertEquals(OPAQUE_RED, pixels[10 * target.getPhysicalWidth() + 10], "the left half");
            assertEquals(OPAQUE_BLUE, pixels[10 * target.getPhysicalWidth() + 30], "the right half");
        } finally {
            target.dispose();
            RTImageShim.dispose(image);
        }
    }

    /**
     * Metal reads a render texture only into a direct buffer and has no read of a part of one, and every
     * pipeline reads the whole content, so the buffer holds the whole content in native-order ints. Metal's
     * read does not flush the draws Prism still holds for the texture, so a graphics of the texture syncs
     * them first.
     */
    @Test
    public void theReadBackGivesTheTextureADirectBufferOfItsWholeContent() {
        List<Buffer> given = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        Graphics graphics = (Graphics) Proxy.newProxyInstance(Graphics.class.getClassLoader(),
                new Class<?>[] {Graphics.class}, (proxy, method, args) -> {
                    calls.add("graphics." + method.getName() + "/" + (args == null ? 0 : args.length));
                    if (!method.getName().equals("sync")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
        RTTexture texture = (RTTexture) Proxy.newProxyInstance(RTTexture.class.getClassLoader(),
                new Class<?>[] {RTTexture.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + "/" + (args == null ? 0 : args.length));
                    return switch (method.getName()) {
                        case "getContentWidth" -> 7;
                        case "getContentHeight" -> 3;
                        case "createGraphics" -> graphics;
                        case "readPixels" -> {
                            given.add((Buffer) args[0]);
                            yield true;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        IntBuffer content = RTImageShim.readContent(texture);
        assertEquals(List.of((Buffer) content), given, "the buffers given to the texture");
        assertTrue(content.isDirect(), "the buffer is direct");
        assertEquals(ByteOrder.nativeOrder(), content.order(), "the buffer's byte order");
        assertEquals(7 * 3, content.capacity(), "the buffer's capacity");
        assertEquals(List.of("createGraphics/0", "graphics.sync/0", "readPixels/1"),
                calls.stream().filter(call -> !call.startsWith("getContent")).toList(),
                "the calls that flush and read the texture, in order");
    }

    /** An image whose physical left half is red and right half blue. */
    private static WCImage redAndBlueHalves(float pixelScale) {
        WCImage image = RTImageShim.create(WIDTH, HEIGHT, pixelScale);
        int physicalWidth = (int) Math.ceil(WIDTH * pixelScale);
        int physicalHeight = (int) Math.ceil(HEIGHT * pixelScale);
        RTImageShim.fillPhysical(image, 0, 0, physicalWidth / 2, physicalHeight, Color.RED);
        RTImageShim.fillPhysical(image, physicalWidth / 2, 0, physicalWidth / 2, physicalHeight, Color.BLUE);
        return image;
    }

    private static BufferedImage print(Consumer<Graphics> drawing) {
        BufferedImage page = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = page.createGraphics();
        try {
            drawing.accept(new PrismPrintGraphics(g2d, WIDTH, HEIGHT));
        } finally {
            g2d.dispose();
        }
        return page;
    }
}

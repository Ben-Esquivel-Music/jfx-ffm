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

package test.com.sun.prism.mtl;

import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.PhongMaterial;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.mtl.MTLGoldenShim;
import com.sun.prism.mtl.MTLObjcShim;
import com.sun.prism.ps.Shader;
import java.util.ArrayList;
import java.util.List;
import test.com.sun.prism.mtl.MTLGoldenChild.Session;
import test.com.sun.prism.mtl.MTLGoldenFormat.GoldenMap;

/**
 * The {@value MTLGoldens#OBJC_RELEASE} child of {@link MTLObjcReleaseTest}: one object of each kind the five former
 * release natives freed, created and disposed through the Prism SPI, so that each reaches
 * {@code MTLNative.objcRelease} from its production caller. Per kind, under {@code objcRelease.<kind>.}:
 * <ul>
 * <li>{@code class}: the Objective-C class of the handle;</li>
 * <li>{@code sentinel}: the retain count of the object's sentinel before and after the dispose ({@link MTLObjcShim}):
 *     {@code 2->1} once the object was deallocated, {@code 2->2} while it lives on;</li>
 * <li>{@code <owned>.retainCount}: how the dispose changed the retain count of an object the handle owns, which
 *     this child retains first so that the count can be read afterwards;</li>
 * <li>{@code <owned>.cleared}: whether the ivar that held it is nil afterwards, read only if the handle lives on.</li>
 * </ul>
 */
final class MTLCorpusObjcRelease {

    static final String PREFIX = MTLGoldens.OBJC_RELEASE + ".";

    /** A prism shader the child creates by name; nothing else in this child creates it. */
    static final String SHADER = "Solid_Color";

    private static final int LIGHT_SLOTS = 3;

    private MTLCorpusObjcRelease() {
    }

    /** One element of an instance variable that holds a retained object, and the key it is reported under. */
    private record Owned(String ivar, int index, String key) {
    }

    static void roundTrips(Session session, GoldenMap out) {
        ResourceFactory factory = session.factory();

        Texture texture = factory.createTexture(PixelFormat.INT_ARGB_PRE, Texture.Usage.DEFAULT,
                Texture.WrapMode.CLAMP_TO_EDGE, 8, 8);
        roundTrip(out, "texture", MTLObjcShim.textureHandle(texture), List.of(new Owned("texture", 0, "texture")),
                texture::dispose);

        RTTexture target = factory.createRTTexture(8, 8, Texture.WrapMode.CLAMP_NOT_NEEDED);
        roundTrip(out, "renderTarget", MTLObjcShim.textureHandle(target),
                List.of(new Owned("texture", 0, "texture")), target::dispose);

        PhongMaterial material = factory.createPhongMaterial();
        roundTrip(out, "phongMaterial", MTLObjcShim.materialHandle(material), List.of(), material::dispose);

        if (MTLGoldenShim.createdShaderNames().contains(SHADER)) {
            throw new IllegalStateException(SHADER + " already exists in this child, so it would not be created");
        }
        Shader shader = MTLGoldenShim.createShader(factory, SHADER);
        roundTrip(out, "shader", MTLObjcShim.shaderHandle(shader), List.of(), shader::dispose);

        Mesh mesh = MTLCorpus3D.boxMesh(factory, false, 14);
        roundTrip(out, "mesh", MTLObjcShim.meshHandle(mesh),
                List.of(new Owned("vertexBuffer", 0, "vertexBuffer"), new Owned("indexBuffer", 0, "indexBuffer")),
                mesh::dispose);

        Mesh viewMesh = MTLCorpus3D.boxMesh(factory, false, 14);
        MeshView view = factory.createMeshView(viewMesh);
        List<Owned> lights = new ArrayList<>();
        for (int index = 0; index < LIGHT_SLOTS; index++) {
            view.setLight(index, 20, 10, -40, 1, 1, 1, 1, 1, 0, 0, 1, 500, 0, 0, 1, 0, 0, 0);
            lights.add(new Owned("lights", index, "light" + index));
        }
        roundTrip(out, "meshView", MTLObjcShim.meshViewHandle(view), lights, view::dispose);
        viewMesh.dispose();
    }

    private static void roundTrip(GoldenMap out, String kind, long handle, List<Owned> owned, Runnable dispose) {
        String prefix = PREFIX + kind + ".";
        out.put(prefix + "class", MTLObjcShim.className(handle));
        long[] objects = new long[owned.size()];
        long[] countsBefore = new long[owned.size()];
        for (int i = 0; i < objects.length; i++) {
            Owned element = owned.get(i);
            objects[i] = MTLObjcShim.ivarPointer(handle, element.ivar(), element.index());
            if (objects[i] == 0) {
                throw new IllegalStateException(kind + " holds nothing in " + element.ivar() + "[" + element.index()
                        + "]");
            }
            MTLObjcShim.retain(objects[i]);
            countsBefore[i] = MTLObjcShim.retainCount(objects[i]);
        }
        long sentinel = MTLObjcShim.attachSentinel(handle);
        long sentinelBefore = MTLObjcShim.retainCount(sentinel);
        dispose.run();
        long sentinelAfter = MTLObjcShim.retainCount(sentinel);
        out.put(prefix + "sentinel", sentinelBefore + "->" + sentinelAfter);
        boolean handleLives = sentinelAfter > 1;
        for (int i = 0; i < objects.length; i++) {
            Owned element = owned.get(i);
            out.put(prefix + element.key() + ".retainCount",
                    Long.toString(MTLObjcShim.retainCount(objects[i]) - countsBefore[i]));
            if (handleLives) {
                out.put(prefix + element.key() + ".cleared",
                        Boolean.toString(MTLObjcShim.ivarPointer(handle, element.ivar(), element.index()) == 0));
            }
            MTLObjcShim.release(objects[i]);
        }
        MTLObjcShim.release(sentinel);
    }
}

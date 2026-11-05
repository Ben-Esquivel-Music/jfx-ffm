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

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Vec3d;
import com.sun.javafx.geom.transform.Affine3D;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.sg.prism.NGPerspectiveCamera;
import com.sun.javafx.sg.prism.NGPointLight;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.Image;
import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.PhongMaterial;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.TextureMap;
import com.sun.prism.paint.Color;
import com.sun.prism.mtl.MTLGoldenShim;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javafx.geometry.Point3D;
import test.com.sun.prism.mtl.MTLGoldenChild.Session;
import test.com.sun.prism.mtl.MTLGoldenChild.Steps;
import test.com.sun.prism.mtl.MTLGoldenFormat.GoldenMap;

/**
 * The 3D corpus, through the Prism SPI: meshes, Phong materials, mesh views and lights set up exactly as
 * {@code NGShape3D.renderMeshView} and {@code setupLights} do, all three light slots always written (L14). It mirrors
 * them instead of rendering an {@code NGMeshView}, because {@code NGShape3D.renderContent} asks
 * {@code Platform.isSupported(SCENE3D)}, which goes through the JavaFX toolkit. Also the teardown of the
 * {@code lifecycle} profile.
 */
final class MTLCorpus3D {

    static final String LIFECYCLE_PROGRESS = "lifecycle-progress.txt";

    private static final int FRAME = 64;
    private static final Image WHITE_1X1 = Image.fromIntArgbPreData(new int[] {0xffffffff}, 1, 1);
    private static final Image DIFFUSE = MTLCorpus2D.image(16, 16, (x, y) -> 0xff000000 | (x * 15) << 16
            | (y * 15) << 8 | ((x ^ y) * 15));
    private static final Image SPECULAR = MTLCorpus2D.image(8, 8, (x, y) -> 0xff000000 | (x * 30) << 16
            | (255 - y * 30) << 8 | 128);
    private static final Image BUMP = MTLCorpus2D.image(16, 16, (x, y) -> 0xff000000 | (128 + (x - 8) * 8) << 16
            | (128 + (y - 8) * 8) << 8 | 230);
    private static final Image SELF_ILLUM = MTLCorpus2D.image(8, 8, (x, y) -> (x + y) % 3 == 0 ? 0xff402010
            : 0x00000000);

    private MTLCorpus3D() {
    }

    static void register(Steps steps) {
        if (!steps.in(MTLGoldens.DEFAULT)) {
            return;
        }
        Light point = point(18, 6, -40, 1f, 0.85f, 0.7f, 1f, 0.004f, 0.0002f, 300);
        Light spot = spot(50, 50, -45, 0.6f, 0.9f, 1f, 0.9f, 0.002f, 0.0001f, 250, -0.4f, -0.5f, 0.77f, 12, 38,
                1.4f);
        Light directional = directional(0.9f, 0.6f, 0.4f, 0.3f, 0.5f, 0.81f);
        add(steps, "mesh.box.defaultLight", Scene.box());
        add(steps, "mesh.box.point", Scene.box().lights(point));
        add(steps, "mesh.box.pointSpot", Scene.box().lights(point, spot));
        add(steps, "mesh.box.threeLights", Scene.box().ambient(0.15f, 0.1f, 0.05f).lights(point, spot, directional));
        add(steps, "mesh.box.distinctLightArguments", Scene.box().lights(new Light(new float[] {
            21.5f, 9.25f, -37.75f, 0.93f, 0.71f, 0.52f, 1f, 0.97f, 0.0031f, 0.00017f, 1f, 280.5f,
            0.23f, 0.51f, 0.83f, 11.5f, 41.25f, 1.7f})));
        add(steps, "mesh.box.ambientOnly", Scene.box().ambient(0.6f, 0.45f, 0.3f).lights());
        add(steps, "mesh.box.directional", Scene.box().lights(directional));
        add(steps, "mesh.box.attenuationRange", Scene.box().lights(point(32, 32, -20, 1f, 1f, 0.8f, 0.2f, 0.03f,
                0.0015f, 52)));
        add(steps, "mesh.box.spotFalloff", Scene.box().lights(spot(32, 10, -50, 1f, 0.9f, 0.6f, 1f, 0, 0, 400,
                0, 0.35f, 0.94f, 5, 20, 3.5f)));
        add(steps, "mesh.specular.texture", Scene.box().lights(point).specular(false, SPECULAR));
        add(steps, "mesh.specular.color", Scene.box().lights(point).specular(true, null));
        add(steps, "mesh.specular.mix", Scene.box().lights(point).specular(true, SPECULAR));
        add(steps, "mesh.map.diffuse", Scene.box().lights(point).diffuseMap(DIFFUSE));
        add(steps, "mesh.map.bump", Scene.box().lights(point).bumpMap(BUMP));
        add(steps, "mesh.map.selfIllum", Scene.box().lights(point).selfIllumMap(SELF_ILLUM));
        add(steps, "mesh.translucentDiffuse", Scene.box().lights(point).diffuse(0.3f, 0.8f, 0.5f, 0.55f));
        add(steps, "mesh.cull.none", Scene.box().lights(point).cull(MeshView.CULL_NONE).open());
        add(steps, "mesh.cull.back", Scene.box().lights(point).cull(MeshView.CULL_BACK).open());
        add(steps, "mesh.cull.front", Scene.box().lights(point).cull(MeshView.CULL_FRONT).open());
        add(steps, "mesh.cull.mirrored", Scene.box().lights(point).cull(MeshView.CULL_BACK).mirrored());
        // The box is symmetric under the mirror, and NGShape3D swaps the culling for it: the same frame.
        steps.designedEqual("mesh.box.point", "mesh.cull.mirrored");
        add(steps, "mesh.wireframe", Scene.box().lights(point).wireframe());
        add(steps, "mesh.twoIntersecting", Scene.box().lights(point, directional).twice());
        add(steps, "mesh.grid.intIndices", Scene.grid().lights(point, spot));
        add(steps, "mesh.grid.overIndexLimit", Scene.grid().lights(point).repeat(24));
        add(steps, "mesh.pixelScale2", Scene.box().lights(point).pixelScale());
        add(steps, "mesh.perspectiveCamera", Scene.box().lights(point, spot).perspective());
        add(steps, "mesh.msaa", Scene.box().lights(point, directional).msaa());
    }

    private static void add(Steps steps, String key, Scene scene) {
        steps.add(key, FRAME, FRAME, s -> scene.render(s, null));
    }

    /**
     * The box under {@code clip}, for the clip group. Metal draws the mesh unclipped, so a 2D fill after it shows that
     * the clip is still in force.
     */
    static int[] clippedMesh(Session s, Rectangle clip) throws Exception {
        Scene scene = Scene.box().lights(point(18, 6, -40, 1f, 0.85f, 0.7f, 1f, 0.004f, 0.0002f, 300));
        scene.overlay = true;
        return scene.render(s, clip);
    }

    // ---------------------------------------------------------------------------------------------
    // Lights, as NGShape3D passes them to MeshView.setLight
    // ---------------------------------------------------------------------------------------------

    /** The 18 arguments of {@code MeshView.setLight} after the index. */
    record Light(float[] arguments) {
    }

    static Light point(float x, float y, float z, float r, float g, float b, float ca, float la, float qa,
                       float maxRange) {
        Point3D direction = NGPointLight.getSimulatedDirection();
        return new Light(new float[] {x, y, z, r, g, b, 1f, ca, la, qa, 1, maxRange,
            (float) direction.getX(), (float) direction.getY(), (float) direction.getZ(),
            NGPointLight.getSimulatedInnerAngle(), NGPointLight.getSimulatedOuterAngle(),
            NGPointLight.getSimulatedFalloff()});
    }

    static Light spot(float x, float y, float z, float r, float g, float b, float ca, float la, float qa,
                      float maxRange, float dx, float dy, float dz, float inner, float outer, float falloff) {
        return new Light(new float[] {x, y, z, r, g, b, 1f, ca, la, qa, 1, maxRange, dx, dy, dz, inner, outer,
            falloff});
    }

    static Light directional(float r, float g, float b, float dx, float dy, float dz) {
        return new Light(new float[] {0, 0, 0, r, g, b, 1f, 1, 0, 0, 0, Float.POSITIVE_INFINITY, dx, dy, dz,
            0, 0, 0});
    }

    // ---------------------------------------------------------------------------------------------
    // Scenes
    // ---------------------------------------------------------------------------------------------

    /** One 3D frame: geometry, material, lights and how it is drawn. */
    private static final class Scene {
        private final boolean grid;
        private float[] ambient;
        private List<Light> lights;
        private float[] diffuse = {0.85f, 0.6f, 0.35f, 1f};
        private boolean specularSet;
        private Image specularMap;
        private Image diffuseMap;
        private Image bumpMap;
        private Image selfIllumMap;
        private int cull = MeshView.CULL_BACK;
        private boolean wireframe;
        private boolean open;
        private boolean mirrored;
        private boolean twice;
        private int repeat = 1;
        private boolean pixelScale;
        private boolean perspective;
        private boolean msaa;
        private boolean overlay;

        private Scene(boolean grid) {
            this.grid = grid;
        }

        static Scene box() {
            return new Scene(false);
        }

        static Scene grid() {
            return new Scene(true);
        }

        Scene ambient(float r, float g, float b) {
            ambient = new float[] {r, g, b};
            return this;
        }

        Scene lights(Light... values) {
            lights = List.of(values);
            return this;
        }

        Scene diffuse(float r, float g, float b, float a) {
            diffuse = new float[] {r, g, b, a};
            return this;
        }

        Scene specular(boolean set, Image map) {
            specularSet = set;
            specularMap = map;
            return this;
        }

        Scene diffuseMap(Image map) {
            diffuseMap = map;
            return this;
        }

        Scene bumpMap(Image map) {
            bumpMap = map;
            return this;
        }

        Scene selfIllumMap(Image map) {
            selfIllumMap = map;
            return this;
        }

        Scene cull(int mode) {
            cull = mode;
            return this;
        }

        Scene wireframe() {
            wireframe = true;
            return this;
        }

        Scene open() {
            open = true;
            return this;
        }

        Scene mirrored() {
            mirrored = true;
            return this;
        }

        Scene twice() {
            twice = true;
            return this;
        }

        Scene repeat(int times) {
            repeat = times;
            return this;
        }

        Scene pixelScale() {
            pixelScale = true;
            return this;
        }

        Scene perspective() {
            perspective = true;
            return this;
        }

        Scene msaa() {
            msaa = true;
            return this;
        }

        int[] render(Session s, Rectangle clip) throws Exception {
            ResourceFactory factory = s.factory();
            Mesh mesh = grid ? gridMesh(factory, 27) : boxMesh(factory, open, 15.5f);
            PhongMaterial material = material(factory);
            List<MeshView> views = new ArrayList<>();
            try {
                return s.renderUncleared(FRAME, FRAME, msaa, g -> {
                    MTLCorpus2D.depthClear(g, true);
                    if (clip != null) {
                        g.setClipRect(new Rectangle(clip));
                    }
                    if (pixelScale) {
                        g.setPixelScaleFactors(2, 2);
                    }
                    if (perspective) {
                        g.setCamera(camera());
                    }
                    for (int copy = 0; copy < (twice ? 2 : repeat); copy++) {
                        g.setTransform(placement(copy));
                        MeshView view = factory.createMeshView(mesh);
                        views.add(view);
                        draw(g, view, material);
                    }
                    if (overlay) {
                        g.setTransform(null);
                        g.setPaint(new Color(0.95f, 0.9f, 0.2f, 0.45f));
                        g.fillRect(0, 0, FRAME, FRAME);
                    }
                });
            } finally {
                for (MeshView view : views) {
                    view.dispose();
                }
                material.dispose();
                mesh.dispose();
            }
        }

        private Affine3D placement(int copy) {
            Affine3D placement = new Affine3D();
            if (pixelScale) {
                placement.scale(2, 2);
            }
            placement.translate(31.5 + copy * (twice ? 9 : 0.25), 32.25 - copy * (twice ? 6 : 0.125), copy * 3);
            placement.rotate(0.65 + copy * 0.8, 1, 0.35, 0.2);
            if (mirrored) {
                placement.scale(-1, 1, 1);
            }
            return placement;
        }

        /** {@code NGShape3D.renderMeshView}: material, culling (swapped under a mirroring transform), lights. */
        private void draw(Graphics g, MeshView view, PhongMaterial material) {
            g.setup3DRendering();
            view.setMaterial(material);
            int cullingMode = cull;
            if (cull != MeshView.CULL_NONE && g.getTransformNoClone().getDeterminant() < 0) {
                cullingMode = cullingMode == MeshView.CULL_BACK ? MeshView.CULL_FRONT : MeshView.CULL_BACK;
            }
            view.setCullingMode(cullingMode);
            view.setWireframe(wireframe);
            int index = 0;
            if (lights == null && ambient == null) {
                view.setAmbientLight(0, 0, 0);
                Vec3d eye = g.getCameraNoClone().getPositionInWorld(null);
                Light camera = point((float) eye.x, (float) eye.y, (float) eye.z, 1, 1, 1,
                        NGPointLight.getDefaultCa(), NGPointLight.getDefaultLa(), NGPointLight.getDefaultQa(),
                        NGPointLight.getDefaultMaxRange());
                set(view, index++, camera);
            } else {
                for (Light light : lights == null ? List.<Light>of() : lights) {
                    set(view, index++, light);
                }
                float[] sum = ambient == null ? new float[3] : ambient;
                view.setAmbientLight(sum[0], sum[1], sum[2]);
            }
            while (index < 3) {
                view.setLight(index++, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
            }
            view.render(g);
        }

        private static void set(MeshView view, int index, Light light) {
            float[] a = light.arguments();
            view.setLight(index, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11],
                    a[12], a[13], a[14], a[15], a[16], a[17]);
        }

        /** {@code NGPhongMaterial.validate} on a fresh material: every map set, diffuse defaulting to white. */
        private PhongMaterial material(ResourceFactory factory) {
            PhongMaterial material = factory.createPhongMaterial();
            material.setDiffuseColor(diffuse[0], diffuse[1], diffuse[2], diffuse[3]);
            material.setTextureMap(map(PhongMaterial.MapType.DIFFUSE, diffuseMap == null ? WHITE_1X1 : diffuseMap));
            material.setTextureMap(map(PhongMaterial.MapType.BUMP, bumpMap));
            material.setTextureMap(map(PhongMaterial.MapType.SELF_ILLUM, selfIllumMap));
            material.setTextureMap(map(PhongMaterial.MapType.SPECULAR, specularMap));
            material.setSpecularColor(specularSet, specularSet ? 0.95f : 1, specularSet ? 0.8f : 1,
                    specularSet ? 0.65f : 1, 6);
            return material;
        }

        private static TextureMap map(PhongMaterial.MapType type, Image image) {
            TextureMap map = new TextureMap(type);
            map.setImage(image);
            map.setDirty(true);
            return map;
        }
    }

    /**
     * {@code PerspectiveCamera} with {@code fixedEyeAtCameraZero=false}: projection, legacy look-at and the scale
     * that maps the z = 0 plane to pixels, as {@code Camera.getProjViewTransform} composes them.
     */
    private static NGPerspectiveCamera camera() {
        double fov = 38;
        double tanOfHalf = Math.tan(Math.toRadians(fov) / 2);
        GeneralTransform3D projView = new GeneralTransform3D();
        projView.perspective(true, Math.toRadians(fov), 1, 0.5, 900);
        Affine3D lookAt = new Affine3D();
        lookAt.setToTranslation(0, 0, -1);
        lookAt.rotate(Math.PI, 1, 0, 0);
        Affine3D view = new Affine3D();
        view.setToTranslation(-tanOfHalf, tanOfHalf, 0);
        view.concatenate(lookAt);
        double scale = 2 * tanOfHalf / FRAME;
        view.scale(scale, scale, scale);
        projView.mul(view);
        NGPerspectiveCamera camera = new NGPerspectiveCamera(false);
        camera.setFieldOfView((float) fov);
        camera.setVerticalFieldOfView(true);
        camera.setNearClip(0.5f);
        camera.setFarClip(900);
        camera.setViewWidth(FRAME);
        camera.setViewHeight(FRAME);
        camera.setProjViewTransform(projView);
        camera.setPosition(new Vec3d(FRAME / 2.0, FRAME / 2.0, -(FRAME / 2.0) / tanOfHalf));
        camera.setWorldTransform(new Affine3D());
        return camera;
    }

    /**
     * The mesh {@code javafx.scene.shape.Box.createMesh} builds for a cube of half size {@code half}, hard edges;
     * {@code open} leaves out the near side, so that culling decides what is seen inside. Geometry is built at size,
     * as {@code Box} does: the Phong shaders measure light distance in a frame that a scaling transform stretches.
     */
    static Mesh boxMesh(ResourceFactory factory, boolean open, float half) {
        float[] points = {-1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1};
        for (int i = 0; i < points.length; i++) {
            points[i] *= half;
        }
        float[] texCoords = {0, 0, 1, 0, 1, 1, 0, 1};
        int[] all = {
            0, 0, 2, 2, 1, 1,
            2, 2, 0, 0, 3, 3,
            1, 0, 6, 2, 5, 1,
            6, 2, 1, 0, 2, 3,
            5, 0, 7, 2, 4, 1,
            7, 2, 5, 0, 6, 3,
            4, 0, 3, 2, 0, 1,
            3, 2, 4, 0, 7, 3,
            3, 0, 6, 2, 2, 1,
            6, 2, 3, 0, 7, 3,
            4, 0, 1, 2, 5, 1,
            1, 2, 4, 0, 0, 3};
        int[] faces = open ? Arrays.copyOfRange(all, 12, all.length) : all;
        return build(factory, points, texCoords, faces, new int[faces.length / 6]);
    }

    /** 257 x 256 points: more than 65,535 vertices, so the geometry takes 32-bit indices. */
    private static Mesh gridMesh(ResourceFactory factory, float half) {
        int columns = 257;
        int rows = 256;
        float[] points = new float[columns * rows * 3];
        float[] texCoords = new float[columns * rows * 2];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < columns; i++) {
                int p = j * columns + i;
                float x = -1 + 2f * i / (columns - 1);
                float y = -1 + 2f * j / (rows - 1);
                points[p * 3] = x * half;
                points[p * 3 + 1] = y * half;
                points[p * 3 + 2] = (float) (0.18 * half * Math.sin(5 * x) * Math.cos(4 * y));
                texCoords[p * 2] = (float) i / (columns - 1);
                texCoords[p * 2 + 1] = (float) j / (rows - 1);
            }
        }
        int[] faces = new int[(columns - 1) * (rows - 1) * 12];
        int f = 0;
        for (int j = 0; j < rows - 1; j++) {
            for (int i = 0; i < columns - 1; i++) {
                int p00 = j * columns + i;
                int p10 = p00 + 1;
                int p01 = p00 + columns;
                int p11 = p01 + 1;
                for (int v : new int[] {p00, p11, p10, p00, p01, p11}) {
                    faces[f++] = v;
                    faces[f++] = v;
                }
            }
        }
        int[] groups = new int[faces.length / 6];
        Arrays.fill(groups, 1);
        return build(factory, points, texCoords, faces, groups);
    }

    private static Mesh build(ResourceFactory factory, float[] points, float[] texCoords, int[] faces,
                              int[] groups) {
        Mesh mesh = factory.createMesh();
        if (!mesh.buildGeometry(false, points, new int[] {0, points.length}, null, new int[] {0, 0},
                texCoords, new int[] {0, texCoords.length}, faces, new int[] {0, faces.length},
                groups, new int[] {0, groups.length})) {
            throw new IllegalStateException("buildGeometry failed");
        }
        return mesh;
    }

    // ---------------------------------------------------------------------------------------------
    // Z: teardown (lifecycle profile)
    // ---------------------------------------------------------------------------------------------

    /**
     * Renders once with a texture, an RTT, a mesh, a material and a mesh view, disposes each, then the pipeline. Every
     * step is written to {@link #LIFECYCLE_PROGRESS} before and after it runs, so that a native crash still leaves
     * the record of how far teardown got (decision D5).
     */
    static void lifecycle(Session s, GoldenMap out) throws Exception {
        Path progress = s.work().resolve(LIFECYCLE_PROGRESS);
        Files.deleteIfExists(progress);
        for (Map.Entry<String, String> entry : out.entrySet()) {
            append(progress, entry.getKey(), entry.getValue());
        }
        ResourceFactory factory = s.factory();
        Texture texture = MTLCorpusTextures.argbTexture(s, 8, 8, 1);
        RTTexture target = factory.createRTTexture(FRAME, FRAME, Texture.WrapMode.CLAMP_NOT_NEEDED);
        Mesh mesh = boxMesh(factory, false, 14);
        PhongMaterial material = factory.createPhongMaterial();
        material.setDiffuseColor(0.7f, 0.5f, 0.3f, 1f);
        for (PhongMaterial.MapType type : PhongMaterial.MapType.values()) {
            TextureMap map = new TextureMap(type);
            map.setImage(type == PhongMaterial.MapType.DIFFUSE ? WHITE_1X1 : null);
            map.setDirty(true);
            material.setTextureMap(map);
        }
        material.setSpecularColor(false, 1, 1, 1, 32);
        MeshView view = factory.createMeshView(mesh);
        int step = 0;
        step = run(progress, step, "render", () -> {
            Graphics g = target.createGraphics();
            MTLCorpus2D.depthClear(g, true);
            g.drawTexture(texture, 2, 3, 30, 29, 0, 0, 8, 8);
            Affine3D placement = new Affine3D();
            placement.translate(36, 34, 0);
            placement.rotate(0.7, 1, 0.4, 0);
            g.setTransform(placement);
            g.setup3DRendering();
            view.setMaterial(material);
            view.setCullingMode(MeshView.CULL_BACK);
            view.setWireframe(false);
            view.setAmbientLight(0.1f, 0.1f, 0.1f);
            Scene.set(view, 0, point(20, 10, -40, 1, 1, 1, 1, 0, 0, 500));
            view.setLight(1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
            view.setLight(2, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
            view.render(g);
            target.getPixels();
        });
        append(progress, MTLGoldens.LIFECYCLE + ".shadersCreated",
               Integer.toString(MTLGoldenShim.createdShaderNames().size()));
        step = run(progress, step, "texture.dispose", texture::dispose);
        step = run(progress, step, "rtt.dispose", target::dispose);
        step = run(progress, step, "meshView.dispose", view::dispose);
        step = run(progress, step, "material.dispose", material::dispose);
        step = run(progress, step, "mesh.dispose", mesh::dispose);
        run(progress, step, "pipeline.dispose", () -> GraphicsPipeline.getPipeline().dispose());
        append(progress, MTLGoldens.LIFECYCLE + ".shadersAfterDispose",
               Integer.toString(MTLGoldenShim.createdShaderNames().size()));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static int run(Path progress, int step, String name, Action action) throws Exception {
        String key = MTLGoldens.LIFECYCLE + ".s" + String.format("%02d", step) + "." + name;
        append(progress, key + ".begin", "1");
        action.run();
        append(progress, key + ".end", "1");
        return step + 1;
    }

    private static void append(Path progress, String key, String value) throws IOException {
        Files.writeString(progress, key + "=" + value + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}

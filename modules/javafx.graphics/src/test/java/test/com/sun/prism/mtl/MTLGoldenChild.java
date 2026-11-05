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

import com.sun.glass.ui.Screen;
import com.sun.javafx.font.MacMachineShim;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.ResourceFactory;
import com.sun.prism.Texture;
import com.sun.prism.mtl.MTLGoldenShim;
import com.sun.prism.ps.Shader;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrFilterContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.LockSupport;
import test.com.sun.prism.mtl.MTLGoldenFormat.GoldenMap;

/**
 * The child JVM of the Metal goldens: {@code main(profile, workDirectory, passes)}. Everything Metal runs on one
 * platform thread named like Quantum's render thread, so that {@code Disposer.cleanUp} runs inline instead of
 * routing through {@code QuantumToolkit}. That thread never exits: it parks after signalling {@code main}, which
 * calls {@code System.exit}, so the objc runtime never drains its autorelease pool at thread exit.
 * <p>
 * Nothing here creates a window. The child proves it before the first render step and after the last: no
 * {@code libglass} image is loaded and AppKit's {@code NSApp} is nil. A failed proof halts the child at once with
 * {@link #EXIT_PROOF_FAILED}; the parent then launches nothing else.
 * <p>
 * Outputs in the work directory: {@code child.txt} (keys that are not per pass), {@code pass-<n>.txt} (frame keys,
 * offsets relative to the pass) and {@code pass-<n>.bin} (the raw frames of the pass, big-endian ARGB).
 */
public final class MTLGoldenChild {

    static final int EXIT_PROOF_FAILED = 3;
    static final int EXIT_FAILED = 2;
    static final String PROOF_FAILURE_FILE = "proof-failure.txt";
    static final String FAILURE_FILE = "failure.txt";
    static final String CHILD_FILE = "child.txt";
    static final String RENDER_THREAD = "QuantumRenderer-mtl-golden";

    private MTLGoldenChild() {
    }

    public static void main(String[] args) throws Exception {
        String profile = args[0];
        Path work = Path.of(args[1]);
        int passes = Integer.parseInt(args[2]);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread render = Thread.ofPlatform().name(RENDER_THREAD).unstarted(() -> {
            try {
                run(profile, work, passes);
                finished.complete(null);
            } catch (Throwable t) {
                finished.completeExceptionally(t);
            }
            while (true) {
                LockSupport.park();
            }
        });
        render.setDaemon(true);
        render.start();
        try {
            finished.get();
        } catch (ExecutionException e) {
            StringWriter trace = new StringWriter();
            e.getCause().printStackTrace(new PrintWriter(trace));
            Files.writeString(work.resolve(FAILURE_FILE), trace.toString(), StandardCharsets.UTF_8);
            e.getCause().printStackTrace();
            System.exit(EXIT_FAILED);
        }
        System.exit(0);
    }

    private static void run(String profile, Path work, int passes) throws Exception {
        GoldenMap out = new GoldenMap();
        requireNoWindow(work, "start");
        GraphicsPipeline pipeline = GraphicsPipeline.createPipeline();
        out.put(profile + ".pipeline", pipeline == null ? MTLGoldenFormat.NULL : pipeline.getClass().getName());
        out.put("info.producer." + profile, producer());
        if (pipeline == null) {
            write(work.resolve(CHILD_FILE), out);
            return;
        }
        Screen screen = new Screen(0L, 32, 0, 0, 1280, 800, 0, 0, 1280, 800,
                0, 0, 1280, 800, 72, 72, 1f, 1f, 1f, 1f);
        ResourceFactory factory = pipeline.getResourceFactory(screen);
        Renderer renderer = Renderer.getRenderer(PrFilterContext.getInstance(screen));
        if (renderer.getAccelType() != Effect.AccelType.METAL) {
            throw new IllegalStateException("Decora picked " + renderer.getAccelType()
                    + ", so the effect pixels would not come from Metal");
        }
        requireNoWindow(work, "before");
        putPipelineKeys(out, profile, pipeline, factory);
        Session session = new Session(profile, work, screen, factory);
        switch (profile) {
            case MTLGoldens.SHADERS -> putShaderTables(out, factory);
            case MTLGoldens.LIFECYCLE -> MTLCorpus3D.lifecycle(session, out);
            case MTLGoldens.OBJC_RELEASE -> MTLCorpusObjcRelease.roundTrips(session, out);
            default -> renderPasses(session, work, passes, out);
        }
        out.put(profile + ".decora.accelType", renderer.getAccelType().name());
        requireNoWindow(work, "after");
        out.put("info.device." + profile, MTLGoldenFormat.nullable(MTLGoldenShim.defaultDeviceDescription()));
        write(work.resolve(CHILD_FILE), out);
    }

    /** {@code jni(n)} while the natives exist, {@code ffm(0)} once every one is gone. */
    static String producer() {
        int natives = MTLGoldenShim.declaredNatives().size();
        return (natives == 0 ? "ffm" : "jni") + "(" + natives + ")";
    }

    private static void requireNoWindow(Path work, String when) throws IOException {
        List<String> violations = new ArrayList<>();
        if (MacMachineShim.imageUuid("/libglass.dylib") != null) {
            violations.add("libglass.dylib is loaded");
        }
        if (!MTLGoldenShim.nsAppIsNil()) {
            violations.add("NSApp is not nil: an NSApplication exists");
        }
        GraphicsPipeline installed = GraphicsPipeline.getPipeline();
        if (installed != null && !installed.getClass().getName().equals("com.sun.prism.mtl.MTLPipeline")) {
            violations.add("the installed pipeline is " + installed.getClass().getName());
        }
        if (!violations.isEmpty()) {
            Files.writeString(work.resolve(PROOF_FAILURE_FILE), when + ": " + String.join("; ", violations),
                    StandardCharsets.UTF_8);
            Runtime.getRuntime().halt(EXIT_PROOF_FAILED);
        }
    }

    private static void putPipelineKeys(GoldenMap out, String profile, GraphicsPipeline pipeline,
                                        ResourceFactory factory) {
        String prefix = profile + ".pipeline.";
        Map<?, ?> details = pipeline.getDeviceDetails();
        List<String> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : new TreeMap<>(details).entrySet()) {
            Object value = entry.getValue();
            String shown = value == null ? MTLGoldenFormat.NULL : value.getClass().getName();
            if ("mtlCommandQueue".equals(entry.getKey())) {
                shown += value instanceof Long address && address != 0L ? ":nonzero" : ":zero";
            } else {
                shown += ":" + value;
            }
            entries.add(entry.getKey() + "=" + shown);
        }
        out.put(prefix + "deviceDetails", String.join(",", entries));
        out.put(prefix + "maximumTextureSize", Integer.toString(factory.getMaximumTextureSize()));
        List<String> formats = new ArrayList<>();
        for (PixelFormat format : PixelFormat.values()) {
            formats.add(format.name() + "=" + factory.isFormatSupported(format));
        }
        out.put(prefix + "formatSupported", String.join(",", formats));
        out.put(prefix + "features", "3d=" + pipeline.is3DSupported() + ",msaa=" + pipeline.isMSAASupported()
                + ",vsync=" + pipeline.isVsyncSupported()
                + ",msl=" + pipeline.supportsShaderType(GraphicsPipeline.ShaderType.MSL)
                + ",sm3=" + pipeline.supportsShaderModel(GraphicsPipeline.ShaderModel.SM3));
        out.put(prefix + "rttWrap", factory.getRTTWidth(37, Texture.WrapMode.CLAMP_TO_ZERO) + "x"
                + factory.getRTTHeight(29, Texture.WrapMode.REPEAT));
    }

    private static void renderPasses(Session session, Path work, int passes, GoldenMap out) throws Exception {
        List<Step> steps = Steps.forProfile(session.profile()).steps();
        MTLCorpusTextures.installRegionTexture(session.factory());
        for (int pass = 1; pass <= passes; pass++) {
            GoldenMap frames = new GoldenMap();
            ByteArrayOutputStream payload = new ByteArrayOutputStream(1 << 20);
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                int[] pixels = step.body().render(session);
                if (pixels.length != step.width() * step.height()) {
                    throw new IllegalStateException(step.key() + " returned " + pixels.length + " pixels for a "
                            + step.width() + "x" + step.height() + " frame");
                }
                byte[] bytes = MTLGoldenFormat.frameBytes(pixels);
                frames.put(MTLGoldens.frameKey(session.profile(), index, step.key()),
                           MTLGoldenFormat.frameValue(step.width(), step.height(), payload.size(),
                                                      MTLGoldenFormat.sha256Hex(bytes)));
                payload.write(bytes);
            }
            write(work.resolve("pass-" + pass + ".txt"), frames);
            Files.write(work.resolve("pass-" + pass + ".bin"), payload.toByteArray());
        }
        Set<String> created = MTLGoldenShim.createdShaderNames();
        out.put(session.profile() + ".coverage.shaders", created.size() + ":" + String.join(",", created));
    }

    private static void putShaderTables(GoldenMap out, ResourceFactory factory) {
        List<String> names = MTLShaderTableGoldenTest.shaderNames();
        int prism = 0;
        for (String name : names) {
            Shader shader = MTLGoldenShim.createShader(factory, name);
            Map<String, Integer> table = MTLGoldenShim.uniformTable(shader);
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : table.entrySet()) {
                entries.add(entry.getKey() + "=" + entry.getValue());
            }
            out.put("shader." + name + ".uniforms", table.size() + ":" + String.join(",", entries));
            if (!MTLShaderTableGoldenTest.DECORA_SHADERS.contains(name)) {
                prism++;
            }
        }
        out.put("shader.count.prism", Integer.toString(prism));
        out.put("shader.count.decora", Integer.toString(names.size() - prism));
        out.put("shader.count.created", Integer.toString(MTLGoldenShim.createdShaderNames().size()));
    }

    static void write(Path file, Map<String, String> values) throws IOException {
        Files.writeString(file, MTLGoldenFormat.format(values, List.of()), StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    interface Body {
        int[] render(Session session) throws Exception;
    }

    /** A step: {@code <group>.<case>}, its frame size, and whether a uniform frame is its designed result. */
    record Step(String key, int width, int height, boolean uniform, Body body) {
    }

    /** The rendering state a step body sees. */
    record Session(String profile, Path work, Screen screen, ResourceFactory factory) {

        /** A fresh RTT, drawn by {@code body} after a clear to {@link MTLCorpus2D#BACKGROUND}, read and disposed. */
        int[] render(int width, int height, boolean msaa, MTLCorpus2D.Draw body) throws Exception {
            return renderUncleared(width, height, msaa, g -> {
                g.clear(MTLCorpus2D.BACKGROUND);
                body.draw(g);
            });
        }

        /** A fresh RTT that {@code body} draws from its initial content, read and disposed. */
        int[] renderUncleared(int width, int height, boolean msaa, MTLCorpus2D.Draw body) throws Exception {
            RTTexture target = factory.createRTTexture(width, height, Texture.WrapMode.CLAMP_NOT_NEEDED, msaa);
            try {
                body.draw(target.createGraphics());
                return target.getPixels().clone();
            } finally {
                target.dispose();
            }
        }
    }

    /**
     * The corpus of one profile, in step order, with the capture-time discrimination checks: pairs whose frames are
     * designed to be equal and sets whose frames are designed to be pairwise distinct.
     */
    static final class Steps {
        private final String profile;
        private final List<Step> steps = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();
        private final List<List<String>> equal = new ArrayList<>();
        private final List<List<String>> distinct = new ArrayList<>();

        private Steps(String profile) {
            this.profile = profile;
        }

        /** Builds the registry only; nothing is rendered and no native is touched. */
        static Steps forProfile(String profile) {
            Steps steps = new Steps(profile);
            MTLCorpus2D.register(steps);
            MTLCorpusTextures.register(steps);
            MTLCorpusEffects.register(steps);
            MTLCorpus3D.register(steps);
            return steps;
        }

        String profile() {
            return profile;
        }

        boolean in(String... profiles) {
            return List.of(profiles).contains(profile);
        }

        void add(String key, int width, int height, Body body) {
            add(key, width, height, false, body);
        }

        void addUniform(String key, int width, int height, Body body) {
            add(key, width, height, true, body);
        }

        private void add(String key, int width, int height, boolean uniform, Body body) {
            if (!keys.add(key)) {
                throw new IllegalStateException("step key registered twice: " + key);
            }
            steps.add(new Step(key, width, height, uniform, body));
        }

        void designedEqual(String... stepKeys) {
            equal.add(List.of(stepKeys));
        }

        void designedDistinct(List<String> stepKeys) {
            distinct.add(List.copyOf(stepKeys));
        }

        List<Step> steps() {
            return steps;
        }

        List<List<String>> equalSets() {
            return equal;
        }

        List<List<String>> distinctSets() {
            return distinct;
        }
    }
}

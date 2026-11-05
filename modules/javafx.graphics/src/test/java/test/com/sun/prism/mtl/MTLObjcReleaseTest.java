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

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The five release natives of {@code com.sun.prism.mtl} ({@code MTLContext.nDisposeShader}, {@code nReleaseMTLMesh},
 * {@code nReleaseMTLPhongMaterial}, {@code nReleaseMTLMeshView} and {@code MTLResourceFactory.nReleaseTexture})
 * compiled to {@code if (handle) objc_release(handle)}, so the peers now call {@code objc_release} from libobjc
 * themselves. One child JVM ({@link MTLCorpusObjcRelease}) disposes an object of each kind through its production
 * caller; this class asserts what the C of each class makes that call do. The ones that keep {@code NSObject}'s
 * {@code -release} are deallocated, with what their {@code -dealloc} releases. {@code MetalMesh} and
 * {@code MetalMeshView} override {@code -release}, which {@code objc_release} dispatches to: it frees their buffers
 * and lights, and since it never calls {@code [super release]} the objects themselves leak (L2, kept).
 */
@EnabledOnOs(OS.MAC)
@Timeout(600)
public class MTLObjcReleaseTest {

    private static Map<String, String> outcome;

    @BeforeAll
    static void disposeOneOfEachKindInAChild() throws Exception {
        MTLNatives.require();
        outcome = MTLGoldens.runChild(MTLGoldens.OBJC_RELEASE, "verify", 1).requireSuccess().keys();
    }

    @AfterAll
    static void oracleRan() {
        ParityGate.ledger(MTLObjcReleaseTest.class).assertOracleRan();
    }

    @Test
    void aTextureIsDeallocatedWithItsMetalTexture() {
        assertRoundTrip("texture", Map.of(
                "class", "MetalTexture",
                "sentinel", "2->1",
                "texture.retainCount", "-1"));
    }

    @Test
    void aRenderTargetIsDeallocatedWithItsMetalTexture() {
        assertRoundTrip("renderTarget", Map.of(
                "class", "MetalRTTexture",
                "sentinel", "2->1",
                "texture.retainCount", "-1"));
    }

    @Test
    void aPhongMaterialIsDeallocated() {
        assertRoundTrip("phongMaterial", Map.of(
                "class", "MetalPhongMaterial",
                "sentinel", "2->1"));
    }

    @Test
    void aShaderIsDeallocated() {
        assertRoundTrip("shader", Map.of(
                "class", "MetalShader",
                "sentinel", "2->1"));
    }

    @Test
    void aMeshRunsItsReleaseOverrideWhichFreesItsBuffersButNotTheMesh() {
        assertRoundTrip("mesh", Map.of(
                "class", "MetalMesh",
                "sentinel", "2->2",
                "vertexBuffer.retainCount", "-1",
                "vertexBuffer.cleared", "true",
                "indexBuffer.retainCount", "-1",
                "indexBuffer.cleared", "true"));
    }

    @Test
    void aMeshViewRunsItsReleaseOverrideWhichFreesItsLightsButNotTheView() {
        assertRoundTrip("meshView", Map.of(
                "class", "MetalMeshView",
                "sentinel", "2->2",
                "light0.retainCount", "-1",
                "light0.cleared", "true",
                "light1.retainCount", "-1",
                "light1.cleared", "true",
                "light2.retainCount", "-1",
                "light2.cleared", "true"));
    }

    private static void assertRoundTrip(String kind, Map<String, String> expected) {
        ParityGate.Ledger ledger = ParityGate.ledger(MTLObjcReleaseTest.class);
        ledger.requireOracle(!MTLGoldenFormat.NULL.equals(outcome.get(MTLGoldens.OBJC_RELEASE + ".pipeline")),
                () -> "MTLPipeline.init() returned false in the child (mtl_supports_metal), so no Metal object"
                        + " could be created.");
        String prefix = MTLCorpusObjcRelease.PREFIX + kind + ".";
        Map<String, String> actual = new TreeMap<>();
        outcome.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                actual.put(key.substring(prefix.length()), value);
            }
        });
        assertEquals(new TreeMap<>(expected), actual, kind);
        ledger.compared();
    }
}

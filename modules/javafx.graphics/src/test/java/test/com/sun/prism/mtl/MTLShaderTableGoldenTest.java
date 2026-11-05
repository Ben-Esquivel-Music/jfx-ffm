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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import test.com.sun.javafx.test.ParityGate;

/**
 * Every Metal shader, created by name in one context in a child JVM, with the uniform name to argument-buffer id table
 * {@code nGetUniformNameIdMap} returned for it. The tables come from the JSLC-generated Objective-C, which differs from
 * the Java loaders' {@code params}, so this golden is their only record (R5).
 */
@EnabledOnOs(OS.MAC)
@Timeout(600)
public class MTLShaderTableGoldenTest {

    /** The Decora shaders; the Prism ones are listed from the module's generated loaders. */
    static final Set<String> DECORA_SHADERS = Set.copyOf(decora());

    @AfterAll
    static void oracleRan() {
        ParityGate.ledger(MTLShaderTableGoldenTest.class).assertOracleRan();
    }

    @Test
    void everyShaderIsCreatedWithTheJniUniformTable() throws Exception {
        MTLGoldens.verifyShaders(MTLShaderTableGoldenTest.class);
    }

    /** The 212 Prism shaders of {@code com/sun/prism/shader/*_Loader.class} and the 50 Decora ones, sorted. */
    static List<String> shaderNames() {
        Set<String> names = new TreeSet<>(DECORA_SHADERS);
        ModuleReader reader;
        try {
            reader = ModuleLayer.boot().configuration().findModule("javafx.graphics").orElseThrow().reference().open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (reader; Stream<String> resources = reader.list()) {
            resources.filter(name -> name.startsWith("com/sun/prism/shader/") && name.endsWith("_Loader.class")
                    && name.indexOf('$') < 0)
                    .map(name -> name.substring("com/sun/prism/shader/".length(),
                                                name.length() - "_Loader.class".length()))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (names.size() != 262) {
            throw new IllegalStateException("expected 262 shaders (212 Prism, 50 Decora), found " + names.size());
        }
        return List.copyOf(names);
    }

    private static List<String> decora() {
        List<String> names = new ArrayList<>(List.of("Brightpass", "ColorAdjust", "DisplacementMap", "InvertMask",
                "PerspectiveTransform", "PhongLighting_DISTANT", "PhongLighting_POINT", "PhongLighting_SPOT",
                "SepiaTone"));
        for (String mode : List.of("ADD", "BLUE", "COLOR_BURN", "COLOR_DODGE", "DARKEN", "DIFFERENCE", "EXCLUSION",
                "GREEN", "HARD_LIGHT", "LIGHTEN", "MULTIPLY", "OVERLAY", "RED", "SCREEN", "SOFT_LIGHT", "SRC_ATOP",
                "SRC_IN", "SRC_OUT", "SRC_OVER")) {
            names.add("Blend_" + mode);
        }
        for (int size : new int[] {4, 8, 12, 16, 20, 24, 28, 32, 64, 96, 128}) {
            names.add("LinearConvolve_" + size);
            names.add("LinearConvolveShadow_" + size);
        }
        return names;
    }
}

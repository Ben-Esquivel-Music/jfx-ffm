/*
 * Copyright (c) 2008, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.scenario.effect.compiler;

import java.nio.file.Path;
import com.sun.scenario.effect.compiler.JSLC.JSLCInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 */
public class SymbolTest {

    @TempDir
    static Path outDir;

    public SymbolTest() {
    }

    static void compile(String s) throws Exception {
        JSLCInfo jslcinfo = new JSLCInfo();
        jslcinfo.outDir = outDir.toString();
        jslcinfo.shaderName = "Effect";
        jslcinfo.peerName = "Foo";
        jslcinfo.outTypes = JSLC.OUT_ALL_PEERS | JSLC.OUT_HW_SHADERS;
        JSLC.compile(jslcinfo, s, Long.MAX_VALUE);
    }

    @Test
    public void specialVarUsedOutsideOfMain() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> {
            String s =
                "param sampler img;\n" +
                "float myfunc(float val) {\n" +
                "    return pos0.x;\n" +
                "}\n" +
                "void main() {\n" +
                "    float foo = pos0.y;\n" +
                "    float funcres = myfunc(1.5);\n" +
                "}\n";
            compile(s);
        });
        assertEquals("Unknown variable pos0", e.getMessage());
    }
}

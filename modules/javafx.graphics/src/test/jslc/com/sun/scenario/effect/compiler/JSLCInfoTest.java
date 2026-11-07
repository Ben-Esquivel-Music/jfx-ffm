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


package com.sun.scenario.effect.compiler;

import com.sun.scenario.effect.compiler.JSLC.JSLCInfo;
import com.sun.scenario.effect.compiler.backend.hw.MetalHeaders;
import java.io.File;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JSLCInfoTest {

    @Test
    public void theMetalHeaderOptionsSetTheDirectoryAndThePrismKind() {
        JSLCInfo jslcinfo = new JSLCInfo();
        assertEquals(4, jslcinfo.parseArgs(new String[] {"-mtlheaders", "headers", "-mtlprism", "-mtl"}));
        assertEquals(new File("headers"), jslcinfo.getMetalHeaderDir());
        assertEquals(MetalHeaders.Kind.PRISM, jslcinfo.metalHeaderKind);
    }

    @Test
    public void theDefaultHeaderDirectoryIsMtlHeadersInTheOutputDirectory() {
        JSLCInfo jslcinfo = new JSLCInfo();
        jslcinfo.outDir = "out";
        assertEquals(new File("out", "mtl-headers"), jslcinfo.getMetalHeaderDir());
        jslcinfo.trimToOutDir = true;
        assertEquals(new File("out", "mtl-headers"), jslcinfo.getMetalHeaderDir());
        assertEquals(MetalHeaders.Kind.DECORA, jslcinfo.metalHeaderKind);
    }

    @Test
    public void withoutAnOutputDirectoryTheHeadersGoToMtlHeadersInTheWorkingDirectory() {
        assertEquals(new File("mtl-headers"), new JSLCInfo().getMetalHeaderDir());
    }
}

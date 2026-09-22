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

package test.com.sun.glass.ui.monocle;

import com.sun.glass.ui.monocle.NativePlatformFactory;
import com.sun.glass.ui.monocle.NativePlatformFactoryShim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the default {@code monocle.platform} cascade. Every name in it must resolve to a
 * {@link NativePlatformFactory} in this module: NativePlatformFactory.getNativePlatform() prints
 * the stack trace of every entry it cannot instantiate before moving on to the next one, so a name
 * left behind after its platform was removed (as the Android port was) turns every Monocle
 * start-up into a ClassNotFoundException trace.
 */
public class NativePlatformFactoryTest {

    @Test
    public void defaultCascadeNamesOnlyExistingFactories() {
        List<String> order = NativePlatformFactoryShim.defaultPlatformOrder();
        assertFalse(order.isEmpty(), "default monocle.platform cascade is empty");
        ClassLoader loader = NativePlatformFactory.class.getClassLoader();
        for (String name : order) {
            String className = "com.sun.glass.ui.monocle." + name.trim() + "PlatformFactory";
            Class<?> clazz = assertDoesNotThrow(() -> Class.forName(className, false, loader),
                    () -> "default monocle.platform cascade names a missing factory: " + className);
            assertTrue(NativePlatformFactory.class.isAssignableFrom(clazz),
                    () -> className + " is not a NativePlatformFactory");
        }
    }
}

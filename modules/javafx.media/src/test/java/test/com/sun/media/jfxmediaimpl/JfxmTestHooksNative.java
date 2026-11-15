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

package test.com.sun.media.jfxmediaimpl;

import com.sun.glass.utils.NativeLibLoader;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The three exports of the hooks build of {@code jfxmedia} ({@code ffi/jfxmedia_test_hooks.h}), bound in a probe's
 * child JVM. {@code JfxMediaNative} binds none of them, by design, so the probes bind them here, against the
 * {@code jfxmedia} {@code NativeLibLoader} loaded for the facade.
 * <p>
 * Only a child started by {@link ChildJvm} may use this class. Binding a downcall is restricted, and this is test
 * code: on the class path of such a child it is covered by {@code --enable-native-access=ALL-UNNAMED}, while in the
 * test JVM it would be unnamed code that nothing grants, and the call would print a warning.
 */
final class JfxmTestHooksNative {

    private final MethodHandle reset;
    private final MethodHandle armSourceFault;
    private final MethodHandle counter;

    private JfxmTestHooksNative(SymbolLookup jfxmedia) {
        reset = bind(jfxmedia, "jfxm_test_reset", FunctionDescriptor.ofVoid());
        armSourceFault = bind(jfxmedia, "jfxm_test_arm_source_fault", FunctionDescriptor.ofVoid(JAVA_INT));
        counter = bind(jfxmedia, "jfxm_test_counter", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    }

    /**
     * Loads the facade's libraries and binds the hooks in the {@code jfxmedia} among them.
     *
     * @return the bound hooks
     * @throws UnsatisfiedLinkError if the loaded {@code jfxmedia} is not the hooks build
     */
    static JfxmTestHooksNative bind() {
        JfxMediaNative.loadLibraries();
        SymbolLookup jfxmedia = NativeLibLoader.libraryLookup("jfxmedia")
                .orElseThrow(() -> new IllegalStateException("NativeLibLoader has loaded no jfxmedia"));
        return new JfxmTestHooksNative(jfxmedia);
    }

    /** {@code jfxm_test_reset}: disarms the fault and sets every counter to 0. */
    void reset() {
        try {
            reset.invokeExact();
        } catch (Throwable t) {
            throw new AssertionError("jfxm_test_reset", t);
        }
    }

    /** {@code jfxm_test_arm_source_fault}: the next {@code CreateSourceElement} that reaches {@code point} fails. */
    void armSourceFault(int point) {
        try {
            armSourceFault.invokeExact(point);
        } catch (Throwable t) {
            throw new AssertionError("jfxm_test_arm_source_fault", t);
        }
    }

    /** {@code jfxm_test_counter}: the value of counter {@code id}, -1 for an id the library does not know. */
    int counter(int id) {
        try {
            return (int) counter.invokeExact(id);
        } catch (Throwable t) {
            throw new AssertionError("jfxm_test_counter", t);
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(SymbolLookup jfxmedia, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = jfxmedia.find(name).orElseThrow(() -> new UnsatisfiedLinkError(
                "the loaded jfxmedia does not export " + name + ", so it is not the hooks build"));
        return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }
}

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

import com.sun.media.jfxmediaimpl.JfxMediaNative;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The frame-ownership guard in {@code NativeVideoBuffer.createVideoBuffer}, pinned the only way a test
 * outside the module can reach it.
 * <p>
 * Java owns a video frame from the moment {@code new_frame} delivers it: no C code deletes a frame it has
 * sent, so {@code createVideoBuffer} is what has to make the handle reachable, and it does that with
 * {@code MediaDisposer.addResourceDisposer}. Every step up to and including that call has to be guarded,
 * because none of them is allocation-free and all of them happen after C has let go: the constructor reads
 * the frame's geometry through {@link JfxMediaNative#frameGetInfo}, and the registration boxes the handle,
 * builds a phantom reference and a record, puts them into a map that may resize, and on the very first
 * frame constructs the disposer singleton and starts its thread. A throw anywhere in that window (an
 * {@code OutOfMemoryError} is the realistic one, entered once per delivered frame at 30-60 Hz) leaves
 * nothing holding the handle and no disposer registered, so neither {@code releaseFrame} nor the
 * phantom-reference safety net can ever free it. The orphan is not just the {@code CVideoFrame}: it pins
 * the {@code GstSample} and the mapped {@code GstBuffer} with it - on macOS a retained,
 * base-address-locked {@code CVPixelBuffer} - so one buffer of the decoder's pool is gone for good, and
 * enough of them stall the appsink permanently.
 * <p>
 * The guard therefore has to be there, and this test asserts it in the compiled method rather than by
 * running it, because the behaviour is out of reach: {@code NativeVideoBuffer} is package private and
 * {@code javafx.media} has no shim source tree, so no test can call {@code createVideoBuffer}, and neither
 * {@code frameGetInfo} nor {@code addResourceDisposer} has a fault-injection seam that would make the
 * handover throw. What is reachable is the bytecode - class files are never encapsulated, so reading it
 * needs neither an export nor the native library.
 * <p>
 * What it pins are the guard's properties, not its shape: some handler spans the whole handover, that
 * handler catches {@code Throwable}, and its own code disposes the frame before it rethrows. How many
 * handlers the method has and how the handler is written are deliberately not asserted, so that the guard
 * can be improved - suppressing a failure of the cleanup into the original throwable, say, or removing a
 * registration that a failed map resize left behind - without touching this test. Only losing one of the
 * properties fails it.
 * <p>
 * TODO: reading compiled shape is a stand-in, forced by {@code javafx.media} having no
 * {@code src/shims/java} tree. Once that tree exists a shim can hand a test {@code createVideoBuffer}
 * together with a fault-injecting {@code frameGetInfo} and a {@code frameDispose} counter, and this test
 * should be replaced by the behavioural one it stands in for: make the handover throw, and assert the
 * frame was disposed exactly once.
 */
public class NativeVideoBufferOwnershipTest {

    private static final String VIDEO_BUFFER = "com/sun/media/jfxmediaimpl/NativeVideoBuffer";
    private static final String DISPOSER = "com/sun/media/jfxmediaimpl/MediaDisposer";
    private static final String FACADE = "com/sun/media/jfxmediaimpl/JfxMediaNative";
    private static final String THROWABLE = "java/lang/Throwable";

    private static final String CONSTRUCT = "<init>";
    private static final String REGISTER = "addResourceDisposer";
    private static final String DISPOSE = "frameDispose";

    @Test
    void createVideoBufferDisposesTheFrameWhenTheHandoverThrows() throws IOException {
        CodeModel code = codeOf("createVideoBuffer");
        CodeAttribute layout = assertInstanceOf(CodeAttribute.class, code, "a parsed method's code");
        List<Op> operations = operationsOf(code, layout);
        List<ExceptionCatch> handlers = code.elementList().stream()
                .filter(ExceptionCatch.class::isInstance)
                .map(ExceptionCatch.class::cast)
                .toList();

        List<ExceptionCatch> spanning = handlers.stream()
                .filter(handler -> {
                    List<Op> region = guardedBy(operations, layout, handler);
                    return calls(region, VIDEO_BUFFER, CONSTRUCT) && calls(region, DISPOSER, REGISTER);
                })
                .toList();
        assertFalse(spanning.isEmpty(),
                () -> "one handler has to span the whole handover, from the NativeVideoBuffer construction"
                        + " through MediaDisposer." + REGISTER + ": both run after C has let go of the frame"
                        + " and before anything in Java holds it, and both allocate, so both can throw.\n"
                        + describe(operations, layout, handlers));

        List<ExceptionCatch> catchAll = spanning.stream()
                .filter(handler -> handler.catchType().isEmpty()
                        || THROWABLE.equals(handler.catchType().get().asInternalName()))
                .toList();
        assertFalse(catchAll.isEmpty(),
                () -> "the guard has to catch Throwable: the handover fails with an Error, not an"
                        + " Exception.\n" + describe(operations, layout, handlers));

        List<ExceptionCatch> disposing = catchAll.stream()
                .filter(handler -> calls(bodyOf(operations, layout, handler), FACADE, DISPOSE))
                .toList();
        assertFalse(disposing.isEmpty(),
                () -> "a handover that threw leaves nobody holding the frame: the handler has to call "
                        + FACADE + '.' + DISPOSE + " before it rethrows.\n"
                        + describe(operations, layout, handlers));

        List<ExceptionCatch> rethrowing = disposing.stream()
                .filter(handler -> rethrows(bodyOf(operations, layout, handler)))
                .toList();
        assertFalse(rethrowing.isEmpty(),
                () -> "the handler disposes and rethrows; swallowing the failure would hand back a"
                        + " half-built buffer.\n" + describe(operations, layout, handlers));
    }

    /** A call or an {@code athrow}, at the bci the exception table is expressed in. */
    private record Op(int bci, String owner, String name) {

        static final String ATHROW = "athrow";

        boolean isCall(String owner, String name) {
            return this.owner.equals(owner) && this.name.equals(name);
        }

        boolean isRethrow() {
            return owner.isEmpty() && ATHROW.equals(name);
        }

        @Override
        public String toString() {
            return isRethrow() ? ATHROW : owner + '.' + name;
        }
    }

    /**
     * Every call and {@code athrow} in the method, in bytecode order. Only labels carry a bci, so an
     * instruction is attributed to the last label before it; that is exact at the boundaries this test
     * compares against, because all three positions of an exception table entry are labels.
     */
    private static List<Op> operationsOf(CodeModel code, CodeAttribute layout) {
        List<Op> operations = new ArrayList<>();
        int bci = 0;
        for (CodeElement element : code.elementList()) {
            if (element instanceof LabelTarget target) {
                bci = layout.labelToBci(target.label());
            } else if (element instanceof InvokeInstruction call) {
                operations.add(new Op(bci, call.owner().asInternalName(), call.name().stringValue()));
            } else if (element instanceof ThrowInstruction) {
                operations.add(new Op(bci, "", Op.ATHROW));
            }
        }
        return operations;
    }

    /** What a handler protects: the operations inside its {@code [tryStart, tryEnd)} range. */
    private static List<Op> guardedBy(List<Op> operations, CodeAttribute layout, ExceptionCatch handler) {
        int start = layout.labelToBci(handler.tryStart());
        int end = layout.labelToBci(handler.tryEnd());
        return operations.stream().filter(op -> op.bci() >= start && op.bci() < end).toList();
    }

    /**
     * What a handler does: its own operations, ending at the throw that leaves it. Anything past that
     * throw belongs to the rest of the method, and a dispose there would not be the handler's.
     */
    private static List<Op> bodyOf(List<Op> operations, CodeAttribute layout, ExceptionCatch handler) {
        int start = layout.labelToBci(handler.handler());
        List<Op> body = new ArrayList<>();
        for (Op op : operations) {
            if (op.bci() >= start) {
                body.add(op);
                if (op.isRethrow()) {
                    break;
                }
            }
        }
        return body;
    }

    private static boolean calls(List<Op> operations, String owner, String name) {
        return operations.stream().anyMatch(op -> op.isCall(owner, name));
    }

    private static boolean rethrows(List<Op> body) {
        return body.stream().anyMatch(Op::isRethrow);
    }

    /** The whole exception table, rendered for a failure message. */
    private static String describe(List<Op> operations, CodeAttribute layout,
                                   List<ExceptionCatch> handlers) {
        StringBuilder text = new StringBuilder(512);
        text.append("createVideoBuffer has ").append(handlers.size()).append(" exception handler(s):");
        for (ExceptionCatch handler : handlers) {
            text.append("\n  try [").append(layout.labelToBci(handler.tryStart())).append(',')
                    .append(layout.labelToBci(handler.tryEnd())).append(") catch ")
                    .append(handler.catchType().map(type -> type.asInternalName()).orElse("<any>"))
                    .append(" -> ").append(layout.labelToBci(handler.handler()))
                    .append("\n    guarding ").append(guardedBy(operations, layout, handler))
                    .append("\n    handling ").append(bodyOf(operations, layout, handler));
        }
        return text.toString();
    }

    /** The one method of that name in the compiled {@code NativeVideoBuffer}. */
    private static CodeModel codeOf(String methodName) throws IOException {
        byte[] bytecode;
        String resource = VIDEO_BUFFER + ".class";
        try (InputStream in = JfxMediaNative.class.getModule().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not readable from " + JfxMediaNative.class.getModule());
            bytecode = in.readAllBytes();
        }
        ClassModel model = ClassFile.of().parse(bytecode);
        List<MethodModel> methods = model.methods().stream()
                .filter(method -> method.methodName().equalsString(methodName))
                .toList();
        assertEquals(1, methods.size(), "expected exactly one " + methodName + " in " + VIDEO_BUFFER);
        return methods.get(0).code().orElseThrow(() -> new AssertionError(methodName + " has no code attribute"));
    }
}

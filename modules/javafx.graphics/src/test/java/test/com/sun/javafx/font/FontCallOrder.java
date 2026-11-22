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

package test.com.sun.javafx.font;

import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The order of the calls in one method of a font class, read from its class file, for the checks that a guard or a
 * lookup comes before the call it protects where no run-time test can tell. A call is {@code owner name}, the owner
 * an internal name: owner and name decide, never the opcode (javac 27 calls a private method with
 * {@code invokevirtual}) or the descriptor.
 */
final class FontCallOrder {

    private FontCallOrder() {
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code first} and {@code then}, and its first call of
     * {@code first} comes before its first call of {@code then}.
     */
    static void assertFirstCallBefore(String type, String method, String first, String then) {
        List<Instruction> code = instructions(type, method);
        int firstIndex = firstCall(code, first);
        int thenIndex = firstCall(code, then);
        String where = type + "." + method;
        if (firstIndex < 0) {
            fail(where + " never calls " + first + ", which must come before its call of " + then);
        }
        if (thenIndex < 0) {
            fail(where + " no longer calls " + then + ", so this order check no longer pins anything");
        }
        if (thenIndex < firstIndex) {
            fail(where + " calls " + then + " before " + first);
        }
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code call}, and each of its calls of {@code call} takes its
     * one argument straight from the local variable that the receiver of its first call of {@code receiverCall}, an
     * instance call without arguments, was loaded from. A {@code checkcast} between that load and
     * {@code receiverCall} is skipped.
     */
    static void assertArgumentIsReceiverOf(String type, String method, String call, String receiverCall) {
        List<Instruction> code = instructions(type, method);
        String where = type + "." + method;
        int receiverIndex = firstCall(code, receiverCall);
        if (receiverIndex < 0) {
            fail(where + " no longer calls " + receiverCall + ", so this check no longer pins anything");
        }
        requireParameters(where, code.get(receiverIndex), 0);
        if (code.get(receiverIndex).opcode() == Opcode.INVOKESTATIC) {
            fail(where + " calls " + receiverCall + " statically, so the call has no receiver");
        }
        int receiverSlot = loadedSlot(code, receiverIndex - 1);
        if (receiverSlot < 0) {
            fail(where + " calls " + receiverCall + " on a value that no local variable holds");
        }
        List<Integer> calls = calls(code, call);
        if (calls.isEmpty()) {
            fail(where + " never calls " + call + " on local " + receiverSlot + ", the receiver of " + receiverCall);
        }
        for (int index : calls) {
            requireParameters(where, code.get(index), 1);
            int slot = loadedSlot(code, index - 1);
            if (slot != receiverSlot) {
                String argument = slot < 0 ? "a value no local variable holds" : "local " + slot;
                fail(where + " calls " + call + " on " + argument + ", not on local " + receiverSlot
                        + ", the receiver of " + receiverCall);
            }
        }
    }

    /** The instructions of the one method of {@code type} named {@code method}, in code order. */
    static List<Instruction> instructions(String type, String method) {
        ClassModel model = FontClassFiles.classModel(type, "the call-order checks read the production classes");
        List<MethodModel> named = model.methods().stream().filter(m -> m.methodName().equalsString(method)).toList();
        if (named.size() != 1) {
            fail(type + " has " + named.size() + " methods named " + method + "; a call-order check needs one");
        }
        List<Instruction> instructions = new ArrayList<>();
        named.getFirst().code().ifPresent(code -> code.forEach(element -> {
            if (element instanceof Instruction instruction) {
                instructions.add(instruction);
            }
        }));
        return instructions;
    }

    /** The index in {@code code} of the first call of {@code call}, or -1. */
    static int firstCall(List<Instruction> code, String call) {
        List<Integer> calls = calls(code, call);
        return calls.isEmpty() ? -1 : calls.getFirst();
    }

    /** The indexes in {@code code} of every call of {@code call}, in code order. */
    static List<Integer> calls(List<Instruction> code, String call) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof InvokeInstruction invoke
                    && call.equals(invoke.owner().asInternalName() + " " + invoke.name().stringValue())) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static void requireParameters(String where, Instruction instruction, int count) {
        InvokeInstruction invoke = (InvokeInstruction) instruction;
        if (invoke.typeSymbol().parameterCount() != count) {
            fail(where + " calls " + invoke.owner().asInternalName() + " " + invoke.name().stringValue()
                    + invoke.typeSymbol().descriptorString() + ", where this check reads calls with " + count
                    + " parameters");
        }
    }

    /** The local the instruction at {@code index} loads, or the one before it if it is a checkcast; else -1. */
    private static int loadedSlot(List<Instruction> code, int index) {
        int load = index;
        if (load >= 0 && code.get(load) instanceof TypeCheckInstruction check && check.opcode() == Opcode.CHECKCAST) {
            load--;
        }
        return load >= 0 && code.get(load) instanceof LoadInstruction instruction ? instruction.slot() : -1;
    }
}

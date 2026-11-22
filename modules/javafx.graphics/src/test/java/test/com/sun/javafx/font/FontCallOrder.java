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
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The calls in one method of a font class, and the branches, returns and exception handlers around them, read from
 * its class file, for the checks that what a call needs comes before or after it, or that a call is never made,
 * where no run-time test can tell. A call is {@code owner name}, the owner an internal name: owner and name decide,
 * never the opcode (javac 27 calls a private method with {@code invokevirtual}) or the descriptor.
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
     * Fails unless {@code method} of {@code type} calls {@code release} on each way out of its first call of
     * {@code region}, javac's two copies of a {@code finally}: it returns after that call, the nearer of a call of
     * {@code region} and a call of {@code release} before each such return is a call of {@code release}, and a
     * handler that catches everything covers that call and calls {@code release} before it throws. {@code protects}
     * says what the release is for: a failure gives it after "so that", and then what was found.
     */
    static void assertReleasedOnEveryExit(String type, String method, String region, String release,
                                          String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must call " + release + " before each return after its first call of "
                + region + ", and in a handler that catches everything that call throws, before the handler throws, so"
                + " that " + protects + "; but ";
        List<Integer> regionCalls = calls(instructions, region);
        if (regionCalls.isEmpty()) {
            fail(pin + "it never calls " + region);
        }
        int regionIndex = regionCalls.getFirst();
        List<Integer> releases = calls(instructions, release);
        boolean returns = false;
        for (int i = regionIndex + 1; i < instructions.size(); i++) {
            if (instructions.get(i) instanceof ReturnInstruction) {
                returns = true;
                int lastRegion = lastBefore(regionCalls, i);
                if (lastBefore(releases, i) < lastRegion) {
                    fail(pin + "the return at instruction " + i + " follows its call of " + region + " at instruction "
                            + lastRegion + " with no call of " + release + " between them");
                }
            }
        }
        if (!returns) {
            fail(pin + "it never returns after its call of " + region + " at instruction " + regionIndex);
        }
        List<String> handlers = new ArrayList<>();
        for (ExceptionCatch handler : code.handlers()) {
            int start = code.labelIndexes().get(handler.tryStart());
            int end = code.labelIndexes().get(handler.tryEnd());
            int first = code.labelIndexes().get(handler.handler());
            String caught = handler.catchType().map(ClassEntry::asInternalName).orElse("any");
            handlers.add(caught + " from instruction " + start + " to " + end + ", handled at " + first);
            boolean catchesEverything = handler.catchType().isEmpty() || caught.equals("java/lang/Throwable");
            if (catchesEverything && start <= regionIndex && regionIndex < end) {
                int releaseIndex = firstAtOrAfter(releases, first);
                int throwIndex = firstThrowAtOrAfter(instructions, first);
                if (releaseIndex >= 0 && (throwIndex < 0 || releaseIndex < throwIndex)) {
                    return;
                }
            }
        }
        fail(pin + "no handler that catches everything covers its call of " + region + " at instruction "
                + regionIndex + " and calls " + release + " before it throws; its handlers are " + handlers);
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code call} and returns what its first call of it answers at
     * once, javac's shape of {@code return call(...);}. {@code protects} says what returning it is for: a failure
     * gives it after "so that", and then what was found.
     */
    static void assertReturnsFirstCallOf(String type, String method, String call, String protects) {
        List<Instruction> code = instructions(type, method);
        String pin = type + "." + method + " must return what its first call of " + call + " answers, so that "
                + protects + "; but ";
        int callIndex = firstCall(code, call);
        if (callIndex < 0) {
            fail(pin + "it never calls " + call);
        }
        Instruction next = code.get(callIndex + 1);
        if (!(next instanceof ReturnInstruction)) {
            fail(pin + "instruction " + (callIndex + 1) + ", after that call at instruction " + callIndex + ", is "
                    + next.opcode().name().toLowerCase(Locale.ROOT) + ", not a return");
        }
    }

    /**
     * Fails if {@code method} of {@code type} calls any of {@code forbidden}. {@code protects} says why it may not: a
     * failure gives it after "so that", and then what was found.
     */
    static void assertNeverCalls(String type, String method, List<String> forbidden, String protects) {
        List<Instruction> code = instructions(type, method);
        for (String call : forbidden) {
            List<Integer> indexes = calls(code, call);
            if (!indexes.isEmpty()) {
                fail(type + "." + method + " must call none of " + String.join(", ", forbidden) + ", so that "
                        + protects + "; but it calls " + call + " at instructions " + indexes);
            }
        }
    }

    /**
     * Fails unless the first branch after the first call of {@code condition} in {@code method} of {@code type} is an
     * {@code ifeq}, javac's shape of {@code if (condition) { ... }}, each call of every one of {@code guarded} lies on
     * its fall-through, after the branch and before its target, and each call of every one of {@code unguarded} lies
     * at or after the target. {@code protects} says what the guard is for: a failure gives it after "so that", and
     * then what was found.
     */
    static void assertGuards(String type, String method, String condition, List<String> guarded,
                             List<String> unguarded, String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must call " + String.join(" and ", guarded) + " only where " + condition
                + " answers true" + (unguarded.isEmpty() ? "" : ", and " + String.join(" and ", unguarded)
                + " outside that branch") + ", so that " + protects + "; but ";
        int conditionIndex = firstCall(instructions, condition);
        if (conditionIndex < 0) {
            fail(pin + "it never calls " + condition);
        }
        int branchIndex = firstBranchAfter(instructions, conditionIndex);
        if (branchIndex < 0) {
            fail(pin + "no branch follows its first call of " + condition);
        }
        BranchInstruction branch = (BranchInstruction) instructions.get(branchIndex);
        if (branch.opcode() != Opcode.IFEQ) {
            fail(pin + "the first branch after its first call of " + condition + " is "
                    + branch.opcode().name().toLowerCase(Locale.ROOT) + " at instruction " + branchIndex
                    + ", not ifeq");
        }
        int targetIndex = code.labelIndexes().get(branch.target());
        if (targetIndex <= branchIndex) {
            fail(pin + "the ifeq at instruction " + branchIndex + " jumps back to instruction " + targetIndex);
        }
        String ifeq = "the ifeq at instruction " + branchIndex + ", which jumps to instruction " + targetIndex;
        for (String call : guarded) {
            for (int index : requiredCalls(instructions, call, pin)) {
                if (index <= branchIndex || index >= targetIndex) {
                    fail(pin + "it calls " + call + " at instruction " + index + ", outside the fall-through of "
                            + ifeq);
                }
            }
        }
        for (String call : unguarded) {
            for (int index : requiredCalls(instructions, call, pin)) {
                if (index < targetIndex) {
                    fail(pin + "it calls " + call + " at instruction " + index + ", before the target of " + ifeq);
                }
            }
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
        return code(type, method).instructions();
    }

    /** The code of the one method of {@code type} named {@code method}. */
    private static MethodCode code(String type, String method) {
        ClassModel model = FontClassFiles.classModel(type, "the call-order checks read the production classes");
        List<MethodModel> named = model.methods().stream().filter(m -> m.methodName().equalsString(method)).toList();
        if (named.size() != 1) {
            fail(type + " has " + named.size() + " methods named " + method + "; a call-order check needs one");
        }
        List<Instruction> instructions = new ArrayList<>();
        Map<Label, Integer> labelIndexes = new HashMap<>();
        List<ExceptionCatch> handlers = new ArrayList<>();
        named.getFirst().code().ifPresent(code -> code.forEach(element -> {
            if (element instanceof Instruction instruction) {
                instructions.add(instruction);
            } else if (element instanceof LabelTarget target) {
                labelIndexes.put(target.label(), instructions.size());
            } else if (element instanceof ExceptionCatch handler) {
                handlers.add(handler);
            }
        }));
        return new MethodCode(instructions, labelIndexes, handlers);
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

    private static List<Integer> requiredCalls(List<Instruction> code, String call, String pin) {
        List<Integer> calls = calls(code, call);
        if (calls.isEmpty()) {
            fail(pin + "it never calls " + call);
        }
        return calls;
    }

    /** The index in {@code code} of the first branch after the instruction at {@code index}, or -1. */
    private static int firstBranchAfter(List<Instruction> code, int index) {
        for (int i = index + 1; i < code.size(); i++) {
            if (code.get(i) instanceof BranchInstruction) {
                return i;
            }
        }
        return -1;
    }

    /** The greatest of {@code indexes} below {@code index}, or -1. */
    private static int lastBefore(List<Integer> indexes, int index) {
        int last = -1;
        for (int candidate : indexes) {
            if (candidate < index) {
                last = candidate;
            }
        }
        return last;
    }

    /** The least of {@code indexes} at or above {@code index}, or -1. */
    private static int firstAtOrAfter(List<Integer> indexes, int index) {
        for (int candidate : indexes) {
            if (candidate >= index) {
                return candidate;
            }
        }
        return -1;
    }

    /** The index in {@code code} of the first throw at or after {@code index}, or -1. */
    private static int firstThrowAtOrAfter(List<Instruction> code, int index) {
        for (int i = index; i < code.size(); i++) {
            if (code.get(i) instanceof ThrowInstruction) {
                return i;
            }
        }
        return -1;
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

    /**
     * The instructions of a method in code order, for each label the index of the instruction it marks, and its
     * exception handlers, all from one parse: the label of a branch or a handler is found only among the label
     * targets of the parse that read it.
     */
    private record MethodCode(List<Instruction> instructions, Map<Label, Integer> labelIndexes,
                              List<ExceptionCatch> handlers) {
    }
}

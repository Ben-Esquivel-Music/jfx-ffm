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
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodHandleEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The calls in one method of a font class, and the branches, returns, exception handlers and monitors around them,
 * read from its class file, for the checks that what a call needs comes before or after it, or that a call is never
 * made, where no run-time test can tell; in one method, where and how often a field is read and stored, with or
 * without its monitor, whether it takes a monitor at all, whether it stores only fresh copies of an array that it
 * filled, or a new array only once it filled it, and whether it writes into any array at all; over all of a class's
 * methods, what is stored in one of its fields and where that field is read; which
 * modifiers a field carries; over the methods of a class and its nest, which of them reach some fields or calls
 * without the instance's monitor; and, over the methods of several classes, which of them make a call. A call is
 * {@code owner name}, the owner an internal name: owner and name decide, never the opcode (javac 27 calls a private
 * method with {@code invokevirtual}) or the descriptor, except where the callers of a private method are found, by
 * owner, name and descriptor. A method is named by its name, or by {@code name(descriptor)} where several share it.
 */
final class FontCallOrder {

    private static final String CLASS_FILES = "the call-order checks read the production classes";

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
     * Fails unless {@code method} of {@code type} calls {@code first}, and each of its calls of {@code first} is
     * followed by a call of {@code then} with no instruction between the two that branches, switches, returns or
     * throws, and no handler beginning after that call of {@code first} and at or before that call of {@code then}:
     * javac's shape of two statements in a row, where {@code then} runs whenever {@code first} returns, unless an
     * instruction between them throws. {@code protects} says what that is for: a failure gives it after "so that",
     * and then what was found.
     */
    static void assertCallFollowsUnconditionally(String type, String method, String first, String then,
                                                 String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must call " + then + " after each call of " + first + ", with no"
                + " branch, switch, return, throw or handler between them, so that " + protects + "; but ";
        List<Integer> firstCalls = requiredCalls(instructions, first, pin);
        List<Integer> thenCalls = requiredCalls(instructions, then, pin);
        for (int firstIndex : firstCalls) {
            int thenIndex = firstAtOrAfter(thenCalls, firstIndex + 1);
            if (thenIndex < 0) {
                fail(pin + "its call of " + first + " at instruction " + firstIndex + " is followed by no call of "
                        + then);
            }
            String between = " between its call of " + first + " at instruction " + firstIndex + " and its call of "
                    + then + " at instruction " + thenIndex;
            for (int i = firstIndex + 1; i < thenIndex; i++) {
                Instruction instruction = instructions.get(i);
                if (!jumpTargets(instruction).isEmpty() || !fallsThrough(instruction)) {
                    fail(pin + shown(instruction) + " at instruction " + i + " comes" + between);
                }
            }
            for (ExceptionCatch handler : code.handlers()) {
                int handlerIndex = code.labelIndexes().get(handler.handler());
                if (firstIndex < handlerIndex && handlerIndex <= thenIndex) {
                    fail(pin + "the handler at instruction " + handlerIndex + " begins" + between);
                }
            }
        }
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code release} on each way out of its first call of
     * {@code region} that its code spells out, javac's copies of a {@code finally}: it returns after that call, the
     * nearer of a call of {@code region} and a call of {@code release} before each return or throw after that call
     * is a call of {@code release}, and each handler whose try range covers that call, whatever it catches, calls
     * {@code release} after its first instruction and before each return or throw, one of these handlers catching
     * everything. Each part reads code order over the instructions that the method's first instruction, or the
     * handler's, reaches without an exception, so a call that only a handler reaches counts only for that handler's
     * exits. A call on one side of a branch counts for every exit after it. An exception that a call outside these
     * handlers throws is not an exit it reads. {@code protects} says what the release is for: a failure gives it after
     * "so that", and then what was found.
     */
    static void assertReleasedOnEveryExit(String type, String method, String region, String release,
                                          String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must call " + release + " before each return or throw after its first"
                + " call of " + region + ", and in each handler that covers that call, one of which catches everything,"
                + " before the handler returns or throws, so that " + protects + "; but ";
        List<Integer> regionCalls = calls(instructions, region);
        if (regionCalls.isEmpty()) {
            fail(pin + "it never calls " + region);
        }
        int regionIndex = regionCalls.getFirst();
        List<Integer> releases = calls(instructions, release);
        BitSet reached = reachedWithoutAnException(code, 0);
        if (!reached.get(regionIndex)) {
            fail(pin + "only a handler reaches its call of " + region + " at instruction " + regionIndex);
        }
        boolean returns = false;
        for (int i = regionIndex + 1; i < instructions.size(); i++) {
            if (reached.get(i) && isExit(instructions.get(i))) {
                if (instructions.get(i) instanceof ReturnInstruction) {
                    returns = true;
                }
                int lastRegion = lastBefore(regionCalls, i, reached);
                if (lastBefore(releases, i, reached) < lastRegion) {
                    fail(pin + "the " + exitName(instructions.get(i)) + " at instruction " + i + " follows its call of "
                            + region + " at instruction " + lastRegion + " with no call of " + release + " between"
                            + " them that the method reaches without an exception");
                }
            }
        }
        if (!returns) {
            fail(pin + "it never returns after its call of " + region + " at instruction " + regionIndex);
        }
        List<String> handlers = new ArrayList<>();
        boolean catchesEverything = false;
        for (ExceptionCatch handler : code.handlers()) {
            int start = code.labelIndexes().get(handler.tryStart());
            int end = code.labelIndexes().get(handler.tryEnd());
            if (start <= regionIndex && regionIndex < end) {
                int first = code.labelIndexes().get(handler.handler());
                String caught = handler.catchType().map(ClassEntry::asInternalName).orElse("any");
                String found = caught + " from instruction " + start + " to " + end + ", handled at " + first;
                String unreleased = unreleasedExit(code, first, releases, release);
                if (unreleased != null) {
                    fail(pin + "the handler of " + found + ", which covers that call, " + unreleased);
                }
                handlers.add(found);
                if (handler.catchType().isEmpty() || caught.equals("java/lang/Throwable")) {
                    catchesEverything = true;
                }
            }
        }
        if (!catchesEverything) {
            fail(pin + "no handler that catches everything covers its call of " + region + " at instruction "
                    + regionIndex + "; the handlers that cover it are " + handlers);
        }
    }

    /**
     * Why the handler that starts at instruction {@code first} fails to call {@code release}, whose calls lie at
     * {@code releases}, after {@code first} and before each return or throw it reaches without an exception: such an
     * exit with no call before it, or no exit at all; null where it does not fail.
     */
    private static String unreleasedExit(MethodCode code, int first, List<Integer> releases, String release) {
        List<Instruction> instructions = code.instructions();
        BitSet reached = reachedWithoutAnException(code, first);
        boolean exits = false;
        for (int i = reached.nextSetBit(0); i >= 0; i = reached.nextSetBit(i + 1)) {
            if (isExit(instructions.get(i))) {
                exits = true;
                if (lastBefore(releases, i, reached) < first) {
                    return "reaches the " + exitName(instructions.get(i)) + " at instruction " + i + " with no call of "
                            + release + " after instruction " + first + " before it";
                }
            }
        }
        return exits ? null : "reaches no return or throw";
    }

    /**
     * Fails unless each handler whose try range covers the first call of {@code region} in {@code method} of
     * {@code type}, whatever it catches, reaches without an exception a store of {@code field}, a field of its own,
     * and a return, and no throw, one of these handlers catching everything: javac's shape of a
     * {@code catch (Throwable t)} that stores the throwable and goes on to the method's end. It reads where each
     * handler leads, not what it stores. {@code protects} says what that is for: a failure gives it after "so that",
     * and then what was found.
     */
    static void assertKeepsWhatItThrows(String type, String method, String region, String field, String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must store " + field + " and return, never throw, in each handler that"
                + " covers its first call of " + region + ", one of which catches everything, so that " + protects
                + "; but ";
        int regionIndex = firstCall(instructions, region);
        if (regionIndex < 0) {
            fail(pin + "it never calls " + region);
        }
        List<String> handlers = new ArrayList<>();
        boolean catchesEverything = false;
        for (ExceptionCatch handler : code.handlers()) {
            int start = code.labelIndexes().get(handler.tryStart());
            int end = code.labelIndexes().get(handler.tryEnd());
            if (start <= regionIndex && regionIndex < end) {
                int first = code.labelIndexes().get(handler.handler());
                String caught = handler.catchType().map(ClassEntry::asInternalName).orElse("any");
                BitSet reached = reachedWithoutAnException(code, first);
                List<String> problems = new ArrayList<>();
                if (reached.stream().noneMatch(i -> accesses(instructions.get(i), Opcode.PUTFIELD, type, field))) {
                    problems.add("never stores it");
                }
                if (reached.stream().noneMatch(i -> instructions.get(i) instanceof ReturnInstruction)) {
                    problems.add("never returns");
                }
                reached.stream().filter(i -> instructions.get(i) instanceof ThrowInstruction).findFirst()
                        .ifPresent(i -> problems.add("throws at instruction " + i));
                String found = caught + " from instruction " + start + " to " + end + ", handled at " + first;
                if (!problems.isEmpty()) {
                    fail(pin + "the handler of " + found + ", which covers that call, "
                            + String.join(" and ", problems));
                }
                handlers.add(found);
                if (handler.catchType().isEmpty() || caught.equals("java/lang/Throwable")) {
                    catchesEverything = true;
                }
            }
        }
        if (!catchesEverything) {
            fail(pin + "no handler that catches everything covers its call of " + region + " at instruction "
                    + regionIndex + "; the handlers that cover it are " + handlers);
        }
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
     * Fails if {@code method} of {@code type} calls any of {@code forbidden}: a call, or a class alone, which stands
     * for every method of it and of its nested classes. {@code protects} says why it may not: a failure gives it
     * after "so that", and then what was found.
     */
    static void assertNeverCalls(String type, String method, List<String> forbidden, String protects) {
        List<Instruction> code = instructions(type, method);
        for (String call : forbidden) {
            List<Integer> indexes = call.contains(" ") ? calls(code, call) : callsOfClass(code, call);
            if (!indexes.isEmpty()) {
                fail(type + "." + method + " must call none of " + String.join(", ", forbidden) + ", so that "
                        + protects + "; but it calls " + call + " at instructions " + indexes);
            }
        }
    }

    /**
     * Fails unless the first branch after the first call of {@code condition} in {@code method} of {@code type} is an
     * {@code ifeq}, javac's shape of {@code if (condition) { ... }}, each call of every one of {@code guarded} lies on
     * its fall-through, after the branch and before its target, where nothing reaches it except through the branch
     * (the rule of {@link #assertCallsOnlyWhereAnswerIsNotZero}), and each call of every one of {@code unguarded} lies
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
                String entry = entryFromOutside(code, branchIndex, branchIndex + 1, targetIndex, index);
                if (entry != null) {
                    fail(pin + "it calls " + call + " at instruction " + index + " on the fall-through of " + ifeq
                            + ", where " + entry);
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
     * Fails unless {@code method} of {@code type} calls {@code call}, and reaches each of its calls of it only where
     * what its call of {@code answering} answered is not 0. That answer is compared with 0 as it is, through the local
     * it was stored in, or through the {@code address()} of the segment it is, in one of javac's shapes of a guard:
     * {@code if (value != 0) { ... }} and {@code if (value > 0) { ... }}, with the call on the fall-through, or
     * {@code if (value == 0) return ...;}, the same with a {@code throw}, and
     * {@code if (value == 0) { ... } else { ... }}, with the call at or after the branch's target and before the end
     * of the {@code else}. Nothing may reach the guarded instructions up to the call except through the comparison:
     * no branch or switch outside them may jump to one of them, and none of them may be a handler of a try range that
     * starts outside them. Another shape of a correct guard, such as {@code 0 < value} or
     * {@code value == 0 || other}, fails this check. It reads the shape of the code, not its data: a store into the
     * value after the comparison, or a call handed another value than the one compared, is not seen.
     * {@code protects} says what the guard is for: a failure gives it after "so that", and then what was found.
     */
    static void assertCallsOnlyWhereAnswerIsNotZero(String type, String method, String answering, String call,
                                                    String protects) {
        assertCallsOnlyWhereNotZero(type, method, "the answer of " + answering,
                                    test -> answering.equals(test.answerOf()), call, protects);
    }

    /**
     * The same for parameter {@code index} of {@code method}, 0 the first, compared with 0 while nothing has been
     * stored in it.
     */
    static void assertCallsOnlyWhereParameterIsNotZero(String type, String method, int index, String call,
                                                       String protects) {
        int slot = parameterSlot(type, method, index);
        assertCallsOnlyWhereNotZero(type, method, "parameter " + index,
                                    test -> test.local() == slot && !test.stored(), call, protects);
    }

    private static void assertCallsOnlyWhereNotZero(String type, String method, String value,
                                                    Predicate<ZeroTest> tested, String call, String protects) {
        MethodCode code = code(type, method);
        String pin = type + "." + method + " must call " + call + " only where " + value + " is not 0, so that "
                + protects + "; but ";
        List<ZeroTest> tests = zeroTests(code).stream().filter(tested).toList();
        for (int index : requiredCalls(code.instructions(), call, pin)) {
            boolean kept = false;
            List<String> leaks = new ArrayList<>();
            for (ZeroTest test : tests) {
                String leak = leak(code, test, index);
                if (leak == null) {
                    kept = true;
                } else {
                    leaks.add(test + ", where " + leak);
                }
            }
            if (!kept) {
                fail(pin + "its call at instruction " + index + " follows no comparison of " + value
                        + " with 0 that keeps it from a 0; " + value + " is compared with 0 at " + leaks);
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

    /**
     * Fails unless {@code method} of {@code type} stores what its first call of {@code answering} answers in a local
     * variable at once and stores nothing else in it, calls {@code call}, and each of its calls of {@code call} takes
     * argument {@code index}, 0 the first, straight from that local: a load of it followed only by what pushes the
     * later arguments, each a load, a constant, a field read, a cast or a call, with no branch or switch jumping among
     * them. {@code protects} says what that is for: a failure gives it after "so that", and then what was found.
     */
    static void assertArgumentIsAnswerOf(String type, String method, String call, int index, String answering,
                                         String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must hand each of its calls of " + call + ", as argument " + index
                + ", what its first call of " + answering + " answers, straight from the local it keeps that in, so"
                + " that " + protects + "; but ";
        int answer = firstCall(instructions, answering);
        if (answer < 0) {
            fail(pin + "it never calls " + answering);
        }
        StoreInstruction kept = instructions.get(answer + 1) instanceof StoreInstruction store ? store : null;
        if (kept == null) {
            fail(pin + "instruction " + (answer + 1) + ", after that call at instruction " + answer + ", is "
                    + shown(instructions.get(answer + 1)) + ", not a store into a local");
        }
        for (int i = 0; i < instructions.size(); i++) {
            if (i != answer + 1 && instructions.get(i) instanceof StoreInstruction store && overlaps(store, kept)) {
                fail(pin + "it also stores into local " + store.slot() + " at instruction " + i + ", where it keeps"
                        + " that answer from instruction " + (answer + 1));
            }
        }
        BitSet jumpedTo = jumpedTo(code);
        for (int callIndex : requiredCalls(instructions, call, pin)) {
            if (index >= ((InvokeInstruction) instructions.get(callIndex)).typeSymbol().parameterCount()) {
                fail(pin + "its call at instruction " + callIndex + " has no argument " + index);
            }
            int push = argumentPush(instructions, callIndex, index);
            if (push < 0) {
                fail(pin + "not every later argument of its call at instruction " + callIndex + " is pushed by a load,"
                        + " a constant, a field read, a cast or a call, which is all this check steps back over");
            }
            int jump = jumpedTo.nextSetBit(push);
            if (jump >= 0 && jump <= callIndex) {
                fail(pin + "a branch or switch jumps to instruction " + jump + ", among the arguments of its call at"
                        + " instruction " + callIndex);
            }
            Instruction pushed = instructions.get(push);
            int slot = pushed instanceof LoadInstruction load ? load.slot() : -1;
            if (slot != kept.slot()) {
                String argument = slot < 0 ? shown(pushed) : "local " + slot;
                fail(pin + "its call at instruction " + callIndex + " takes argument " + index + " from " + argument
                        + " at instruction " + push + ", not from local " + kept.slot());
            }
        }
    }

    /**
     * Fails unless a handler of {@code method} of {@code type} that catches everything covers its first call of
     * {@code region} and calls {@code release} before it throws, with no branch from its first instruction to that
     * call. {@code protects} says what the release is for: a failure gives it after "so that", and then what was found.
     */
    static void assertHandlerReleasesUnconditionally(String type, String method, String region, String release,
                                                     String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must call " + release + ", with no condition before it, in a handler that"
                + " catches everything its first call of " + region + " throws, before the handler throws, so that "
                + protects + "; but ";
        int regionIndex = firstCall(instructions, region);
        if (regionIndex < 0) {
            fail(pin + "it never calls " + region);
        }
        List<Integer> releases = calls(instructions, release);
        List<String> handlers = new ArrayList<>();
        for (ExceptionCatch handler : code.handlers()) {
            int start = code.labelIndexes().get(handler.tryStart());
            int end = code.labelIndexes().get(handler.tryEnd());
            int first = code.labelIndexes().get(handler.handler());
            String caught = handler.catchType().map(ClassEntry::asInternalName).orElse("any");
            boolean catchesEverything = handler.catchType().isEmpty() || caught.equals("java/lang/Throwable");
            int releaseIndex = firstAtOrAfter(releases, first);
            int throwIndex = firstThrowAtOrAfter(instructions, first);
            int branchIndex = firstBranchAfter(instructions, first - 1);
            String found = caught + " from instruction " + start + " to " + end + ", handled at " + first;
            if (catchesEverything && start <= regionIndex && regionIndex < end && releaseIndex >= 0
                    && (throwIndex < 0 || releaseIndex < throwIndex)) {
                if (branchIndex < 0 || branchIndex > releaseIndex) {
                    return;
                }
                found += ", where " + instructions.get(branchIndex).opcode().name().toLowerCase(Locale.ROOT)
                        + " at instruction " + branchIndex + " comes before the call of " + release + " at instruction "
                        + releaseIndex;
            }
            handlers.add(found);
        }
        fail(pin + "no handler that catches everything covers its call of " + region + " at instruction "
                + regionIndex + " and calls " + release + " unconditionally before it throws; its handlers are "
                + handlers);
    }

    /**
     * Fails unless {@code type} stores {@code field}, a field of its own, and the instruction before each of its
     * stores, in any method, is a call of one of {@code values}. {@code protects} says what that is for: a failure
     * gives it after "so that", and then what was found.
     */
    static void assertFieldStoresFollow(String type, String field, List<String> values, String protects) {
        String pin = type + "." + field + " may only be stored with what a call of " + String.join(" or ", values)
                + " answers, so that " + protects + "; but ";
        boolean stored = false;
        for (MethodModel method : FontClassFiles.classModel(type, CLASS_FILES).methods()) {
            List<Instruction> code = instructionsOf(method);
            for (int i = 0; i < code.size(); i++) {
                if (accesses(code.get(i), Opcode.PUTFIELD, type, field)) {
                    stored = true;
                    Instruction before = i > 0 ? code.get(i - 1) : null;
                    if (!(before instanceof InvokeInstruction invoke) || !values.contains(callOf(invoke))) {
                        fail(pin + method.methodName().stringValue() + " stores it at instruction " + i + " after "
                                + (before == null ? "nothing" : shown(before)));
                    }
                }
            }
        }
        if (!stored) {
            fail(pin + "no method stores it, so this check no longer pins anything");
        }
    }

    /**
     * Fails unless {@code type} stores {@code field}, a field of its own, and no store of it, in any method, follows
     * {@code aconst_null}: javac's shape of {@code field = null;}. It reads code order: a null that reaches the store
     * through a local, a branch or a call is not seen. {@code protects} says what that is for: a failure gives it after
     * "so that", and then what was found.
     */
    static void assertNeverStoresNull(String type, String field, String protects) {
        String pin = type + "." + field + " may never be stored with null, so that " + protects + "; but ";
        boolean stored = false;
        for (MethodModel method : FontClassFiles.classModel(type, CLASS_FILES).methods()) {
            List<Instruction> code = instructionsOf(method);
            for (int store : fieldStores(code, type, field)) {
                stored = true;
                if (code.get(store - 1).opcode() == Opcode.ACONST_NULL) {
                    fail(pin + method.methodName().stringValue() + " stores null into it at instruction " + store);
                }
            }
        }
        if (!stored) {
            fail(pin + "no method stores it, so this check no longer pins anything");
        }
    }

    /**
     * Fails unless {@code type} reads {@code field}, a field of its own, and every read of it lies in one of
     * {@code methods}. {@code protects} says what that is for: a failure gives it after "so that", and then what was
     * found.
     */
    static void assertFieldReadOnlyIn(String type, String field, List<String> methods, String protects) {
        String pin = type + "." + field + " may only be read in " + String.join(" and ", methods) + ", so that "
                + protects + "; but ";
        boolean read = false;
        for (MethodModel method : FontClassFiles.classModel(type, CLASS_FILES).methods()) {
            List<Instruction> code = instructionsOf(method);
            for (int i = 0; i < code.size(); i++) {
                if (accesses(code.get(i), Opcode.GETFIELD, type, field)) {
                    read = true;
                    if (!methods.contains(method.methodName().stringValue())) {
                        fail(pin + method.methodName().stringValue() + " reads it at instruction " + i);
                    }
                }
            }
        }
        if (!read) {
            fail(pin + "no method reads it, so this check no longer pins anything");
        }
    }

    /**
     * Fails unless some method of {@code types} calls {@code call}, and every method of them that does, constructors,
     * initialisers and synthetic methods included, is one of {@code callers}, each {@code type method}.
     * {@code protects} says what that is for: a failure gives it after "so that", and then what was found.
     */
    static void assertCalledOnlyIn(List<String> types, String call, Set<String> callers, String protects) {
        String pin = call + " may only be called in " + String.join(" and ", callers) + ", so that " + protects
                + "; but ";
        List<String> found = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        for (String type : types) {
            for (MethodModel method : FontClassFiles.classModel(type, CLASS_FILES).methods()) {
                List<Integer> indexes = calls(instructionsOf(method), call);
                String caller = type + " " + method.methodName().stringValue();
                if (!indexes.isEmpty()) {
                    found.add(caller);
                    if (!callers.contains(caller)) {
                        outside.add(caller + " calls it at instructions " + indexes);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            fail(pin + "no method of " + types.size() + " classes calls it, so this check no longer pins anything");
        }
        if (!outside.isEmpty()) {
            fail(pin + String.join("; ", outside));
        }
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code call}, and makes each of its calls of it in a handler
     * of {@code catchType} whose try range covers its first call of {@code covered}, after the handler's first
     * instruction and before the first return or throw from there. {@code protects} says what that is for: a failure
     * gives it after "so that", and then what was found.
     */
    static void assertCalledOnlyInHandler(String type, String method, String call, String catchType, String covered,
                                          String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " may call " + call + " only in a handler of " + catchType + " around its"
                + " call of " + covered + ", so that " + protects + "; but ";
        int coveredIndex = firstCall(instructions, covered);
        if (coveredIndex < 0) {
            fail(pin + "it never calls " + covered);
        }
        List<int[]> handlerRanges = new ArrayList<>();
        for (ExceptionCatch handler : code.handlers()) {
            int start = code.labelIndexes().get(handler.tryStart());
            int end = code.labelIndexes().get(handler.tryEnd());
            boolean caught = handler.catchType().map(ClassEntry::asInternalName).orElse("any").equals(catchType);
            if (caught && start <= coveredIndex && coveredIndex < end) {
                int first = code.labelIndexes().get(handler.handler());
                handlerRanges.add(new int[] {first, firstExitAtOrAfter(instructions, first)});
            }
        }
        if (handlerRanges.isEmpty()) {
            fail(pin + "no handler of " + catchType + " covers its call of " + covered + " at instruction "
                    + coveredIndex);
        }
        for (int index : requiredCalls(instructions, call, pin)) {
            if (handlerRanges.stream().noneMatch(range -> range[0] <= index && index < range[1])) {
                fail(pin + "it calls " + call + " at instruction " + index + ", outside the handlers that start and"
                        + " end at " + handlerRanges.stream().map(range -> range[0] + "-" + range[1]).toList());
            }
        }
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code call} and stores {@code field}, {@code owner name},
     * and makes each of its stores of it after its first call of {@code call}. {@code protects} says what that is
     * for: a failure gives it after "so that", and then what was found.
     */
    static void assertWrittenAfter(String type, String method, String field, String call, String protects) {
        List<Instruction> code = instructions(type, method);
        String pin = type + "." + method + " may store " + field + " only after its first call of " + call
                + ", so that " + protects + "; but ";
        int callIndex = firstCall(code, call);
        if (callIndex < 0) {
            fail(pin + "it never calls " + call);
        }
        boolean stored = false;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof FieldInstruction store
                    && (store.opcode() == Opcode.PUTFIELD || store.opcode() == Opcode.PUTSTATIC)
                    && field.equals(store.owner().asInternalName() + " " + store.name().stringValue())) {
                stored = true;
                if (i < callIndex) {
                    fail(pin + "it stores it at instruction " + i + ", before that call at instruction " + callIndex);
                }
            }
        }
        if (!stored) {
            fail(pin + "it never stores it, so this check no longer pins anything");
        }
    }

    /**
     * Fails unless {@code method} of {@code type} calls {@code guarded}, {@code after} and {@code before}, and stores
     * the local variable that each call of {@code guarded} depends on, the one loaded just ahead of the last branch
     * before that call (javac's shape of {@code if (local) { ... }}), between its first call of {@code after} and its
     * first call of {@code before}. {@code protects} says what that order is for: a failure gives it after "so that",
     * and then what was found.
     */
    static void assertGuardStoredBetween(String type, String method, String guarded, String after, String before,
                                         String protects) {
        List<Instruction> code = instructions(type, method);
        String pin = type + "." + method + " must store the local that its calls of " + guarded + " depend on after"
                + " its first call of " + after + " and before its first call of " + before + ", so that " + protects
                + "; but ";
        int afterIndex = firstCall(code, after);
        int beforeIndex = firstCall(code, before);
        if (afterIndex < 0 || beforeIndex < 0) {
            fail(pin + "it never calls " + (afterIndex < 0 ? after : before));
        }
        for (int index : requiredCalls(code, guarded, pin)) {
            int branch = index - 1;
            while (branch >= 0 && !(code.get(branch) instanceof BranchInstruction)) {
                branch--;
            }
            int slot = branch > 0 ? loadedSlot(code, branch - 1) : -1;
            if (slot < 0) {
                fail(pin + "no branch on a local variable comes before its call of " + guarded + " at instruction "
                        + index);
            }
            int store = lastStoreBefore(code, slot, beforeIndex);
            if (store < afterIndex) {
                fail(pin + "its call of " + guarded + " at instruction " + index + " depends on local " + slot
                        + ", which it last stores before instruction " + beforeIndex + " at instruction " + store
                        + ", not after instruction " + afterIndex);
            }
        }
    }

    /**
     * Fails if {@code method} of {@code type} is {@code synchronized} or enters a monitor. {@code protects} says why
     * it may take none: a failure gives it after "so that", and then what was found.
     */
    static void assertTakesNoMonitor(String type, String method, String protects) {
        String pin = type + "." + method + " may take no monitor, so that " + protects + "; but ";
        if (methodModel(type, method).flags().has(AccessFlag.SYNCHRONIZED)) {
            fail(pin + "it is synchronized");
        }
        List<Instruction> code = instructions(type, method);
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).opcode() == Opcode.MONITORENTER) {
                fail(pin + "it enters a monitor at instruction " + i);
            }
        }
    }

    /**
     * Fails unless every method of {@code nest}, a class and the members of its nest, that uses one of
     * {@code fields}, instance or static fields of the class, or makes one of {@code calls}, of any descriptor, holds
     * the monitor of the class's instance throughout: it is a {@code synchronized} instance method of the class, or it
     * is private and every method that calls it, found by owner, name and descriptor, meets this rule. A use is a
     * read, or a store other than a constructor's store of an instance field and the static initialiser's store of a
     * static field, which come before any other thread can reach the field: a constructor's store of a static field is
     * a use. A method of another member of the nest never meets the rule, nor does a private method that
     * a method handle constant of the nest names (the body of a lambda or a method reference, which any thread may
     * run); a private method that no method calls does. It reads which methods reach the fields and calls, not whose
     * they are. {@code protects} says what that is for: a failure gives it after "so that", and then each method that
     * breaks the rule and why.
     */
    static void assertReachedOnlyUnderTheMonitor(List<String> nest, List<String> fields, List<String> calls,
                                                 String protects) {
        String type = nest.getFirst();
        String pin = "every method of " + type + " or of its nest that reads or stores " + String.join(" or ", fields)
                + (calls.isEmpty() ? "" : ", or calls " + String.join(" or ", calls)) + ", must be a synchronized"
                + " instance method of " + type + ", or private and called only by methods that meet this rule, so"
                + " that " + protects + "; but ";
        List<MethodModel> methods = FontClassFiles.classModel(type, CLASS_FILES).methods();
        Map<String, MethodModel> nestMethods = new LinkedHashMap<>();
        Map<String, List<String>> callers = new HashMap<>();
        Map<String, String> unguarded = new LinkedHashMap<>();
        List<String> handles = new ArrayList<>();
        Map<String, Opcode> initialiserStores = new HashMap<>();
        for (String member : nest) {
            ClassModel model = FontClassFiles.classModel(member, CLASS_FILES);
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof MethodHandleEntry handle
                        && handle.reference().owner().asInternalName().equals(type)) {
                    handles.add(signature(handle.reference()));
                }
            }
            for (MethodModel method : model.methods()) {
                String caller = member.equals(type) ? signature(method) : member + " " + signature(method);
                nestMethods.put(caller, method);
                if (member.equals(type) && method.methodName().equalsString("<init>")) {
                    initialiserStores.put(caller, Opcode.PUTFIELD);
                } else if (member.equals(type) && method.methodName().equalsString("<clinit>")) {
                    initialiserStores.put(caller, Opcode.PUTSTATIC);
                }
                if (!member.equals(type)) {
                    unguarded.put(caller, "it is a method of " + member + ", which holds no monitor of " + type);
                }
                for (Instruction instruction : instructionsOf(method)) {
                    if (instruction instanceof InvokeInstruction invoke
                            && invoke.owner().asInternalName().equals(type)) {
                        String callee = invoke.name().stringValue() + invoke.typeSymbol().descriptorString();
                        callers.computeIfAbsent(callee, key -> new ArrayList<>()).add(caller);
                    }
                }
            }
        }
        List<MethodModel> privates = new ArrayList<>();
        for (MethodModel method : methods) {
            String signature = signature(method);
            boolean instance = !method.flags().has(AccessFlag.STATIC);
            if (method.flags().has(AccessFlag.SYNCHRONIZED) && instance) {
                continue;
            }
            if (method.flags().has(AccessFlag.SYNCHRONIZED)) {
                unguarded.put(signature, "it is static, so the monitor it holds is the class's");
            } else if (!method.flags().has(AccessFlag.PRIVATE)) {
                unguarded.put(signature, "it is neither synchronized nor private");
            } else if (handles.contains(signature)) {
                unguarded.put(signature, "it is private, but a method handle constant names it, so any thread may"
                        + " run it");
            } else {
                privates.add(method);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodModel method : privates) {
                String signature = signature(method);
                if (!unguarded.containsKey(signature)) {
                    for (String caller : callers.getOrDefault(signature, List.of())) {
                        if (unguarded.containsKey(caller)) {
                            unguarded.put(signature, "it is private, but " + caller + " calls it");
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }
        List<String> problems = new ArrayList<>();
        boolean reached = false;
        for (Map.Entry<String, MethodModel> method : nestMethods.entrySet()) {
            if (reaches(method.getValue(), initialiserStores.get(method.getKey()), type, fields, calls)) {
                reached = true;
                String why = unguarded.get(method.getKey());
                if (why != null) {
                    problems.add(method.getKey() + ": " + why);
                }
            }
        }
        if (!reached) {
            fail(pin + "no method reads those fields or makes those calls, so this check no longer pins anything");
        }
        if (!problems.isEmpty()) {
            fail(pin + String.join("; ", problems));
        }
    }

    /**
     * Fails unless {@code method} of {@code type} enters one monitor, only where a read of {@code field}, a field of
     * its own, finds it null, and makes each call of {@code call} inside that monitor, where its last read of
     * {@code field} since entering found it null again: javac's shape of
     * {@code if (field == null) { synchronized (this) { if (field == null) { ... } } }}, each read a {@code getfield}
     * followed by an {@code ifnonnull} that jumps past the monitor's exit, or past the call. Another shape of the
     * same locking fails this check. {@code protects} says what that is for: a failure gives it after "so that", and
     * then what was found.
     */
    static void assertCalledOnlyWhileNullUnderLock(String type, String method, String field, String call,
                                                   String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must enter its monitor only where " + field + " is null, and call " + call
                + " only inside it where " + field + " is null again, so that " + protects + "; but ";
        int[] monitor = monitorBlock(instructions, pin);
        int enter = monitor[0];
        int exit = monitor[1];
        String outside = nullTestPast(code, type, field, enter, exit);
        if (outside != null) {
            fail(pin + "before its monitorenter at instruction " + enter + ", " + outside);
        }
        for (int index : requiredCalls(instructions, call, pin)) {
            if (index <= enter || index >= exit) {
                fail(pin + "it calls " + call + " at instruction " + index + ", outside its monitor from instruction "
                        + enter + " to " + exit);
            }
            String inside = nullTestPast(code, type, field, index, index);
            if (inside != null) {
                fail(pin + "before its call of " + call + " at instruction " + index + ", " + inside);
            }
            int read = lastFieldRead(instructions, type, field, index);
            if (read < enter) {
                fail(pin + "it calls " + call + " at instruction " + index + " without reading " + field
                        + " after its monitorenter at instruction " + enter);
            }
        }
    }

    /**
     * Fails unless {@code method} of {@code type} stores each of {@code earlier} and {@code last}, fields of its own,
     * only inside its one monitor, and {@code last} after every store of the others, and no other method of
     * {@code type} stores any of them, except that a method named in {@code copiers} may store {@code last} with a
     * fresh copy it filled (the rule of {@link #assertPublishesOnlyFilledCopies}), which replaces what was published
     * and publishes nothing new. {@code protects} says what that is for: a failure gives it after "so that", and then
     * what was found.
     */
    static void assertStoredLastUnderLock(String type, String method, String last, List<String> earlier,
                                          List<String> copiers, String protects) {
        List<Instruction> instructions = instructions(type, method);
        String copies = copiers.isEmpty() ? "" : ", but for a filled copy of " + last + " in "
                + String.join(" or ", copiers);
        String pin = type + "." + method + " must store " + String.join(" and ", earlier) + " and only then " + last
                + ", all inside its monitor, and no other method of " + type + " may store any of them" + copies
                + ", so that " + protects + "; but ";
        int[] monitor = monitorBlock(instructions, pin);
        List<String> fields = new ArrayList<>(earlier);
        fields.add(last);
        int lastEarlier = -1;
        for (String field : fields) {
            List<Integer> stores = fieldStores(instructions, type, field);
            if (stores.isEmpty()) {
                fail(pin + "it never stores " + field);
            }
            for (int store : stores) {
                if (store <= monitor[0] || store >= monitor[1]) {
                    fail(pin + "it stores " + field + " at instruction " + store + ", outside its monitor from"
                            + " instruction " + monitor[0] + " to " + monitor[1]);
                }
            }
            if (!field.equals(last)) {
                lastEarlier = Math.max(lastEarlier, stores.getLast());
            }
        }
        int firstLast = fieldStores(instructions, type, last).getFirst();
        if (firstLast < lastEarlier) {
            fail(pin + "it stores " + last + " at instruction " + firstLast + ", before its store at instruction "
                    + lastEarlier);
        }
        for (MethodModel other : FontClassFiles.classModel(type, CLASS_FILES).methods()) {
            if (!other.methodName().equalsString(method)) {
                for (String field : fields) {
                    List<Integer> stores = fieldStores(instructionsOf(other), type, field);
                    boolean copy = field.equals(last) && copiers.contains(other.methodName().stringValue());
                    String unfilled = copy && !stores.isEmpty()
                            ? unfilledCopy(code(type, signature(other)), type, last) : null;
                    if (!stores.isEmpty() && (!copy || unfilled != null)) {
                        fail(pin + other.methodName().stringValue() + " stores " + field + " at instructions "
                                + stores + (unfilled == null ? "" : ", where " + unfilled));
                    }
                }
            }
        }
    }

    /**
     * Fails unless {@code method} of {@code type} reads {@code field}, a field of its own, once, stores it once,
     * reads it nowhere that the store leads to without an exception, and returns after the store: javac's shape of
     * {@code T value = field; if (value == null) { ...; field = value; } return value;}, which answers what it read
     * or stored and never what a second read, racing a store of another thread, finds. The read is the method's one
     * {@code getfield} of {@code field}, and no loop of the method runs it again. Another shape fails this check, and
     * a read in another method is not seen here. {@code protects} says what that is for: a failure gives it after "so
     * that", and then what was found.
     */
    static void assertReadOnceAndStoredOnce(String type, String method, String field, String protects) {
        MethodCode code = code(type, method);
        List<Instruction> instructions = code.instructions();
        String pin = type + "." + method + " must read " + field + " once, store it once and read " + field
                + " nowhere after that store, so that " + protects + "; but ";
        List<Integer> stores = fieldStores(instructions, type, field);
        if (stores.size() != 1) {
            fail(pin + "it stores it at instructions " + stores);
        }
        int store = stores.getFirst();
        BitSet reached = reachedWithoutAnException(code, store);
        boolean returns = false;
        for (int i = reached.nextSetBit(0); i >= 0; i = reached.nextSetBit(i + 1)) {
            if (accesses(instructions.get(i), Opcode.GETFIELD, type, field)) {
                fail(pin + "it reads it at instruction " + i + ", after its store at instruction " + store);
            }
            if (instructions.get(i) instanceof ReturnInstruction) {
                returns = true;
            }
        }
        if (!returns) {
            fail(pin + "it never returns after its store at instruction " + store);
        }
        requireOneRead(code, type, field, pin);
    }

    /**
     * Fails unless {@code method} of {@code type} reads {@code field}, a field of its own, once: with one
     * {@code getfield} that no loop of the method runs again, so that one call sees one value of the field, never two
     * between which another thread stored it. {@code protects} says what that is for: a failure gives it after "so
     * that", and then what was found.
     */
    static void assertFieldReadOnceIn(String type, String method, String field, String protects) {
        requireOneRead(code(type, method), type, field, type + "." + method + " must read " + field + " once, so that "
                + protects + "; but ");
    }

    /** Fails with {@code pin} unless {@code code} reads {@code field} of {@code type} once, in no loop. */
    private static void requireOneRead(MethodCode code, String type, String field, String pin) {
        List<Instruction> instructions = code.instructions();
        List<Integer> reads = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            if (accesses(instructions.get(i), Opcode.GETFIELD, type, field)) {
                reads.add(i);
            }
        }
        if (reads.size() != 1) {
            fail(pin + "it reads " + field + " at instructions " + reads);
        }
        int read = reads.getFirst();
        if (reachedWithoutAnException(code, read + 1).get(read)) {
            fail(pin + "its read at instruction " + read + " is in a loop, which reads it again");
        }
    }

    /**
     * Fails unless {@code method} of {@code type} stores {@code field}, an array field of its own, and that store and
     * every {@code aastore} of the method write only into a fresh copy, which is filled before it is published: the
     * value of each {@code putfield} of the field, and the array of each {@code aastore}, is an {@code aload} of a
     * local whose last store before it, in code order, directly follows {@code invokestatic java/util/Arrays copyOf}
     * and a {@code checkcast}; no branch, switch or handler lands anywhere from that call to that {@code aload}, both
     * included; and no {@code putfield} of the field that loads the local comes between that copy and an
     * {@code aastore} into it. With no landing in that range, code order is the order in which they run. That is
     * javac's shape of {@code T[] filled = Arrays.copyOf(array, length); filled[index] = value; field = filled;},
     * which writes only into an array that no other thread can read yet, and publishes it only once it is filled.
     * The local-variable form is required by design: an {@code aastore} is read as {@code local[local] = local}, so
     * {@code filled[index] = call();} fails this check. {@code protects} says what that is for: a failure gives it
     * after "so that", and then what was found.
     */
    static void assertPublishesOnlyFilledCopies(String type, String method, String field, String protects) {
        String unfilled = unfilledCopy(code(type, method), type, field);
        if (unfilled != null) {
            fail(type + "." + method + " may store " + field + ", and into an array, only a fresh copy that it fills"
                    + " before it stores it, so that " + protects + "; but " + unfilled);
        }
    }

    /**
     * Why {@code code} stores {@code field} of {@code type}, or into an array, something other than a fresh copy it
     * fills before it publishes it (the rule of {@link #assertPublishesOnlyFilledCopies}), or null where it does not.
     */
    private static String unfilledCopy(MethodCode code, String type, String field) {
        List<Instruction> instructions = code.instructions();
        BitSet landings = jumpedTo(code);
        for (ExceptionCatch handler : code.handlers()) {
            landings.set(code.labelIndexes().get(handler.handler()));
        }
        Map<Integer, Integer> publishedAt = new HashMap<>();
        boolean stored = false;
        for (int i = 0; i < instructions.size(); i++) {
            boolean publishes = accesses(instructions.get(i), Opcode.PUTFIELD, type, field);
            String what;
            int load;
            if (publishes) {
                stored = true;
                what = "its store of " + field;
                load = i - 1;
            } else if (instructions.get(i).opcode() == Opcode.AASTORE) {
                what = "the array of its aastore";
                load = i - 3;
                if (load < 0 || !(instructions.get(i - 2) instanceof LoadInstruction)
                        || !(instructions.get(i - 1) instanceof LoadInstruction)) {
                    return "its aastore at instruction " + i + " is not array[index] = value from locals, the shape"
                            + " this check reads";
                }
            } else {
                continue;
            }
            if (load < 0 || !(instructions.get(load) instanceof LoadInstruction local)
                    || local.typeKind() != TypeKind.REFERENCE) {
                return what + " at instruction " + i + " does not come from a local";
            }
            int copy = lastStoreBefore(instructions, local.slot(), load);
            if (copy < 2 || instructions.get(copy - 1).opcode() != Opcode.CHECKCAST
                    || !(instructions.get(copy - 2) instanceof InvokeInstruction invoke)
                    || !callOf(invoke).equals("java/util/Arrays copyOf")) {
                return what + " at instruction " + i + " comes from local " + local.slot() + ", which instruction "
                        + copy + " last stores before that, and not with what a call of Arrays.copyOf answers";
            }
            int landing = landings.nextSetBit(copy - 2);
            if (landing >= 0 && landing <= load) {
                return what + " at instruction " + i + " comes from the copy made at instruction " + (copy - 2)
                        + ", and a branch or handler lands at instruction " + landing + ", at or before its load at"
                        + " instruction " + load;
            }
            if (publishes) {
                publishedAt.put(local.slot(), i);
            } else if (publishedAt.getOrDefault(local.slot(), -1) > copy) {
                return what + " at instruction " + i + " is the copy made at instruction " + (copy - 2) + ", which its"
                        + " store of " + field + " at instruction " + publishedAt.get(local.slot())
                        + " published before that aastore filled it";
            }
        }
        if (!stored) {
            return "it never stores " + field + ", so this check no longer pins anything";
        }
        return null;
    }

    /**
     * Fails unless {@code method} of {@code type} stores {@code field}, an array field of its own, once with something
     * other than null, and that store publishes a new array only once the method has filled it: the value it stores is
     * an {@code aload} of a local whose last store before it, in code order, directly follows a {@code newarray} or
     * {@code anewarray}; and from that {@code putfield} up to the next {@code newarray} or {@code anewarray}, or the
     * end of the method, whichever comes first, no instruction stores an array element of any kind, makes a call, or
     * branches or switches to that {@code putfield} or before it. Any other store of the field stores the constant
     * null, which publishes no array; where no reader makes the array again, {@link #assertNeverStoresNull} rules
     * those out. That is javac's shape of
     * {@code T[] filled = new T[n]; filled[i] = value; field = filled;}, with nothing after the store that could write
     * into the array or run the fill again. It reads code order, with no model of the stack: a write into the array
     * after the next array allocation is not seen. {@code protects} says what that is for: a failure gives it after
     * "so that", and then what was found.
     */
    static void assertPublishesNewArrayOnceFilled(String type, String method, String field, String protects) {
        String unfilled = unfilledNewArray(code(type, method), type, field);
        if (unfilled != null) {
            fail(type + "." + method + " may store " + field + " once with a new array that it filled before, and"
                    + " otherwise only with null, so that " + protects + "; but " + unfilled);
        }
    }

    /**
     * Why {@code code} stores {@code field} of {@code type} other than once with a new array it filled before and
     * otherwise with null (the rule of {@link #assertPublishesNewArrayOnceFilled}), or null where it does not.
     */
    private static String unfilledNewArray(MethodCode code, String type, String field) {
        List<Instruction> instructions = code.instructions();
        List<Integer> stores = new ArrayList<>();
        for (int store : fieldStores(instructions, type, field)) {
            if (instructions.get(store - 1).opcode() != Opcode.ACONST_NULL) {
                stores.add(store);
            }
        }
        if (stores.size() != 1) {
            return "it stores it with something other than null at instructions " + stores + ", not once";
        }
        int store = stores.getFirst();
        if (!(instructions.get(store - 1) instanceof LoadInstruction local) || local.typeKind() != TypeKind.REFERENCE) {
            return "its store at instruction " + store + " follows " + shown(instructions.get(store - 1))
                    + ", not a load of a local";
        }
        int kept = lastStoreBefore(instructions, local.slot(), store - 1);
        if (kept < 1 || !allocatesArray(instructions.get(kept - 1))) {
            return "its store at instruction " + store + " publishes local " + local.slot() + ", which instruction "
                    + kept + " last stores before that, and not right after a newarray or anewarray";
        }
        for (int i = store + 1; i < instructions.size() && !allocatesArray(instructions.get(i)); i++) {
            Instruction instruction = instructions.get(i);
            String found = shown(instruction) + " at instruction " + i + ", after its store at instruction " + store;
            if (instruction instanceof ArrayStoreInstruction) {
                return "it writes an array element with " + found;
            }
            if (instruction instanceof InvokeInstruction || instruction instanceof InvokeDynamicInstruction) {
                return "it makes a call, " + found;
            }
            for (Label label : jumpTargets(instruction)) {
                int target = code.labelIndexes().get(label);
                if (target <= store) {
                    return found + ", jumps back to instruction " + target;
                }
            }
        }
        return null;
    }

    private static boolean allocatesArray(Instruction instruction) {
        return instruction.opcode() == Opcode.NEWARRAY || instruction.opcode() == Opcode.ANEWARRAY;
    }

    /**
     * Fails if any of {@code methods} of {@code type} stores an array element, into an array of any type.
     * {@code protects} says why none of them may: a failure gives it after "so that", and then what was found.
     */
    static void assertStoresNoArrayElement(String type, List<String> methods, String protects) {
        for (String method : methods) {
            List<Instruction> code = instructions(type, method);
            for (int i = 0; i < code.size(); i++) {
                if (code.get(i) instanceof ArrayStoreInstruction) {
                    fail(type + "." + method + " may store no array element, so that " + protects + "; but it stores"
                            + " one with " + shown(code.get(i)) + " at instruction " + i);
                }
            }
        }
    }

    /**
     * Fails unless {@code type} declares {@code field} with {@code flag}, such as {@code volatile} or {@code final}.
     * {@code protects} says what that is for: a failure gives it after "so that", and then what was found.
     */
    static void assertFieldIs(String type, String field, AccessFlag flag, String protects) {
        String pin = type + "." + field + " must be " + flag.name().toLowerCase(Locale.ROOT) + ", so that " + protects
                + "; but ";
        List<FieldModel> named = FontClassFiles.classModel(type, CLASS_FILES).fields().stream()
                .filter(model -> model.fieldName().equalsString(field)).toList();
        if (named.isEmpty()) {
            fail(pin + "it has no field of that name");
        }
        if (!named.getFirst().flags().has(flag)) {
            fail(pin + "it is not");
        }
    }

    /** The instructions of the one method of {@code type} named {@code method}, in code order. */
    static List<Instruction> instructions(String type, String method) {
        return code(type, method).instructions();
    }

    /** The code of the one method of {@code type} named {@code method}. */
    private static MethodCode code(String type, String method) {
        List<Instruction> instructions = new ArrayList<>();
        Map<Label, Integer> labelIndexes = new HashMap<>();
        List<ExceptionCatch> handlers = new ArrayList<>();
        methodModel(type, method).code().ifPresent(code -> code.forEach(element -> {
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

    /**
     * The one method of {@code type} named {@code method}, or, where {@code method} is {@code name(descriptor)}, the
     * one with that name and descriptor.
     */
    private static MethodModel methodModel(String type, String method) {
        ClassModel model = FontClassFiles.classModel(type, CLASS_FILES);
        if (method.contains("(")) {
            List<MethodModel> described = model.methods().stream().filter(m -> signature(m).equals(method)).toList();
            if (described.size() != 1) {
                fail(type + " has " + described.size() + " methods " + method + "; a call-order check needs one");
            }
            return described.getFirst();
        }
        List<MethodModel> named = model.methods().stream().filter(m -> m.methodName().equalsString(method)).toList();
        if (named.size() != 1) {
            fail(type + " has " + named.size() + " methods named " + method + "; a call-order check needs one");
        }
        return named.getFirst();
    }

    /** The local variable slot of parameter {@code index}, 0 the first, of the one method of {@code type} named so. */
    private static int parameterSlot(String type, String method, int index) {
        MethodModel model = methodModel(type, method);
        List<ClassDesc> parameters = model.methodTypeSymbol().parameterList();
        if (index < 0 || index >= parameters.size()) {
            fail(type + "." + method + " has " + parameters.size() + " parameters, so none has index " + index);
        }
        int slot = model.flags().has(AccessFlag.STATIC) ? 0 : 1;
        for (ClassDesc parameter : parameters.subList(0, index)) {
            String descriptor = parameter.descriptorString();
            slot += descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
        }
        return slot;
    }

    /**
     * Each comparison of a long with 0 in {@code code} and the branch on it, javac's {@code lconst_0; lcmp; <branch>}:
     * of the local it loads, directly or as the segment whose {@code address()} it reads, and of the call whose answer
     * that local last held or, where no local is loaded, of the call whose answer is compared.
     */
    private static List<ZeroTest> zeroTests(MethodCode code) {
        List<Instruction> instructions = code.instructions();
        List<ZeroTest> tests = new ArrayList<>();
        for (int i = 1; i + 2 < instructions.size(); i++) {
            if (instructions.get(i).opcode() == Opcode.LCONST_0 && instructions.get(i + 1).opcode() == Opcode.LCMP
                    && instructions.get(i + 2) instanceof BranchInstruction branch) {
                int load = isAddress(instructions.get(i - 1)) ? i - 2 : i - 1;
                int local = load >= 0 && instructions.get(load) instanceof LoadInstruction loaded ? loaded.slot() : -1;
                int store = local < 0 ? -1 : lastStoreBefore(instructions, local, i);
                String answerOf = local < 0 ? answerBefore(instructions, i)
                        : store < 0 ? null : answerBefore(instructions, store);
                tests.add(new ZeroTest(i + 2, branch.opcode(), code.labelIndexes().get(branch.target()), local,
                                       store >= 0, answerOf));
            }
        }
        return tests;
    }

    /**
     * The call whose answer the instruction at {@code index} takes, or the call whose segment's {@code address()} it
     * takes: {@code owner name}, never {@code address} itself; null for any other value.
     */
    private static String answerBefore(List<Instruction> code, int index) {
        int call = index > 0 && isAddress(code.get(index - 1)) ? index - 2 : index - 1;
        return call >= 0 && code.get(call) instanceof InvokeInstruction invoke && !isAddress(invoke)
                ? callOf(invoke) : null;
    }

    private static boolean isAddress(Instruction instruction) {
        return instruction instanceof InvokeInstruction invoke
                && callOf(invoke).equals("java/lang/foreign/MemorySegment address");
    }

    /** The index of the last store into {@code slot} before {@code index}, in code order, or -1. */
    private static int lastStoreBefore(List<Instruction> code, int slot, int index) {
        int last = -1;
        for (int i = 0; i < index; i++) {
            if (code.get(i) instanceof StoreInstruction store && store.slot() == slot) {
                last = i;
            }
        }
        return last;
    }

    /** Why {@code test} does not keep the call at {@code call} from a 0, or null where it does. */
    private static String leak(MethodCode code, ZeroTest test, int call) {
        List<Instruction> instructions = code.instructions();
        if (call <= test.branch()) {
            return "the call comes before the branch";
        }
        switch (test.opcode()) {
            case IFEQ, IFLE -> {
                if (call >= test.target()) {
                    return "the call is not on its fall-through";
                }
                return entryFromOutside(code, test.branch(), test.branch() + 1, test.target(), call);
            }
            case IFNE, IFGT -> {
                if (test.target() <= test.branch()) {
                    return "it jumps back";
                }
                if (call < test.target()) {
                    return "the call is on its fall-through, where the value may be 0";
                }
                Instruction before = instructions.get(test.target() - 1);
                int end = instructions.size();
                if (before instanceof BranchInstruction jump
                        && (jump.opcode() == Opcode.GOTO || jump.opcode() == Opcode.GOTO_W)) {
                    end = code.labelIndexes().get(jump.target());
                } else if (!(before instanceof ReturnInstruction) && !(before instanceof ThrowInstruction)) {
                    return "the instruction before its target, " + shown(before) + ", leads on to the call";
                }
                if (call >= end) {
                    return "the call is at or after instruction " + end + ", where the goto before its target goes";
                }
                return entryFromOutside(code, test.branch(), test.target(), end, call);
            }
            default -> {
                return "only an ifeq, ifle, ifne or ifgt keeps a 0 on one side of it";
            }
        }
    }

    /**
     * What reaches the instructions from {@code start} to {@code call}, of the guarded instructions from {@code start}
     * to before {@code end}, other than the guard's branch at {@code guard}: a branch or switch outside them that
     * jumps to one of them, or a handler among them of a try range that starts outside them; null for nothing.
     */
    private static String entryFromOutside(MethodCode code, int guard, int start, int end, int call) {
        List<Instruction> instructions = code.instructions();
        for (int i = 0; i < instructions.size(); i++) {
            if (i != guard && (i < start || i >= end)) {
                for (Label label : jumpTargets(instructions.get(i))) {
                    int target = code.labelIndexes().get(label);
                    if (start <= target && target <= call) {
                        return shown(instructions.get(i)) + " at instruction " + i + " jumps to instruction " + target
                                + ", past the guard";
                    }
                }
            }
        }
        for (ExceptionCatch handler : code.handlers()) {
            int first = code.labelIndexes().get(handler.handler());
            int tryStart = code.labelIndexes().get(handler.tryStart());
            if (start <= first && first <= call && (tryStart < start || tryStart >= end)) {
                return "the handler at instruction " + first + " catches for a try range that starts at instruction "
                        + tryStart + ", outside the guarded instructions";
            }
        }
        return null;
    }

    /**
     * The instructions of {@code code} that the one at {@code start} reaches without an exception: through
     * fall-through, branches and switches, never into a handler.
     */
    private static BitSet reachedWithoutAnException(MethodCode code, int start) {
        List<Instruction> instructions = code.instructions();
        BitSet reached = new BitSet(instructions.size());
        Deque<Integer> pending = new ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            int index = pending.pop();
            if (index < instructions.size() && !reached.get(index)) {
                reached.set(index);
                Instruction instruction = instructions.get(index);
                for (Label label : jumpTargets(instruction)) {
                    pending.push(code.labelIndexes().get(label));
                }
                if (fallsThrough(instruction)) {
                    pending.push(index + 1);
                }
            }
        }
        return reached;
    }

    private static boolean fallsThrough(Instruction instruction) {
        Opcode opcode = instruction.opcode();
        return !(instruction instanceof ReturnInstruction) && !(instruction instanceof ThrowInstruction)
                && opcode != Opcode.GOTO && opcode != Opcode.GOTO_W && opcode != Opcode.TABLESWITCH
                && opcode != Opcode.LOOKUPSWITCH;
    }

    private static List<Label> jumpTargets(Instruction instruction) {
        List<Label> targets = new ArrayList<>();
        if (instruction instanceof BranchInstruction branch) {
            targets.add(branch.target());
        } else if (instruction instanceof TableSwitchInstruction table) {
            targets.add(table.defaultTarget());
            table.cases().stream().map(SwitchCase::target).forEach(targets::add);
        } else if (instruction instanceof LookupSwitchInstruction lookup) {
            targets.add(lookup.defaultTarget());
            lookup.cases().stream().map(SwitchCase::target).forEach(targets::add);
        }
        return targets;
    }

    private static List<Instruction> instructionsOf(MethodModel method) {
        List<Instruction> instructions = new ArrayList<>();
        method.code().ifPresent(code -> code.forEach(element -> {
            if (element instanceof Instruction instruction) {
                instructions.add(instruction);
            }
        }));
        return instructions;
    }

    private static boolean accesses(Instruction instruction, Opcode opcode, String type, String field) {
        return instruction instanceof FieldInstruction access && access.opcode() == opcode
                && access.owner().asInternalName().equals(type) && access.name().equalsString(field);
    }

    /**
     * Whether {@code method} reads one of {@code fields} of {@code type}, stores one other than with
     * {@code initialiserStore}, the store that an initialiser of {@code type} makes before any other thread can reach
     * the field ({@code putfield} in a constructor, {@code putstatic} in the static initialiser, null in any other
     * method), or makes one of {@code calls}.
     */
    private static boolean reaches(MethodModel method, Opcode initialiserStore, String type, List<String> fields,
                                   List<String> calls) {
        for (Instruction instruction : instructionsOf(method)) {
            if (instruction instanceof FieldInstruction access && access.owner().asInternalName().equals(type)
                    && fields.contains(access.name().stringValue()) && access.opcode() != initialiserStore) {
                return true;
            }
            if (instruction instanceof InvokeInstruction invoke && calls.contains(callOf(invoke))) {
                return true;
            }
        }
        return false;
    }

    /** The indexes in {@code code} of every store of {@code field} of {@code type}, in code order. */
    private static List<Integer> fieldStores(List<Instruction> code, String type, String field) {
        List<Integer> stores = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (accesses(code.get(i), Opcode.PUTFIELD, type, field)) {
                stores.add(i);
            }
        }
        return stores;
    }

    /** The index in {@code code} of the last read of {@code field} of {@code type} before {@code index}, or -1. */
    private static int lastFieldRead(List<Instruction> code, String type, String field, int index) {
        int last = -1;
        for (int i = 0; i < index; i++) {
            if (accesses(code.get(i), Opcode.GETFIELD, type, field)) {
                last = i;
            }
        }
        return last;
    }

    /**
     * Why the last read of {@code field} of {@code type} before the instruction at {@code before} does not keep the
     * code up to {@code past} to where it found the field null: there is no such read, or the instruction after it
     * is not an {@code ifnonnull} that jumps past {@code past}; null where it does.
     */
    private static String nullTestPast(MethodCode code, String type, String field, int before, int past) {
        List<Instruction> instructions = code.instructions();
        int read = lastFieldRead(instructions, type, field, before);
        if (read < 0) {
            return "it does not read " + field;
        }
        Instruction next = instructions.get(read + 1);
        if (!(next instanceof BranchInstruction branch) || branch.opcode() != Opcode.IFNONNULL) {
            return "its last read of " + field + ", at instruction " + read + ", is followed by " + shown(next)
                    + ", not ifnonnull";
        }
        int target = code.labelIndexes().get(branch.target());
        if (target <= past) {
            return "the ifnonnull after its last read of " + field + ", at instruction " + (read + 1)
                    + ", jumps to instruction " + target + ", not past instruction " + past;
        }
        return null;
    }

    /** The indexes in {@code code} of its one monitorenter and of the first monitorexit after it; fails otherwise. */
    private static int[] monitorBlock(List<Instruction> code, String pin) {
        List<Integer> enters = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).opcode() == Opcode.MONITORENTER) {
                enters.add(i);
            }
        }
        if (enters.size() != 1) {
            fail(pin + "it enters a monitor at instructions " + enters + ", where this check reads one monitor");
        }
        int enter = enters.getFirst();
        for (int i = enter + 1; i < code.size(); i++) {
            if (code.get(i).opcode() == Opcode.MONITOREXIT) {
                return new int[] {enter, i};
            }
        }
        return fail(pin + "no monitorexit follows its monitorenter at instruction " + enter);
    }

    private static String signature(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    private static String signature(MemberRefEntry member) {
        return member.name().stringValue() + member.type().stringValue();
    }

    private static String callOf(InvokeInstruction invoke) {
        return invoke.owner().asInternalName() + " " + invoke.name().stringValue();
    }

    private static String shown(Instruction instruction) {
        String opcode = instruction.opcode().name().toLowerCase(Locale.ROOT);
        return instruction instanceof InvokeInstruction invoke ? opcode + " of " + callOf(invoke) : opcode;
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

    /** The indexes in {@code code} of every call of a method of {@code type} or of a class nested in it. */
    private static List<Integer> callsOfClass(List<Instruction> code, String type) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof InvokeInstruction invoke
                    && FontClassFiles.isSameOrNested(invoke.owner().asInternalName(), type)) {
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

    /** The greatest of {@code indexes} below {@code index} that {@code reached} holds, or -1. */
    private static int lastBefore(List<Integer> indexes, int index, BitSet reached) {
        int last = -1;
        for (int candidate : indexes) {
            if (candidate < index && reached.get(candidate)) {
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

    /** The index in {@code code} of the first return or throw at or after {@code index}, or its size. */
    private static int firstExitAtOrAfter(List<Instruction> code, int index) {
        for (int i = index; i < code.size(); i++) {
            if (isExit(code.get(i))) {
                return i;
            }
        }
        return code.size();
    }

    private static boolean isExit(Instruction instruction) {
        return instruction instanceof ReturnInstruction || instruction instanceof ThrowInstruction;
    }

    private static String exitName(Instruction instruction) {
        return instruction instanceof ReturnInstruction ? "return" : "throw";
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

    /** Whether {@code store} writes a local variable slot that {@code other} writes. */
    private static boolean overlaps(StoreInstruction store, StoreInstruction other) {
        return store.slot() < other.slot() + other.typeKind().slotSize()
                && other.slot() < store.slot() + store.typeKind().slotSize();
    }

    /** The indexes in {@code code} that a branch or switch jumps to. */
    private static BitSet jumpedTo(MethodCode code) {
        BitSet targets = new BitSet(code.instructions().size());
        for (Instruction instruction : code.instructions()) {
            for (Label label : jumpTargets(instruction)) {
                targets.set(code.labelIndexes().get(label));
            }
        }
        return targets;
    }

    /**
     * The index in {@code code} of the instruction that pushes argument {@code argument}, 0 the first, of the call at
     * {@code call}, found by stepping back over what pushes the later arguments; -1 where it meets an instruction that
     * {@link #valuesTakenForOne} does not count.
     */
    private static int argumentPush(List<Instruction> code, int call, int argument) {
        int above = ((InvokeInstruction) code.get(call)).typeSymbol().parameterCount() - 1 - argument;
        for (int i = call - 1; i >= 0; i--) {
            if (above == 0) {
                return i;
            }
            int taken = valuesTakenForOne(code.get(i));
            if (taken < 0) {
                return -1;
            }
            above += taken - 1;
        }
        return -1;
    }

    /**
     * How many values {@code instruction} takes from the stack to push one: a load, a constant, a field read, a cast
     * or a call that answers a value; -1 for any other instruction.
     */
    private static int valuesTakenForOne(Instruction instruction) {
        return switch (instruction) {
            case LoadInstruction _, ConstantInstruction _ -> 0;
            case FieldInstruction field when field.opcode() == Opcode.GETSTATIC -> 0;
            case FieldInstruction field when field.opcode() == Opcode.GETFIELD -> 1;
            case TypeCheckInstruction _ -> 1;
            case InvokeInstruction invoke when !invoke.typeSymbol().returnType().descriptorString().equals("V") ->
                    invoke.typeSymbol().parameterCount() + (invoke.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
            default -> -1;
        };
    }

    /**
     * The instructions of a method in code order, for each label the index of the instruction it marks, and its
     * exception handlers, all from one parse: the label of a branch or a handler is found only among the label
     * targets of the parse that read it.
     */
    private record MethodCode(List<Instruction> instructions, Map<Label, Integer> labelIndexes,
                              List<ExceptionCatch> handlers) {
    }

    /**
     * A comparison of a long with 0 and the branch at {@code branch} on it, which jumps to {@code target}: of the
     * local {@code local}, -1 for none, which held what {@code answerOf} answered, null for no call, and into which
     * something was {@code stored} before.
     */
    private record ZeroTest(int branch, Opcode opcode, int target, int local, boolean stored, String answerOf) {

        @Override
        public String toString() {
            return opcode.name().toLowerCase(Locale.ROOT) + " at instruction " + branch + " to " + target;
        }
    }
}

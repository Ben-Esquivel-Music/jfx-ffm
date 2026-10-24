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

import com.sun.javafx.font.PrismFontFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The class-file reading behind the capture guards and binding witnesses of the font goldens. A target is
 * {@code owner name(descriptor)}, owner as an internal name.
 */
final class FontClassFiles {

    static final String FONT_PACKAGE = "com/sun/javafx/font/";

    private FontClassFiles() {
    }

    /** The class file of the font module, or a failure that ends with {@code knownClasses}. */
    static ClassModel classModel(String internalName, String knownClasses) {
        try (InputStream in = PrismFontFactory.class.getModule().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                fail("no class file " + internalName + ".class in " + PrismFontFactory.class.getModule() + ": "
                        + knownClasses);
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every method under {@code com/sun/javafx/font/} the class invokes, constructors excluded; with
     * {@code excludeSelf}, the members of the class and its nested classes too.
     */
    static Set<String> invokeTargets(ClassModel model, boolean excludeSelf) {
        String self = model.thisClass().asInternalName();
        Set<String> targets = new TreeSet<>();
        for (MethodModel method : model.methods()) {
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeInstruction invoke) {
                    String owner = invoke.owner().asInternalName();
                    String name = invoke.name().stringValue();
                    if (owner.startsWith(FONT_PACKAGE) && !name.equals("<init>")
                            && !(excludeSelf && isSameOrNested(owner, self))) {
                        targets.add(target(owner, name, invoke.typeSymbol().descriptorString()));
                    }
                }
            }));
        }
        return targets;
    }

    /** The methods of the font classes a reflective shim references whose names are among its string constants. */
    static Set<String> reflectiveTargets(ClassModel shim) {
        Set<String> names = stringConstants(shim);
        Set<String> targets = new TreeSet<>();
        for (String internalName : fontClasses(shim)) {
            Class<?> owner;
            try {
                owner = Class.forName(internalName.replace('/', '.'), false, PrismFontFactory.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                fail(shim.thisClass().asInternalName() + " references " + internalName + ", which does not exist", e);
                return targets;
            }
            for (Method method : owner.getDeclaredMethods()) {
                if (names.contains(method.getName())) {
                    String descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                                  .descriptorString();
                    targets.add(target(internalName, method.getName(), descriptor));
                }
            }
        }
        return targets;
    }

    static Set<String> stringConstants(ClassModel model) {
        Set<String> strings = new TreeSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof StringEntry string) {
                strings.add(string.stringValue());
            }
        }
        return strings;
    }

    /** The classes of {@code com/sun/javafx/font} and below a class file names, other than itself and its members. */
    static Set<String> fontClasses(ClassModel model) {
        String self = model.thisClass().asInternalName();
        Set<String> classes = new TreeSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry classEntry) {
                String name = classEntry.asInternalName().replaceFirst("^\\[+L?", "").replaceFirst(";$", "");
                if (name.startsWith(FONT_PACKAGE) && !isSameOrNested(name, self)) {
                    classes.add(name);
                }
            }
        }
        return classes;
    }

    /**
     * {@code Owner.native} per invoke instruction of one of {@code natives} (owner internal name to native names),
     * sorted, a count above 1 as {@code *n}. Owner and name decide, not the {@code native} flag.
     */
    static String nativeCallSites(ClassModel model, Map<String, List<String>> natives) {
        Map<String, Integer> counts = new TreeMap<>();
        for (MethodModel method : model.methods()) {
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeInstruction invoke) {
                    String owner = invoke.owner().asInternalName();
                    String name = invoke.name().stringValue();
                    if (natives.getOrDefault(owner, List.of()).contains(name)) {
                        counts.merge(owner.substring(owner.lastIndexOf('/') + 1) + "." + name, 1, Integer::sum);
                    }
                }
            }));
        }
        List<String> sites = new ArrayList<>();
        counts.forEach((site, count) -> sites.add(count == 1 ? site : site + "*" + count));
        return String.join(" ", sites);
    }

    /** Refuses a capture unless every method of {@code className} named in {@code names} is {@code native}. */
    static void requireNative(String className, List<String> names, String commit) {
        List<Method> methods = Arrays.asList(declaredMethods(className));
        for (String name : names) {
            List<Method> matches = methods.stream().filter(m -> m.getName().equals(name)).toList();
            if (matches.isEmpty() || matches.stream().anyMatch(m -> !Modifier.isNative(m.getModifiers()))) {
                fail("refusing to capture: " + className + "." + name + " is not a native method, so this is not"
                        + " the JNI build of commit " + commit);
            }
        }
    }

    static List<Method> nativeMethods(String className) {
        return Arrays.stream(declaredMethods(className)).filter(m -> Modifier.isNative(m.getModifiers())).toList();
    }

    private static Method[] declaredMethods(String className) {
        try {
            return Class.forName(className, false, PrismFontFactory.class.getClassLoader()).getDeclaredMethods();
        } catch (ClassNotFoundException e) {
            fail("refusing to capture: " + className + " does not exist, so this is not the JNI build", e);
            return new Method[0];
        }
    }

    static String target(String owner, String name, String descriptor) {
        return owner + " " + name + descriptor;
    }

    static String owner(String target) {
        return target.substring(0, target.indexOf(' '));
    }

    static String member(String target) {
        return target.substring(target.indexOf(' ') + 1);
    }

    static String name(String target) {
        String member = member(target);
        return member.substring(0, member.indexOf('('));
    }

    static boolean isSameOrNested(String internalName, String outer) {
        return internalName.equals(outer) || internalName.startsWith(outer + "$");
    }
}

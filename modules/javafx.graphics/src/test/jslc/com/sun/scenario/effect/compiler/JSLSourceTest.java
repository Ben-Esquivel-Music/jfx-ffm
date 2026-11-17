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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.sun.scenario.effect.compiler.JSLSource.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JSLSourceTest extends ProgramBase {

    private static final String DETAIL = "the detail";

    private static final String GRINNING_FACE = "😀";

    private static final String TEMPLATE_NAME = "<Probe template>";

    private static final Locale THAI_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");

    @TempDir
    Path tmp;

    static Stream<Arguments> templatesAndArguments() {
        return Stream.of(
                Arguments.of("no placeholder\n", new Object[] {}),
                Arguments.of("param float4 weights[%d];\nfloat f = %d.0;\n", new Object[] {4, -1234567}),
                Arguments.of("%s\n%s x %s\n", new Object[] {named("A", "a;\nb;"), "str", named("B", "")}),
                Arguments.of("%d%s%d", new Object[] {Integer.MIN_VALUE, named("C", "c %s").format("1"),
                        Integer.MAX_VALUE}));
    }

    @ParameterizedTest
    @MethodSource("templatesAndArguments")
    public void formatGivesTheTextThatStringFormatGivesInTheRootLocale(String template, Object[] args) {
        Object[] texts = Arrays.stream(args)
                .map(arg -> arg instanceof JSLSource source ? source.text() : arg)
                .toArray();

        assertEquals(String.format(Locale.ROOT, template, texts), named("T", template).format(args).text());
    }

    @Test
    public void aNumberIsInsertedInAsciiDigitsWhateverTheDefaultLocale() {
        Locale defaultLocale = Locale.getDefault();
        Locale defaultDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale defaultFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(THAI_DIGITS);
        try {
            assertEquals("weights[128];", named("T", "weights[%d];").format(128).text());
        } finally {
            // Setting the default locale also sets both categories, so it is restored first.
            Locale.setDefault(defaultLocale);
            Locale.setDefault(Locale.Category.DISPLAY, defaultDisplayLocale);
            Locale.setDefault(Locale.Category.FORMAT, defaultFormatLocale);
        }
    }

    // Program line 2 is "ab XYZ cd 12345 ef", from the template line "ab %s cd %d ef".
    @ParameterizedTest
    @CsvSource({
            "0, T:2:0",
            "2, T:2:2",
            "3, T:2:3",
            "5, T:2:3",
            "6, T:2:5",
            "8, T:2:7",
            "10, T:2:9",
            "14, T:2:9",
            "15, T:2:11",
            "17, T:2:13"
    })
    public void aPositionOnALineWithPlaceholdersMapsToTheTemplateAndAnInsertedValueToItsPlaceholder(
            int column, String expected) {
        JSLSource source = named("T", "first\nab %s cd %d ef\n").format("XYZ", 12345);

        assertLocation(expected, source, 2, column);
    }

    // Program lines: "out1", "in1", "in2 leaf1", "leaf2;", "", "out3 leaf1", "leaf2 str", and the end.
    @ParameterizedTest
    @CsvSource({
            "1, 0, Outer:1:0",
            "1, 4, Outer:1:4",
            "2, 1, Inner:1:1",
            "3, 3, Inner:2:3",
            "3, 4, Leaf:1:0",
            "3, 8, Leaf:1:4",
            "4, 4, Leaf:2:4",
            "4, 5, Inner:2:6",
            "4, 6, Inner:2:7",
            "5, 0, Outer:2:2",
            "6, 4, Outer:3:4",
            "6, 5, Leaf:1:0",
            "7, 2, Leaf:2:2",
            "7, 5, Outer:3:7",
            "7, 8, Outer:3:8",
            "7, 9, Outer:3:10",
            "8, 0, Outer:4:0"
    })
    public void aPositionInAnAssembledProgramMapsToThePieceThatHoldsIt(int line, int column, String expected) {
        JSLSource leaf = named("Leaf", "leaf1\nleaf2");
        JSLSource inner = named("Inner", "in1\nin2 %s;\n").format(leaf);
        JSLSource outer = named("Outer", "out1\n%s\nout3 %s %s\n").format(inner, leaf, "str");

        assertLocation(expected, outer, line, column);
    }

    @Test
    public void theEndOfATextWithAFinalNewlineIsTheStartOfTheLineAfterIt() {
        assertLocation("F:3:0", named("F", "a\nbc\n"), 3, 0);
    }

    @Test
    public void theEndOfATextWithoutAFinalNewlineIsTheEndOfItsLastLine() {
        assertLocation("F:2:2", named("F", "a\nbc"), 2, 2);
    }

    @Test
    public void theEndOfTheEmptyTextIsTheStartOfItsPiece() {
        assertLocation("E:1:0", named("E", ""), 1, 0);
    }

    @Test
    public void theEndOfATemplateThatEndsInAPlaceholderIsTheEndOfTheTemplate() {
        assertLocation("T:2:2", named("T", "x\n%s").format(named("A", "a\nbbb")), 3, 3);
        assertLocation("T:1:4", named("T", "x %s").format(named("A", "a\n")), 2, 0);
        assertLocation("T:1:6", named("T", "n = %d").format(12), 1, 6);
    }

    // Program lines: "a\r", "p\r", "q x\r", and the end.
    @ParameterizedTest
    @CsvSource({
            "1, 1, W:1:1",
            "2, 1, A:1:1",
            "3, 0, A:2:0",
            "3, 2, W:2:3",
            "3, 3, W:2:4",
            "4, 0, W:3:0"
    })
    public void aCarriageReturnIsAColumnAndOnlyALineFeedEndsALine(int line, int column, String expected) {
        JSLSource source = named("W", "a\r\n%s x\r\n").format(named("A", "p\r\nq"));

        assertLocation(expected, source, line, column);
    }

    @Test
    public void aSupplementaryCharacterInTheTemplateBeforeAnArgumentIsOneColumn() {
        JSLSource source = named("T", "/* " + GRINNING_FACE + " */ %s\n").format(named("A", "x )"));

        assertLocation("A:1:2", source, 1, 10);
    }

    @Test
    public void aSupplementaryCharacterInAnArgumentBeforeTemplateTextIsOneColumn() {
        JSLSource source = named("T", "%s )\n").format(named("A", GRINNING_FACE));

        assertLocation("T:1:3", source, 1, 2);
    }

    @Test
    public void aSupplementaryCharacterInAPieceIsOneColumnOfThatPiece() {
        JSLSource source = named("T", "%s\n").format(named("A", GRINNING_FACE + " x"));

        assertLocation("A:1:2", source, 1, 2);
    }

    @Test
    public void anEmptyStringArgumentOwnsNoPosition() {
        assertLocation("T:1:3", named("T", "a%sb\n").format(""), 1, 1);
    }

    @Test
    public void anEmptyPieceArgumentOwnsNoPosition() {
        assertLocation("T:1:3", named("T", "a%sb\n").format(named("E", "")), 1, 1);
    }

    @Test
    public void anEmptyFirstArgumentLeavesTheStartOfTheProgramToTheNextPiece() {
        assertLocation("P:1:0", named("T", "%s%s\n").format("", named("P", "p")), 1, 0);
    }

    @Test
    public void anEmptyAssembledArgumentOwnsNoPosition() {
        assertLocation("T:1:3", named("T", "a%sb\n").format(named("C", "%s").format("")), 1, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"first\n%s ab %% cd\n", "first\n%s ab %n cd\n", "first\n%s ab %5d cd\n", "first\n%s ab %"})
    public void aConversionOtherThanSOrDIsRejectedAtItsPercentSign(String template) {
        JSLSource source = named(TEMPLATE_NAME, template);

        IllegalArgumentException e = assertThrowsExactly(IllegalArgumentException.class, () -> source.format("x"));

        assertStartsWith(TEMPLATE_NAME + ":2:6: ", e.getMessage());
    }

    static Stream<Arguments> argumentsThatDoNotFitTheirPlaceholder() {
        String decimal = "first\n%s ab %d cd\n";
        String string = "first\n%s ab %s cd\n";
        return Stream.of(
                Arguments.of(decimal, 4L),
                Arguments.of(decimal, "4"),
                Arguments.of(decimal, 4.0),
                Arguments.of(decimal, null),
                Arguments.of(string, 4),
                Arguments.of(string, 'c'),
                Arguments.of(string, new StringBuilder("sb")),
                Arguments.of(string, null),
                Arguments.of(string, JSLSource.unnamed("unnamed")));
    }

    @ParameterizedTest
    @MethodSource("argumentsThatDoNotFitTheirPlaceholder")
    public void anArgumentThatDoesNotFitItsPlaceholderIsRejectedAtThePlaceholder(String template, Object argument) {
        JSLSource source = named(TEMPLATE_NAME, template);

        IllegalArgumentException e = assertThrowsExactly(IllegalArgumentException.class,
                () -> source.format("x", argument));

        assertStartsWith(TEMPLATE_NAME + ":2:6: ", e.getMessage());
    }

    static Stream<Arguments> argumentCountsOtherThanTwo() {
        return Stream.of(
                Arguments.of((Object) new Object[] {}),
                Arguments.of((Object) new Object[] {"x"}),
                Arguments.of((Object) new Object[] {"x", 1, "extra"}));
    }

    @ParameterizedTest
    @MethodSource("argumentCountsOtherThanTwo")
    public void anArgumentCountOtherThanThePlaceholderCountIsRejectedNamingThePiece(Object[] args) {
        JSLSource source = named(TEMPLATE_NAME, "first\n%s ab %d cd\n");

        IllegalArgumentException e = assertThrowsExactly(IllegalArgumentException.class, () -> source.format(args));

        assertTrue(e.getMessage().contains(TEMPLATE_NAME),
                () -> "expected a message naming " + TEMPLATE_NAME + " but was <" + e.getMessage() + ">");
    }

    @Test
    public void anUnnamedSourceCannotBeATemplate() {
        JSLSource source = JSLSource.unnamed("a %s\n");

        assertThrowsExactly(IllegalStateException.class, () -> source.format("x"));
    }

    @Test
    public void anAssembledSourceCannotBeATemplate() {
        JSLSource source = named("T", "a %s\n").format("%s");

        assertThrowsExactly(IllegalStateException.class, () -> source.format("x"));
    }

    @Test
    public void describeNamesThePieceItsLineAndColumnAndTheProgram() {
        assertEquals("Main.jsl:2:1: in program Probe_Helper: " + DETAIL,
                named("Main.jsl", "a\nbc\n").describe(2, 1, "Probe_Helper", DETAIL));
    }

    @Test
    public void describeWithoutAProgramNamesThePieceItsLineAndColumn() {
        assertEquals("Main.jsl:2:1: " + DETAIL, named("Main.jsl", "a\nbc\n").describe(2, 1, null, DETAIL));
    }

    @Test
    public void describeOfAnUnnamedSourceGivesTheProgramLineAndColumnWithoutTheProgram() {
        assertEquals("line 2:1 " + DETAIL, JSLSource.unnamed("a\nbc\n").describe(2, 1, "Probe", DETAIL));
    }

    @Test
    public void describeOfAnUnnamedSourceDoesNotReadItsText() {
        assertEquals("line 40:7 " + DETAIL, JSLSource.unnamed("").describe(40, 7, "Probe", DETAIL));
    }

    @Test
    public void aFileSourceIsNamedAfterTheFileAndHoldsItsText() throws IOException {
        Path file = Files.createDirectories(tmp.resolve("shaders")).resolve("Probe.jsl");
        Files.writeString(file, "a\nbc\n");

        JSLSource source = JSLSource.file(file.toFile());

        assertEquals("a\nbc\n", source.text());
        assertLocation("Probe.jsl:2:1", source, 2, 1);
    }

    @Test
    public void aByteOfAFileThatIsNotUtf8IsReplacedRatherThanRejected() throws IOException {
        Path file = tmp.resolve("Probe.jsl");
        Files.write(file, "a\n\u00FFbc\n".getBytes(StandardCharsets.ISO_8859_1));

        JSLSource source = JSLSource.file(file.toFile());

        assertEquals("a\n\uFFFDbc\n", source.text());
        assertLocation("Probe.jsl:2:1", source, 2, 1);
    }

    private static void assertLocation(String expected, JSLSource source, int line, int column) {
        assertEquals(expected + ": " + DETAIL, source.describe(line, column, null, DETAIL));
    }
}

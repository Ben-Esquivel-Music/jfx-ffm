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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The text of a JSL program and, for each position in it, the named piece of text it comes from: a {@code .jsl}
 * file, or text that a generator writes itself. Generators assemble programs from pieces with {@link #format}, and
 * the compiler reports an error at the line and column of the piece that holds it.
 * <p>
 * Lines and columns are counted as ANTLR counts them: only {@code '\n'} ends a line, lines are 1-based, and a column
 * is a 0-based count of code points.
 */
public final class JSLSource {

    private record Piece(String name, String text) {
    }

    /**
     * The program characters from {@code start} to {@code end} (exclusive) come from {@code piece}, from its offset
     * {@code pieceStart} on; those of an inserted string or number all map to their placeholder at {@code pieceStart}.
     */
    private record Segment(int start, int end, Piece piece, int pieceStart, boolean placeholder) {

        static Segment copiedFrom(Piece piece, int pieceStart, int start, int end) {
            return new Segment(start, end, piece, pieceStart, false);
        }

        static Segment insertedAt(Piece piece, int placeholderOffset, int start, int end) {
            return new Segment(start, end, piece, placeholderOffset, true);
        }

        int pieceOffset(int programOffset) {
            return placeholder ? pieceStart : pieceStart + (programOffset - start);
        }

        Segment shiftedBy(int distance) {
            return new Segment(start + distance, end + distance, piece, pieceStart, placeholder);
        }
    }

    private record Placeholder(int offset, char conversion) {
    }

    private final String text;
    private final Piece root;
    private final List<Segment> segments;
    private final boolean composite;

    private JSLSource(String text, Piece root, List<Segment> segments, boolean composite) {
        this.text = text;
        this.root = root;
        this.segments = segments;
        this.composite = composite;
    }

    /** A program without a source name: its errors give the line and column of the program text alone. */
    public static JSLSource unnamed(String text) {
        return new JSLSource(Objects.requireNonNull(text), null, List.of(), false);
    }

    /**
     * One piece of text: the text of a file, named by the file's name, or text that a generator writes itself, named
     * in angle brackets.
     */
    public static JSLSource named(String name, String text) {
        Piece piece = new Piece(Objects.requireNonNull(name), Objects.requireNonNull(text));
        if (text.isEmpty()) {
            return new JSLSource(text, piece, List.of(), false);
        }
        Segment wholeText = Segment.copiedFrom(piece, 0, 0, text.length());
        return new JSLSource(text, piece, List.of(wholeText), false);
    }

    /**
     * The whole text of a file, decoded as UTF-8 with malformed input replaced, and named by the file's name. The file
     * is closed when this method returns or throws.
     */
    public static JSLSource file(File file) throws IOException {
        // Not Files: readString rejects malformed input, which the compiler has always replaced, and a read through
        // Files reports a directory without its path.
        try (FileInputStream in = new FileInputStream(file)) {
            return named(file.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Returns a source whose text is this piece's text with each placeholder replaced by its argument: {@code %s} by a
     * {@code String} or a named {@code JSLSource}, {@code %d} by an {@code Integer} in ASCII digits. The text equals
     * {@code String.format(Locale.ROOT, template, args)} with each {@code JSLSource} argument replaced by its
     * {@link #text()}. The positions of an inserted source keep their own piece; those of an inserted string or number
     * map to its placeholder.
     *
     * @throws IllegalStateException if this source is unnamed or was itself assembled by {@code format}
     * @throws IllegalArgumentException if the template holds another conversion, an argument does not fit its
     *         placeholder, or the number of arguments differs from the number of placeholders
     */
    public JSLSource format(Object... args) {
        if (root == null || composite) {
            throw new IllegalStateException("only a single named piece can be a template");
        }
        List<Placeholder> placeholders = placeholders();
        if (args.length != placeholders.size()) {
            throw new IllegalArgumentException(root.name() + " has " + placeholders.size() + " placeholders, but "
                    + args.length + " arguments were given");
        }
        StringBuilder program = new StringBuilder();
        List<Segment> programSegments = new ArrayList<>();
        int templateStart = 0;
        for (int i = 0; i < args.length; i++) {
            Placeholder placeholder = placeholders.get(i);
            appendTemplate(templateStart, placeholder.offset(), program, programSegments);
            appendArgument(placeholder, args[i], program, programSegments);
            templateStart = placeholder.offset() + 2;
        }
        appendTemplate(templateStart, root.text().length(), program, programSegments);
        return new JSLSource(program.toString(), root, List.copyOf(programSegments), true);
    }

    public String text() {
        return text;
    }

    /**
     * The message of an error at a line and column of the program text: {@code <piece>:<line>:<column>: in program
     * <programName>: <detail>}, without {@code in program <programName>: } when {@code programName} is {@code null},
     * or {@code line <line>:<column> <detail>} for an unnamed source.
     */
    String describe(int line, int column, String programName, String detail) {
        if (root == null) {
            return "line " + line + ":" + column + " " + detail;
        }
        int offset = offsetOf(text, line, column);
        String location;
        if (offset == text.length()) {
            location = locationIn(root, root.text().length());
        } else {
            Segment segment = segmentAt(offset);
            location = locationIn(segment.piece(), segment.pieceOffset(offset));
        }
        return programName == null
                ? location + ": " + detail
                : location + ": in program " + programName + ": " + detail;
    }

    private List<Placeholder> placeholders() {
        String template = root.text();
        List<Placeholder> placeholders = new ArrayList<>();
        for (int i = template.indexOf('%'); i >= 0; i = template.indexOf('%', i + 2)) {
            String conversion = template.substring(i, Math.min(i + 2, template.length()));
            if (!conversion.equals("%s") && !conversion.equals("%d")) {
                throw rejected(i, "unsupported conversion '" + conversion + "': a template holds only %s and %d");
            }
            placeholders.add(new Placeholder(i, conversion.charAt(1)));
        }
        return placeholders;
    }

    private void appendTemplate(int start, int end, StringBuilder program, List<Segment> programSegments) {
        if (start < end) {
            programSegments.add(Segment.copiedFrom(root, start, program.length(), program.length() + end - start));
            program.append(root.text(), start, end);
        }
    }

    private void appendArgument(Placeholder placeholder, Object arg, StringBuilder program,
                                List<Segment> programSegments) {
        if (placeholder.conversion() == 'd') {
            if (!(arg instanceof Integer number)) {
                throw rejected(placeholder.offset(), "%d takes an Integer, not " + typeName(arg));
            }
            appendAtPlaceholder(placeholder, Integer.toString(number), program, programSegments);
        } else if (arg instanceof String string) {
            appendAtPlaceholder(placeholder, string, program, programSegments);
        } else if (arg instanceof JSLSource source) {
            if (source.root == null) {
                throw rejected(placeholder.offset(), "%s takes a named JSLSource, not an unnamed one");
            }
            for (Segment segment : source.segments) {
                programSegments.add(segment.shiftedBy(program.length()));
            }
            program.append(source.text);
        } else {
            throw rejected(placeholder.offset(), "%s takes a String or a JSLSource, not " + typeName(arg));
        }
    }

    private void appendAtPlaceholder(Placeholder placeholder, String inserted, StringBuilder program,
                                     List<Segment> programSegments) {
        if (!inserted.isEmpty()) {
            programSegments.add(Segment.insertedAt(root, placeholder.offset(), program.length(),
                    program.length() + inserted.length()));
            program.append(inserted);
        }
    }

    private IllegalArgumentException rejected(int templateOffset, String reason) {
        return new IllegalArgumentException(locationIn(root, templateOffset) + ": " + reason);
    }

    /** The segments are contiguous and cover the whole text, so the first one that ends after the offset holds it. */
    private Segment segmentAt(int offset) {
        int index = 0;
        while (segments.get(index).end() <= offset) {
            index++;
        }
        return segments.get(index);
    }

    private static String typeName(Object arg) {
        return arg == null ? "null" : arg.getClass().getName();
    }

    private static int offsetOf(String text, int line, int column) {
        int lineStart = 0;
        for (int i = 1; i < line; i++) {
            lineStart = text.indexOf('\n', lineStart) + 1;
        }
        return text.offsetByCodePoints(lineStart, column);
    }

    private static String locationIn(Piece piece, int offset) {
        String text = piece.text();
        int line = 1;
        int lineStart = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < offset; i = text.indexOf('\n', i + 1)) {
            line++;
            lineStart = i + 1;
        }
        return piece.name() + ":" + line + ":" + text.codePointCount(lineStart, offset);
    }
}

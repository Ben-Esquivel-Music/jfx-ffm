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

import com.sun.scenario.effect.compiler.tree.SourcePosition;

/**
 * A mistake in a JSL program, other than a syntax error, at a line and column of the program text. Its message is
 * {@code line <line>:<column> <detail>} until {@code JSLC} locates it in the program's {@link JSLSource}, which names
 * the piece, its line and column, and the program.
 * <p>
 * The line is 1-based and the column 0-based, as ANTLR counts them, and the detail is the message without the
 * location.
 */
public final class JSLSourceException extends RuntimeException {

    private final int line;
    private final int column;
    private final String detail;
    private String message;

    public JSLSourceException(int line, int column, String detail) {
        this(line, column, detail, null);
    }

    public JSLSourceException(int line, int column, String detail, Throwable cause) {
        super(cause);
        this.line = line;
        this.column = column;
        this.detail = detail;
        this.message = "line " + line + ":" + column + " " + detail;
    }

    public JSLSourceException(SourcePosition position, String detail) {
        this(position.line(), position.column(), detail);
    }

    JSLSourceException locate(JSLSource source, String programName) {
        message = source.describe(line, column, programName, detail);
        return this;
    }

    @Override
    public String getMessage() {
        return message;
    }
}

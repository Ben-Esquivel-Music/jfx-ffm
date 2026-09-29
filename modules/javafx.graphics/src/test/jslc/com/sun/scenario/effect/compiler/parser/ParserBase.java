/*
 * Copyright (c) 2008, 2026, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.scenario.effect.compiler.parser;

import com.sun.scenario.effect.compiler.JSLLexer;
import com.sun.scenario.effect.compiler.JSLParser;
import com.sun.scenario.effect.compiler.ThrowingErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import static org.junit.jupiter.api.Assertions.fail;

public abstract class ParserBase {

    protected JSLParser parserOver(String text) {
        CharStream charStream = CharStreams.fromString(text);
        JSLLexer lexer = new JSLLexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        JSLParser parser = new JSLParser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);
        return parser;
    }

    /**
     * Fails unless the rule just parsed consumed the whole input. A rule called directly has no EOF
     * after it, so once it has matched a complete alternative it can stop, without reporting an error,
     * at the first token that cannot extend the match. This check makes such a prefix match fail.
     */
    protected static void assertAllInputConsumed(JSLParser parser) {
        Token next = parser.getCurrentToken();
        if (next.getType() != Token.EOF) {
            fail("trailing input '" + next.getText() + "' at " + next.getLine() + ":" + next.getCharPositionInLine());
        }
    }
}

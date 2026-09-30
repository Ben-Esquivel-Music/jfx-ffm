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

import com.sun.scenario.effect.compiler.JSLParser;
import com.sun.scenario.effect.compiler.model.BinaryOpType;
import com.sun.scenario.effect.compiler.model.Qualifier;
import com.sun.scenario.effect.compiler.model.SymbolTable;
import com.sun.scenario.effect.compiler.model.Type;
import com.sun.scenario.effect.compiler.model.Variable;
import com.sun.scenario.effect.compiler.tree.BinaryExpr;
import com.sun.scenario.effect.compiler.tree.Expr;
import com.sun.scenario.effect.compiler.tree.FieldSelectExpr;
import com.sun.scenario.effect.compiler.tree.JSLVisitor;
import com.sun.scenario.effect.compiler.tree.LiteralExpr;
import com.sun.scenario.effect.compiler.tree.VariableExpr;
import com.sun.scenario.effect.compiler.tree.VectorCtorExpr;
import org.antlr.v4.runtime.misc.ParseCancellationException;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AssignmentExprTest extends ParserBase {

    private static final String CONST_LEFT_HAND_SIDE =
            "Left-hand side of assignment expression cannot be const variable";

    @Test
    public void userVar() {
        BinaryExpr tree = parseTreeFor("foo = 32.0");
        assertEquals(Type.FLOAT, tree.getResultType());
        assertEquals(BinaryOpType.EQ, tree.getOp());
        assertEquals(VariableExpr.class, tree.getLeft().getClass());
        Variable var = ((VariableExpr) tree.getLeft()).getVariable();
        assertEquals("foo", var.getName());
        assertEquals(Type.FLOAT, tree.getRight().getResultType());
        assertEquals(LiteralExpr.class, tree.getRight().getClass());
        Object val = ((LiteralExpr) tree.getRight()).getValue();
        assertEquals(32.0f, val);
    }

    @Test
    public void userROVar() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> parseTreeFor("readonly = 32.0"));
        assertEquals(CONST_LEFT_HAND_SIDE, e.getMessage());
    }

    @Test
    public void coreVar() {
        BinaryExpr tree = parseTreeFor("color = float4(1.0)");
        assertEquals(Type.FLOAT4, tree.getResultType());
        assertEquals(BinaryOpType.EQ, tree.getOp());
        assertEquals(VariableExpr.class, tree.getLeft().getClass());
        Variable var = ((VariableExpr) tree.getLeft()).getVariable();
        assertEquals("color", var.getName());
        assertEquals(Type.FLOAT4, tree.getRight().getResultType());
        assertEquals(VectorCtorExpr.class, tree.getRight().getClass());
        List<Expr> params = ((VectorCtorExpr) tree.getRight()).getParams();

        assertEquals(4, params.size());

        for (int i = 0; i < 4; i++) {
            Object val = ((LiteralExpr) params.get(i)).getValue();
            assertEquals(Type.FLOAT, params.get(i).getResultType());
            assertEquals(1.0f, val);
        }
    }

    @Test
    public void coreVarField() {
        BinaryExpr tree = parseTreeFor("color.r = 3.0");
        assertEquals(Type.FLOAT, tree.getResultType());
        assertEquals(BinaryOpType.EQ, tree.getOp());
        assertEquals(FieldSelectExpr.class, tree.getLeft().getClass());
        FieldSelectExpr fsExpr = (FieldSelectExpr) tree.getLeft();
        VariableExpr expr = (VariableExpr) fsExpr.getExpr();
        assertEquals(Type.FLOAT4, expr.getResultType());
        assertEquals("r", fsExpr.getFields());
        assertEquals("color", expr.getVariable().getName());
        assertEquals(LiteralExpr.class, tree.getRight().getClass());
        Object val = ((LiteralExpr) tree.getRight()).getValue();
        assertEquals(3.0f, val);
    }

    @Test
    public void coreROVar() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> parseTreeFor("pos0 = float2(1.0)"));
        assertEquals(CONST_LEFT_HAND_SIDE, e.getMessage());
    }

    @Test
    public void coreROVarField() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> parseTreeFor("pos0.x = 1.0"));
        assertEquals(CONST_LEFT_HAND_SIDE, e.getMessage());
    }

    @Test
    public void notAnAssignment() {
        // a type qualifier can start a declaration, never an expression
        ParseCancellationException e = assertThrows(ParseCancellationException.class,
                () -> parseTreeFor("const foo"));
        assertTrue(e.getMessage().startsWith("line 1:0 extraneous input 'const' expecting "), e.getMessage());
    }

    private BinaryExpr parseTreeFor(String text) {
        JSLParser parser = parserOver(text);
        JSLVisitor visitor = new JSLVisitor();
        SymbolTable st = visitor.getSymbolTable();
        st.declareVariable("foo", Type.FLOAT, null);
        st.declareVariable("readonly", Type.FLOAT, Qualifier.CONST);
        // trick test into thinking main() function is currently in
        // scope so that we can test core variables such as color and pos0
        st.enterFrame();
        st.declareFunction("main", Type.VOID, null);
        JSLParser.Assignment_expressionContext tree = parser.assignment_expression();
        assertAllInputConsumed(parser);
        return (BinaryExpr) visitor.visit(tree);
    }
}

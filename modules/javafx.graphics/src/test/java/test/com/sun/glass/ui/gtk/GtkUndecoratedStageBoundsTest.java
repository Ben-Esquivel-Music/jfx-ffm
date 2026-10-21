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

package test.com.sun.glass.ui.gtk;

import com.sun.javafx.stage.WindowHelper;
import com.sun.javafx.tk.quantum.WindowStage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.com.sun.glass.ui.gtk.GtkFrameExtentsStandIn.rect;

/**
 * The public API of an undecorated stage on GTK while a window manager publishes frame extents for its window: an
 * {@code UNDECORATED} {@code Stage} at (0, 0) whose scene is as large as the primary screen - 1920x1080 on the
 * displays these tests run on; the child records the screen it used - reports x, y = 0, 0, the screen's width and
 * height, and its scene at (0, 0), which is also where its X window, the client, is on the screen. The stage has no
 * frame, so the extents a window manager publishes for it (Weston publishes a decorated frame's) must not reach
 * those numbers.
 * <p>
 * The extents are the window manager's where it publishes non-zero ones (Weston), otherwise Weston's 38, 38, 59, 38
 * written on the stage's X window by the test ({@link GtkFrameExtentsStandIn}). With the stand-in, the size half is
 * covered on any display. The position half - the stage's x and y and the scene's offset - is covered only where the
 * window manager's own extents are already in force when the stage's configure events arrive (Weston). The stand-in
 * is written after the stage has been placed, and glass does not set an undecorated window's bounds again when its
 * extents change, so no configure event reads the position with the stand-in in force. Under openbox the position
 * half is covered by {@link GtkFrameExtentsNativeTest}, which moves each window after the stand-in is in force.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(300)
public class GtkUndecoratedStageBoundsTest {

    private static GtkGlassChildJvm.Run run;

    @BeforeAll
    @Timeout(GtkGlassChildJvm.BEFORE_ALL_SECONDS)
    static void runScenario() {
        GtkGlassChildJvm.requireDisplay();
        run = GtkGlassChildJvm.run(GtkUndecoratedStageBoundsTest.class, "stageScenario", List.of());
    }

    /** The stage reports the rectangle it was shown at, the whole screen, and its scene at the stage's origin. */
    @Test
    public void anUndecoratedStageAtTheOriginReportsTheScreen() {
        Map<String, String> values = reading();
        assertEquals(rect(0, 0, screenWidth(), screenHeight()) + " scene 0,0",
                values.get("stage") + " scene " + values.get("scene"),
                describe(values, "the stage at 0,0 as large as the screen, " + values.get("screen")
                        + ", and the scene at 0,0"));
    }

    /** The stage's rectangle is its X window's, the client on the screen. */
    @Test
    public void anUndecoratedStageIsItsClient() {
        Map<String, String> values = reading();
        assertEquals(values.get("client"), values.get("stage"), describe(values, "the stage's x, y, width and height"
                + " to be the client's root position and size"));
    }

    private static int screenWidth() {
        return GtkFrameExtentsStandIn.parseRect(value("stage.screen"))[2];
    }

    private static int screenHeight() {
        return GtkFrameExtentsStandIn.parseRect(value("stage.screen"))[3];
    }

    private static String value(String key) {
        String value = run.values().get(key);
        if (value == null) {
            throw new AssertionError("the child recorded no " + key + ": " + run.describe());
        }
        return value;
    }

    /**
     * The settled reading; fails unless it settled and had non-zero frame extents in force, without which it would
     * prove nothing.
     */
    private static Map<String, String> reading() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : List.of("screen", "stage", "scene", "client", "extents", "settled", "rewrites",
                "extentsSource")) {
            values.put(key, value("stage." + key));
        }
        assertNotEquals("no", values.get("settled"), describe(values, "a reading that settled within "
                + GtkFrameExtentsStandIn.SETTLE_ROUNDS + " rounds"));
        assertTrue(GtkFrameExtentsStandIn.nonZero(values.get("extents")), describe(values, "non-zero frame extents"
                + " in force, from the window manager or the stand-in"));
        return values;
    }

    /** The extents in force and where they come from, the stage's rectangle and the client's, and the expectation. */
    private static String describe(Map<String, String> values, String expectation) {
        return "UNDECORATED stage, _NET_FRAME_EXTENTS " + values.get("extents") + " from "
                + values.get("extentsSource") + ": stage " + values.get("stage") + ", scene " + values.get("scene")
                + "; client " + values.get("client") + "; primary screen " + values.get("screen") + ". Expected: "
                + expectation;
    }

    // ---------------------------------------------------------------------------------------------
    // The scenario (child JVM)
    // ---------------------------------------------------------------------------------------------

    /** Runs in {@link GtkGlassChild}. */
    static void stageScenario(Map<String, String> out) throws Exception {
        GtkGlassChild.recordEnvironment(out);
        GtkEventTrace t = new GtkEventTrace();
        Stage stage = GtkGlassChild.onFx(() -> {
            Rectangle2D screen = Screen.getPrimary().getBounds();
            out.put("stage.screen", rect((int) screen.getMinX(), (int) screen.getMinY(), (int) screen.getWidth(),
                    (int) screen.getHeight()));
            Stage s = new Stage(StageStyle.UNDECORATED);
            s.setScene(new Scene(new Group(), screen.getWidth(), screen.getHeight()));
            s.setX(0);
            s.setY(0);
            s.show();
            return s;
        });
        try {
            long xid = GtkGlassChild.onFx(() ->
                    ((WindowStage) WindowHelper.getPeer(stage)).getPlatformWindow().getNativeWindow());
            t.settle();
            boolean standIn = GtkFrameExtentsStandIn.provide(out, "stage", xid);
            GtkFrameExtentsStandIn.settled(t, out, "stage", xid, standIn, () -> {
                Map<String, String> values = new LinkedHashMap<>();
                values.put("stage", number(stage.getX()) + "," + number(stage.getY()) + " " + number(stage.getWidth())
                        + "x" + number(stage.getHeight()));
                values.put("scene", number(stage.getScene().getX()) + "," + number(stage.getScene().getY()));
                return values;
            });
        } finally {
            GtkGlassChild.onFx(() -> {
                stage.hide();
                return null;
            });
        }
    }

    /** {@code value} as an integer where it is one, as Java writes a {@code double} where it is not. */
    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}

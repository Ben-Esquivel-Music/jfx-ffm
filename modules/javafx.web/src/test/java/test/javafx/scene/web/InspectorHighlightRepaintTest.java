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

package test.javafx.scene.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static test.javafx.scene.web.PixelAssertions.BLUE;
import static test.javafx.scene.web.PixelAssertions.RED;
import static test.javafx.scene.web.PixelAssertions.assertSamples;
import static test.javafx.scene.web.PixelAssertions.pageWithControlPatch;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import test.javafx.scene.web.PixelAssertions.Sample;

/**
 * The Web Inspector's highlight is drawn by the queue that closes each update and covers the whole view.
 * Outside compositing mode an update repaints only its dirty rects, and the back buffer keeps the last
 * frame's highlight everywhere else, so that queue paints the page under the highlight again, except after an
 * update whose dirty rects repainted the whole view over an opaque background.
 */
@ExtendWith(CaseAnnouncer.class)
public class InspectorHighlightRepaintTest extends TestBase {

    private static final String BOX = "<div id=box style='position:absolute;left:0;top:0;width:200px;height:200px;"
            + "background:#f00'></div>";

    /* The text gives the page far more commands than the highlight, so a second paint of it shows in the sizes. */
    private static final String TEXT_PAGE = pageWithControlPatch(BOX
            + "<div style='position:absolute;left:210px;top:0;width:180px;height:220px;overflow:hidden;"
            + "font:12px sans-serif'>" + "The quick brown fox jumps over the lazy dog. ".repeat(20) + "</div>");

    private static final String TRANSPARENT_PAGE = "<html><body style='margin:0'>" + BOX
            + "<div style='position:absolute;left:220px;top:20px;width:80px;height:80px;background:#f00'></div>"
            + "</body></html>";

    private static final Sample AWAY_FROM_THE_BOX = new Sample(260, 60, RED);

    private static final String SPOT = "const spot = document.createElement('div');"
            + " spot.style.cssText = 'position:absolute;left:100px;top:250px;width:5px;height:5px;background:#000';"
            + " document.body.appendChild(spot);";

    @Test
    public void aFullyRepaintedUpdateDrawsThePageUnderTheHighlightOnce() {
        try (PaintLog paintLog = new PaintLog(); PumpedPage page = PumpedPage.withDefaultSettings(TEXT_PAGE)) {
            page.settle();
            assertFalse(page.isInCompositingMode(), "the page is in compositing mode");
            highlightTheBox(page);

            paintLog.clear();
            page.resize(PumpedPage.WIDTH, PumpedPage.HEIGHT);
            Update full = paintLog.last(Update::repaintsTheWholeView, "an update that repainted the whole view");
            assertTrue(full.postPaintSize() < full.pageSize() / 2,
                    "the highlight's queue painted the page again after the whole view was repainted: " + full);

            paintLog.clear();
            page.script(SPOT);
            page.pumpUntilDrawn(102, 252, 0x000000);
            Update spot = paintLog.last(Update::repaintsPartOfTheView, "an update that repainted a spot");
            assertTrue(spot.postPaintSize() >= full.pageSize() / 2,
                    "the highlight's queue did not paint the page under it after a spot was repainted: " + spot
                    + ", whole page " + full.pageSize());
        }
    }

    /**
     * Under a transparent page fill each queue's clip is cleared before the queue is drawn, so the highlight's
     * queue must paint the page under it again even after the whole view was repainted.
     */
    @Test
    public void aTransparentPageKeepsItsContentUnderTheHighlight() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(TRANSPARENT_PAGE)) {
            page.setPageFill(Color.TRANSPARENT);
            BufferedImage before = page.settle();
            assertEquals(0, before.getRGB(300, 250) >>> 24, "the page fill did not make the page transparent");
            assertSamples("before the highlight", before, AWAY_FROM_THE_BOX);
            highlightTheBox(page);

            page.resize(PumpedPage.WIDTH, PumpedPage.HEIGHT);
            assertSamples("after an update that repainted the whole view", page.settle(), AWAY_FROM_THE_BOX);

            page.script(SPOT);
            assertSamples("after an update that repainted a spot", page.pumpUntilDrawn(102, 252, 0x000000),
                    AWAY_FROM_THE_BOX);
        }
    }

    /**
     * An update recorded over an opaque page fill repaints the whole view without painting the page under the
     * highlight again, so its queues must be drawn without clearing even if the fill turned transparent
     * before they were. The update turns the box away from the highlighted one blue, which shows it was drawn.
     */
    @Test
    public void anUpdateRecordedOverAnOpaqueFillIsDrawnUnclearedAfterTheFillTurnsTransparent() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(TRANSPARENT_PAGE)) {
            assertSamples("before the highlight", page.settle(), AWAY_FROM_THE_BOX);
            highlightTheBox(page);

            BufferedImage drawn = page.recordAnUpdateThenSetPageFillAndPaint(
                    "document.body.lastElementChild.style.background = '#00f'", Color.TRANSPARENT);
            assertSamples("the update recorded over an opaque fill", drawn,
                    new Sample(AWAY_FROM_THE_BOX.x(), AWAY_FROM_THE_BOX.y(), BLUE));
        }
    }

    @Test
    public void hidingTheHighlightRestoresThePage() {
        try (PumpedPage page = PumpedPage.withDefaultSettings(TEXT_PAGE)) {
            int box = page.settle().getRGB(100, 100);
            assertFalse(page.isInCompositingMode(), "the page is in compositing mode");
            highlightTheBox(page);
            assertNotEquals(box, page.settle().getRGB(100, 100), "the highlight did not tint the box");

            List<String> replies = page.hideHighlightInInspector();
            assertTrue(replies.stream().noneMatch(reply -> reply.contains("\"error\"")),
                    "the inspector refused to hide the highlight: " + replies);
            page.settle();
            page.script(SPOT);
            BufferedImage after = page.pumpUntilDrawn(102, 252, 0x000000);
            assertEquals(Integer.toHexString(box), Integer.toHexString(after.getRGB(100, 100)),
                    "the box after the highlight was hidden");
        }
    }

    /** The highlight is followed by an update that repaints the whole view, as the print test's is. */
    private static void highlightTheBox(PumpedPage page) {
        List<String> replies = page.highlightInInspector("#box");
        assertTrue(replies.stream().noneMatch(reply -> reply.contains("\"error\"")),
                "the inspector refused a request: " + replies);
        page.resize(PumpedPage.WIDTH, PumpedPage.HEIGHT);
        page.settle();
    }

    private record Queue(int x, int y, int width, int height, int size, boolean opaque) {
        boolean coversTheView() {
            return x <= 0 && y <= 0 && x + width >= PumpedPage.WIDTH && y + height >= PumpedPage.HEIGHT;
        }
    }

    /** The queues of one update: one opaque queue per dirty rect, then the highlight's, which is not opaque. */
    private record Update(List<Queue> queues) {
        private static final Pattern QUEUE = Pattern.compile("WCRenderQueue\\{clip=WCRectangle\\{x:(-?[\\d.]+)"
                + " y:(-?[\\d.]+) w:([\\d.]+) h:([\\d.]+)\\}, size=(\\d+), opaque=(true|false)\\}");

        static Update parse(String frame) {
            List<Queue> queues = new ArrayList<>();
            Matcher queue = QUEUE.matcher(frame);
            while (queue.find()) {
                queues.add(new Queue((int) Double.parseDouble(queue.group(1)), (int) Double.parseDouble(queue.group(2)),
                        (int) Double.parseDouble(queue.group(3)), (int) Double.parseDouble(queue.group(4)),
                        Integer.parseInt(queue.group(5)), Boolean.parseBoolean(queue.group(6))));
            }
            return new Update(List.copyOf(queues));
        }

        boolean repaintsTheWholeView() {
            return queues.stream().anyMatch(queue -> queue.opaque() && queue.coversTheView());
        }

        boolean repaintsPartOfTheView() {
            return queues.stream().anyMatch(Queue::opaque) && !repaintsTheWholeView();
        }

        int pageSize() {
            return queues.stream().filter(Queue::opaque).mapToInt(Queue::size).sum();
        }

        int postPaintSize() {
            return queues.stream().filter(queue -> !queue.opaque()).mapToInt(Queue::size).sum();
        }
    }

    /** The updates the paint logger reports once each update's dirty rects and highlight are recorded. */
    private static final class PaintLog extends Handler implements AutoCloseable {
        private static final String RECORD = "Dirty rects processed";

        private final Logger logger = Logger.getLogger("com.sun.webkit.WebPage.paint");
        private final Level level = logger.getLevel();
        private final SimpleFormatter formatter = new SimpleFormatter();
        private final List<Update> updates = new CopyOnWriteArrayList<>();

        PaintLog() {
            logger.setLevel(Level.FINEST);
            logger.addHandler(this);
        }

        void clear() {
            updates.clear();
        }

        Update last(Predicate<Update> kind, String description) {
            List<Update> matching = updates.stream().filter(kind).toList();
            if (matching.isEmpty()) {
                throw new AssertionError("the paint log shows no " + description + ": " + updates);
            }
            return matching.getLast();
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message != null && message.startsWith(RECORD)) {
                String text = formatter.formatMessage(record);
                updates.add(Update.parse(text.substring(text.indexOf("currentFrame:"))));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(level);
        }
    }
}

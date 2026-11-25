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

import test.com.sun.javafx.font.MacFontReleaseVerdict.Outcome;
import test.com.sun.javafx.font.MacFontReleaseVerdict.Verdict;

/**
 * The verdict of the {@link MacGlyphImageTest} check whose instrument depends on the machine, decided from the images
 * the child wrote and nothing else, so that the rule is tested on every OS. Each image is {@code length;sha256}.
 */
final class MacGlyphImageVerdict {

    private MacGlyphImageVerdict() {
    }

    /**
     * Whether a glyph drawn into the cached context, which a strike without sub-pixel positions made, was drawn with
     * its own strike's options: {@code subject} is that image, {@code own} the glyph drawn half a pixel over into a
     * context made with its own strike's options, {@code other} the same draw into one made with the other strike's.
     * A machine that draws {@code own} and {@code other} alike cannot show which options drew the subject.
     */
    static Verdict ownStrikeOptions(String subject, String own, String other) {
        String images = "; the glyph drawn half a pixel over with its own strike's options is " + own
                + ", with the options of the strike that made the cached context " + other + ", and into the cached"
                + " context " + subject;
        if (other.equals(own)) {
            return new Verdict(Outcome.CANNOT_SHOW, "this machine draws the glyph half a pixel over alike with and"
                    + " without sub-pixel positioning, so no image shows which options drew it" + images);
        }
        if (subject.equals(own)) {
            return new Verdict(Outcome.PROVED, "the glyph drawn into the cached context has its own strike's"
                    + " options" + images);
        }
        if (subject.equals(other)) {
            return new Verdict(Outcome.REFUTED, "the glyph drawn into the cached context has the options of the"
                    + " strike that made the context, not its own" + images);
        }
        return new Verdict(Outcome.REFUTED, "the glyph drawn into the cached context matches neither its own"
                + " strike's options nor the other strike's" + images);
    }
}

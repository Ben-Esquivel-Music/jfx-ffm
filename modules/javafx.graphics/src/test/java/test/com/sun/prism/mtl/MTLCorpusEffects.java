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

package test.com.sun.prism.mtl;

import com.sun.javafx.geom.RectBounds;
import com.sun.prism.paint.Color;
import com.sun.prism.paint.Gradient;
import com.sun.prism.paint.LinearGradient;
import com.sun.prism.paint.RadialGradient;
import com.sun.prism.paint.Stop;
import com.sun.scenario.effect.Blend;
import com.sun.scenario.effect.Bloom;
import com.sun.scenario.effect.BoxBlur;
import com.sun.scenario.effect.BoxShadow;
import com.sun.scenario.effect.Brightpass;
import com.sun.scenario.effect.Color4f;
import com.sun.scenario.effect.ColorAdjust;
import com.sun.scenario.effect.Crop;
import com.sun.scenario.effect.DisplacementMap;
import com.sun.scenario.effect.DropShadow;
import com.sun.scenario.effect.Effect;
import com.sun.scenario.effect.FloatMap;
import com.sun.scenario.effect.Flood;
import com.sun.scenario.effect.GaussianBlur;
import com.sun.scenario.effect.GaussianShadow;
import com.sun.scenario.effect.InnerShadow;
import com.sun.scenario.effect.InvertMask;
import com.sun.scenario.effect.Merge;
import com.sun.scenario.effect.Offset;
import com.sun.scenario.effect.PerspectiveTransform;
import com.sun.scenario.effect.PhongLighting;
import com.sun.scenario.effect.Reflection;
import com.sun.scenario.effect.SepiaTone;
import com.sun.scenario.effect.impl.prism.PrEffectHelper;
import com.sun.scenario.effect.light.DistantLight;
import com.sun.scenario.effect.light.Light;
import com.sun.scenario.effect.light.PointLight;
import com.sun.scenario.effect.light.SpotLight;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import test.com.sun.prism.mtl.MTLGoldenChild.Steps;

/**
 * The Decora corpus: every Metal effect peer, rendered through {@code PrEffectHelper.render} into a Prism RTT. The
 * child has already proved that Decora runs on Metal ({@code AccelType.METAL}); {@code PrRenderer} would otherwise
 * fall back to the Java peers without an error. Inputs are gradient floods with transparent stops, so every peer sees
 * asymmetric colour and alpha.
 */
final class MTLCorpusEffects {

    private static final int FRAME = 64;

    /** Gaussian radii whose kernels {@code 2r + 1} select every {@code LinearConvolve(Shadow)_N} peer. */
    private static final int[] RADII = {1, 3, 5, 7, 9, 11, 13, 15, 31, 47, 63};

    private MTLCorpusEffects() {
    }

    static void register(Steps steps) {
        if (steps.in(MTLGoldens.DEFAULT)) {
            List<String> blends = new ArrayList<>();
            for (Blend.Mode mode : Blend.Mode.values()) {
                String key = "effect.blend." + mode.name();
                blends.add(key);
                add(steps, key, () -> new Blend(mode, bottom(), top()));
            }
            steps.designedDistinct(blends);
            add(steps, "effect.bloom", () -> {
                Bloom bloom = new Bloom(top());
                bloom.setThreshold(0.35f);
                return bloom;
            });
            add(steps, "effect.brightpass", () -> {
                Brightpass brightpass = new Brightpass(bottom());
                brightpass.setThreshold(0.4f);
                return brightpass;
            });
            add(steps, "effect.colorAdjust", MTLCorpusEffects::colorAdjust);
            add(steps, "effect.displacementMap", MTLCorpusEffects::displacement);
            add(steps, "effect.innerShadow", MTLCorpusEffects::innerShadow);
            add(steps, "effect.invertMask", () -> new InvertMask(3, top()));
            add(steps, "effect.perspectiveTransform", () -> {
                PerspectiveTransform transform = new PerspectiveTransform(bottom());
                transform.setQuadMapping(3, 9, 57, 2, 61, 55, 8, 47);
                return transform;
            });
            add(steps, "effect.sepiaTone", () -> {
                SepiaTone sepia = new SepiaTone(bottom());
                sepia.setLevel(0.7f);
                return sepia;
            });
            add(steps, "effect.lighting.distant", () -> lighting(new DistantLight(35, 50,
                    new Color4f(1f, 0.9f, 0.7f, 1f))));
            add(steps, "effect.lighting.point", () -> lighting(new PointLight(20, 15, 30,
                    new Color4f(0.8f, 1f, 0.6f, 1f))));
            add(steps, "effect.lighting.spot", () -> {
                SpotLight spot = new SpotLight(10, 50, 40, new Color4f(1f, 0.7f, 0.9f, 1f));
                spot.setPointsAtX(40);
                spot.setPointsAtY(20);
                spot.setPointsAtZ(0);
                spot.setSpecularExponent(2.5f);
                return lighting(spot);
            });
            for (int radius : RADII) {
                add(steps, "effect.gaussianBlur.r" + radius, () -> new GaussianBlur(radius, top()));
                add(steps, "effect.gaussianShadow.r" + radius, () -> new GaussianShadow(radius,
                        new Color4f(0.6f, 0.1f, 0.3f, 0.9f), top()));
            }
            add(steps, "effect.dropShadow", () -> dropShadow(9));
            add(steps, "effect.boxBlur", () -> new BoxBlur(5, 3, 3, bottom()));
            add(steps, "effect.boxShadow", () -> new BoxShadow(7, 4, 2, top()));
            add(steps, "effect.reflection", () -> {
                Reflection reflection = new Reflection(top());
                reflection.setTopOffset(2);
                reflection.setFraction(0.6f);
                reflection.setTopOpacity(0.8f);
                reflection.setBottomOpacity(0.1f);
                return reflection;
            });
            add(steps, "effect.crop", () -> new Crop(bottom(), new Flood(Color.WHITE, new RectBounds(9, 7, 47, 41))));
            add(steps, "effect.merge", () -> new Merge(bottom(), new Offset(5, -3, top())));
        }
        if (steps.in(MTLGoldens.POW2)) {
            add(steps, "effect.blend.SRC_OVER", () -> new Blend(Blend.Mode.SRC_OVER, bottom(), top()));
            add(steps, "effect.gaussianBlur.r5", () -> new GaussianBlur(5, top()));
            add(steps, "effect.dropShadow", () -> dropShadow(7));
            add(steps, "effect.colorAdjust", MTLCorpusEffects::colorAdjust);
            add(steps, "effect.displacementMap", MTLCorpusEffects::displacement);
            add(steps, "effect.innerShadow", MTLCorpusEffects::innerShadow);
        }
    }

    private static void add(Steps steps, String key, Supplier<Effect> effect) {
        steps.add(key, FRAME, FRAME, s -> s.render(FRAME, FRAME, false,
                g -> PrEffectHelper.render(effect.get(), g, 1.5f, 0.75f, null)));
    }

    /** A linear gradient flood with a transparent stop, over {@code [4,6]-[44,40]}. */
    static Effect top() {
        List<Stop> stops = List.of(new Stop(new Color(1f, 0.85f, 0.1f, 1f), 0f),
                new Stop(new Color(0.9f, 0.1f, 0.5f, 0.6f), 0.55f), new Stop(new Color(0.1f, 0.4f, 0.9f, 0f), 1f));
        return new Flood(new LinearGradient(4, 6, 40, 34, null, false, Gradient.REFLECT, stops),
                new RectBounds(4, 6, 44, 40));
    }

    /** A radial gradient flood over {@code [14,10]-[58,54]}. */
    static Effect bottom() {
        List<Stop> stops = List.of(new Stop(new Color(0.2f, 0.9f, 0.6f, 0.95f), 0f),
                new Stop(new Color(0.7f, 0.3f, 0.1f, 0.8f), 0.6f), new Stop(new Color(0.3f, 0.2f, 0.8f, 0.35f), 1f));
        return new Flood(new RadialGradient(34, 30, 40, 0.3f, 22, null, false, Gradient.PAD, stops),
                new RectBounds(14, 10, 58, 54));
    }

    private static Effect colorAdjust() {
        ColorAdjust adjust = new ColorAdjust(bottom());
        adjust.setHue(0.2f);
        adjust.setSaturation(-0.3f);
        adjust.setBrightness(0.1f);
        adjust.setContrast(0.4f);
        return adjust;
    }

    private static Effect displacement() {
        FloatMap map = new FloatMap(16, 12);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) {
                map.setSamples(x, y, (x - 7.5f) * 0.011f, (y * y - 30) * 0.0017f);
            }
        }
        DisplacementMap displacement = new DisplacementMap(map, bottom());
        displacement.setScaleX(1.3f);
        displacement.setScaleY(0.8f);
        displacement.setOffsetX(0.02f);
        displacement.setOffsetY(-0.03f);
        return displacement;
    }

    private static Effect innerShadow() {
        InnerShadow shadow = new InnerShadow(top());
        shadow.setRadius(6);
        shadow.setChoke(0.2f);
        shadow.setOffsetX(3);
        shadow.setOffsetY(2);
        shadow.setColor(new Color4f(0.1f, 0.6f, 0.2f, 0.9f));
        return shadow;
    }

    private static Effect lighting(Light light) {
        PhongLighting lighting = new PhongLighting(light, top(), bottom());
        lighting.setDiffuseConstant(1.2f);
        lighting.setSpecularConstant(0.6f);
        lighting.setSpecularExponent(18f);
        lighting.setSurfaceScale(2.5f);
        return lighting;
    }

    private static Effect dropShadow(float radius) {
        DropShadow shadow = new DropShadow(top());
        shadow.setRadius(radius);
        shadow.setSpread(0.25f);
        shadow.setOffsetX(4);
        shadow.setOffsetY(-2);
        shadow.setColor(new Color4f(0.3f, 0.1f, 0.6f, 0.85f));
        return shadow;
    }
}

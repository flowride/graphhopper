/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.application.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DataFeedSpeedSelectionTest {

    private static final double F = DataFeedResource.LIVE_TRAFFIC_SPEED_FACTOR;

    @Test
    public void prefersRelativeTimesOsmFreeflowEvenWhenAbsoluteLooksFast() {
        // absolute 100 / rel 1 / osm 130 → 1.0 × 117 × factor
        assertEquals(1.0 * 130 * 0.9 * F, DataFeedResource.resolveEdgeSpeedKmh(100, 1, 130), 0.001);
    }

    @Test
    public void appliesRelativeToOsmFreeflowWhenCongested() {
        // absolute 20 / rel 0.3 / osm 50 → 0.3 × 45 × factor
        assertEquals(0.3 * 50 * 0.9 * F, DataFeedResource.resolveEdgeSpeedKmh(20, 0.3, 50), 0.001);
    }

    @Test
    public void withoutRelativeCapsAbsoluteByOsmFreeflowThenAppliesFactor() {
        // min(absolu, osm×0.9)×factor
        assertEquals(Math.min(45, 31 * 0.9) * F, DataFeedResource.resolveEdgeSpeedKmh(45, Double.NaN, 31), 0.001);
        assertEquals(Math.min(20, 50 * 0.9) * F, DataFeedResource.resolveEdgeSpeedKmh(20, Double.NaN, 50), 0.001);
    }

    @Test
    public void appliesCoefficientAloneToOsmFreeflow() {
        assertEquals(0.5 * 50 * 0.9 * F, DataFeedResource.resolveEdgeSpeedKmh(Double.NaN, 0.5, 50), 0.001);
    }

    @Test
    public void appliesCoefficientAloneAgainstDefaultFreeflowWhenOsmSpeedIsMissing() {
        // 0.5 × 120 × 0.9 × factor
        assertEquals(0.5 * 120 * 0.9 * F, DataFeedResource.resolveEdgeSpeedKmh(Double.NaN, 0.5, Double.NaN), 0.001);
    }

    @Test
    public void keepsAbsoluteWithoutOsmUncappedThenAppliesFactor() {
        assertEquals(80 * F, DataFeedResource.resolveEdgeSpeedKmh(80, Double.NaN, Double.NaN), 0.001);
    }
}

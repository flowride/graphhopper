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

    @Test
    public void keepsAbsoluteSpeedWhenBelowOsmLimit() {
        assertEquals(20, DataFeedResource.resolveEdgeSpeedKmh(20, 0.3, 50), 0.001);
    }

    @Test
    public void appliesCoefficientWhenAbsoluteSpeedExceedsOsmLimit() {
        assertEquals(0.94 * 31, DataFeedResource.resolveEdgeSpeedKmh(45, 0.94, 31), 0.001);
    }

    @Test
    public void capsAtOsmLimitWhenCoefficientIsMissing() {
        assertEquals(31, DataFeedResource.resolveEdgeSpeedKmh(45, Double.NaN, 31), 0.001);
    }

    @Test
    public void keepsAbsoluteSpeedOnAFastRoadBelowItsLimit() {
        assertEquals(100, DataFeedResource.resolveEdgeSpeedKmh(100, 1, 130), 0.001);
    }

    @Test
    public void appliesCoefficientAloneToOsmSpeed() {
        assertEquals(25, DataFeedResource.resolveEdgeSpeedKmh(Double.NaN, 0.5, 50), 0.001);
    }

    @Test
    public void appliesCoefficientAloneAgainstDefaultWhenOsmSpeedIsMissing() {
        assertEquals(60, DataFeedResource.resolveEdgeSpeedKmh(Double.NaN, 0.5, Double.NaN), 0.001);
    }
}

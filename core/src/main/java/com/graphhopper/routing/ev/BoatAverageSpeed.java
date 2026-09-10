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
package com.graphhopper.routing.ev;

/**
 * Encoded value for average boat speed in km/h on waterway / ferry edges.
 * Uses 5 bits and factor 2 (same storage profile as ferry_speed).
 */
public class BoatAverageSpeed {
    public static final String KEY = "boat_average_speed";

    /**
     * Creates a unidirectional decimal encoded value for boat speed.
     *
     * @return boat_average_speed encoded value
     */
    public static DecimalEncodedValue create() {
        return new DecimalEncodedValueImpl(KEY, 5, 2, false);
    }
}

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
package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BoatAverageSpeed;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.FerrySpeed;
import com.graphhopper.routing.util.FerrySpeedCalculator;
import com.graphhopper.storage.IntsRef;

/**
 * Sets {@code boat_average_speed} for ferry and navigable waterway edges.
 * Ferry edges reuse {@code ferry_speed} when available.
 */
public class OSMBoatAverageSpeedParser implements TagParser {

    private static final double DEFAULT_FERRY_SPEED = 20;
    private static final double FAIRWAY_SPEED = 20;
    private static final double RIVER_SPEED = 15;
    private static final double CANAL_SPEED = 10;

    private final DecimalEncodedValue speedEnc;
    private final DecimalEncodedValue ferrySpeedEnc;

    /**
     * @param lookup encoded value lookup containing boat_average_speed and ferry_speed
     */
    public OSMBoatAverageSpeedParser(EncodedValueLookup lookup) {
        this(lookup.getDecimalEncodedValue(BoatAverageSpeed.KEY),
                lookup.getDecimalEncodedValue(FerrySpeed.KEY));
    }

    /**
     * @param speedEnc      boat_average_speed encoded value
     * @param ferrySpeedEnc ferry_speed encoded value
     */
    public OSMBoatAverageSpeedParser(DecimalEncodedValue speedEnc, DecimalEncodedValue ferrySpeedEnc) {
        this.speedEnc = speedEnc;
        this.ferrySpeedEnc = ferrySpeedEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        if (!OSMBoatAccessParser.isBoatAccessible(way))
            return;

        double speed = resolveSpeed(edgeId, edgeIntAccess, way);
        speed = FerrySpeedCalculator.minmax(speed, speedEnc);
        speedEnc.setDecimal(false, edgeId, edgeIntAccess, speed);
    }

    private double resolveSpeed(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way) {
        if (FerrySpeedCalculator.isFerry(way)) {
            double ferrySpeed = ferrySpeedEnc.getDecimal(false, edgeId, edgeIntAccess);
            return ferrySpeed > 0 ? ferrySpeed : DEFAULT_FERRY_SPEED;
        }

        if ("pier".equals(way.getTag("man_made")))
            return CANAL_SPEED;

        String waterway = way.getTag("waterway");
        if (waterway == null)
            return CANAL_SPEED;

        return switch (waterway) {
            case "fairway", "tidal_channel" -> FAIRWAY_SPEED;
            case "river" -> RIVER_SPEED;
            case "canal", "lock", "dock" -> CANAL_SPEED;
            default -> CANAL_SPEED;
        };
    }
}

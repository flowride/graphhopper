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
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.storage.IntsRef;

import java.util.Set;

/**
 * Sets bus_lane_hint to true when the way has tags indicating a bus/PSV lane:
 * lanes:bus=*, bus:lanes=*, psv:lanes=*, busway=lane|opposite_lane|yes, or psv=yes on a road.
 */
public class OSMBusLaneHintParser implements TagParser {

    private static final Set<String> BUSWAY_LANE_VALUES = Set.of("lane", "opposite_lane", "yes", "lane;lane");

    private final BooleanEncodedValue busLaneHintEnc;

    public OSMBusLaneHintParser(BooleanEncodedValue busLaneHintEnc) {
        this.busLaneHintEnc = busLaneHintEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        boolean hint = hasBusLaneHint(way);
        if (hint)
            busLaneHintEnc.setBool(false, edgeId, edgeIntAccess, true);
    }

    static boolean hasBusLaneHint(ReaderWay way) {
        if (way.hasTag("lanes:bus") && way.getTag("lanes:bus").length() > 0)
            return true;
        if (way.hasTag("bus:lanes") && way.getTag("bus:lanes").length() > 0)
            return true;
        if (way.hasTag("psv:lanes") && way.getTag("psv:lanes").length() > 0)
            return true;
        String busway = way.getTag("busway");
        if (busway != null && BUSWAY_LANE_VALUES.contains(busway))
            return true;
        if ("yes".equals(way.getTag("psv")) && way.hasTag("highway"))
            return true;
        return false;
    }
}

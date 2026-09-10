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
import com.graphhopper.routing.util.FerrySpeedCalculator;
import com.graphhopper.storage.IntsRef;

import java.util.List;
import java.util.Set;

/**
 * Sets {@code boat_access} on ferry, navigable waterway and pier edges.
 * Restriction keys are read in order: ship, motorboat, boat, access.
 */
public class OSMBoatAccessParser implements TagParser {

    private static final List<String> RESTRICTION_KEYS = List.of("ship", "motorboat", "boat", "access");
    private static final Set<String> DENIED = Set.of("no", "private", "military", "restricted");
    private static final Set<String> ALLOWED = Set.of("yes", "designated", "official", "permissive");
    private static final Set<String> ALWAYS_NAVIGABLE_WATERWAYS = Set.of(
            "fairway", "tidal_channel", "canal", "lock", "dock");
    private static final Set<String> ONEWAY_YES = Set.of("yes", "true", "1");
    private static final Set<String> ONEWAY_REVERSE = Set.of("-1", "reverse");

    private final BooleanEncodedValue accessEnc;

    /**
     * @param accessEnc bidirectional boat_access encoded value
     */
    public OSMBoatAccessParser(BooleanEncodedValue accessEnc) {
        this.accessEnc = accessEnc;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
        if (!isBoatAccessible(way))
            return;

        boolean forward = true;
        boolean backward = true;
        String boatOneway = way.getTag("boat:oneway");
        String oneway = boatOneway != null ? boatOneway : way.getTag("oneway");
        if (oneway != null) {
            if (ONEWAY_YES.contains(oneway)) {
                backward = false;
            } else if (ONEWAY_REVERSE.contains(oneway)) {
                forward = false;
            }
        }

        if (forward)
            accessEnc.setBool(false, edgeId, edgeIntAccess, true);
        if (backward)
            accessEnc.setBool(true, edgeId, edgeIntAccess, true);
    }

    /**
     * Returns whether the OSM way should be considered navigable for boats.
     *
     * @param way OSM way
     * @return true when boat access is granted
     */
    static boolean isBoatAccessible(ReaderWay way) {
        String restriction = firstRestrictionValue(way);
        if (DENIED.contains(restriction))
            return false;

        if (FerrySpeedCalculator.isFerry(way))
            return true;

        if ("pier".equals(way.getTag("man_made")))
            return true;

        String waterway = way.getTag("waterway");
        if (waterway == null)
            return false;

        if (ALWAYS_NAVIGABLE_WATERWAYS.contains(waterway))
            return !DENIED.contains(restriction);

        if ("river".equals(waterway))
            return ALLOWED.contains(restriction);

        return false;
    }

    /**
     * Reads the first non-empty restriction among ship, motorboat, boat, access.
     *
     * @param way OSM way
     * @return restriction value or empty string when none is set
     */
    static String firstRestrictionValue(ReaderWay way) {
        for (String key : RESTRICTION_KEYS) {
            String value = way.getTag(key);
            if (value != null && !value.isEmpty())
                return value;
        }
        return "";
    }
}

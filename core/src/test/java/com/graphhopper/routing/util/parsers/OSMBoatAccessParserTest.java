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
import com.graphhopper.routing.ev.ArrayEdgeIntAccess;
import com.graphhopper.routing.ev.BoatAccess;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.OSMParsers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OSMBoatAccessParserTest {

    private final EncodingManager em = new EncodingManager.Builder().add(BoatAccess.create()).build();
    private final OSMBoatAccessParser parser = new OSMBoatAccessParser(em.getBooleanEncodedValue(BoatAccess.KEY));
    private final BooleanEncodedValue accessEnc = em.getBooleanEncodedValue(BoatAccess.KEY);
    private final OSMParsers osmParsers = new OSMParsers();

    @Test
    void ferryIsAccessible() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("route", "ferry");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertTrue(accessEnc.getBool(false, 0, access));
        assertTrue(accessEnc.getBool(true, 0, access));
    }

    @Test
    void canalIsAccessibleUnlessBoatNo() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("waterway", "canal");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertTrue(accessEnc.getBool(false, 0, access));

        way.setTag("boat", "no");
        access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertFalse(accessEnc.getBool(false, 0, access));
    }

    @Test
    void riverRequiresExplicitBoatAccess() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("waterway", "river");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertFalse(accessEnc.getBool(false, 0, access));

        way.setTag("boat", "yes");
        access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertTrue(accessEnc.getBool(false, 0, access));
    }

    @Test
    void pierIsAccessible() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("man_made", "pier");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertTrue(accessEnc.getBool(false, 0, access));
    }

    @Test
    void highwayIsNotAccessible() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "primary");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertFalse(accessEnc.getBool(false, 0, access));
    }

    @Test
    void onewayRestrictsBackward() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("waterway", "canal");
        way.setTag("oneway", "yes");
        EdgeIntAccess access = ArrayEdgeIntAccess.createFromBytes(em.getBytesForFlags());
        parser.handleWayTags(0, access, way, null);
        assertTrue(accessEnc.getBool(false, 0, access));
        assertFalse(accessEnc.getBool(true, 0, access));
    }

    @Test
    void acceptWayImportsNavigableWaterways() {
        ReaderWay canal = new ReaderWay(1);
        canal.setTag("waterway", "canal");
        assertTrue(osmParsers.acceptWay(canal));

        ReaderWay fairway = new ReaderWay(2);
        fairway.setTag("waterway", "fairway");
        assertTrue(osmParsers.acceptWay(fairway));

        ReaderWay riverPlain = new ReaderWay(3);
        riverPlain.setTag("waterway", "river");
        assertFalse(osmParsers.acceptWay(riverPlain));

        ReaderWay riverBoat = new ReaderWay(4);
        riverBoat.setTag("waterway", "river");
        riverBoat.setTag("boat", "designated");
        assertTrue(osmParsers.acceptWay(riverBoat));

        ReaderWay stream = new ReaderWay(5);
        stream.setTag("waterway", "stream");
        assertFalse(osmParsers.acceptWay(stream));
    }
}

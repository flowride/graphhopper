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
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FerrySpeedCalculator;
import com.graphhopper.routing.util.OSMParsers;
import com.graphhopper.routing.weighting.custom.CustomModelParser;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.graphhopper.json.Statement.*;
import static com.graphhopper.json.Statement.Op.LIMIT;
import static com.graphhopper.json.Statement.Op.MULTIPLY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BoatCustomModelTest {

    private EncodingManager em;
    private OSMParsers parsers;
    private CustomModel boatModel;

    @BeforeEach
    void setup() {
        BooleanEncodedValue boatAccess = BoatAccess.create();
        DecimalEncodedValue boatSpeed = BoatAverageSpeed.create();
        DecimalEncodedValue ferrySpeed = FerrySpeed.create();
        EnumEncodedValue<RoadEnvironment> roadEnv = RoadEnvironment.create();
        em = new EncodingManager.Builder()
                .add(boatAccess)
                .add(boatSpeed)
                .add(ferrySpeed)
                .add(roadEnv)
                .build();

        parsers = new OSMParsers()
                .addWayTagParser(new OSMRoadEnvironmentParser(roadEnv))
                .addWayTagParser(new FerrySpeedCalculator(ferrySpeed))
                .addWayTagParser(new OSMBoatAccessParser(boatAccess))
                .addWayTagParser(new OSMBoatAverageSpeedParser(boatSpeed, ferrySpeed));

        boatModel = new CustomModel();
        boatModel.setDistanceInfluence(200.0);
        boatModel.addToPriority(If("boat_access", MULTIPLY, "1"));
        boatModel.addToPriority(Else(MULTIPLY, "0"));
        boatModel.addToSpeed(If("road_environment == FERRY", LIMIT, "ferry_speed"));
        boatModel.addToSpeed(Else(LIMIT, "boat_average_speed"));
    }

    private EdgeIteratorState createEdge(ReaderWay way) {
        BaseGraph graph = new BaseGraph.Builder(em).create();
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(100);
        // ferry speed calculator expects edge_distance when no duration is present
        if (FerrySpeedCalculator.isFerry(way) && !way.hasTag("edge_distance"))
            way.setTag("edge_distance", 1000.0);
        parsers.handleWayTags(edge.getEdge(), graph.getEdgeAccess(), way, em.createRelationFlags());
        return edge;
    }

    @Test
    void canalHasPriorityOne() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("waterway", "canal");
        EdgeIteratorState edge = createEdge(way);
        CustomWeighting.Parameters p = CustomModelParser.createWeightingParameters(boatModel, em);
        assertEquals(1, p.getEdgeToPriorityMapping().get(edge, false), 0.01);
    }

    @Test
    void highwayHasPriorityZero() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "primary");
        EdgeIteratorState edge = createEdge(way);
        CustomWeighting.Parameters p = CustomModelParser.createWeightingParameters(boatModel, em);
        assertEquals(0, p.getEdgeToPriorityMapping().get(edge, false), 0.01);
    }

    @Test
    void ferryUsesFerrySpeed() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("route", "ferry");
        way.setTag("edge_distance", 2000.0);
        EdgeIteratorState edge = createEdge(way);
        CustomWeighting.Parameters p = CustomModelParser.createWeightingParameters(boatModel, em);
        assertEquals(1, p.getEdgeToPriorityMapping().get(edge, false), 0.01);
        double speed = p.getEdgeToSpeedMapping().get(edge, false);
        assertEquals(6, speed, 0.01);
    }
}

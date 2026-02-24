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

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resource to feed real-time traffic data (e.g. speed) into the graph.
 * POST /datafeed with JSON body: [{"id":"1","points":[[lon,lat]],"value":50,"value_type":"speed","mode":"REPLACE"}, ...]
 * Points are in GeoJSON order (longitude, latitude). The first point of each entry is used to find the nearest edge.
 */
@Path("datafeed")
public class DataFeedResource {

    private static final Logger logger = LoggerFactory.getLogger(DataFeedResource.class);

    private final GraphHopper graphHopper;
    private final ReentrantLock writeLock = new ReentrantLock();

    @Inject
    public DataFeedResource(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response postDataFeed(List<DataFeedEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Response.ok("{\"updated\":0}").build();
        }

        EncodingManager em = graphHopper.getEncodingManager();
        String vehicle = resolveVehicle(em);
        BooleanEncodedValue accessEnc = em.getBooleanEncodedValue(VehicleAccess.key(vehicle));
        DecimalEncodedValue speedEnc = em.getDecimalEncodedValue(VehicleSpeed.key(vehicle));

        BaseGraph graph = graphHopper.getBaseGraph();
        LocationIndex locationIndex = graphHopper.getLocationIndex();

        writeLock.lock();
        try {
            int updated = 0;
            int errNoPoints = 0, errBadValue = 0, errBadFormat = 0, errNoSnap = 0, errNoEdge = 0;
            for (DataFeedEntry entry : entries) {
                String entryCtx = "id=" + entry.getId();
                if (entry.getPoints() == null || entry.getPoints().isEmpty()) {
                    errNoPoints++;
                    logger.warn("Datafeed error [no_points]: {} — points null or empty", entryCtx);
                    continue;
                }
                // GeoJSON: first point as [lon, lat]
                List<Double> pt = entry.getPoints().get(0);
                if (pt == null || pt.size() < 2) {
                    errNoPoints++;
                    logger.warn("Datafeed error [no_points]: {} — first point null or size<2", entryCtx);
                    continue;
                }
                double lon = pt.get(0);
                double lat = pt.get(1);
                double value = entry.getValue();
                entryCtx = String.format("id=%s lon=%.6f lat=%.6f value=%.1f", entry.getId(), lon, lat, value);
                if (value < 0) {
                    errBadValue++;
                    logger.warn("Datafeed error [bad_value]: {} — value < 0", entryCtx);
                    continue;
                }
                if (!"speed".equalsIgnoreCase(entry.getValue_type())) {
                    errBadFormat++;
                    logger.warn("Datafeed error [bad_format]: {} — value_type='{}' (expected speed)", entryCtx, entry.getValue_type());
                    continue;
                }
                if (!"REPLACE".equalsIgnoreCase(entry.getMode())) {
                    errBadFormat++;
                    logger.warn("Datafeed error [bad_format]: {} — mode='{}' (expected REPLACE)", entryCtx, entry.getMode());
                    continue;
                }

                Snap snap = locationIndex.findClosest(lat, lon, EdgeFilter.ALL_EDGES);
                if (!snap.isValid()) {
                    errNoSnap++;
                    logger.warn("Datafeed error [no_snap]: {} — no edge found near point (hors graphe ou trop loin)", entryCtx);
                    continue;
                }
                EdgeIteratorState edge = snap.getClosestEdge();
                if (edge == null) {
                    errNoEdge++;
                    logger.warn("Datafeed error [no_edge]: {} — getClosestEdge() null", entryCtx);
                    continue;
                }
                int edgeId = edge.getEdge();
                EdgeIteratorState state = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
                if (state == null) {
                    errNoEdge++;
                    logger.warn("Datafeed error [no_edge]: {} — edgeId={} getEdgeIteratorState null", entryCtx, edgeId);
                    continue;
                }
                double speed = Math.min(value, speedEnc.getMaxOrMaxStorableDecimal());
                GHUtility.setSpeed(speed, speed, accessEnc, speedEnc, state);
                updated++;
            }
            int errors = errNoPoints + errBadValue + errBadFormat + errNoSnap + errNoEdge;
            logger.info("Datafeed: updated {} edges, {} errors (no_points:{}, bad_value:{}, bad_format:{}, no_snap:{}, no_edge:{})",
                    updated, errors, errNoPoints, errBadValue, errBadFormat, errNoSnap, errNoEdge);
            return Response.ok("{\"updated\":" + updated + ",\"errors\":" + errors + "}").build();
        } finally {
            writeLock.unlock();
        }
    }

    private static String resolveVehicle(EncodingManager em) {
        List<String> vehicles = em.getVehicles();
        if (vehicles.isEmpty()) {
            throw new IllegalStateException("No vehicles in EncodingManager");
        }
        for (String v : vehicles) {
            if ("car".equals(v) || "car4wd".equals(v)) {
                return v;
            }
        }
        return vehicles.get(0);
    }

    /**
     * DTO for one datafeed entry. JSON: id, points (array of [lon,lat]), value, value_type, mode.
     */
    public static class DataFeedEntry {
        private String id;
        private List<List<Double>> points;
        private double value;
        private String value_type = "speed";
        private String mode = "REPLACE";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<List<Double>> getPoints() {
            return points;
        }

        public void setPoints(List<List<Double>> points) {
            this.points = points;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }

        public String getValue_type() {
            return value_type;
        }

        public void setValue_type(String value_type) {
            this.value_type = value_type;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }
}

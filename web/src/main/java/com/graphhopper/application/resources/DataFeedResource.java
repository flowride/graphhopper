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
import com.graphhopper.routing.ev.MaxSpeed;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.ev.VehicleSpeed;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resource to feed real-time traffic data (e.g. speed) into the graph.
 * POST /datafeed with JSON body: [{"id":"1","points":[[lon,lat]],"value":50,"value_type":"speed","mode":"REPLACE"}, ...]
 * Points are in GeoJSON order (longitude, latitude). Each point of the polyline is snapped to find edges.
 * <p>
 * value_type: "speed" — value is the measured speed in km/h. Optional relative_speed is the TomTom
 * coefficient in [0, 1]. Per edge the freeflow reference is OSM max × 0.9 (same convention as
 * CarAverageSpeedParser). When relative_speed is present it is applied to that reference; otherwise
 * the absolute speed is capped by the reference. The result is then scaled by LIVE_TRAFFIC_SPEED_FACTOR.
 * value_type: "relative_speed" — value in [0, 1]; per-edge speed = value × freeflow reference × factor.
 */
@Path("datafeed")
public class DataFeedResource {

    private static final Logger logger = LoggerFactory.getLogger(DataFeedResource.class);

    private final GraphHopper graphHopper;
    private static final ReentrantLock writeLock = new ReentrantLock();
    /** Segments mis à jour (géométrie + valeur) pour affichage GET /roads — static pour être partagé entre toutes les instances (Jersey en crée une par requête). */
    private static final CopyOnWriteArrayList<TrafficSegment> roadSegments = new CopyOnWriteArrayList<>();

    @Inject
    public DataFeedResource(GraphHopper graphHopper) {
        this.graphHopper = graphHopper;
    }

    /**
     * GET /roads : GeoJSON FeatureCollection des segments avec trafic (pour la couche carte).
     * Couleur par propriété "value" (vitesse km/h) ou "relative_speed" (0–1).
     */
    @GET
    @Path("roads")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRoads() {
        List<TrafficSegment> list = new ArrayList<>(roadSegments);
        return Response.ok(buildGeoJSON(list)).build();
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
        DecimalEncodedValue maxSpeedEnc = em.hasEncodedValue(MaxSpeed.KEY) ? em.getDecimalEncodedValue(MaxSpeed.KEY) : null;

        BaseGraph graph = graphHopper.getBaseGraph();
        LocationIndex locationIndex = graphHopper.getLocationIndex();

        writeLock.lock();
        try {
            roadSegments.clear();
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
                String valueType = entry.getValue_type();
                entryCtx = String.format("id=%s lon=%.6f lat=%.6f value=%.1f type=%s", entry.getId(), lon, lat, value, valueType);
                if (!"REPLACE".equalsIgnoreCase(entry.getMode())) {
                    errBadFormat++;
                    logger.warn("Datafeed error [bad_format]: {} — mode='{}' (expected REPLACE)", entryCtx, entry.getMode());
                    continue;
                }
                boolean isRelativeSpeed = "relative_speed".equalsIgnoreCase(valueType);
                Double relativeField = entry.getRelative_speed();
                boolean hasCompanionRelative = relativeField != null && Double.isFinite(relativeField) && relativeField >= 0 && relativeField <= 1;
                if ("speed".equalsIgnoreCase(valueType)) {
                    if (!Double.isFinite(value) || value < 0) {
                        errBadValue++;
                        logger.warn("Datafeed error [bad_value]: {} — value must be finite and >= 0", entryCtx);
                        continue;
                    }
                    if (relativeField != null && !hasCompanionRelative) {
                        errBadValue++;
                        logger.warn("Datafeed error [bad_value]: {} — relative_speed must be finite and in [0, 1]", entryCtx);
                        continue;
                    }
                } else if (isRelativeSpeed) {
                    if (!Double.isFinite(value) || value < 0 || value > 1) {
                        errBadValue++;
                        logger.warn("Datafeed error [bad_value]: {} — relative_speed must be finite and in [0, 1]", entryCtx);
                        continue;
                    }
                } else {
                    errBadFormat++;
                    logger.warn("Datafeed error [bad_format]: {} — value_type='{}' (expected speed or relative_speed)", entryCtx, valueType);
                    continue;
                }

                // Collecter toutes les arêtes traversées par la polyline (snap de chaque point)
                Set<Integer> edgeIds = new HashSet<>();
                for (List<Double> p : entry.getPoints()) {
                    if (p == null || p.size() < 2) continue;
                    double plon = p.get(0);
                    double plat = p.get(1);
                    Snap snap = locationIndex.findClosest(plat, plon, EdgeFilter.ALL_EDGES);
                    if (!snap.isValid()) continue;
                    EdgeIteratorState edge = snap.getClosestEdge();
                    if (edge == null) continue;
                    edgeIds.add(edge.getEdge());
                }
                if (edgeIds.isEmpty()) {
                    errNoSnap++;
                    logger.warn("Datafeed error [no_snap]: {} — no edge found along polyline", entryCtx);
                    continue;
                }

                // Mesure absolue, ou coefficient seul. Le choix se fait par arête selon sa vitesse OSM.
                double absoluteForResolve = isRelativeSpeed ? Double.NaN : value;
                double relativeForResolve = isRelativeSpeed ? value : (hasCompanionRelative ? relativeField : Double.NaN);
                Double segmentMaxSpeed = null;
                double segmentSpeedForDisplay = Double.NaN;
                double maxStorable = speedEnc.getMaxOrMaxStorableDecimal();
                for (Integer edgeId : edgeIds) {
                    EdgeIteratorState state = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
                    if (state == null) continue;
                    double osmSpeed = readOsmMaxSpeedKmh(maxSpeedEnc, graph, state);
                    double speed = resolveEdgeSpeedKmh(absoluteForResolve, relativeForResolve, osmSpeed);
                    if (!Double.isFinite(speed) || speed < 0) continue;
                    // Une mesure nulle fermerait l'arête ; on garde un plancher roulant.
                    speed = Math.max(MIN_TRAFFIC_SPEED_KMH, Math.min(speed, maxStorable));
                    GHUtility.setSpeed(speed, speed, accessEnc, speedEnc, state);
                    if (Double.isNaN(segmentSpeedForDisplay)) {
                        segmentSpeedForDisplay = speed;
                        if (Double.isFinite(osmSpeed)) segmentMaxSpeed = osmSpeed;
                    }
                }
                if (Double.isNaN(segmentSpeedForDisplay)) continue;
                // Affichage : géométrie envoyée (polyline complète) ou première arête en secours
                List<double[]> coords = null;
                if (entry.getPoints() != null && entry.getPoints().size() >= 2) {
                    coords = new ArrayList<>(entry.getPoints().size());
                    for (List<Double> p : entry.getPoints()) {
                        if (p != null && p.size() >= 2)
                            coords.add(new double[]{p.get(0), p.get(1)});
                    }
                }
                if (coords == null || coords.size() < 2) {
                    Integer firstEdgeId = edgeIds.iterator().next();
                    EdgeIteratorState state = graph.getEdgeIteratorState(firstEdgeId, Integer.MIN_VALUE);
                    if (state != null) {
                        PointList pl = state.fetchWayGeometry(FetchMode.ALL);
                        if (pl != null && pl.size() >= 2) {
                            coords = new ArrayList<>(pl.size());
                            for (int i = 0; i < pl.size(); i++) {
                                coords.add(new double[]{pl.getLon(i), pl.getLat(i)});
                            }
                        }
                    }
                }
                if (coords != null && coords.size() >= 2) {
                    roadSegments.add(new TrafficSegment(coords, segmentSpeedForDisplay, segmentMaxSpeed));
                }
                updated += edgeIds.size();
            }
            int errors = errNoPoints + errBadValue + errBadFormat + errNoSnap + errNoEdge;
            logger.info("Datafeed: updated {} edges, {} errors (no_points:{}, bad_value:{}, bad_format:{}, no_snap:{}, no_edge:{})",
                    updated, errors, errNoPoints, errBadValue, errBadFormat, errNoSnap, errNoEdge);
            return Response.ok("{\"updated\":" + updated + ",\"errors\":" + errors + "}").build();
        } finally {
            writeLock.unlock();
        }
    }

    private static final double MAX_SPEED_KMH = 120.0;
    /** Au-delà, la valeur encodée n'est pas une limitation OSM utilisable (sentinelle ou illimitée). */
    private static final double MAX_PLAUSIBLE_OSM_SPEED_KMH = 150.0;
    /** Plancher pour ne pas retirer l'arête du graphe quand la mesure est nulle. */
    private static final double MIN_TRAFFIC_SPEED_KMH = 5.0;
    /**
     * Same factor as CarAverageSpeedParser.applyMaxSpeed: import freeflow is maxspeed × 0.9.
     */
    static final double OSM_FREEFLOW_FACTOR = 0.9;
    /**
     * Extra conservatism so live routes stay closer to TomTom calculateRoute when flow tiles look free.
     * Tunable constant: change only this value if Nice→museum still drifts after a re-push.
     * Calibrated to 0.45 after 0.70 still left live ~15 min faster than TomTom on green tiles.
     */
    static final double LIVE_TRAFFIC_SPEED_FACTOR = 0.45;

    /**
     * OSM max speed of one edge, or NaN when the encoded value is missing or not a plausible limit.
     *
     * @param maxSpeedEnc - encoded max speed, absent when the graph has no max_speed
     * @param graph - base graph that stores the encoded value
     * @param state - edge being updated
     * @return speed in km/h, or NaN
     */
    static double readOsmMaxSpeedKmh(DecimalEncodedValue maxSpeedEnc, BaseGraph graph, EdgeIteratorState state) {
        if (maxSpeedEnc == null || state == null) return Double.NaN;
        double maxSpeed = maxSpeedEnc.getDecimal(false, state.getEdge(), graph.getEdgeAccess());
        if (!Double.isFinite(maxSpeed) || maxSpeed <= 0 || maxSpeed > MAX_PLAUSIBLE_OSM_SPEED_KMH) return Double.NaN;
        return maxSpeed;
    }

    /**
     * Freeflow reference used as the base for traffic coefficients.
     * Matches GraphHopper OSM import: legal max × 0.9, or 120 × 0.9 when max is unknown.
     *
     * @param osmSpeedKmh - OSM max speed of the edge, or NaN when unknown
     * @return reference speed in km/h
     */
    static double freeflowReferenceKmh(double osmSpeedKmh) {
        boolean hasOsm = Double.isFinite(osmSpeedKmh) && osmSpeedKmh > 0;
        return (hasOsm ? osmSpeedKmh : MAX_SPEED_KMH) * OSM_FREEFLOW_FACTOR;
    }

    /**
     * Chooses the speed written on an edge.
     * When a TomTom coefficient is present it is applied to the freeflow reference (OSM max × 0.9).
     * Otherwise an absolute measurement is capped by that reference.
     * The candidate is then scaled by {@link #LIVE_TRAFFIC_SPEED_FACTOR}.
     *
     * @param absoluteSpeedKmh - measured speed in km/h, or NaN when the feed only has a coefficient
     * @param relativeSpeed - TomTom coefficient in [0, 1], or NaN when absent
     * @param osmSpeedKmh - OSM max speed of the edge, or NaN when unknown
     * @return speed in km/h, or NaN when the feed has neither measurement nor coefficient
     */
    static double resolveEdgeSpeedKmh(double absoluteSpeedKmh, double relativeSpeed, double osmSpeedKmh) {
        boolean hasAbsolute = Double.isFinite(absoluteSpeedKmh) && absoluteSpeedKmh >= 0;
        boolean hasRelative = Double.isFinite(relativeSpeed) && relativeSpeed >= 0 && relativeSpeed <= 1;
        boolean hasOsm = Double.isFinite(osmSpeedKmh) && osmSpeedKmh > 0;
        double osmRef = freeflowReferenceKmh(osmSpeedKmh);

        double candidate;
        if (hasRelative) {
            candidate = relativeSpeed * osmRef;
        } else if (hasAbsolute) {
            candidate = hasOsm ? Math.min(absoluteSpeedKmh, osmRef) : absoluteSpeedKmh;
        } else {
            return Double.NaN;
        }
        return candidate * LIVE_TRAFFIC_SPEED_FACTOR;
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

    private static final double COORD_EPS = 1e-7;

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < COORD_EPS && Math.abs(a[1] - b[1]) < COORD_EPS;
    }

    /** Merge two coordinate lists if they share an endpoint; otherwise return null. */
    private static List<double[]> mergeCoords(List<double[]> seg1, List<double[]> seg2) {
        if (seg1 == null || seg1.isEmpty()) return seg2;
        if (seg2 == null || seg2.isEmpty()) return seg1;
        double[] first1 = seg1.get(0), last1 = seg1.get(seg1.size() - 1);
        double[] first2 = seg2.get(0), last2 = seg2.get(seg2.size() - 1);
        List<double[]> out;
        if (samePoint(last1, first2)) {
            out = new ArrayList<>(seg1);
            for (int i = 1; i < seg2.size(); i++) out.add(seg2.get(i));
            return out;
        }
        if (samePoint(last1, last2)) {
            out = new ArrayList<>(seg1);
            for (int i = seg2.size() - 2; i >= 0; i--) out.add(seg2.get(i));
            return out;
        }
        if (samePoint(first1, last2)) {
            out = new ArrayList<>(seg2);
            for (int i = 1; i < seg1.size(); i++) out.add(seg1.get(i));
            return out;
        }
        if (samePoint(first1, first2)) {
            out = new ArrayList<>(seg2.size() + seg1.size() - 1);
            for (int i = seg2.size() - 1; i >= 0; i--) out.add(seg2.get(i));
            for (int i = 1; i < seg1.size(); i++) out.add(seg1.get(i));
            return out;
        }
        return null;
    }

    private static Object buildGeoJSON(List<TrafficSegment> segments) {
        List<Object> features = new ArrayList<>(segments.size());
        for (TrafficSegment seg : segments) {
            List<Object> coords = new ArrayList<>(seg.coordinates.size());
            for (double[] c : seg.coordinates) {
                coords.add(List.of(c[0], c[1]));
            }
            double refSpeed = (seg.maxSpeed != null && seg.maxSpeed > 0) ? seg.maxSpeed : MAX_SPEED_KMH;
            double rel = Math.min(1.0, Math.max(0.0, seg.value / refSpeed));
            String color = speedToHexColor(rel);
            features.add(Map.of(
                    "type", "Feature",
                    "geometry", Map.of("type", "LineString", "coordinates", coords),
                    "properties", Map.of(
                            "value", seg.value,
                            "relative_speed", rel,
                            "max_speed", seg.maxSpeed,
                            "color", color
                    )
            ));
        }
        return Map.of("type", "FeatureCollection", "features", features);
    }

    /** Même dégradé que tomtom-tiles.html : rouge → orange → vert. */
    private static String speedToHexColor(double relativeSpeed) {
        float t = (float) Math.max(0, Math.min(1, relativeSpeed));
        int r, g, b;
        if (t <= 0.5f) {
            float u = t / 0.5f;
            r = Math.round(0xc0 + (0xe6 - 0xc0) * u);
            g = Math.round(0x39 + (0x7e - 0x39) * u);
            b = Math.round(0x2b + (0x22 - 0x2b) * u);
        } else {
            float u = (t - 0.5f) / 0.5f;
            r = Math.round(0xe6 + (0x27 - 0xe6) * u);
            g = Math.round(0x7e + (0xae - 0x7e) * u);
            b = Math.round(0x22 + (0x60 - 0x22) * u);
        }
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private static final class TrafficSegment {
        final List<double[]> coordinates;
        final double value;
        /** Vitesse max de la route (km/h), null si non disponible (max_speed non dans graph.encoded_values). */
        final Double maxSpeed;

        TrafficSegment(List<double[]> coordinates, double value, Double maxSpeed) {
            this.coordinates = coordinates;
            this.value = value;
            this.maxSpeed = maxSpeed;
        }
    }

    /**
     * DTO for one datafeed entry. JSON: id, points (array of [lon,lat]), value, value_type, mode,
     * and optional relative_speed when value_type is speed.
     * value_type "speed" = measured km/h. With relative_speed, speed = relative × (OSM max × 0.9)
     * × LIVE_TRAFFIC_SPEED_FACTOR; without relative, absolute is capped by that freeflow reference
     * then scaled by the same factor.
     * value_type "relative_speed" = coefficient in [0, 1], applied as value × freeflow × factor.
     */
    public static class DataFeedEntry {
        private String id;
        private List<List<Double>> points;
        private double value;
        private String value_type = "speed";
        /** TomTom coefficient in [0, 1], used together with a measured speed. Absent when not sent. */
        private Double relative_speed;
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

        public Double getRelative_speed() {
            return relative_speed;
        }

        public void setRelative_speed(Double relative_speed) {
            this.relative_speed = relative_speed;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }
}

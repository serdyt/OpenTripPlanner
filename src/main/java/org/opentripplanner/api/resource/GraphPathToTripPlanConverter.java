/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (props, at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.api.resource;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.api.model.*;
import org.opentripplanner.common.geometry.DirectionUtils;
import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.model.*;
import org.opentripplanner.routing.alertpatch.Alert;
import org.opentripplanner.routing.alertpatch.AlertPatch;
import org.opentripplanner.routing.alertpatch.StopCondition;
import org.opentripplanner.routing.core.*;
import org.opentripplanner.routing.edgetype.*;
import org.opentripplanner.routing.edgetype.flex.PartialPatternHop;
import org.opentripplanner.routing.edgetype.flex.TemporaryDirectPatternHop;
import org.opentripplanner.routing.error.TrivialPathException;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.location.TemporaryStreetLocation;
import org.opentripplanner.routing.services.FareService;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.trippattern.TripTimes;
import org.opentripplanner.routing.vertextype.*;
import org.opentripplanner.util.PolylineEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A library class with only static methods used in converting internal GraphPaths to TripPlans, which are
 * returned by the OTP "planner" web service. TripPlans are made up of Itineraries, so the functions to produce them
 * are also bundled together here.
 */
public abstract class GraphPathToTripPlanConverter {

    private static final Logger LOG = LoggerFactory.getLogger(GraphPathToTripPlanConverter.class);
    private static final double MAX_ZAG_DISTANCE = 30; // TODO add documentation, what is a "zag"?

    /**
     * Generates a TripPlan from a set of paths
     */
    public static TripPlan generatePlan(List<GraphPath> paths, RoutingRequest request) {

        Locale requestedLocale = request.locale;

        GraphPath exemplar = paths.get(0);
        Vertex tripStartVertex = exemplar.getStartVertex();
        Vertex tripEndVertex = exemplar.getEndVertex();
        String startName = tripStartVertex.getName(requestedLocale);
        String endName = tripEndVertex.getName(requestedLocale);

        // Use vertex labels if they don't have names
        if (startName == null) {
            startName = tripStartVertex.getLabel();
        }
        if (endName == null) {
            endName = tripEndVertex.getLabel();
        }
        Place from = new Place(tripStartVertex.getX(), tripStartVertex.getY(), startName);
        Place to = new Place(tripEndVertex.getX(), tripEndVertex.getY(), endName);

        from.orig = request.from.name;
        to.orig = request.to.name;

        TripPlan plan = new TripPlan(from, to, request.getDateTime());

        // Convert GraphPaths to Itineraries, keeping track of the best non-transit (e.g. walk/bike-only) option time
        long bestNonTransitTime = Long.MAX_VALUE;
        List<Itinerary> itineraries = new LinkedList<>();
        for (GraphPath path : paths) {
            Itinerary itinerary = generateItinerary(path, request.showIntermediateStops, request.disableAlertFiltering, requestedLocale);
            itinerary = adjustItinerary(request, itinerary);
            if (itinerary.transitTime == 0 && itinerary.walkTime < bestNonTransitTime) {
                bestNonTransitTime = itinerary.walkTime;
            }
            itineraries.add(itinerary);
        }

        // Filter and add itineraries to plan
        for (Itinerary itinerary : itineraries) {
            // If this is a transit option whose walk/bike time is greater than that of the walk/bike-only option,
            // do not include in plan
            if (itinerary.transitTime > 0 && itinerary.walkTime > bestNonTransitTime) continue;

            plan.addItinerary(itinerary);
        }

        if (plan != null) {
            for (Itinerary i : plan.itinerary) {
                /* Communicate the fact that the only way we were able to get a response was by removing a slope limit. */
                i.tooSloped = request.rctx.slopeRestrictionRemoved;
                /* fix up from/to on first/last legs */
                if (i.legs.size() == 0) {
                    LOG.warn("itinerary has no legs");
                    continue;
                }
                Leg firstLeg = i.legs.get(0);
                firstLeg.from.orig = plan.from.orig;
                Leg lastLeg = i.legs.get(i.legs.size() - 1);
                lastLeg.to.orig = plan.to.orig;
                if (!i.startTime.equals(firstLeg.startTime)) {
                    i.startTime = firstLeg.startTime;
                    LOG.info("Start time of itinerary did not correspond to first leg start time. This is likely because " +
                            "compactLegsByReversedSearch has failed.");
                }
                if (!i.endTime.equals(lastLeg.endTime)) {
                    i.endTime = lastLeg.endTime;
                    LOG.info("End time of itinerary did not correspond to last leg end time. This is likely because " +
                            "compactLegsByReversedSearch has failed.");
                }
            }
        }
        request.rctx.debugOutput.finishedRendering();
        return plan;
    }

    /**
     * Check whether itinerary needs adjustments based on the request.
     *
     * @param itinerary is the itinerary
     * @param request   is the request containing the original trip planning options
     * @return the (adjusted) itinerary
     */
    private static Itinerary adjustItinerary(RoutingRequest request, Itinerary itinerary) {
        // Check walk limit distance
        if (itinerary.walkDistance > request.maxWalkDistance) {
            itinerary.walkLimitExceeded = true;
        }
        // Return itinerary
        return itinerary;
    }

    /**
     * Generate an itinerary from a {@link GraphPath}. This method first slices the list of states
     * at the leg boundaries. These smaller state arrays are then used to generate legs. Finally the
     * rest of the itinerary is generated based on the complete state array.
     *
     * @param path The graph path to base the itinerary on
     * @param showIntermediateStops Whether to include intermediate stops in the itinerary or not
     * @return The generated itinerary
     */
    public static Itinerary generateItinerary(GraphPath path, boolean showIntermediateStops, boolean disableAlertFiltering, Locale requestedLocale) {
        Itinerary itinerary = new Itinerary();

        State[] states = new State[path.states.size()];
        State lastState = path.states.getLast();
        states = path.states.toArray(states);

        Edge[] edges = new Edge[path.edges.size()];
        edges = path.edges.toArray(edges);

        Graph graph = path.getRoutingContext().graph;

        FareService fareService = graph.getService(FareService.class);

        State[][] legsStates = sliceStates(states);

        if (fareService != null) {
            itinerary.fare = fareService.getCost(path);
        }

        for (State[] legStates : legsStates) {
            itinerary.addLeg(generateLeg(graph, legStates, showIntermediateStops, disableAlertFiltering, requestedLocale));
        }

        addWalkSteps(graph, itinerary.legs, legsStates, requestedLocale);


        for (int i = 0; i < itinerary.legs.size(); i++) {
            Leg leg = itinerary.legs.get(i);
            boolean isFirstLeg = i == 0;

            addAlertPatchesToLeg(graph, leg, isFirstLeg, requestedLocale);
        }

        fixupLegs(itinerary.legs, legsStates);

        moveTransferDetailsToTransitLegs(itinerary);

        removeStaySeatedTransferLegs(itinerary);

        itinerary.duration = lastState.getElapsedTimeSeconds();
        itinerary.distance = itinerary.legs.stream().mapToDouble(l -> l.distance).sum();
        itinerary.startTime = makeCalendar(states[0]);
        itinerary.endTime = makeCalendar(lastState);
        itinerary.weight = lastState.weight;

        calculateTimes(itinerary, states);

        calculateElevations(itinerary, edges);

        itinerary.walkDistance = lastState.getWalkDistance();

        itinerary.transfers = lastState.getNumBoardings();
        if (itinerary.transfers > 0 && !(states[0].getVertex() instanceof OnboardDepartVertex)) {
            itinerary.transfers--;
        }

        return itinerary;
    }

    private static void moveTransferDetailsToTransitLegs(Itinerary itinerary) {
        // Loop through all legs except first and last to check for transfer legs.
        for (int i = 1; i < itinerary.legs.size() - 1; i++) {
            // Move transfer details to the legs immediately before and after
            TimedTransferEdge transferEdge = itinerary.legs.get(i).timedTransferEdge;
            if (itinerary.legs.get(i).timedTransferEdge != null) {
                itinerary.legs.get(i - 1).transferTo = transferEdge;
                itinerary.legs.get(i + 1).transferFrom = transferEdge;
            }
        }
    }

    private static void removeStaySeatedTransferLegs(Itinerary itinerary) {
        itinerary.legs = itinerary.legs.stream().filter(l -> l.timedTransferEdge == null
                || l.timedTransferEdge.getTransferDetails() == null
                || !l.timedTransferEdge.getTransferDetails().isStaySeated()).collect(Collectors.toList());
    }


    private static Calendar makeCalendar(State state) {
        RoutingContext rctx = state.getContext();
        TimeZone timeZone = rctx.graph.getTimeZone();
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(state.getTimeInMillis());
        return calendar;
    }

    private static Calendar makeCalendar(TimeZone timeZone, long time) {
        Calendar calendar = Calendar.getInstance(timeZone);
        calendar.setTimeInMillis(time);
        return calendar;
    }

    /**
     * Generate a {@link CoordinateArrayListSequence} based on an {@link Edge} array.
     *
     * @param edges The array of input edges
     * @return The coordinates of the points on the edges
     */
    public static CoordinateArrayListSequence makeCoordinates(Edge[] edges) {
        CoordinateArrayListSequence coordinates = new CoordinateArrayListSequence();

        for (Edge edge : edges) {
            LineString geometry = edge.getDisplayGeometry();

            if (geometry != null) {
                if (coordinates.size() == 0) {
                    coordinates.extend(geometry.getCoordinates());
                } else {
                    coordinates.extend(geometry.getCoordinates(), 1); // Avoid duplications
                }
            }
        }

        return coordinates;
    }

    /**
     * Slice a {@link State} array at the leg boundaries. Leg switches occur when:
     * 1. A LEG_SWITCH mode (which itself isn't part of any leg) is seen
     * 2. The mode changes otherwise, for instance from BICYCLE to WALK
     * 3. A PatternInterlineDwell edge (i.e. interlining) is seen
     *
     * @param states The one-dimensional array of input states
     * @return An array of arrays of states belonging to a single leg (i.e. a two-dimensional array)
     */
    private static State[][] sliceStates(State[] states) {
        boolean trivial = true;

        for (State state : states) {
            TraverseMode traverseMode = state.getBackMode();

            if (traverseMode != null && traverseMode != TraverseMode.LEG_SWITCH) {
                trivial = false;
                break;
            }
        }

        if (trivial) {
            throw new TrivialPathException();
        }

        int[] legIndexPairs = {0, states.length - 1};
        List<int[]> legsIndexes = new ArrayList<int[]>();

        for (int i = 1; i < states.length - 1; i++) {
            TraverseMode backMode = states[i].getBackMode();
            TraverseMode forwardMode = states[i + 1].getBackMode();

            if (backMode == null || forwardMode == null) continue;

            Edge edge = states[i + 1].getBackEdge();

            if (backMode == TraverseMode.LEG_SWITCH || forwardMode == TraverseMode.LEG_SWITCH) {
                if (backMode != TraverseMode.LEG_SWITCH) {              // Start of leg switch
                    legIndexPairs[1] = i;
                } else if (forwardMode != TraverseMode.LEG_SWITCH) {    // End of leg switch
                    if (legIndexPairs[1] != states.length - 1) {
                        legsIndexes.add(legIndexPairs);
                    }
                    if (states[i - 1].getBackEdge() instanceof LegSwitchingEdge) {
                        // Include from from LegSwitchingEdge
                        legIndexPairs = new int[]{i - 1, states.length - 1};
                    } else {
                        legIndexPairs = new int[]{i, states.length - 1};
                    }
                }
            } else if (backMode != forwardMode) {                       // Mode change => leg switch
                legIndexPairs[1] = i;
                legsIndexes.add(legIndexPairs);
                legIndexPairs = new int[]{i, states.length - 1};
            } else if (edge instanceof PatternInterlineDwell) {         // Interlining => leg switch
                legIndexPairs[1] = i;
                legsIndexes.add(legIndexPairs);
                legIndexPairs = new int[]{i + 1, states.length - 1};
            }
        }

        // Final leg
        legsIndexes.add(legIndexPairs);

        State[][] legsStates = new State[legsIndexes.size()][];

        // Fill the two-dimensional array with states
        for (int i = 0; i < legsStates.length; i++) {
            legIndexPairs = legsIndexes.get(i);
            legsStates[i] = new State[legIndexPairs[1] - legIndexPairs[0] + 1];
            for (int j = 0; j <= legIndexPairs[1] - legIndexPairs[0]; j++) {
                legsStates[i][j] = states[legIndexPairs[0] + j];
            }
        }

        return legsStates;
    }

    /**
     * Generate one leg of an itinerary from a {@link State} array.
     *
     * @param states                The array of states to base the leg on
     * @param showIntermediateStops Whether to include intermediate stops in the leg or not
     * @return The generated leg
     */
    private static Leg generateLeg(Graph graph, State[] states, boolean showIntermediateStops, boolean disableAlertFiltering, Locale requestedLocale) {
        Leg leg = new Leg();

        Edge[] edges = new Edge[states.length - 1];

        leg.startTime = makeCalendar(states[0]);
        leg.endTime = makeCalendar(states[states.length - 1]);

        // Calculate leg distance and fill array of edges
        leg.distance = 0.0;
        for (int i = 0; i < edges.length; i++) {
            edges[i] = states[i + 1].getBackEdge();
            leg.distance += edges[i].getDistance();
        }

        TimeZone timeZone = leg.startTime.getTimeZone();
        leg.agencyTimeZoneOffset = timeZone.getOffset(leg.startTime.getTimeInMillis());

        addTripFields(leg, states, requestedLocale);

        addPlaces(leg, states, edges, showIntermediateStops, requestedLocale);

        CoordinateArrayListSequence coordinates = makeCoordinates(edges);
        Geometry geometry = GeometryUtils.getGeometryFactory().createLineString(coordinates);

        leg.legGeometry = PolylineEncoder.createEncodings(geometry);

        leg.interlineWithPreviousLeg = states[0].getBackEdge() instanceof PatternInterlineDwell;

        addFrequencyFields(states, leg);

        leg.rentedBike = states[0].isBikeRenting() && states[states.length - 1].isBikeRenting();

        addModeAndAlerts(graph, leg, states, disableAlertFiltering, requestedLocale);
        if (leg.isTransitLeg()) addRealTimeData(leg, states);

        addTransferDetails(states, leg);
        addEffectiveBookingArrangements(states, leg, graph);

        return leg;
    }

    private static void addTransferDetails (State[] states, Leg leg) {
        Edge backEdge = states[states.length - 1].backEdge;
        if (backEdge instanceof TimedTransferEdge) {
            leg.timedTransferEdge = (TimedTransferEdge) backEdge;
        }
    }

    /**
     * Generate a BookingArrantment object with the effective booking requirements for this leg.
     * <p>
     * BookingRequirements may be specified on three levels, Route (highest), Trip and StopTimes (lowest).
     * Values specified on a lower level will always override corresponding value on a higher level.
     */
    private static void addEffectiveBookingArrangements(State[] states, Leg leg, Graph graph) {
        BookingArrangement bookingArrangements = null;
        Trip trip = states[states.length - 1].getBackTrip();
        if (trip != null) {
            Route route = trip.getRoute();
            if (route != null && route.getBookingArrangements() != null) {
                bookingArrangements = route.getBookingArrangements().copy();
            }

            if (trip.getBookingArrangements() != null) {
                if (bookingArrangements == null) {
                    bookingArrangements = trip.getBookingArrangements().copy();
                } else {
                    bookingArrangements.addOverrides(trip.getBookingArrangements());
                }
            }

            TripPattern tripPattern = graph.index.patternForTrip.get(trip);
            if (tripPattern != null && tripPattern.stopPattern != null &&
            tripPattern.stopPattern.bookingArrangements != null && leg.from != null && leg.from.stopIndex != null){
                BookingArrangement stopPointBookingArrangements = tripPattern.stopPattern.bookingArrangements[leg.from.stopIndex];

                if (stopPointBookingArrangements != null) {
                    if (bookingArrangements == null) {
                        bookingArrangements = stopPointBookingArrangements.copy();
                    } else {
                        bookingArrangements.addOverrides(stopPointBookingArrangements);
                    }
                }
            }
        }
        leg.bookingArrangements = bookingArrangements;
    }

    private static void addFrequencyFields(State[] states, Leg leg) {
        /* TODO adapt to new frequency handling.
        if (states[0].getBackEdge() instanceof FrequencyBoard) {
            State preBoardState = states[0].getBackState();

            FrequencyBoard fb = (FrequencyBoard) states[0].getBackEdge();
            FrequencyBasedTripPattern pt = fb.getPattern();
            int boardTime;
            if (preBoardState.getServiceDay() == null) {
                boardTime = 0; //TODO why is this happening?
            } else {
                boardTime = preBoardState.getServiceDay().secondsSinceMidnight(
                        preBoardState.getTimeSeconds());
            }
            int period = pt.getPeriod(fb.getStopIndex(), boardTime); //TODO fix

            leg.isNonExactFrequency = !pt.isExact();
            leg.headway = period;
        }
        */
    }

    /**
     * Add a {@link WalkStep} {@link List} to a {@link Leg} {@link List}.
     * It's more convenient to process all legs in one go because the previous step should be kept.
     *
     * @param legs       The legs of the itinerary
     * @param legsStates The states that go with the legs
     */
    private static void addWalkSteps(Graph graph, List<Leg> legs, State[][] legsStates, Locale requestedLocale) {
        WalkStep previousStep = null;

        String lastMode = null;

        BikeRentalStationVertex onVertex = null, offVertex = null;

        for (int i = 0; i < legsStates.length; i++) {
            List<WalkStep> walkSteps = generateWalkSteps(graph, legsStates[i], previousStep, requestedLocale);
            String legMode = legs.get(i).mode;
            if (legMode != lastMode && !walkSteps.isEmpty()) {
                walkSteps.get(0).newMode = legMode;
                lastMode = legMode;
            }

            legs.get(i).walkSteps = walkSteps;

            if (walkSteps.size() > 0) {
                previousStep = walkSteps.get(walkSteps.size() - 1);
            } else {
                previousStep = null;
            }
        }
    }

    /**
     * This was originally in TransitUtils.handleBoardAlightType.
     * Edges that always block traversal (forbidden pickups/dropoffs) are simply not ever created.
     */
    public static String getBoardAlightMessage(int boardAlightType) {
        switch (boardAlightType) {
            case 1:
                return "impossible";
            case 2:
                return "mustPhone";
            case 3:
                return "coordinateWithDriver";
            default:
                return null;
        }
    }

    /**
     * Fix up a {@link Leg} {@link List} using the information available at the leg boundaries.
     * This method will fill holes in the arrival and departure times associated with a
     * {@link Place} within a leg and add board and alight rules. It will also ensure that stop
     * names propagate correctly to the non-transit legs that connect to them.
     *
     * @param legs       The legs of the itinerary
     * @param legsStates The states that go with the legs
     */
    private static void fixupLegs(List<Leg> legs, State[][] legsStates) {
        for (int i = 0; i < legsStates.length; i++) {
            boolean toOther = i + 1 < legsStates.length && legs.get(i + 1).interlineWithPreviousLeg;
            boolean fromOther = legs.get(i).interlineWithPreviousLeg;
            String boardRule = null;
            String alightRule = null;

            for (int j = 1; j < legsStates[i].length; j++) {
                if (legsStates[i][j].getBackEdge() instanceof PatternEdge) {
                    PatternEdge patternEdge = (PatternEdge) legsStates[i][j].getBackEdge();
                    TripPattern tripPattern = patternEdge.getPattern();

                    Integer fromIndex = legs.get(i).from.stopIndex;
                    Integer toIndex = legs.get(i).to.stopIndex;

                    int boardType = (fromIndex != null) ? (tripPattern.getBoardType(fromIndex)) : 0;
                    int alightType = (toIndex != null) ? (tripPattern.getAlightType(toIndex)) : 0;

                    boardRule = getBoardAlightMessage(boardType);
                    alightRule = getBoardAlightMessage(alightType);
                }
                if (legsStates[i][j].getBackEdge() instanceof PathwayEdge) {
                    legs.get(i).pathway = true;
                }
            }

            if (i + 1 < legsStates.length) {
                if (legs.get(i).intermediatePlace) {
                    Leg leg = legs.get(i);
                    Leg nextLeg = legs.get(i + 1);
                    if (nextLeg != null && !nextLeg.intermediatePlace && nextLeg.isTransitLeg()) {
                        long waitTime = nextLeg.startTime.getTimeInMillis() -
                                                leg.endTime.getTimeInMillis();
                        leg.startTime.setTimeInMillis(leg.startTime.getTimeInMillis() + waitTime);
                        leg.from.departure = leg.startTime;
                        leg.endTime.setTimeInMillis(leg.endTime.getTimeInMillis() + waitTime);
                        leg.to.arrival = leg.endTime;
                    }
                }

                legs.get(i + 1).from.arrival = legs.get(i).to.arrival;
                legs.get(i).to.departure = legs.get(i + 1).from.departure;

                if (legs.get(i).isTransitLeg() && !legs.get(i + 1).isTransitLeg()) {
                    legs.get(i + 1).from = legs.get(i).to;
                }
                if (!legs.get(i).isTransitLeg() && legs.get(i + 1).isTransitLeg()) {
                    legs.get(i).to = legs.get(i + 1).from;
                }
            }

            if (legs.get(i).isTransitLeg()) {
                if (boardRule != null && !fromOther) {      // If boarding in some other leg
                    legs.get(i).boardRule = boardRule;      // (interline), don't board now.
                }
                if (alightRule != null && !toOther) {       // If alighting in some other
                    legs.get(i).alightRule = alightRule;    // leg, don't alight now.
                }
            }

        }
    }

    /**
     * Calculate the walkTime, transitTime and waitingTime of an {@link Itinerary}.
     *
     * @param itinerary The itinerary to calculate the times for
     * @param states    The states that go with the itinerary
     */
    private static void calculateTimes(Itinerary itinerary, State[] states) {
        for (State state : states) {
            if (state.getBackMode() == null) continue;

            switch (state.getBackMode()) {
                default:
                    itinerary.transitTime += state.getTimeDeltaSeconds();
                    break;

                case LEG_SWITCH:
                    itinerary.waitingTime += state.getTimeDeltaSeconds();
                    break;

                case WALK:
                case BICYCLE:
                case CAR:
                    itinerary.walkTime += state.getTimeDeltaSeconds();
            }
        }
    }

    /**
     * Calculate the elevationGained and elevationLost fields of an {@link Itinerary}.
     *
     * @param itinerary The itinerary to calculate the elevation changes for
     * @param edges     The edges that go with the itinerary
     */
    private static void calculateElevations(Itinerary itinerary, Edge[] edges) {
        for (Edge edge : edges) {
            if (!(edge instanceof StreetEdge)) continue;

            StreetEdge edgeWithElevation = (StreetEdge) edge;
            PackedCoordinateSequence coordinates = edgeWithElevation.getElevationProfile();

            if (coordinates == null) continue;
            // TODO Check the test below, AFAIU current elevation profile has 3 dimensions.
            if (coordinates.getDimension() != 2) continue;

            for (int i = 0; i < coordinates.size() - 1; i++) {
                double change = coordinates.getOrdinate(i + 1, 1) - coordinates.getOrdinate(i, 1);

                if (change > 0) {
                    itinerary.elevationGained += change;
                } else if (change < 0) {
                    itinerary.elevationLost -= change;
                }
            }
        }
    }

    /**
     * Add mode and alerts fields to a {@link Leg}.
     *
     * @param leg    The leg to add the mode and alerts to
     * @param states The states that go with the leg
     */
    private static void addModeAndAlerts(Graph graph, Leg leg, State[] states, boolean disableAlertFiltering, Locale requestedLocale) {
        for (State state : states) {
            TraverseMode mode = state.getBackMode();
            Set<Alert> alerts = graph.streetNotesService.getNotes(state);
            Edge edge = state.getBackEdge();

            if (mode != null) {
                leg.mode = mode.toString();
            }

            if (alerts != null) {
                for (Alert alert : alerts) {
                    leg.addAlert(alert, requestedLocale);
                }
            }

            for (AlertPatch alertPatch : graph.getAlertPatches(edge)) {
                if (disableAlertFiltering || alertPatch.displayDuring(state)) {
                    if (alertPatch.hasTrip()) {
                        // If the alert patch contains a trip and that trip match this leg only add the alert for
                        // this leg.
                        if (alertPatch.getTrip().equals(leg.tripId)) {
                            leg.addAlert(alertPatch.getAlert(), requestedLocale);
                            leg.addAlertPatch(alertPatch);
                        }
                    } else {
                        // If we are not matching a particular trip add all known alerts for this trip pattern.
                        leg.addAlert(alertPatch.getAlert(), requestedLocale);
                        leg.addAlertPatch(alertPatch);
                    }
                }
            }
        }
    }

    private static void addAlertPatchesToLeg(Graph graph, Leg leg, boolean isFirstLeg, Locale requestedLocale) {

        if (graph.index != null) {
            Set<StopCondition> departingStopConditions = new HashSet<>();
            departingStopConditions.add(StopCondition.STOP);
            departingStopConditions.add(StopCondition.START_POINT);

            if(!isFirstLeg) {
                departingStopConditions.add(StopCondition.EXCEPTIONAL_STOP);
            }

            Set<StopCondition> passingStopConditions = new HashSet<>();
            passingStopConditions.add(StopCondition.STOP);
            passingStopConditions.add(StopCondition.NOT_STOPPING);

            Set<StopCondition> arrivingStopConditions = new HashSet<>();
            arrivingStopConditions.add(StopCondition.STOP);
            arrivingStopConditions.add(StopCondition.DESTINATION);

            if (leg.routeId != null) {

                if (leg.from != null && leg.from.stopId != null) {
                    addAlertPatchesToLeg(leg, departingStopConditions, getAlertsForStopAndRoute(graph, leg.from.stopId, leg.routeId), requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
                }

                if (leg.to != null && leg.to.stopId != null) {
                    addAlertPatchesToLeg(leg, arrivingStopConditions, getAlertsForStopAndRoute(graph, leg.to.stopId, leg.routeId), requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
                }
            }

            if (leg.tripId != null) {
                if (leg.from != null && leg.from.stopId != null) {
                    addAlertPatchesToLeg(leg, departingStopConditions, getAlertsForStopAndTrip(graph, leg.from.stopId, leg.tripId), requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
                }

                if (leg.to != null && leg.to.stopId != null) {
                    addAlertPatchesToLeg(leg, arrivingStopConditions, getAlertsForStopAndTrip(graph, leg.to.stopId, leg.tripId), requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
                }

                if (leg.stop != null) {
                    for (Place place : leg.stop) {
                        if (place.stopId != null) {
                            addAlertPatchesToLeg(leg, passingStopConditions, getAlertsForStopAndTrip(graph, place.stopId, leg.tripId),
                                    requestedLocale, place.arrival.getTime(), place.departure.getTime());
                        }
                    }
                }
            }

            if (leg.stop != null) {
                for (Place place : leg.stop) {
                    if (place.stopId != null) {
                        addAlertPatchesToLeg(leg, passingStopConditions, getAlertsForStop(graph, place.stopId),
                                requestedLocale, place.arrival.getTime(), place.departure.getTime());
                    }
                }
            }

            if (leg.from != null && leg.from.stopId != null) {
                addAlertPatchesToLeg(leg, departingStopConditions, getAlertsForStop(graph, leg.from.stopId),
                        requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
            }

            if (leg.to != null && leg.to.stopId != null) {
                addAlertPatchesToLeg(leg, arrivingStopConditions, getAlertsForStop(graph, leg.to.stopId),
                        requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
            }

            if (leg.tripId != null) {
                addAlertPatchesToLeg(leg, graph.index.getAlertsForTripId(leg.tripId),
                        requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
            }
            if (leg.routeId != null) {
                addAlertPatchesToLeg(leg, graph.index.getAlertsForRouteId(leg.routeId),
                        requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
            }

            if (leg.agencyId != null) {
                addAlertPatchesToLeg(leg, graph.index.getAlertsForAgency(graph.index.getAgencyWithoutFeedId(leg.agencyId)),
                        requestedLocale, leg.startTime.getTime(), leg.endTime.getTime());
            }

            // Filter alerts when there are multiple timePeriods for each alert
            leg.alertPatches.removeIf(alertPatch ->  !alertPatch.displayDuring(leg.startTime.getTimeInMillis()/1000, leg.endTime.getTimeInMillis()/1000));
        }
    }

    private static Collection<AlertPatch> getAlertsForStopAndRoute(Graph graph, AgencyAndId stopId, AgencyAndId routeId) {
        return getAlertsForStopAndRoute(graph, stopId, routeId, true);
    }

    private static Collection<AlertPatch> getAlertsForStopAndRoute(Graph graph, AgencyAndId stopId, AgencyAndId routeId, boolean checkParentStop) {

        Stop stop = graph.index.stopForId.get(stopId);
        if (stop == null) {
            return new ArrayList<>();
        }
        Collection<AlertPatch> alertsForStopAndRoute = graph.index.getAlertsForStopAndRoute(stopId,routeId);
        if (checkParentStop) {
            if (alertsForStopAndRoute == null) {
                alertsForStopAndRoute = new HashSet<>();
            }
            if (stop.getParentStation() != null) {
                //Also check parent
                Collection<AlertPatch> parentStopAlerts = graph.index.getAlertsForStopAndRoute(stop.getParentStationAgencyAndId(), routeId);
                if (parentStopAlerts != null) {
                    alertsForStopAndRoute.addAll(parentStopAlerts);
                }
            }
            if (stop.getMultiModalStation() != null) {
                //Also check multimodal parent

                AgencyAndId multimodalStopId = new AgencyAndId(stopId.getAgencyId(), stop.getMultiModalStation());
                Collection<AlertPatch> multimodalStopAlerts = graph.index.getAlertsForStopAndRoute(multimodalStopId, routeId);
                if (multimodalStopAlerts != null) {
                    alertsForStopAndRoute.addAll(multimodalStopAlerts);
                }
            }
        }
        return alertsForStopAndRoute;
    }

    private static Collection<AlertPatch> getAlertsForStopAndTrip(Graph graph, AgencyAndId stopId, AgencyAndId tripId) {
        return getAlertsForStopAndTrip(graph, stopId, tripId, true);
    }

    private static Collection<AlertPatch> getAlertsForStopAndTrip(Graph graph, AgencyAndId stopId, AgencyAndId tripId, boolean checkParentStop) {

        Stop stop = graph.index.stopForId.get(stopId);
        if (stop == null) {
            return new ArrayList<>();
        }

        Collection<AlertPatch> alertsForStopAndTrip = graph.index.getAlertsForStopAndTrip(stopId, tripId);
        if (checkParentStop) {
            if (alertsForStopAndTrip == null) {
                alertsForStopAndTrip = new HashSet<>();
            }
            if  (stop.getParentStation() != null) {
                // Also check parent
                Collection<AlertPatch> parentStopAlerts = graph.index.getAlertsForStopAndTrip(stop.getParentStationAgencyAndId(), tripId);
                if (parentStopAlerts != null) {
                    alertsForStopAndTrip.addAll(parentStopAlerts);
                }
            }
            if (stop.getMultiModalStation() != null) {
                //Also check multimodal parent
                AgencyAndId multimodalStopId = new AgencyAndId(stopId.getAgencyId(), stop.getMultiModalStation());
                Collection<AlertPatch> multimodalStopAlerts = graph.index.getAlertsForStopAndTrip(multimodalStopId, tripId);
                if (multimodalStopAlerts != null) {
                    alertsForStopAndTrip.addAll(multimodalStopAlerts);
                }
            }
        }
        return alertsForStopAndTrip;
    }

    private static Collection<AlertPatch> getAlertsForStop(Graph graph, AgencyAndId stopId) {
        return getAlertsForStop(graph, stopId, true);
    }

    private static Collection<AlertPatch> getAlertsForStop(Graph graph, AgencyAndId stopId, boolean checkParentStop) {
        Stop stop = graph.index.stopForId.get(stopId);
        if (stop == null) {
            return new ArrayList<>();
        }

        Collection<AlertPatch> alertsForStop  = graph.index.getAlertsForStopId(stopId);
        if (checkParentStop) {
            if (alertsForStop == null) {
                alertsForStop = new HashSet<>();
            }
            if  (stop.getParentStation() != null) {
                // Also check parent
                Collection<AlertPatch> parentStopAlerts = graph.index.getAlertsForStopId(stop.getParentStationAgencyAndId());
                if (parentStopAlerts != null) {
                    alertsForStop.addAll(parentStopAlerts);
                }
            }
            if (stop.getMultiModalStation() != null) {
                //Also check multimodal parent
                AgencyAndId multimodalStopId = new AgencyAndId(stopId.getAgencyId(), stop.getMultiModalStation());
                Collection<AlertPatch> multimodalStopAlerts = graph.index.getAlertsForStopId(multimodalStopId);
                if (multimodalStopAlerts != null) {
                    alertsForStop.addAll(multimodalStopAlerts);
                }
            }

        }
        return alertsForStop;
    }


    private static void addAlertPatchesToLeg(Leg leg, Collection<StopCondition> stopConditions, Collection<AlertPatch> alertPatches, Locale requestedLocale, Date fromTime, Date toTime) {
        if (alertPatches != null) {
            for (AlertPatch alert : alertPatches) {
                if (alert.getAlert().effectiveStartDate.before(toTime) &&
                        (alert.getAlert().effectiveEndDate == null || alert.getAlert().effectiveEndDate.after(fromTime))) {

                    if (!alert.getStopConditions().isEmpty() &&  // Skip if stopConditions are not set for alert
                            stopConditions != null && !stopConditions.isEmpty()) { // ...or specific stopConditions are not requested
                        for (StopCondition stopCondition : stopConditions) {
                            if (alert.getStopConditions().contains(stopCondition)) {
                                leg.addAlertPatch(alert);
                                break; //Only add alert once
                            }
                        }
                    } else {
                        leg.addAlertPatch(alert);
                    }
                }
            }
        }
    }

    private static void addAlertPatchesToLeg(Leg leg, Collection<AlertPatch> alertPatches, Locale requestedLocale, Date fromTime, Date toTime) {
        addAlertPatchesToLeg(leg, null, alertPatches, requestedLocale, fromTime, toTime);
    }

    /**
     * Add trip-related fields to a {@link Leg}.
     *
     * @param leg    The leg to add the trip-related fields to
     * @param states The states that go with the leg
     */
    private static void addTripFields(Leg leg, State[] states, Locale requestedLocale) {
        Trip trip = states[states.length - 1].getBackTrip();

        if (trip != null) {
            Route route = trip.getRoute();
            Agency organization = route.getAgency();
            ServiceDay serviceDay = states[states.length - 1].getServiceDay();

            leg.agencyId = organization.getId();
            leg.agencyName = organization.getName();
            leg.agencyUrl = organization.getUrl();
            leg.agencyBrandingUrl = organization.getBrandingUrl();
            leg.headsign = states[1].getBackDirection();
            leg.route = states[states.length - 1].getBackEdge().getName(requestedLocale);
            leg.routeColor = route.getColor();
            leg.routeId = route.getId();
            leg.routeLongName = route.getLongName();
            leg.routeShortName = route.getShortName();
            leg.routeTextColor = route.getTextColor();
            leg.routeType = route.getType();
            leg.routeBrandingUrl = route.getBrandingUrl();
            leg.tripId = trip.getId();
            leg.tripShortName = trip.getTripShortName();
            leg.tripBlockId = trip.getBlockId();
            leg.drtAdvanceBookMin = trip.getDrtAdvanceBookMin();
            leg.drtPickupMessage = trip.getDrtPickupMessage();
            leg.drtDropOffMessage = trip.getDrtDropOffMessage();
            leg.continuousPickupMessage = trip.getContinuousPickupMessage();
            leg.continuousDropOffMessage = trip.getContinuousDropOffMessage();

            if (serviceDay != null) {
                leg.serviceDate = serviceDay.getServiceDate().getAsString();
            }

            if (leg.headsign == null) {
                leg.headsign = trip.getTripHeadsign();
            }


            Edge edge = states[states.length - 1].backEdge;
            if (edge instanceof TemporaryDirectPatternHop) {
                leg.callAndRide = true;
            }
            if (edge instanceof PartialPatternHop) {
                PartialPatternHop hop = (PartialPatternHop) edge;
                int directTime = hop.getDirectVehicleTime();
                leg.directTime = directTime;
                TripTimes tt = states[states.length - 1].getTripTimes();
                int maxTime = tt.getDemandResponseMaxTime(directTime);
                int avgTime = tt.getDemandResponseAvgTime(directTime);
                int delta = maxTime - avgTime;
                if (directTime != 0 && delta > 0) {
                    if (hop.isDeviatedRouteBoard()) {
                        long maxStartTime = leg.startTime.getTimeInMillis() + (delta * 1000);
                        leg.maxStartTime = makeCalendar(leg.startTime.getTimeZone(), maxStartTime);
                    }
                    if (hop.isDeviatedRouteAlight()) {
                        long minEndTime = leg.endTime.getTimeInMillis() - (delta * 1000);
                        leg.minEndTime = makeCalendar(leg.endTime.getTimeZone(), minEndTime);
                    }
                }
            }

        }
    }

    /**
     * Add {@link Place} fields to a {@link Leg}.
     * There is some code duplication because of subtle differences between departure, arrival and
     * intermediate stops.
     *
     * @param leg                   The leg to add the places to
     * @param states                The states that go with the leg
     * @param edges                 The edges that go with the leg
     * @param showIntermediateStops Whether to include intermediate stops in the leg or not
     */
    private static void addPlaces(Leg leg, State[] states, Edge[] edges, boolean showIntermediateStops,
                                         Locale requestedLocale) {
        Vertex firstVertex = states[0].getVertex();
        Vertex lastVertex = states[states.length - 1].getVertex();

        Stop firstStop = firstVertex instanceof TransitVertex ?
                                 ((TransitVertex) firstVertex).getStop() : null;
        Stop lastStop = lastVertex instanceof TransitVertex ?
                                ((TransitVertex) lastVertex).getStop() : null;
        TripTimes tripTimes = states[states.length - 1].getTripTimes();

        leg.from = makePlace(states[0], firstVertex, edges[0], firstStop, tripTimes, requestedLocale);
        leg.from.arrival = null;
        leg.to = makePlace(states[states.length - 1], lastVertex, null, lastStop, tripTimes, requestedLocale);
        leg.to.departure = null;

        if (states[0].getBackEdge() instanceof LegSwitchingEdge) {
            leg.intermediatePlace = true;
        }

        if (showIntermediateStops) {
            leg.stop = new ArrayList<Place>();

            Stop previousStop = null;
            Stop currentStop;

            for (int i = 1; i < edges.length; i++) {
                Vertex vertex = states[i].getVertex();

                if (!(vertex instanceof TransitVertex)) continue;

                currentStop = ((TransitVertex) vertex).getStop();
                if (currentStop == firstStop) continue;

                if (currentStop == previousStop) {                  // Avoid duplication of stops
                    leg.stop.get(leg.stop.size() - 1).departure = makeCalendar(states[i]);
                    continue;
                }

                previousStop = currentStop;
                if (currentStop == lastStop) break;

                leg.stop.add(makePlace(states[i], vertex, edges[i], currentStop, tripTimes, requestedLocale));
            }
        }
    }

    /**
     * Make a {@link Place} to add to a {@link Leg}.
     *
     * @param state     The {@link State} that the {@link Place} pertains to.
     * @param vertex    The {@link Vertex} at the {@link State}.
     * @param edge      The {@link Edge} leading out of the {@link Vertex}.
     * @param stop      The {@link Stop} associated with the {@link Vertex}.
     * @param tripTimes The {@link TripTimes} associated with the {@link Leg}.
     * @return The resulting {@link Place} object.
     */
    private static Place makePlace(State state, Vertex vertex, Edge edge, Stop stop, TripTimes tripTimes, Locale requestedLocale) {
        // If no edge was given, it means we're at the end of this leg and need to work around that.
        boolean endOfLeg = (edge == null);
        String name = vertex.getName(requestedLocale);

        //This gets nicer names instead of osm:node:id when changing mode of transport
        //Names are generated from all the streets in a corner, same as names in origin and destination
        //We use name in TemporaryStreetLocation since this name generation already happened when temporary location was generated
        if (vertex instanceof StreetVertex && !(vertex instanceof TemporaryStreetLocation)) {
            name = ((StreetVertex) vertex).getIntersectionName(requestedLocale).toString(requestedLocale);
        }
        // Sets the name as the connecting stop in case the leg starts/ends at a splitter vertex
        if (vertex instanceof SplitterVertex || vertex instanceof OsmVertex) {
            for (Edge outgoing : vertex.getOutgoing()) {
                if (outgoing.getToVertex() instanceof TransitStop) {
                    name = outgoing.getToVertex().getName();
                    break;
                }
            }
        }

        Place place = new Place(vertex.getX(), vertex.getY(), name,
                                       makeCalendar(state), makeCalendar(state));

        if (endOfLeg) edge = state.getBackEdge();

        if (vertex instanceof TransitVertex && edge instanceof OnboardEdge) {
            place.stopId = stop.getId();
            place.stopCode = stop.getCode();
            place.platformCode = stop.getPlatformCode();
            place.zoneId = stop.getZoneId();
            place.stopIndex = ((OnboardEdge) edge).getStopIndex();
            if (endOfLeg) place.stopIndex++;
            if (tripTimes != null) {
                place.stopSequence = tripTimes.getStopSequence(place.stopIndex);
            }
            place.vertexType = VertexType.TRANSIT;
            place.boardAlightType = BoardAlightType.DEFAULT;
            if (edge instanceof PartialPatternHop) {
                PartialPatternHop hop = (PartialPatternHop) edge;
                if (hop.hasBoardArea() && !endOfLeg) {
                    place.flagStopArea = PolylineEncoder.createEncodings(hop.getBoardArea());
                }
                if (hop.hasAlightArea() && endOfLeg) {
                    place.flagStopArea = PolylineEncoder.createEncodings(hop.getAlightArea());
                }
                if ((endOfLeg && hop.isFlagStopAlight()) || (!endOfLeg && hop.isFlagStopBoard())) {
                    place.boardAlightType = BoardAlightType.FLAG_STOP;
                }
                if ((endOfLeg && hop.isDeviatedRouteAlight()) || (!endOfLeg && hop.isDeviatedRouteBoard())) {
                    place.boardAlightType = BoardAlightType.DEVIATED;
                }
            }
        }
        else if (vertex instanceof TransitVertex && edge instanceof SimpleTransfer) {
            place.stopId = stop.getId();
            place.stopCode = stop.getCode();
            place.platformCode = stop.getPlatformCode();
            place.zoneId = stop.getZoneId();
            place.vertexType = VertexType.TRANSIT;
        } else if (vertex instanceof BikeRentalStationVertex) {
            place.bikeShareId = ((BikeRentalStationVertex) vertex).getId();
            LOG.trace("Added bike share Id {} to place", place.bikeShareId);
            place.vertexType = VertexType.BIKESHARE;
        } else if (vertex instanceof BikeParkVertex) {
            place.bikeParkId = ((BikeParkVertex) vertex).getId();
            LOG.trace("Added bike parking Id {} to place", place.bikeParkId);
            place.vertexType = VertexType.BIKEPARK;
        } else if (vertex instanceof ParkAndRideVertex) {
            place.carParkId = ((ParkAndRideVertex) vertex).getId();
            LOG.trace("Added bike parking Id {} to place", place.bikeParkId);
            place.vertexType = VertexType.PARKANDRIDE;
        } else {
            place.vertexType = VertexType.NORMAL;
        }

        return place;
    }

    /**
     * Add information about real-time data to a {@link Leg}.
     *
     * @param leg    The leg to add the real-time information to
     * @param states The states that go with the leg
     */
    private static void addRealTimeData(Leg leg, State[] states) {
        TripTimes tripTimes = states[states.length - 1].getTripTimes();

        if (tripTimes != null && !tripTimes.isScheduled()) {
            leg.realTime = true;
            if (leg.from.stopIndex != null) {
                leg.departureDelay = tripTimes.getDepartureDelay(leg.from.stopIndex);
            }
            leg.arrivalDelay = tripTimes.getArrivalDelay(leg.to.stopIndex);
        }
    }

    /**
     * Converts a list of street edges to a list of turn-by-turn directions.
     *
     * @param previous a non-transit leg that immediately precedes this one (bike-walking, say), or null
     * @return
     */
    public static List<WalkStep> generateWalkSteps(Graph graph, State[] states, WalkStep previous, Locale requestedLocale) {
        List<WalkStep> steps = new ArrayList<WalkStep>();
        WalkStep step = null;
        double lastAngle = 0, distance = 0; // distance used for appending elevation profiles
        int roundaboutExit = 0; // track whether we are in a roundabout, and if so the exit number
        String roundaboutPreviousStreet = null;

        State onBikeRentalState = null, offBikeRentalState = null;

        // Check if this leg is a SimpleTransfer; if so, rebuild state array based on stored transfer edges
        if (states.length == 2 && states[1].getBackEdge() instanceof SimpleTransfer) {
            SimpleTransfer transferEdge = ((SimpleTransfer) states[1].getBackEdge());
            List<Edge> transferEdges = transferEdge.getEdges();
            if (transferEdges != null) {
                // Create a new initial state. Some parameters may have change along the way, copy them from the first state
                StateEditor se = new StateEditor(states[0].getOptions(), transferEdges.get(0).getFromVertex());
                se.setNonTransitOptionsFromState(states[0]);
                State s = se.makeState();
                ArrayList<State> transferStates = new ArrayList<>();
                transferStates.add(s);
                for (Edge e : transferEdges) {
                    s = e.traverse(s);
                    if (s == null) {
                        LOG.warn("Unable to traverse transfer edges while generating walk steps. Got null state from edge: " + e);
                        break;
                    }

                    transferStates.add(s);
                }
                states = transferStates.toArray(new State[transferStates.size()]);
            }
        }

        for (int i = 0; i < states.length - 1; i++) {
            State backState = states[i];
            State forwardState = states[i + 1];
            Edge edge = forwardState.getBackEdge();

            if (edge instanceof RentABikeOnEdge) onBikeRentalState = forwardState;
            if (edge instanceof RentABikeOffEdge) offBikeRentalState = forwardState;

            boolean createdNewStep = false, disableZagRemovalForThisStep = false;
            if (edge instanceof FreeEdge) {
                continue;
            }
            if (forwardState.getBackMode() == null || !forwardState.getBackMode().isOnStreetNonTransit()) {
                continue; // ignore STLs and the like
            }
            Geometry geom = edge.getGeometry();
            if (geom == null) {
                continue;
            }

            // generate a step for getting off an elevator (all
            // elevator narrative generation occurs when alighting). We don't need to know what came
            // before or will come after
            if (edge instanceof ElevatorAlightEdge) {
                // don't care what came before or comes after
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;
                disableZagRemovalForThisStep = true;

                // tell the user where to get off the elevator using the exit notation, so the
                // i18n interface will say 'Elevator to <exit>'
                // what happens is that the webapp sees name == null and ignores that, and it sees
                // exit != null and uses to <exit>
                // the floor name is the AlightEdge name
                // reset to avoid confusion with 'Elevator on floor 1 to floor 1'
                step.streetName = ((ElevatorAlightEdge) edge).getName(requestedLocale);

                step.relativeDirection = RelativeDirection.ELEVATOR;

                steps.add(step);
                continue;
            }

            String streetName = edge.getName(requestedLocale);
            int idx = streetName.indexOf('(');
            String streetNameNoParens;
            if (idx > 0)
                streetNameNoParens = streetName.substring(0, idx - 1);
            else
                streetNameNoParens = streetName;

            if (step == null) {
                // first step
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;

                steps.add(step);
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                if (previous == null) {
                    step.setAbsoluteDirection(thisAngle);
                    step.relativeDirection = RelativeDirection.DEPART;
                } else {
                    step.setDirections(previous.angle, thisAngle, false);
                }
                // new step, set distance to length of first edge
                distance = edge.getDistance();
            } else if (((step.streetName != null && !step.streetNameNoParens().equals(streetNameNoParens))
                                && (!step.bogusName || !edge.hasBogusName())) ||
                               edge.isRoundabout() != (roundaboutExit > 0) || // went on to or off of a roundabout
                               isLink(edge) && !isLink(backState.getBackEdge())) {
                // Street name has changed, or we've gone on to or off of a roundabout.
                if (roundaboutExit > 0) {
                    // if we were just on a roundabout,
                    // make note of which exit was taken in the existing step
                    step.exit = Integer.toString(roundaboutExit); // ordinal numbers from
                    if (streetNameNoParens.equals(roundaboutPreviousStreet)) {
                        step.stayOn = true;
                    }
                    roundaboutExit = 0;
                }
                /* start a new step */
                step = createWalkStep(graph, forwardState, requestedLocale);
                createdNewStep = true;

                steps.add(step);
                if (edge.isRoundabout()) {
                    // indicate that we are now on a roundabout
                    // and use one-based exit numbering
                    roundaboutExit = 1;
                    roundaboutPreviousStreet = backState.getBackEdge().getName(requestedLocale);
                    idx = roundaboutPreviousStreet.indexOf('(');
                    if (idx > 0)
                        roundaboutPreviousStreet = roundaboutPreviousStreet.substring(0, idx - 1);
                }
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                step.setDirections(lastAngle, thisAngle, edge.isRoundabout());
                // new step, set distance to length of first edge
                distance = edge.getDistance();
            } else {
                /* street name has not changed */
                double thisAngle = DirectionUtils.getFirstAngle(geom);
                RelativeDirection direction = WalkStep.getRelativeDirection(lastAngle, thisAngle,
                        edge.isRoundabout());
                boolean optionsBefore = backState.multipleOptionsBefore();
                if (edge.isRoundabout()) {
                    // we are on a roundabout, and have already traversed at least one edge of it.
                    if (optionsBefore) {
                        // increment exit count if we passed one.
                        roundaboutExit += 1;
                    }
                }
                if (edge.isRoundabout() || direction == RelativeDirection.CONTINUE) {
                    // we are continuing almost straight, or continuing along a roundabout.
                    // just append elevation info onto the existing step.

                } else {
                    // we are not on a roundabout, and not continuing straight through.

                    // figure out if there were other plausible turn options at the last
                    // intersection
                    // to see if we should generate a "left to continue" instruction.
                    boolean shouldGenerateContinue = false;
                    if (edge instanceof StreetEdge) {
                        // the next edges will be PlainStreetEdges, we hope
                        double angleDiff = getAbsoluteAngleDiff(thisAngle, lastAngle);
                        for (Edge alternative : backState.getVertex().getOutgoingStreetEdges()) {
                            if (alternative.getName(requestedLocale).equals(streetName)) {
                                // alternatives that have the same name
                                // are usually caused by street splits
                                continue;
                            }
                            double altAngle = DirectionUtils.getFirstAngle(alternative
                                                                                   .getGeometry());
                            double altAngleDiff = getAbsoluteAngleDiff(altAngle, lastAngle);
                            if (angleDiff > Math.PI / 4 || altAngleDiff - angleDiff < Math.PI / 16) {
                                shouldGenerateContinue = true;
                                break;
                            }
                        }
                    } else {
                        double angleDiff = getAbsoluteAngleDiff(lastAngle, thisAngle);
                        // FIXME: this code might be wrong with the removal of the edge-based graph
                        State twoStatesBack = backState.getBackState();
                        Vertex backVertex = twoStatesBack.getVertex();
                        for (Edge alternative : backVertex.getOutgoingStreetEdges()) {
                            List<Edge> alternatives = alternative.getToVertex()
                                                              .getOutgoingStreetEdges();
                            if (alternatives.size() == 0) {
                                continue; // this is not an alternative
                            }
                            alternative = alternatives.get(0);
                            if (alternative.getName(requestedLocale).equals(streetName)) {
                                // alternatives that have the same name
                                // are usually caused by street splits
                                continue;
                            }
                            double altAngle = DirectionUtils.getFirstAngle(alternative
                                                                                   .getGeometry());
                            double altAngleDiff = getAbsoluteAngleDiff(altAngle, lastAngle);
                            if (angleDiff > Math.PI / 4 || altAngleDiff - angleDiff < Math.PI / 16) {
                                shouldGenerateContinue = true;
                                break;
                            }
                        }
                    }

                    if (shouldGenerateContinue) {
                        // turn to stay on same-named street
                        step = createWalkStep(graph, forwardState, requestedLocale);
                        createdNewStep = true;
                        steps.add(step);
                        step.setDirections(lastAngle, thisAngle, false);
                        step.stayOn = true;
                        // new step, set distance to length of first edge
                        distance = edge.getDistance();
                    }
                }
            }

            State exitState = backState;
            Edge exitEdge = exitState.getBackEdge();
            while (exitEdge instanceof FreeEdge) {
                exitState = exitState.getBackState();
                exitEdge = exitState.getBackEdge();
            }
            if (exitState.getVertex() instanceof ExitVertex) {
                step.exit = ((ExitVertex) exitState.getVertex()).getExitName();
            }

            if (createdNewStep && !disableZagRemovalForThisStep && forwardState.getBackMode() == backState.getBackMode()) {
                //check last three steps for zag
                int last = steps.size() - 1;
                if (last >= 2) {
                    WalkStep threeBack = steps.get(last - 2);
                    WalkStep twoBack = steps.get(last - 1);
                    WalkStep lastStep = steps.get(last);

                    if (twoBack.distance < MAX_ZAG_DISTANCE
                                && lastStep.streetNameNoParens().equals(threeBack.streetNameNoParens())) {

                        if (((lastStep.relativeDirection == RelativeDirection.RIGHT ||
                                      lastStep.relativeDirection == RelativeDirection.HARD_RIGHT) &&
                                     (twoBack.relativeDirection == RelativeDirection.RIGHT ||
                                              twoBack.relativeDirection == RelativeDirection.HARD_RIGHT)) ||
                                    ((lastStep.relativeDirection == RelativeDirection.LEFT ||
                                              lastStep.relativeDirection == RelativeDirection.HARD_LEFT) &&
                                             (twoBack.relativeDirection == RelativeDirection.LEFT ||
                                                      twoBack.relativeDirection == RelativeDirection.HARD_LEFT))) {
                            // in this case, we have two left turns or two right turns in quick
                            // succession; this is probably a U-turn.

                            steps.remove(last - 1);

                            lastStep.distance += twoBack.distance;
                            lastStep.duration += twoBack.duration;

                            // A U-turn to the left, typical in the US. 
                            if (lastStep.relativeDirection == RelativeDirection.LEFT || 
                                    lastStep.relativeDirection == RelativeDirection.HARD_LEFT)

                            // A U-turn to the left, typical in the US.
                            if (lastStep.relativeDirection == RelativeDirection.LEFT ||
                                        lastStep.relativeDirection == RelativeDirection.HARD_LEFT)
                                lastStep.relativeDirection = RelativeDirection.UTURN_LEFT;
                            else
                                lastStep.relativeDirection = RelativeDirection.UTURN_RIGHT;

                            // in this case, we're definitely staying on the same street
                            // (since it's zag removal, the street names are the same)
                            lastStep.stayOn = true;
                        } else {
                            // What is a zag? TODO write meaningful documentation for this.
                            // It appears to mean simplifying out several rapid turns in succession
                            // from the description.
                            // total hack to remove zags.
                            steps.remove(last);
                            steps.remove(last - 1);
                            step = threeBack;
                            step.distance += twoBack.distance;
                            step.duration += twoBack.duration;
                            distance += step.distance;
                            if (twoBack.elevation != null) {
                                if (step.elevation == null) {
                                    step.elevation = twoBack.elevation;
                                } else {
                                    for (P2<Double> d : twoBack.elevation) {
                                        step.elevation.add(new P2<Double>(d.first + step.distance, d.second));
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (!createdNewStep && step.elevation != null) {
                    List<P2<Double>> s = encodeElevationProfile(edge, distance,
                            backState.getOptions().geoidElevation ? -graph.ellipsoidToGeoidDifference : 0);
                    if (step.elevation != null && step.elevation.size() > 0) {
                        step.elevation.addAll(s);
                    } else {
                        step.elevation = s;
                    }
                }
                distance += edge.getDistance();

            }

            // increment the total length for this step
            step.distance += edge.getDistance();
            step.duration += forwardState.getTimeDeltaSeconds();
            step.addAlerts(graph.streetNotesService.getNotes(forwardState), requestedLocale);
            lastAngle = DirectionUtils.getLastAngle(geom);

            step.edges.add(edge);
        }

        // add bike rental information if applicable
        if (onBikeRentalState != null && !steps.isEmpty()) {
            steps.get(steps.size() - 1).bikeRentalOnStation =
                    new BikeRentalStationInfo((BikeRentalStationVertex) onBikeRentalState.getBackEdge().getToVertex());
        }
        if (offBikeRentalState != null && !steps.isEmpty()) {
            steps.get(0).bikeRentalOffStation =
                    new BikeRentalStationInfo((BikeRentalStationVertex) offBikeRentalState.getBackEdge().getFromVertex());
        }

        return steps;
    }

    private static boolean isLink(Edge edge) {
        return edge instanceof StreetEdge && (((StreetEdge) edge).getStreetClass() & StreetEdge.CLASS_LINK) == StreetEdge.CLASS_LINK;
    }

    private static double getAbsoluteAngleDiff(double thisAngle, double lastAngle) {
        double angleDiff = thisAngle - lastAngle;
        if (angleDiff < 0) {
            angleDiff += Math.PI * 2;
        }
        double ccwAngleDiff = Math.PI * 2 - angleDiff;
        if (ccwAngleDiff < angleDiff) {
            angleDiff = ccwAngleDiff;
        }
        return angleDiff;
    }

    private static WalkStep createWalkStep(Graph graph, State s, Locale wantedLocale) {
        Edge en = s.getBackEdge();
        WalkStep step;
        step = new WalkStep();
        step.streetName = en.getName(wantedLocale);
        step.lon = en.getFromVertex().getX();
        step.lat = en.getFromVertex().getY();
        step.elevation = encodeElevationProfile(s.getBackEdge(), 0,
                s.getOptions().geoidElevation ? -graph.ellipsoidToGeoidDifference : 0);
        step.bogusName = en.hasBogusName();
        step.addAlerts(graph.streetNotesService.getNotes(s), wantedLocale);
        step.angle = DirectionUtils.getFirstAngle(s.getBackEdge().getGeometry());
        if (s.getBackEdge() instanceof AreaEdge) {
            step.area = true;
        }
        return step;
    }

    private static List<P2<Double>> encodeElevationProfile(Edge edge, double distanceOffset, double heightOffset) {
        if (!(edge instanceof StreetEdge)) {
            return new ArrayList<P2<Double>>();
        }
        StreetEdge elevEdge = (StreetEdge) edge;
        if (elevEdge.getElevationProfile() == null) {
            return new ArrayList<P2<Double>>();
        }
        ArrayList<P2<Double>> out = new ArrayList<P2<Double>>();
        Coordinate[] coordArr = elevEdge.getElevationProfile().toCoordinateArray();
        for (int i = 0; i < coordArr.length; i++) {
            out.add(new P2<Double>(coordArr[i].x + distanceOffset, coordArr[i].y + heightOffset));
        }
        return out;
    }

}

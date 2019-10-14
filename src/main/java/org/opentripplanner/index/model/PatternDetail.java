package org.opentripplanner.index.model;

import java.util.Collection;

import org.opentripplanner.model.FeedScopedId;
import org.opentripplanner.model.TripPattern;

import com.beust.jcommander.internal.Lists;

public class PatternDetail extends PatternShort {

    /* Maybe these should just be lists of IDs only, since there are stops and trips subendpoints. */
    public FeedScopedId routeId;
    public Collection<StopShort> stops = Lists.newArrayList();
    public Collection<TripShort> trips = Lists.newArrayList();
    
    // Include all known headsigns
    
    public PatternDetail(TripPattern pattern) {
        super (pattern);
        routeId = pattern.route.getId();
        stops = StopShort.list(pattern.getStops());
        trips = TripShort.list(pattern.getTrips());
    }
    
}

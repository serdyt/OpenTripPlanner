/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.updater.siri;

import com.fasterxml.jackson.databind.JsonNode;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.updater.*;
import org.opentripplanner.updater.stoptime.TimetableSnapshotSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.Siri;

import java.util.concurrent.ExecutionException;

/**
 * Update OTP stop time tables from some (realtime) source
 *
 * Usage example ('rt' name is an example) in file 'Graph.properties':
 *
 * <pre>
 * rt.type = stop-time-updater
 * rt.frequencySec = 60
 * rt.sourceType = gtfs-http
 * rt.url = http://host.tld/path
 * rt.feedId = TA
 * </pre>
 *
 */
public class SiriETUpdater extends PollingGraphUpdater {
    private static final Logger LOG = LoggerFactory.getLogger(SiriETUpdater.class);

    /**
     * Parent update manager. Is used to execute graph writer runnables.
     */
    protected GraphUpdaterManager updaterManager;

    /**
     * Update streamer
     */
    private EstimatedTimetableSource updateSource;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer logFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Integer maxSnapshotFrequency;

    /**
     * Property to set on the RealtimeDataSnapshotSource
     */
    private Boolean purgeExpiredData;

    /**
     * Feed id that is used for the trip ids in the TripUpdates
     */
    private String feedId;

    /**
     * Set only if we should attempt to match the trip_id from other data in TripDescriptor
     */
    private SiriFuzzyTripMatcher siriFuzzyTripMatcher;

    @Override
    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
        this.updaterManager = updaterManager;
    }

    @Override
    public void configurePolling(Graph graph, JsonNode config) throws Exception {
        // Create update streamer from preferences
        feedId = config.path("feedId").asText("");
        String sourceType = config.path("sourceType").asText();

        updateSource = new SiriETHttpTripUpdateSource();

        // Configure update source
        if (updateSource instanceof JsonConfigurable) {
            ((JsonConfigurable) updateSource).configure(graph, config);
        } else {
            throw new IllegalArgumentException(
                    "Unknown update streamer source type: " + sourceType);
        }

        int logFrequency = config.path("logFrequency").asInt(-1);
        if (logFrequency >= 0) {
            this.logFrequency = logFrequency;
        }
        int maxSnapshotFrequency = config.path("maxSnapshotFrequencyMs").asInt(-1);
        if (maxSnapshotFrequency >= 0) {
            this.maxSnapshotFrequency = maxSnapshotFrequency;
        }
        this.purgeExpiredData = config.path("purgeExpiredData").asBoolean(true);
        if (config.path("fuzzyTripMatching").asBoolean(true)) {
            this.siriFuzzyTripMatcher = new SiriFuzzyTripMatcher(graph.index);
        }
        LOG.info("Creating stop time updater (SIRI ET) running every {} seconds : {}", frequencySec, updateSource);
    }

    @Override
    public void setup() throws InterruptedException, ExecutionException {
        // Create a realtime data snapshot source and wait for runnable to be executed
        updaterManager.executeBlocking(new GraphWriterRunnable() {
            @Override
            public void run(Graph graph) {
                // Only create a realtime data snapshot source if none exists already
                TimetableSnapshotSource snapshotSource = graph.timetableSnapshotSource;
                if (snapshotSource == null) {
                    snapshotSource = new TimetableSnapshotSource(graph);
                    // Add snapshot source to graph
                    graph.timetableSnapshotSource = (snapshotSource);
                }

                // Set properties of realtime data snapshot source
                if (logFrequency != null) {
                    snapshotSource.logFrequency = (logFrequency);
                }
                if (maxSnapshotFrequency != null) {
                    snapshotSource.maxSnapshotFrequency = (maxSnapshotFrequency);
                }
                if (purgeExpiredData != null) {
                    snapshotSource.purgeExpiredData = (purgeExpiredData);
                }
                if (siriFuzzyTripMatcher != null) {
                    snapshotSource.siriFuzzyTripMatcher = siriFuzzyTripMatcher;
                }
            }
        });
    }

    /**
     * Repeatedly makes blocking calls to an UpdateStreamer to retrieve new stop time updates, and
     * applies those updates to the graph.
     */
    @Override
    public void runPolling() throws Exception {
        // Get update lists from update source
        Siri updates = updateSource.getUpdates();
        boolean fullDataset = updateSource.getFullDatasetValueOfLastUpdates();

        if (updates != null && updates.getServiceDelivery().getEstimatedTimetableDeliveries() != null) {
            // Handle trip updates via graph writer runnable
            EstimatedTimetableGraphWriterRunnable runnable =
                    new EstimatedTimetableGraphWriterRunnable(fullDataset, updates.getServiceDelivery().getEstimatedTimetableDeliveries());
            if (blockReadinessUntilInitialized && !isInitialized) {
                LOG.info("Execute blocking tripupdates");
                updaterManager.executeBlocking(runnable);
            } else {
                updaterManager.execute(runnable);
            }
        }
        if (updates != null &&
                updates.getServiceDelivery() != null &&
                updates.getServiceDelivery().isMoreData() != null &&
                updates.getServiceDelivery().isMoreData()) {
            LOG.info("More data is available - fetching immediately");
            runPolling();
        }
    }

    @Override
    public void teardown() {
    }

    public String toString() {
        String s = (updateSource == null) ? "NONE" : updateSource.toString();
        return "Polling SIRI ET updater with update source = " + s;
    }
}

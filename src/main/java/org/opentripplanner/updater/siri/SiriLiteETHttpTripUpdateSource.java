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
import org.opentripplanner.updater.JsonConfigurable;
import org.opentripplanner.util.HttpUtils;
import org.rutebanken.siri20.util.SiriXml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.Siri;

import java.io.InputStream;
import java.time.ZonedDateTime;

public class SiriLiteETHttpTripUpdateSource implements EstimatedTimetableSource, JsonConfigurable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SiriLiteETHttpTripUpdateSource.class);

    /**
     * True iff the last list with updates represent all updates that are active right now, i.e. all
     * previous updates should be disregarded
     */
    private boolean fullDataset = true;

    /**
     * Feed id that is used to match trip ids in the TripUpdates
     */
    private String feedId;

    private String url;

    private ZonedDateTime lastTimestamp = ZonedDateTime.now().minusMonths(1);

    @Override
    public void configure(Graph graph, JsonNode config) throws Exception {
        String url = config.path("url").asText();
        if (url == null) {
            throw new IllegalArgumentException("Missing mandatory 'url' parameter");
        }
        this.url = url;

        this.feedId = config.path("feedId").asText();

    }

    @Override
    public Siri getUpdates() {
        long t1 = System.currentTimeMillis();
        try {

            InputStream is = HttpUtils.getData(url);
            if (is != null) {
                // Decode message
                LOG.info("Fetching ET-data took {} ms", (System.currentTimeMillis()-t1));
                t1 = System.currentTimeMillis();

                Siri siri = SiriXml.parseXml(is);

                LOG.info("Unmarshalling ET-data took {} ms", (System.currentTimeMillis()-t1));

                if (siri.getServiceDelivery().getResponseTimestamp().isBefore(lastTimestamp)) {
                    LOG.info("Newer data has already been processed");
                    return null;
                }
                lastTimestamp = siri.getServiceDelivery().getResponseTimestamp();

                //All subsequent requests will return changes since last request
                fullDataset = false;
                return siri;

            }
        } catch (Exception e) {
            LOG.info("Failed after {} ms", (System.currentTimeMillis()-t1));
            LOG.warn("Failed to parse SIRI Lite ET feed from " + url + ":", e);
        }
        return null;
    }

    @Override
    public boolean getFullDatasetValueOfLastUpdates() {
        return fullDataset;
    }
    
    public String toString() {
        return "SiriLiteETHttpTripUpdateSource(" + url + ")";
    }

    @Override
    public String getFeedId() {
        return this.feedId;
    }
}

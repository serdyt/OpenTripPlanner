///* This program is free software: you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public License
// as published by the Free Software Foundation, either version 3 of
// the License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>. */
//
//package org.opentripplanner.updater.siri;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.google.cloud.pubsub.v1.AckReplyConsumer;
//import com.google.cloud.pubsub.v1.MessageReceiver;
//import com.google.cloud.pubsub.v1.Subscriber;
//import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
//import com.google.protobuf.ByteString;
////import com.google.protobuf.Duration;
//import com.google.protobuf.InvalidProtocolBufferException;
//import com.google.pubsub.v1.ExpirationPolicy;
//import com.google.pubsub.v1.ProjectSubscriptionName;
//import com.google.pubsub.v1.ProjectTopicName;
//import com.google.pubsub.v1.PubsubMessage;
//import com.google.pubsub.v1.PushConfig;
//import com.google.pubsub.v1.Subscription;
//import org.apache.commons.io.FileUtils;
//import org.apache.commons.lang3.time.DurationFormatUtils;
//import org.entur.protobuf.mapper.SiriMapper;
//import org.opentripplanner.routing.graph.Graph;
//import org.opentripplanner.updater.GraphUpdater;
//import org.opentripplanner.updater.GraphUpdaterManager;
//import org.opentripplanner.updater.GraphWriterRunnable;
//import org.opentripplanner.updater.ReadinessBlockingUpdater;
//import org.opentripplanner.updater.stoptime.TimetableSnapshotSource;
//import org.opentripplanner.util.HttpUtils;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import uk.org.siri.siri20.EstimatedTimetableDeliveryStructure;
//import uk.org.siri.siri20.Siri;
////import uk.org.siri.www.siri.SiriType;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.time.ZonedDateTime;
//import java.util.List;
//import java.util.UUID;
//import java.util.concurrent.ExecutionException;
//import java.util.concurrent.atomic.AtomicLong;
//
///**
// * This class starts a Google PubSub subscription
// *
// * NOTE:
// *   - Path to Google credentials (.json-file) MUST exist in environment-variable "GOOGLE_APPLICATION_CREDENTIALS"
// *     as described here: https://cloud.google.com/docs/authentication/getting-started
// *   - ServiceAccount need access to create subscription ("editor")
// *
// *
// *
// * Startup-flow:
// *   1. Create subscription to topic. Subscription will receive all updates after creation.
// *   2. Fetch current data to initialize state.
// *   3. Flag updater as initialized
// *   3. Start receiving updates from Pubsub-subscription
// *
// *
// * <pre>
// *   "type": "google-pubsub-siri-et-updater",
// *   "projectName":"project-1234",                                                      // Google Cloud project name
// *   "topicName": "protobuf.estimated_timetables",                                      // Google Cloud Pubsub topic
// *   "dataInitializationUrl": "http://server/realtime/protobuf/et"  // Optional URL used to initialize with all existing data
// * </pre>
// *
// */
//public class SiriEstimatedTimetableGooglePubsubUpdater extends ReadinessBlockingUpdater implements GraphUpdater {
//
//    private static final int DEFAULT_RECONNECT_PERIOD_SEC = 5; // Five seconds
//
//    private static Logger LOG = LoggerFactory.getLogger(SiriEstimatedTimetableGooglePubsubUpdater.class);
//
//    /**
//     * Parent update manager. Is used to execute graph writer runnables.
//     */
//    private GraphUpdaterManager updaterManager;
//
//    /**
//     * The URL used to fetch all initial updates
//     */
//    private String dataInitializationUrl;
//
//    /**
//     * The ID for the static feed to which these TripUpdates are applied
//     */
//    private String feedId;
//
//    /**
//     * The number of seconds to wait before reconnecting after a failed connection.
//     */
//    private int reconnectPeriodSec;
//
//    private SubscriptionAdminClient subscriptionAdminClient;
//    private ProjectSubscriptionName subscriptionName;
//    private ProjectTopicName topic;
//    private PushConfig pushConfig;
//
//    private static transient final AtomicLong messageCounter = new AtomicLong(0);
//    private static transient final AtomicLong updateCounter = new AtomicLong(0);
//    private static transient final AtomicLong sizeCounter = new AtomicLong(0);
//    private transient long startTime;
//
//    public SiriEstimatedTimetableGooglePubsubUpdater() {
//
//        try {
//            if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null &&
//                    !System.getenv("GOOGLE_APPLICATION_CREDENTIALS").isEmpty()) {
//
//                /*
//                  Google libraries expects path to credentials json-file is stored in environment variable "GOOGLE_APPLICATION_CREDENTIALS"
//                  Ref.: https://cloud.google.com/docs/authentication/getting-started
//                 */
//
//                subscriptionAdminClient = SubscriptionAdminClient.create();
//
//                addShutdownHook();
//
//            } else {
//                throw new RuntimeException("Google Pubsub updater is configured, but environment variable 'GOOGLE_APPLICATION_CREDENTIALS' is not defined. " +
//                        "See https://cloud.google.com/docs/authentication/getting-started");
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void addShutdownHook() {
//        // TODO: This should probably be on a higher level?
//        try {
//            Runtime.getRuntime().addShutdownHook(new Thread(this::teardown));
//            LOG.info("Shutdown-hook to clean up Google Pubsub subscription has been added.");
//        } catch (IllegalStateException e) {
//            // Handling cornercase when instance is being shut down before it has been initialized
//            LOG.info("Instance is already shutting down - cleaning up immediately.", e);
//            teardown();
//        }
//    }
//
//    @Override
//    public void setGraphUpdaterManager(GraphUpdaterManager updaterManager) {
//        this.updaterManager = updaterManager;
//    }
//
//    @Override
//    public void configure(Graph graph, JsonNode config) throws Exception {
//
//        /*
//           URL that responds to HTTP GET which returns all initial data in protobuf-format.
//           Will be called once to initialize realtime-data. All updates will be received from Google Cloud Pubsub
//          */
//        dataInitializationUrl = config.path("dataInitializationUrl").asText();
//
//        feedId = config.path("feedId").asText("");
//        reconnectPeriodSec = config.path("reconnectPeriodSec").asInt(DEFAULT_RECONNECT_PERIOD_SEC);
//
//        blockReadinessUntilInitialized = config.path("blockReadinessUntilInitialized").asBoolean(false);
//
//        // set subscriber
//        String subscriptionId = System.getenv("HOSTNAME");
//        if (subscriptionId == null || subscriptionId.isBlank()) {
//            subscriptionId = "otp-"+UUID.randomUUID().toString();
//        }
//
//        String projectName = config.path("projectName").asText();
//
//        String topicName = config.path("topicName").asText();
//
//        subscriptionName = ProjectSubscriptionName.of(
//                projectName, subscriptionId);
//        topic = ProjectTopicName.of(projectName, topicName);
//
//        pushConfig = PushConfig.getDefaultInstance();
//
//    }
//
//    @Override
//    public void setup() throws InterruptedException, ExecutionException {
//        // Create a realtime data snapshot source and wait for runnable to be executed
//        updaterManager.executeBlocking(new GraphWriterRunnable() {
//            @Override
//            public void run(Graph graph) {
//                // Only create a realtime data snapshot source if none exists already
//                if (graph.timetableSnapshotSource == null) {
//                    TimetableSnapshotSource snapshotSource = new TimetableSnapshotSource(graph);
//                    // Add snapshot source to graph
//                    graph.timetableSnapshotSource = (snapshotSource);
//                }
//            }
//        });
//    }
//
//    @Override
//    public void run() throws IOException {
//
//        if (subscriptionAdminClient == null) {
//            throw new RuntimeException("Unable to initialize Google Pubsub-updater: System.getenv('GOOGLE_APPLICATION_CREDENTIALS') = " + System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));
//        }
//
//        LOG.info("Creating subscription {}", subscriptionName);
//
//        Subscription subscription = subscriptionAdminClient.createSubscription(Subscription.newBuilder()
//                .setTopic(topic.toString())
//                .setName(subscriptionName.toString())
//                .setPushConfig(pushConfig)
////                .setMessageRetentionDuration(
////                        // How long will an unprocessed message be kept - minimum 10 minutes
////                        Duration.newBuilder().setSeconds(600).build()
////                )
////                .setExpirationPolicy(ExpirationPolicy.newBuilder()
////                        // How long will the subscription exist when no longer in use - minimum 1 day
////                        .setTtl(Duration.newBuilder().setSeconds(86400).build()).build()
////                )
//                .build());
//
//        LOG.info("Created subscription {}", subscriptionName);
//
//        startTime = now();
//
//        final EstimatedTimetableMessageReceiver receiver = new EstimatedTimetableMessageReceiver();
//
//        initializeData(dataInitializationUrl, receiver);
//
//        Subscriber subscriber = null;
//        while (true) {
//            try {
//                subscriber = Subscriber.newBuilder(subscription.getName(), receiver).build();
//                subscriber.startAsync().awaitRunning();
//
//                subscriber.awaitTerminated();
//            } catch (IllegalStateException e) {
//
//                if (subscriber != null) {
//                    subscriber.stopAsync();
//                }
//            }
//            try {
//                Thread.sleep(reconnectPeriodSec * 1000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//    private long now() {
//        return ZonedDateTime.now().toInstant().toEpochMilli();
//    }
//
//    @Override
//    public void teardown() {
//        if (subscriptionAdminClient != null) {
//            LOG.info("Deleting subscription {}", subscriptionName);
//            subscriptionAdminClient.deleteSubscription(subscriptionName);
//            LOG.info("Subscription deleted {} - time since startup: {} sec", subscriptionName, ((now() - startTime)/1000));
//        }
//    }
//
//    private String getTimeSinceStartupString() {
//        return DurationFormatUtils.formatDuration((now() - startTime), "HH:mm:ss");
//    }
//
//    private void initializeData(String dataInitializationUrl, EstimatedTimetableMessageReceiver receiver) throws IOException {
//        if (dataInitializationUrl != null) {
//
//            LOG.info("Fetching initial data from " + dataInitializationUrl);
//            final long t1 = System.currentTimeMillis();
//
//            final InputStream data = HttpUtils.getData(dataInitializationUrl, "Content-Type", "application/x-protobuf");
//            ByteString value = ByteString.readFrom(data);
//
//            final long t2 = System.currentTimeMillis();
//            LOG.info("Fetching initial data - finished after {} ms, got {} bytes", (t2 - t1), FileUtils.byteCountToDisplaySize(value.size()));
//
//
//            final PubsubMessage message = PubsubMessage.newBuilder().setData(value).build();
//            receiver.receiveMessage(message, new AckReplyConsumer() {
//                @Override
//                public void ack() {
//                    LOG.info("Pubsub updater initialized after {} ms: [messages: {},  updates: {}, total size: {}, time since startup: {}]",
//                            (System.currentTimeMillis() - t2),
//                            messageCounter.get(),
//                            updateCounter.get(),
//                            FileUtils.byteCountToDisplaySize(sizeCounter.get()),
//                            getTimeSinceStartupString()
//                    );
//
//                    isInitialized = true;
//                }
//
//                @Override
//                public void nack() {
//
//                }
//            });
//        }
//    }
//
//    class EstimatedTimetableMessageReceiver implements MessageReceiver {
//        @Override
//        public void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
//
////            Siri siri;
////            try {
////                sizeCounter.addAndGet(message.getData().size());
////
////                final ByteString data = message.getData();
////
//////                final SiriType siriType = SiriType.parseFrom(data);
//////                siri = SiriMapper.mapToJaxb(siriType);
////                siri = SiriMapper.mapToJaxb(data.toByteArray());
////
////            } catch (InvalidProtocolBufferException e) {
////                throw new RuntimeException(e);
////            }
////
////            if (siri.getServiceDelivery() != null) {
////                // Handle trip updates via graph writer runnable
////                List<EstimatedTimetableDeliveryStructure> estimatedTimetableDeliveries = siri.getServiceDelivery().getEstimatedTimetableDeliveries();
////
////                int numberOfUpdatedTrips = 0;
////                try {
////                    numberOfUpdatedTrips = estimatedTimetableDeliveries.get(0).getEstimatedJourneyVersionFrames().get(0).getEstimatedVehicleJourneies().size();
////                } catch (Throwable t) {
////                    //ignore
////                }
////                long numberOfUpdates = updateCounter.addAndGet(numberOfUpdatedTrips);
////                long numberOfMessages = messageCounter.incrementAndGet();
////
////                if (numberOfMessages % 1000 == 0) {
////                    LOG.info("Pubsub stats: [messages: {},  updates: {}, total size: {}, current delay {} ms, time since startup: {}]", numberOfMessages, numberOfUpdates, FileUtils.byteCountToDisplaySize(sizeCounter.get()),
////                            (now() - siri.getServiceDelivery().getResponseTimestamp().toInstant().toEpochMilli()),
////                            getTimeSinceStartupString());
////                }
////
////                EstimatedTimetableGraphWriterRunnable runnable =
////                        new EstimatedTimetableGraphWriterRunnable(false,
////                                estimatedTimetableDeliveries);
////
////                if (!isReady()) {
////                    try {
////                        updaterManager.executeBlocking(runnable);
////                    } catch (Throwable e) {
////                        throw new RuntimeException(e);
////                    }
////                } else {
////                    updaterManager.execute(runnable);
////                }
////            }
////
////
////            // Ack only after all work for the message is complete.
////            consumer.ack();
//        }
//    }
//}

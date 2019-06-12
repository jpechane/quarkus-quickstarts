package org.acme.quarkus.sample.kafkastreams;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.json.Json;
import javax.json.JsonObject;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.StreamsMetadata;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class KafkaStreamsPipeline {

    private static final String WEATHER_STATIONS_STORE = "weather-stations-store";

    private static final String WEATHER_STATIONS_TOPIC = "weather-stations";
    private static final String TEMPERATURE_VALUES_TOPIC = "temperature-values";
    private static final String TEMPERATURES_AGGREGATED_TOPIC = "temperatures-aggregated";

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStreamsPipeline.class);

    @ConfigProperty(name="org.acme.quarkus.sample.kafkastreams.bootstrap.servers", defaultValue="localhost:9092")
    private String bootstrapServers;

    @ConfigProperty(name="HOSTNAME")
    private String host;

    @ConfigProperty(name = "quarkus.http.port")
    private int port;

    private KafkaStreams streams;

    private ExecutorService executor;

    void onStart(@Observes StartupEvent ev) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "temperature-aggregator");
        props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, host + ":" + port);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.put(CommonClientConfigs.METADATA_MAX_AGE_CONFIG, 500);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        StreamsBuilder builder = new StreamsBuilder();

        JsonObjectSerde jsonNodeSerde = new JsonObjectSerde();
        KeyValueBytesStoreSupplier storeSupplier = Stores.inMemoryKeyValueStore(WEATHER_STATIONS_STORE);

        KTable<Integer, JsonObject> stations = builder.table(
                WEATHER_STATIONS_TOPIC,
                Consumed.with(Serdes.Integer(), jsonNodeSerde));

        KStream<Integer, JsonObject> temperatures = builder
                .stream(TEMPERATURE_VALUES_TOPIC, Consumed.with(Serdes.Integer(), Serdes.Double()))
                .join(
                        stations,
                        (temp, station) -> {
                            return Json.createObjectBuilder()
                                    .add("temperature", temp)
                                    .add("name", station.get("name"))
                                    .build();
                        },
                        Joined.with(Serdes.Integer(), Serdes.Double(), jsonNodeSerde));

        temperatures.groupByKey()
                .aggregate(
                        () -> Json.createObjectBuilder()
                            .add("min", Double.MAX_VALUE)
                            .add("max", Double.MIN_VALUE)
                            .build(),
                        (id, measurement, aggregated) -> {
                            double max = Math.max(
                                    measurement.getJsonNumber("temperature").doubleValue(),
                                    aggregated.getJsonNumber("max").doubleValue()
                            );
                            double min = Math.min(
                                    measurement.getJsonNumber("temperature").doubleValue(),
                                    aggregated.getJsonNumber("min").doubleValue()
                            );
                            return Json.createObjectBuilder(aggregated)
                                .add("id", id)
                                .add("name", measurement.get("name"))
                                .add("min", min)
                                .add("max", max)
                                .build();
                        },
                        Materialized.<Integer, JsonObject> as(storeSupplier)
                            .withKeySerde(Serdes.Integer())
                            .withValueSerde(jsonNodeSerde))
                .toStream()
                .to(TEMPERATURES_AGGREGATED_TOPIC, Produced.with(Serdes.Integer(), jsonNodeSerde));

        streams = new KafkaStreams(builder.build(), props);

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            waitForTopicsToBeCreated(bootstrapServers);
            streams.start();
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        streams.close();
        executor.shutdown();
    }

    public String getMetaData() {
        return streams.allMetadataForStore(WEATHER_STATIONS_STORE)
                .stream()
                .map(m -> "host: " + m.hostInfo().host() + ":" + m.hostInfo().port() + " - " + m.topicPartitions())
                .collect(Collectors.joining("\n"));
    }

    public GetWeatherStationDataResponse getWeatherStationData(int id) {
        StreamsMetadata metadata = streams.metadataForKey(
                WEATHER_STATIONS_STORE,
                id,
                Serdes.Integer().serializer()
        );

        if (metadata == null || metadata == StreamsMetadata.NOT_AVAILABLE) {
            LOG.warn("Found no metadata for key {}", id);
            return GetWeatherStationDataResponse.notFound();
        }
        else if (metadata.host().equals(host)) {
            LOG.info("Found data for key {} locally", id);
            return GetWeatherStationDataResponse.found(getWeatherStationStore().get(id));
        }
        else {
            LOG.info("Found data for key {} on remote host {}:{}", id, metadata.host(), metadata.port());
            return GetWeatherStationDataResponse.foundRemotely(metadata.host(), metadata.port());
        }
    }

    private ReadOnlyKeyValueStore<Integer, JsonObject> getWeatherStationStore() {
        while (true) {
            try {
                return streams.store(WEATHER_STATIONS_STORE, QueryableStoreTypes.keyValueStore());
            } catch (InvalidStateStoreException e) {
                // ignore, store not ready yet
            }
        }
    }

    private void waitForTopicsToBeCreated(String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (AdminClient adminClient = AdminClient.create(config)) {
            AtomicBoolean topicsCreated = new AtomicBoolean(false);

            while (topicsCreated.get() == false) {
                ListTopicsResult topics = adminClient.listTopics();
                topics.names().whenComplete((t, e) -> {
                    if (e != null) {
                        throw new RuntimeException(e);
                    } else if (t.contains(WEATHER_STATIONS_TOPIC) && t.contains(TEMPERATURE_VALUES_TOPIC)) {
                        topicsCreated.set(true);
                    }
                });

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
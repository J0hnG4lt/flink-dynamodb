package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.streaming.api.functions.sink.SinkFunction.Context;
import org.apache.flink.util.Collector;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KinesisToDynamoDBJob {

    public static void main(String[] args) throws Exception {
        // disable CBOR in the AWS SDK v1 (used by the Flink Kinesis connector)
        System.setProperty("com.amazonaws.sdk.disableCbor", "true");
        System.setProperty("aws.cborEnabled", "false");

        // set up Flink environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // configure Kinesis consumer
        Properties kinesisProps = new Properties();
        String region = System.getenv("AWS_REGION");
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        String endpoint = System.getenv("KINESIS_ENDPOINT");

        if (region != null) {
            kinesisProps.setProperty(AWSConfigConstants.AWS_REGION, region);
        }
        if (accessKey != null) {
            kinesisProps.setProperty(AWSConfigConstants.AWS_ACCESS_KEY_ID, accessKey);
        }
        if (secretKey != null) {
            kinesisProps.setProperty(AWSConfigConstants.AWS_SECRET_ACCESS_KEY, secretKey);
        }
        // use BASIC provider for dummy creds
        kinesisProps.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "BASIC");
        if (endpoint != null) {
            kinesisProps.setProperty(AWSConfigConstants.AWS_ENDPOINT, endpoint);
        }

        String nowIso = java.time.Instant.now().toString();
        kinesisProps.setProperty(
            ConsumerConfigConstants.STREAM_INITIAL_TIMESTAMP,
            nowIso
        );

        String streamName = "MyStream";
        DataStream<String> raw = env.addSource(
            new FlinkKinesisConsumer<>(
                streamName,
                new org.apache.flink.api.common.serialization.SimpleStringSchema(),
                kinesisProps
            )
        );

        // parse, dedupe, and sink
        DataStream<Event> events = raw.flatMap(new JsonParser());
        KeyedStream<Event, String> keyed = events.keyBy(ev -> ev.id);
        DataStream<Event> deduped = keyed.process(new DeduplicationFunction());
        deduped.addSink(new DynamoDBUpsertSink("DeduplicatedEvents"));

        env.execute("Kinesis→DynamoDB Upsert");
    }

    // simple event holder
    public static class Event {
        public String id;
        public long eventTs;
        public JsonNode payload;

        public Event() {}
        public Event(String id, long ts, JsonNode p) {
            this.id = id;
            this.eventTs = ts;
            this.payload = p;
        }
    }

    // JSON parser
    public static class JsonParser extends RichFlatMapFunction<String, Event> {
        private transient ObjectMapper mapper;
        @Override
        public void open(Configuration parameters) {
            mapper = new ObjectMapper();
        }
        @Override
        public void flatMap(String value, Collector<Event> out) throws Exception {
            JsonNode node = mapper.readTree(value);
            String id = node.get("id").asText();
            long ts = node.get("event_timestamp").asLong();
            out.collect(new Event(id, ts, node));
        }
    }

    // dedup by id & timestamp
    public static class DeduplicationFunction extends KeyedProcessFunction<String, Event, Event> {
        private transient ValueState<Long> lastSeen;
        @Override
        public void open(Configuration parameters) {
            lastSeen = getRuntimeContext().getState(
                new ValueStateDescriptor<>("lastSeen", Long.class)
            );
        }
        @Override
        public void processElement(Event value, Context ctx, Collector<Event> out) throws Exception {
            Long prev = lastSeen.value();
            if (prev == null || value.eventTs > prev) {
                lastSeen.update(value.eventTs);
                out.collect(value);
            }
        }
    }

    // writes/upserts to DynamoDB
    public static class DynamoDBUpsertSink extends RichSinkFunction<Event> {
        private final String table;
        private transient DynamoDbClient client;
        public DynamoDBUpsertSink(String tableName) {
            this.table = tableName;
        }
        @Override
        public void open(Configuration parameters) {
            String endpoint = System.getenv("DYNAMODB_ENDPOINT");
            String region = System.getenv("AWS_REGION");
            String key = System.getenv("AWS_ACCESS_KEY_ID");
            String secret = System.getenv("AWS_SECRET_ACCESS_KEY");
            client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key, secret)
                    )
                )
                .build();
        }
        @Override
        public void invoke(Event value, Context context) {
            Map<String,AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(value.id).build());
            item.put("event_timestamp", AttributeValue.builder().n(Long.toString(value.eventTs)).build());
            item.put("payload", AttributeValue.builder().s(value.payload.toString()).build());
            client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
        }
        @Override
        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }
}

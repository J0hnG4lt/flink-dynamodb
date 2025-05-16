package org.example;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction.Context;
import org.apache.flink.util.Collector;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.*;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class WordCountJob {

    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<String, Integer>> counts =
            env.fromElements("hello world", "hello flink", "flink is fast")
               .flatMap(new LineSplitter())
               .keyBy(t -> t.f0)
               .sum(1);

        counts.addSink(new DynamoDBSink("WordCount"));

        env.execute("Flink Streaming Word Count → DynamoDB");
    }

    // splits lines into (word,1)
    public static class LineSplitter implements FlatMapFunction<String, Tuple2<String,Integer>> {
        @Override
        public void flatMap(String line, Collector<Tuple2<String,Integer>> out) {
            for (String w : line.split("\\s+")) {
                out.collect(new Tuple2<>(w, 1));
            }
        }
    }

    // a RichSinkFunction that writes each Tuple2 to DynamoDB
    public static class DynamoDBSink extends RichSinkFunction<Tuple2<String,Integer>> {
        private final String tableName;
        private transient DynamoDbClient client;

        public DynamoDBSink(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            String endpoint = System.getenv("DYNAMODB_ENDPOINT");
            String region   = System.getenv("AWS_REGION");
            String keyId    = System.getenv("AWS_ACCESS_KEY_ID");
            String secret   = System.getenv("AWS_SECRET_ACCESS_KEY");

            this.client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(keyId, secret)
                ))
                .build();
        }

        @Override
        public void invoke(Tuple2<String,Integer> value, Context context) throws Exception {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("word",  AttributeValue.builder().s(value.f0).build());
            item.put("count", AttributeValue.builder().n(value.f1.toString()).build());

            PutItemRequest req = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

            client.putItem(req);
        }

        @Override
        public void close() throws Exception {
            super.close();
            if (client != null) {
                client.close();
            }
        }
    }
}

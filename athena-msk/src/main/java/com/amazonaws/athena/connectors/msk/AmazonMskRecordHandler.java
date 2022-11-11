/*-
 * #%L
 * athena-msk
 * %%
 * Copyright (C) 2019 - 2022 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.msk;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.handlers.RecordHandler;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import com.amazonaws.athena.connectors.msk.dto.Field;
import com.amazonaws.athena.connectors.msk.dto.SplitParam;
import com.amazonaws.athena.connectors.msk.dto.TopicResultSet;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.athena.AmazonAthenaClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.AWSSecretsManagerClientBuilder;
import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class AmazonMskRecordHandler
        extends RecordHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AmazonMskRecordHandler.class);
    private static final int MAX_EMPTY_RESULT_FOUND_COUNT = 3;

    AmazonMskRecordHandler()
    {
        this(AmazonS3ClientBuilder.defaultClient(),
                AWSSecretsManagerClientBuilder.defaultClient(),
                AmazonAthenaClientBuilder.defaultClient()
        );
        LOGGER.debug("  AmazonMskRecordHandler constructor() ");
    }

    @VisibleForTesting
    public AmazonMskRecordHandler(AmazonS3 amazonS3, AWSSecretsManager secretsManager, AmazonAthena athena)
    {
        super(amazonS3, secretsManager, athena, AmazonMskConstants.KAFKA_SOURCE);
        LOGGER.debug(" STEP 2.0  AmazonMskRecordHandler constructor() ");
    }

    /**
     * generates the sql to executes on basis of where condition and executes it.
     *
     * @param spiller - instance of {@link BlockSpiller}
     * @param recordsRequest - instance of {@link ReadRecordsRequest}
     * @param queryStatusChecker - instance of {@link QueryStatusChecker}
     */
    @Override
    public void readWithConstraint(BlockSpiller spiller, ReadRecordsRequest recordsRequest, QueryStatusChecker queryStatusChecker) throws Exception
    {
        // Taking the Split parameters in a readable pojo format.
        SplitParam splitParam = AmazonMskUtils.createSplitParam(recordsRequest.getSplit().getProperties());
        System.out.printf("[kafka]%s RecordHandler running %n", splitParam);

        // Initiate new KafkaConsumer that MUST not belong to any consumer group.
        try (Consumer<String, TopicResultSet> kafkaConsumer = AmazonMskUtils.getKafkaConsumer(recordsRequest.getSchema())) {
            // Set which topic and partition we are going to read.
            TopicPartition partition = new TopicPartition(splitParam.topic, splitParam.partition);
            Collection<TopicPartition> partitions = List.of(partition);

            // Assign the topic and partition into this consumer.
            kafkaConsumer.assign(partitions);

            // Setting the start offset from where we are interested to read data from topic partition.
            // We have configured this start offset when we had created the split on MetadataHandler.
            kafkaConsumer.seek(partition, splitParam.startOffset);

            // If endOffsets is 0 that means there is no data close consumer and exit
            Map<TopicPartition, Long> endOffsets = kafkaConsumer.endOffsets(partitions);
            if (endOffsets.get(partition) == 0) {
                System.out.printf("[kafka]%s topic does not have data, closing consumer %n", splitParam);
                kafkaConsumer.close();
                return;
            }
            // Consume topic data
            consume(spiller, recordsRequest, queryStatusChecker, splitParam, kafkaConsumer);
        }
    }

    /**
     * Consume topic data as batch.
     *
     * @param spiller - instance of {@link BlockSpiller}
     * @param recordsRequest - instance of {@link ReadRecordsRequest}
     * @param queryStatusChecker - instance of {@link QueryStatusChecker}
     * @param splitParam - instance of {@link SplitParam}
     * @param kafkaConsumer - instance of {@link KafkaConsumer}
     * @throws Exception - {@link Exception}
     */
    private void consume(
            BlockSpiller spiller,
            ReadRecordsRequest recordsRequest,
            QueryStatusChecker queryStatusChecker,
            SplitParam splitParam,
            Consumer<String, TopicResultSet> kafkaConsumer) throws Exception
    {
        int emptyResultFoundCount = 0;

        whileLoop:
        while (true) {
            System.out.printf("[kafka]%s Polling for data %n", splitParam);

            if (!queryStatusChecker.isQueryRunning()) {
                System.out.printf("[kafka]%s Stopping and closing consumer due to query execution terminated by athena %n", splitParam);
                kafkaConsumer.close();
                break;
            }

            // Call the poll on consumer to fetch data from kafka server
            // poll returns data as batch which can be configured.
            ConsumerRecords<String, TopicResultSet> records = kafkaConsumer.poll(Duration.ofSeconds(1L));
            System.out.printf("[kafka]%s polled records size %s %n", splitParam, records.count());

            // Keep track for how many times we are getting empty result for the polling call.
            if (records.count() == 0) {
                emptyResultFoundCount++;
            }

            // We will close KafkaConsumer if we are getting empty result again and again.
            // Here we are comparing with a max threshold (MAX_EMPTY_RESULT_FOUNT_COUNT) to
            // stop the polling.
            if (emptyResultFoundCount >= MAX_EMPTY_RESULT_FOUND_COUNT) {
                System.out.printf("[kafka]%s Closing consumer due to getting empty result from broker %n", splitParam);
                kafkaConsumer.close();
                break;
            }

            for (ConsumerRecord<String, TopicResultSet> record : records) {
                // Pass batch data one by one to be processed to execute. execute method is
                // a kind of abstraction to keep data filtering and writing on spiller separate.
                execute(spiller, recordsRequest, queryStatusChecker, splitParam, record);

                // If we have reached at the end offset of the partition. we will not continue
                // to call the polling.
                if (record.offset() >= splitParam.endOffset - 1) {
                    System.out.printf("[kafka]%s Closing consumer due to reach at end offset (current record offset is %s) %n", splitParam, record.offset());
                    kafkaConsumer.close();
                    break whileLoop;
                }
            }
        }
    }

    /**
     * Abstraction to keep the data filtering and writing on spiller separate.
     *
     * @param spiller - instance of {@link BlockSpiller}
     * @param recordsRequest - instance of {@link ReadRecordsRequest}
     * @param queryStatusChecker - instance of {@link QueryStatusChecker}
     * @param splitParam - instance of {@link SplitParam}
     * @param record - instance of {@link ConsumerRecord}
     * @throws Exception - {@link Exception}
     */
    private void execute(
            BlockSpiller spiller,
            ReadRecordsRequest recordsRequest,
            QueryStatusChecker queryStatusChecker,
            SplitParam splitParam,
            ConsumerRecord<String, TopicResultSet> record) throws Exception
    {
        spiller.writeRows((Block block, int rowNum) -> {
            boolean isMatched;
            for (Field field : record.value().getFields()) {
                isMatched = block.offerValue(field.getName(), rowNum, field.getValue());
                if (!isMatched) {
                    return 0;
                }
            }
            return 1;
        });
    }
}
/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.status.KafkaTopicStatus;
import io.strimzi.operator.common.MaxAttemptsExceededException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fabric8.kubernetes.client.Watcher.Action.ADDED;
import static io.fabric8.kubernetes.client.Watcher.Action.DELETED;
import static io.fabric8.kubernetes.client.Watcher.Action.MODIFIED;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

@RunWith(VertxUnitRunner.class)
@SuppressWarnings("checkstyle:ClassDataAbstractionCoupling")
public class TopicOperatorTest {

    private final Labels labels = Labels.fromString("app=strimzi");

    private final TopicName topicName = new TopicName("my-topic");
    private final ResourceName resourceName = topicName.asKubeName();
    private Vertx vertx = Vertx.vertx();
    private MockKafka mockKafka;
    private MockTopicStore mockTopicStore = new MockTopicStore();
    private MockK8s mockK8s = new MockK8s();
    private TopicOperator topicOperator;
    private Config config;
    private ObjectMeta metadata = new ObjectMeta();

    private static final Map<String, String> MANDATORY_CONFIG = new HashMap<>();

    static {
        MANDATORY_CONFIG.put(Config.ZOOKEEPER_CONNECT.key, "localhost:2181");
        MANDATORY_CONFIG.put(Config.KAFKA_BOOTSTRAP_SERVERS.key, "localhost:9092");
        MANDATORY_CONFIG.put(Config.NAMESPACE.key, "default");
        // Not mandatory, but makes the time test quicker
        MANDATORY_CONFIG.put(Config.TOPIC_METADATA_MAX_ATTEMPTS.key, "3");
    }

    @Before
    public void setup() {
        mockKafka = new MockKafka();
        mockTopicStore = new MockTopicStore();
        mockK8s = new MockK8s();
        config = new Config(new HashMap<>(MANDATORY_CONFIG));
        topicOperator = new TopicOperator(vertx, mockKafka, mockK8s, mockTopicStore, labels, "default-namespace", config);
        metadata.setName(topicName.toString());
        Map<String, String> lbls = new HashMap<>();
        lbls.put("app", "strimzi");
        metadata.setLabels(lbls);
    }

    @After
    public void teardown() {
        vertx.close();
        mockKafka = null;
        mockTopicStore = null;
        mockK8s = null;
        topicOperator = null;
    }

    private Map<String, String> map(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        Map<String, String> result = new HashMap<>(pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /** Test what happens when a non-topic KafkaTopic gets created in kubernetes */
    @Test
    public void testOnKafkaTopicAdded_ignorable(TestContext context) {
        KafkaTopic kafkaTopic = new KafkaTopicBuilder().withMetadata(new ObjectMetaBuilder().withName("non-topic").build()).build();

        Async async = context.async();
        K8sTopicWatcher w = new K8sTopicWatcher(topicOperator, Future.succeededFuture());
        w.eventReceived(ADDED, kafkaTopic);
        mockKafka.assertEmpty(context);
        mockTopicStore.assertEmpty(context);
        async.complete();
    }

    /** Test what happens when a non-topic KafkaTopic gets created in kubernetes */
    @Test
    public void testOnKafkaTopicAdded_invalidResource(TestContext context) {
        KafkaTopic kafkaTopic = new KafkaTopicBuilder()
                .withMetadata(new ObjectMetaBuilder().withName("invalid").withLabels(labels.labels()).build())
                .withNewSpec()
                    .withReplicas(1)
                    .withPartitions(1)
                    .withConfig(singletonMap(null, null))
                .endSpec()
            .build();
        mockK8s.setGetFromNameResponse(new ResourceName(kafkaTopic), Future.succeededFuture(kafkaTopic));
        LogContext logContext = LogContext.kubeWatch(Watcher.Action.ADDED, kafkaTopic);
        Async async = context.async();
        topicOperator.onResourceEvent(logContext, kafkaTopic, ADDED).setHandler(ar -> {
            assertFailed(context, ar);
            context.assertTrue(ar.cause() instanceof InvalidTopicException);
            context.assertEquals("KafkaTopic's spec.config has invalid entry: The key 'null' of the topic config is invalid: The value corresponding to the key must have a string, number or boolean value but the value was null", ar.cause().getMessage());
            mockKafka.assertEmpty(context);
            mockTopicStore.assertEmpty(context);
            assertNotReadyStatus(context, new InvalidTopicException(null, ar.cause().getMessage()));
            async.complete();

        });
    }

    /**
     * Trigger {@link TopicOperator#onResourceEvent(LogContext, KafkaTopic, io.fabric8.kubernetes.client.Watcher.Action)}
     * and have the Kafka and TopicStore respond with the given exceptions.
     */
    private TopicOperator resourceAdded(TestContext context, Exception createException, Exception storeException) {
        mockKafka.setCreateTopicResponse(topicName.toString(), createException);
        mockTopicStore.setCreateTopicResponse(topicName, storeException);

        KafkaTopic kafkaTopic = new KafkaTopicBuilder()
                .withMetadata(metadata)
                .withNewSpec()
                    .withReplicas(2)
                    .withPartitions(10)
                .endSpec()
            .build();
        mockKafka.setTopicMetadataResponses(
            topicName -> Future.succeededFuture(),
            topicName -> Future.succeededFuture(Utils.getTopicMetadata(TopicSerialization.fromTopicResource(kafkaTopic))));
        LogContext logContext = LogContext.kubeWatch(Watcher.Action.ADDED, kafkaTopic);
        Async async = context.async();
        mockK8s.setGetFromNameResponse(new ResourceName(kafkaTopic), Future.succeededFuture(kafkaTopic));

        topicOperator.onResourceEvent(logContext, kafkaTopic, ADDED).setHandler(ar -> {
            if (createException != null
                    || storeException != null) {
                assertFailed(context, ar);
                Class<? extends Exception> expectedExceptionType;
                if (createException != null) {
                    expectedExceptionType = createException.getClass();
                } else {
                    expectedExceptionType = storeException.getClass();
                }
                if (!expectedExceptionType.equals(ar.cause().getClass())) {
                    ar.cause().printStackTrace();
                }
                context.assertEquals(expectedExceptionType, ar.cause().getClass(), ar.cause().getMessage());
                TopicName topicName = TopicSerialization.fromTopicResource(kafkaTopic).getTopicName();
                if (createException != null) {
                    mockKafka.assertNotExists(context, topicName);
                } else {
                    mockKafka.assertExists(context, topicName);
                }
                mockTopicStore.assertNotExists(context, topicName);
                //TODO mockK8s.assertContainsEvent(context, e -> "Error".equals(e.getKind()));
            } else {
                assertSucceeded(context, ar);
                Topic expectedTopic = TopicSerialization.fromTopicResource(kafkaTopic);
                mockKafka.assertContains(context, expectedTopic);
                mockTopicStore.assertContains(context, expectedTopic);
                mockK8s.assertNoEvents(context);
            }
            async.complete();
        });
        async.await();

        return topicOperator;
    }

    /**
     * 1. operator is notified that a KafkaTopic is created
     * 2. operator successfully creates topic in kafka
     * 3. operator successfully creates in topic store
     */
    @Test
    public void testOnKafkaTopicAdded(TestContext context) {
        resourceAdded(context, null, null);
    }

    /**
     * 1. operator is notified that a KafkaTopic is created
     * 2. error when creating topic in kafka
     */
    @Test
    public void testOnKafkaTopicAdded_TopicExistsException(TestContext context) {
        Exception createException = new TopicExistsException("");
        resourceAdded(context, createException, null);
        // TODO check a k8s event got created
        // TODO what happens when we subsequently reconcile?
        assertNotReadyStatus(context, createException);
    }

    void assertNotReadyStatus(TestContext context, Exception createException) {
        List<KafkaTopicStatus> statuses = mockK8s.getStatuses();
        context.assertEquals(1, statuses.size());
        context.assertEquals(0L, statuses.get(0).getObservedGeneration());
        context.assertTrue(statuses.get(0).getConditions().stream().anyMatch(
            condition -> "NotReady".equals(condition.getType())
                    && "True".equals(condition.getStatus())
                    && createException.getClass().getSimpleName().equals(condition.getReason())
                    && Objects.equals(createException.getMessage(), condition.getMessage())),
                statuses.get(0).getConditions().toString());
    }

    /**
     * 1. operator is notified that a KafkaTopic is created
     * 2. error when creating topic in kafka
     */
    @Test
    public void testOnKafkaTopicAdded_ClusterAuthorizationException(TestContext context) {
        Exception createException = new ClusterAuthorizationException("Test exception");
        TopicOperator op = resourceAdded(context, createException, null);
        assertNotReadyStatus(context, createException);
        // TODO check a k8s event got created
        // TODO what happens when we subsequently reconcile?
    }

    /**
     * 1. operator is notified that a KafkaTopic is created
     * 2. operator successfully creates topic in kafka
     * 3. error when creating in topic store
     */
    @Test
    public void testOnKafkaTopicAdded_EntityExistsException(TestContext context) {
        TopicStore.EntityExistsException storeException = new TopicStore.EntityExistsException();
        resourceAdded(context,
                null,
                storeException);
        // TODO what happens when we subsequently reconcile?
        assertNotReadyStatus(context, storeException);
    }

    // TODO ^^ but a disconnected/loss of session error

    /**
     * 1. operator is notified that a topic is created
     * 2. operator successfully queries kafka to get topic metadata
     * 3. operator successfully creates KafkaTopic
     * 4. operator successfully creates in topic store
     */
    @Test
    public void testOnTopicCreated(TestContext context) {
        TopicMetadata topicMetadata = Utils.getTopicMetadata(topicName.toString(),
                new org.apache.kafka.clients.admin.Config(Collections.emptyList()));

        mockTopicStore.setCreateTopicResponse(topicName, null);
        mockKafka.setTopicMetadataResponse(topicName, topicMetadata, null);
        mockK8s.setCreateResponse(resourceName, null);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());
        Async async = context.async();
        topicOperator.onTopicCreated(logContext, topicName).setHandler(ar -> {
            assertSucceeded(context, ar);
            mockK8s.assertExists(context, resourceName);
            Topic t = TopicSerialization.fromTopicMetadata(topicMetadata);
            mockTopicStore.assertContains(context, t);
            async.complete();
        });
    }

    /**
     * 1. operator is notified that a topic is created
     * 2. operator initially failed querying kafka to get topic metadata
     * 3. operator is subsequently successful in querying kafka to get topic metadata
     * 4. operator successfully creates KafkaTopic
     * 5. operator successfully creates in topic store
     */
    @Test
    public void testOnTopicCreated_retry(TestContext context) {
        TopicMetadata topicMetadata = Utils.getTopicMetadata(topicName.toString(),
                new org.apache.kafka.clients.admin.Config(Collections.emptyList()));

        mockTopicStore.setCreateTopicResponse(topicName, null);
        AtomicInteger counter = new AtomicInteger();
        mockKafka.setTopicMetadataResponse(t -> {
            int count = counter.getAndIncrement();
            if (count == 3) {
                return Future.succeededFuture(topicMetadata);
            } else if (count < 3) {
                return Future.succeededFuture(null);
            }
            context.fail("This should never happen");
            return Future.failedFuture("This should never happen");
        });
        mockK8s.setCreateResponse(resourceName, null);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());
        Async async = context.async();
        topicOperator.onTopicCreated(logContext, topicName).setHandler(ar -> {
            assertSucceeded(context, ar);
            context.assertEquals(4, counter.get());
            mockK8s.assertExists(context, resourceName);
            mockTopicStore.assertContains(context, TopicSerialization.fromTopicMetadata(topicMetadata));
            async.complete();
        });
    }

    private <T> void assertSucceeded(TestContext context, AsyncResult<T> ar) {
        if (ar.failed()) {
            ar.cause().printStackTrace();
        }
        context.assertTrue(ar.succeeded(), ar.cause() != null ? ar.cause().toString() : "");
    }

    private <T> void assertFailed(TestContext context, AsyncResult<T> ar) {
        context.assertFalse(ar.succeeded(), String.valueOf(ar.result()));
    }


    /**
     * 1. operator is notified that a topic is created
     * 2. operator times out getting metadata
     */
    @Test
    public void testOnTopicCreated_retryTimeout(TestContext context) {

        mockKafka.setTopicMetadataResponse(topicName, null, null);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());
        Async async = context.async();
        topicOperator.onTopicCreated(logContext, topicName).setHandler(ar -> {
            assertFailed(context, ar);
            context.assertEquals(ar.cause().getClass(), MaxAttemptsExceededException.class);
            mockK8s.assertNotExists(context, resourceName);
            mockTopicStore.assertNotExists(context, topicName);
            async.complete();
        });
    }

    /**
     * 0. ZK notifies of a change in topic config
     * 1. operator gets updated topic metadata
     * 2. operator updates k8s and topic store.
     */
    @Test
    public void testOnTopicChanged(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).build();
        Topic kafkaTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "baz")).build();
        Topic privateTopic = kubeTopic;
        KafkaTopic resource = TopicSerialization.toTopicResource(kubeTopic, labels);

        mockKafka.setCreateTopicResponse(topicName.toString(), null)
                .createTopic(kafkaTopic);
        mockKafka.setTopicMetadataResponse(topicName, Utils.getTopicMetadata(kafkaTopic), null);
        //mockKafka.setUpdateTopicResponse(topicName -> Future.succeededFuture());

        mockTopicStore.setCreateTopicResponse(topicName, null)
                .create(privateTopic);
        mockTopicStore.setUpdateTopicResponse(topicName, null);

        mockK8s.setCreateResponse(resourceName, null)
                .createResource(resource);
        mockK8s.setModifyResponse(resourceName, null);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());
        Async async = context.async(3);
        topicOperator.onTopicConfigChanged(logContext, topicName).setHandler(ar -> {
            assertSucceeded(context, ar);
            context.assertEquals("baz", mockKafka.getTopicState(topicName).getConfig().get("cleanup.policy"));
            mockTopicStore.read(topicName).setHandler(ar2 -> {
                assertSucceeded(context, ar2);
                context.assertEquals("baz", ar2.result().getConfig().get("cleanup.policy"));
                async.countDown();
            });
            mockK8s.getFromName(resourceName).setHandler(ar2 -> {
                assertSucceeded(context, ar2);
                context.assertEquals("baz", TopicSerialization.fromTopicResource(ar2.result()).getConfig().get("cleanup.policy"));
                async.countDown();
            });
            async.countDown();
        });
    }

    // TODO error getting full topic metadata, and then reconciliation
    // TODO error creating KafkaTopic (exists), and then reconciliation

    /**
     * Test reconciliation when a resource has been created while the operator wasn't running
     */
    @Test
    public void testReconcile_withResource_noKafka_noPrivate(TestContext context) {

        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).build();
        Topic kafkaTopic = null;
        Topic privateTopic = null;

        Async async0 = context.async();
        mockKafka.setCreateTopicResponse(topicName.toString(), null);
        //mockKafka.setTopicMetadataResponse(topicName, null, null);
        mockKafka.setTopicMetadataResponse(
            topicName -> Future.succeededFuture(Utils.getTopicMetadata(kubeTopic)));

        mockTopicStore.setCreateTopicResponse(topicName, null);
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        KafkaTopic topicResource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.kubeWatch(Watcher.Action.ADDED, topicResource);
        mockK8s.createResource(topicResource).setHandler(ar -> async0.countDown());

        Async async = context.async(1);
        topicOperator.reconcile(null, logContext, null, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockKafka.assertExists(context, kubeTopic.getTopicName());
            mockTopicStore.assertExists(context, kubeTopic.getTopicName());
            mockK8s.assertNoEvents(context);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kubeTopic, readResult.result());
                async.countDown();
            });
        });
    }

    /**
     * Test reconciliation when a topic has been deleted while the operator
     * wasn't running
     */
    @Test
    public void testReconcile_withResource_noKafka_withPrivate(TestContext context) {

        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).build();
        Topic kafkaTopic = null;
        Topic privateTopic = kubeTopic;

        Async async0 = context.async(2);
        KafkaTopic topicResource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.kubeWatch(Watcher.Action.DELETED, topicResource);
        mockK8s.setCreateResponse(resourceName, null)
                .createResource(topicResource).setHandler(ar -> async0.countDown());
        mockK8s.setDeleteResponse(resourceName, null);
        mockTopicStore.setCreateTopicResponse(topicName, null)
                .create(privateTopic).setHandler(ar -> async0.countDown());
        mockTopicStore.setDeleteTopicResponse(topicName, null);

        Async async = context.async();

        topicOperator.reconcile(reconciliation(), logContext, null, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockKafka.assertNotExists(context, kubeTopic.getTopicName());
            mockTopicStore.assertNotExists(context, kubeTopic.getTopicName());
            mockK8s.assertNotExists(context, kubeTopic.getResourceName());
            mockK8s.assertNoEvents(context);
            async.complete();
        });
    }

    /**
     * Test reconciliation when a topic has been created while the operator wasn't running
     */
    @Test
    public void testReconcile_noResource_withKafka_noPrivate(TestContext context) {

        Topic kubeTopic = null;
        Topic kafkaTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar"), metadata).build();
        Topic privateTopic = null;

        Async async0 = context.async();
        mockTopicStore.setCreateTopicResponse(topicName, null);
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        mockKafka.setCreateTopicResponse(topicName -> Future.succeededFuture());
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.complete());
        async0.await();
        LogContext logContext = LogContext.periodic(topicName.toString());
        Async async = context.async(2);
        topicOperator.reconcile(reconciliation(), logContext, null, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockTopicStore.assertExists(context, topicName);
            mockK8s.assertExists(context, topicName.asKubeName());
            mockKafka.assertExists(context, topicName);
            mockK8s.assertNoEvents(context);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kafkaTopic, readResult.result());
                async.countDown();
            });
            mockK8s.getFromName(topicName.asKubeName()).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kafkaTopic, TopicSerialization.fromTopicResource(readResult.result()));
                async.countDown();
            });
            context.assertEquals(kafkaTopic, mockKafka.getTopicState(topicName));
        });
    }

    TopicOperator.Reconciliation reconciliation() {
        return topicOperator.new Reconciliation("test") {
            @Override
            public Future<Void> execute() {
                return null;
            }
        };
    }

    /**
     * Test reconciliation when a resource has been deleted while the operator
     * wasn't running
     */
    @Test
    public void testReconcile_noResource_withKafka_withPrivate(TestContext context) {
        Topic kubeTopic = null;
        Topic kafkaTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).build();
        Topic privateTopic = kafkaTopic;

        Async async0 = context.async(2);
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.countDown());
        mockKafka.setDeleteTopicResponse(topicName, null);
        mockTopicStore.setCreateTopicResponse(topicName, null);
        mockTopicStore.create(kafkaTopic).setHandler(ar -> async0.countDown());
        mockTopicStore.setDeleteTopicResponse(topicName, null);
        async0.await();
        LogContext logContext = LogContext.periodic(topicName.toString());
        Async async = context.async();
        topicOperator.reconcile(reconciliation(), logContext, null, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockTopicStore.assertNotExists(context, topicName);
            mockK8s.assertNotExists(context, topicName.asKubeName());
            mockKafka.assertNotExists(context, topicName);
            mockK8s.assertNoEvents(context);
            async.complete();
        });
    }

    /**
     * Test reconciliation when a resource has been added both in kafka and in k8s while the operator was down, and both
     * topics are identical.
     */
    @Test
    public void testReconcile_withResource_withKafka_noPrivate_matching(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar"), metadata).build();
        Topic kafkaTopic = kubeTopic;
        Topic privateTopic = null;

        Async async0 = context.async(2);
        mockKafka.setCreateTopicResponse(topicName -> Future.succeededFuture());
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.countDown());
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        KafkaTopic topicResource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.periodic(topicName.toString());
        mockK8s.createResource(topicResource).setHandler(ar -> async0.countDown());
        mockTopicStore.setCreateTopicResponse(topicName, null);
        async0.await();

        Async async = context.async();
        topicOperator.reconcile(reconciliation(), logContext, null, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockTopicStore.assertExists(context, topicName);
            mockK8s.assertExists(context, topicName.asKubeName());
            mockK8s.assertNoEvents(context);
            mockKafka.assertExists(context, topicName);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kubeTopic, readResult.result());
                async.complete();
            });
            context.assertEquals(kafkaTopic, mockKafka.getTopicState(topicName));

        });
    }

    /**
     * Test reconciliation when a resource has been added both in kafka and in k8s while the operator was down, and
     * the topics are irreconcilably different: Kafka wins
     */
    @Test
    public void testReconcile_withResource_withKafka_noPrivate_configsReconcilable(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar"), metadata).build();
        Topic kafkaTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("unclean.leader.election.enable", "true"), metadata).build();
        Topic privateTopic = null;
        Topic mergedTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("unclean.leader.election.enable", "true", "cleanup.policy", "bar"), metadata).build();

        Async async0 = context.async(2);
        mockKafka.setCreateTopicResponse(topicName -> Future.succeededFuture());
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.countDown());
        mockKafka.setUpdateTopicResponse(topicName -> Future.succeededFuture());

        KafkaTopic topic = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.periodic(topicName.toString());
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        mockK8s.createResource(topic).setHandler(ar -> async0.countDown());
        mockK8s.setModifyResponse(topicName.asKubeName(), null);
        mockTopicStore.setCreateTopicResponse(topicName, null);
        async0.await();

        Async async = context.async(2);
        topicOperator.reconcile(reconciliation(), logContext, topic, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockTopicStore.assertExists(context, topicName);
            mockK8s.assertExists(context, topicName.asKubeName());
            mockKafka.assertExists(context, topicName);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(mergedTopic, readResult.result());
                async.countDown();
            });
            mockK8s.getFromName(topicName.asKubeName()).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(mergedTopic, TopicSerialization.fromTopicResource(readResult.result()));
                async.countDown();
            });
            context.assertEquals(mergedTopic, mockKafka.getTopicState(topicName));
        });
    }

    /**
     * Test reconciliation when a resource has been added both in kafka and in k8s while the operator was down, and
     * the topics are irreconcilably different: Kafka wins
     */
    @Test
    public void testReconcile_withResource_withKafka_noPrivate_irreconcilable(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar"), metadata).build();
        Topic kafkaTopic = new Topic.Builder(topicName.toString(), 12, (short) 2, map("cleanup.policy", "baz"), metadata).build();
        Topic privateTopic = null;

        Async async0 = context.async(2);
        mockKafka.setCreateTopicResponse(topicName -> Future.succeededFuture());
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.countDown());

        KafkaTopic topic = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.periodic(topicName.toString());
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        mockK8s.createResource(topic).setHandler(ar -> async0.countDown());
        mockK8s.setModifyResponse(topicName.asKubeName(), null);
        mockTopicStore.setCreateTopicResponse(topicName, null);
        async0.await();

        Async async = context.async(2);
        topicOperator.reconcile(reconciliation(), logContext, topic, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockK8s.assertContainsEvent(context, e ->
                    e.getMessage().contains("KafkaTopic is incompatible with the topic metadata. " +
                            "The topic metadata will be treated as canonical."));
            mockTopicStore.assertExists(context, topicName);
            mockK8s.assertExists(context, topicName.asKubeName());
            mockKafka.assertExists(context, topicName);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kafkaTopic, readResult.result());
                async.countDown();
            });
            mockK8s.getFromName(topicName.asKubeName()).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(kafkaTopic, TopicSerialization.fromTopicResource(readResult.result()));
                async.countDown();
            });
            context.assertEquals(kafkaTopic, mockKafka.getTopicState(topicName));
        });
    }

    /**
     * Test reconciliation when a resource has been changed both in kafka and in k8s while the operator was down, and
     * a 3 way merge is needed.
     */
    @Test
    public void testReconcile_withResource_withKafka_withPrivate_3WayMerge(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName, resourceName, 10, (short) 2, map("cleanup.policy", "bar"), metadata).build();
        Topic kafkaTopic = new Topic.Builder(topicName, resourceName, 12, (short) 2, map("cleanup.policy", "baz"), metadata).build();
        Topic privateTopic = new Topic.Builder(topicName, resourceName, 10, (short) 2, map("cleanup.policy", "baz"), metadata).build();
        Topic resultTopic = new Topic.Builder(topicName, resourceName, 12, (short) 2, map("cleanup.policy", "bar"), metadata).build();

        Async async0 = context.async(3);
        mockKafka.setCreateTopicResponse(topicName -> Future.succeededFuture());
        mockKafka.createTopic(kafkaTopic).setHandler(ar -> async0.countDown());
        mockKafka.setUpdateTopicResponse(topicName -> Future.succeededFuture());

        KafkaTopic resource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.periodic(topicName.toString());
        mockK8s.setCreateResponse(topicName.asKubeName(), null);
        mockK8s.createResource(resource).setHandler(ar -> async0.countDown());
        mockK8s.setModifyResponse(topicName.asKubeName(), null);
        mockTopicStore.setCreateTopicResponse(topicName, null);
        mockTopicStore.create(privateTopic).setHandler(ar -> async0.countDown());
        async0.await();

        Async async = context.async(3);
        topicOperator.reconcile(reconciliation(), logContext, resource, kubeTopic, kafkaTopic, privateTopic).setHandler(reconcileResult -> {
            assertSucceeded(context, reconcileResult);
            mockK8s.assertNoEvents(context);
            mockTopicStore.read(topicName).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(resultTopic, readResult.result());
                async.countDown();
            });
            mockK8s.getFromName(topicName.asKubeName()).setHandler(readResult -> {
                assertSucceeded(context, readResult);
                context.assertEquals(resultTopic, TopicSerialization.fromTopicResource(readResult.result()));
                async.countDown();
            });
            context.assertEquals(resultTopic, mockKafka.getTopicState(topicName));
            async.countDown();
        });
    }

    // TODO 3way reconcilation where kafka and kube agree
    // TODO 3way reconcilation where all three agree
    // TODO 3way reconcilation with conflict
    // TODO reconciliation where only private state exists => delete the private state

    // TODO tests for the other reconciliation cases
    // + non-matching predicate
    // + error cases

    private void resourceRemoved(TestContext context, Exception deleteTopicException, Exception storeException) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).build();
        Topic kafkaTopic = kubeTopic;
        Topic privateTopic = kubeTopic;

        mockKafka.setCreateTopicResponse(topicName.toString(), null)
                .createTopic(kafkaTopic);
        mockKafka.setTopicMetadataResponse(topicName, Utils.getTopicMetadata(kubeTopic), null);
        mockKafka.setDeleteTopicResponse(topicName, deleteTopicException);

        mockTopicStore.setCreateTopicResponse(topicName, null)
                .create(privateTopic);
        mockTopicStore.setDeleteTopicResponse(topicName, storeException);

        KafkaTopic resource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.kubeWatch(Watcher.Action.DELETED, resource);

        Async async = context.async();
        topicOperator.onResourceEvent(logContext, resource, DELETED).setHandler(ar -> {
            if (deleteTopicException != null
                    || storeException != null) {
                assertFailed(context, ar);
                if (deleteTopicException != null) {
                    // should still exist
                    mockKafka.assertExists(context, kafkaTopic.getTopicName());
                } else {
                    mockKafka.assertNotExists(context, kafkaTopic.getTopicName());
                }
                mockTopicStore.assertExists(context, kafkaTopic.getTopicName());
            } else {
                assertSucceeded(context, ar);
                mockKafka.assertNotExists(context, kafkaTopic.getTopicName());
                mockTopicStore.assertNotExists(context, kafkaTopic.getTopicName());
            }
            async.complete();
        });
    }

    @Test
    public void testOnKafkaTopicChanged(TestContext context) {
        Topic kubeTopic = new Topic.Builder(topicName, resourceName, 10, (short) 2, map("cleanup.policy", "baz"), null).build();
        Topic kafkaTopic = new Topic.Builder(topicName, resourceName, 10, (short) 2, map("cleanup.policy", "bar"), null).build();
        Topic privateTopic = kafkaTopic;
        KafkaTopic resource = TopicSerialization.toTopicResource(kubeTopic, labels);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());

        mockKafka.setCreateTopicResponse(topicName.toString(), null)
                .createTopic(kafkaTopic);
        mockKafka.setTopicMetadataResponse(topicName, Utils.getTopicMetadata(kafkaTopic), null);
        mockKafka.setUpdateTopicResponse(topicName -> Future.succeededFuture());

        mockTopicStore.setCreateTopicResponse(topicName, null)
                .create(privateTopic);
        mockTopicStore.setUpdateTopicResponse(topicName, null);

        mockK8s.setCreateResponse(resourceName, null);
        mockK8s.createResource(resource).setHandler(ar -> {
            assertSucceeded(context, ar);
        });
        mockK8s.setModifyResponse(resourceName, null);

        Async async = context.async(3);
        topicOperator.onResourceEvent(logContext, resource, MODIFIED).setHandler(ar -> {
            assertSucceeded(context, ar);
            context.assertEquals("baz", mockKafka.getTopicState(topicName).getConfig().get("cleanup.policy"));
            mockTopicStore.read(topicName).setHandler(ar2 -> {
                assertSucceeded(context, ar2);
                context.assertEquals("baz", ar2.result().getConfig().get("cleanup.policy"));
                async.countDown();
            });
            mockK8s.getFromName(resourceName).setHandler(ar2 -> {
                assertSucceeded(context, ar2);
                context.assertNotNull(ar2.result());
                context.assertEquals("baz", TopicSerialization.fromTopicResource(ar2.result()).getConfig().get("cleanup.policy"));
                async.countDown();
            });
            async.countDown();
        });
    }

    @Test
    public void testOnKafkaTopicRemoved(TestContext context) {
        Exception deleteTopicException = null;
        Exception storeException = null;
        resourceRemoved(context, deleteTopicException, storeException);
    }

    @Test
    public void testOnKafkaTopicRemoved_UnknownTopicOrPartitionException(TestContext context) {
        Exception deleteTopicException = new UnknownTopicOrPartitionException();
        Exception storeException = null;
        resourceRemoved(context, deleteTopicException, storeException);
    }

    @Test
    public void testOnKafkaTopicRemoved_NoSuchEntityExistsException(TestContext context) {
        Exception deleteTopicException = null;
        Exception storeException = new TopicStore.NoSuchEntityExistsException();
        resourceRemoved(context, deleteTopicException, storeException);
    }

    private void topicDeleted(TestContext context, Exception storeException, Exception k8sException) {
        Topic kubeTopic = new Topic.Builder(topicName.toString(), 10, (short) 2, map("cleanup.policy", "bar")).withMapName(resourceName).build();
        Topic kafkaTopic = kubeTopic;
        Topic privateTopic = kubeTopic;

        mockK8s.setCreateResponse(resourceName, null)
                .createResource(TopicSerialization.toTopicResource(kubeTopic, labels));
        mockK8s.setDeleteResponse(resourceName, k8sException);

        mockTopicStore.setCreateTopicResponse(topicName, null)
                .create(privateTopic);
        mockTopicStore.setDeleteTopicResponse(topicName, storeException);
        LogContext logContext = LogContext.zkWatch("///", topicName.toString());
        Async async = context.async();
        topicOperator.onTopicDeleted(logContext, topicName).setHandler(ar -> {
            if (k8sException != null
                    || storeException != null) {
                assertFailed(context, ar);
                if (k8sException == null) {
                    mockK8s.assertNotExists(context, resourceName);
                } else {
                    mockK8s.assertExists(context, resourceName);
                }
                mockTopicStore.assertExists(context, topicName);
            } else {
                assertSucceeded(context, ar);
                mockK8s.assertNotExists(context, resourceName);
                mockTopicStore.assertNotExists(context, topicName);
            }
            async.complete();
        });
    }

    @Test
    public void testOnTopicDeleted(TestContext context) {
        Exception storeException = null;
        Exception k8sException = null;
        topicDeleted(context, storeException, k8sException);
    }

    @Test
    public void testOnTopicDeleted_NoSuchEntityExistsException(TestContext context) {
        Exception k8sException = null;
        Exception storeException = new TopicStore.NoSuchEntityExistsException();
        topicDeleted(context, storeException, k8sException);
    }

    @Test
    public void testOnTopicDeleted_KubernetesClientException(TestContext context) {
        Exception k8sException = new KubernetesClientException("Test exception");
        Exception storeException = null;
        topicDeleted(context, storeException, k8sException);
    }

    @Test
    public void testReconcileAllTopics_listTopicsFails(TestContext context) {
        RuntimeException error = new RuntimeException("some failure");
        mockKafka.setTopicsListResponse(Future.failedFuture(error));

        Future<?> reconcileFuture = topicOperator.reconcileAllTopics("periodic");

        reconcileFuture.setHandler(context.asyncAssertFailure(e -> {
            context.assertEquals("Error listing existing topics during periodic reconciliation", e.getMessage());
            context.assertEquals(error, e.getCause());

        }));
    }

    @Test
    public void testReconcileAllTopics_getResourceFails(TestContext context) {
        RuntimeException error = new RuntimeException("some failure");
        mockKafka.setTopicsListResponse(Future.succeededFuture(singleton(topicName.toString())));
        mockKafka.setDeleteTopicResponse(topicName, null);
        mockTopicStore.setGetTopicResponse(topicName, Future.failedFuture(error));

        Future<?> reconcileFuture = topicOperator.reconcileAllTopics("periodic");

        reconcileFuture.setHandler(context.asyncAssertFailure(e -> {
            context.assertEquals("Error getting KafkaTopic my-topic during periodic reconciliation", e.getMessage());
            context.assertEquals(error, e.getCause());
        }));
    }

    @Test
    public void testReconcileAllTopics_listMapsFails(TestContext context) {
        RuntimeException error = new RuntimeException("some failure");
        mockKafka.setTopicsListResponse(Future.succeededFuture(emptySet()));
        mockK8s.setListMapsResult(() -> Future.failedFuture(error));

        Future<?> reconcileFuture = topicOperator.reconcileAllTopics("periodic");

        reconcileFuture.setHandler(context.asyncAssertFailure(e -> {
            context.assertEquals("Error listing existing KafkaTopics during periodic reconciliation", e.getMessage());
            context.assertEquals(error, e.getCause());
        }));
    }

    // TODO tests for nasty races (e.g. create on both ends, update on one end and delete on the other)
    // I think in these cases we should seek to detect the concurrent modification
    // and perform a full reconciliation, possibly after a backoff time
    // (to cover the case where topic config and other aspects get changed via multiple calls)
    // TODO test for zookeeper session timeout
    // TODO test for Kubernetes connection death
}

/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watcher;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.status.KafkaTopicStatus;
import io.strimzi.operator.cluster.model.StatusDiff;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.MaxAttemptsExceededException;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.util.Collections.disjoint;

@SuppressWarnings({"checkstyle:ClassDataAbstractionCoupling", "checkstyle:ClassFanOutComplexity"})
class TopicOperator {

    private final static Logger LOGGER = LogManager.getLogger(TopicOperator.class);
    private final static Logger EVENT_LOGGER = LogManager.getLogger("Event");
    private final Kafka kafka;
    private final K8s k8s;
    private final Vertx vertx;
    private final Labels labels;
    private final String namespace;
    private TopicStore topicStore;
    private final Config config;
    private final ConcurrentHashMap<TopicName, Integer> inflight = new ConcurrentHashMap<>();

    enum EventType {
        INFO("Info"),
        WARNING("Warning");
        final String name;
        EventType(String name) {
            this.name = name;
        }
    }

    class Event implements Handler<Void> {
        private final EventType eventType;
        private final String message;
        private final HasMetadata involvedObject;
        private final Handler<AsyncResult<Void>> handler;

        public Event(HasMetadata involvedObject, String message, EventType eventType, Handler<AsyncResult<Void>> handler) {
            this.involvedObject = involvedObject;
            this.message = message;
            this.handler = handler;
            this.eventType = eventType;
        }

        @Override
        public void handle(Void v) {
            EventBuilder evtb = new EventBuilder().withApiVersion("v1");
            final String eventTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("YYYY-MM-dd'T'HH:mm:ss'Z'"));
            
            if (involvedObject != null) {
                evtb.withNewInvolvedObject()
                        .withKind(involvedObject.getKind())
                        .withName(involvedObject.getMetadata().getName())
                        .withApiVersion(involvedObject.getApiVersion())
                        .withNamespace(involvedObject.getMetadata().getNamespace())
                        .withUid(involvedObject.getMetadata().getUid())
                        .endInvolvedObject();
            }
            evtb.withType(eventType.name)
                    .withMessage(message)
                    .withNewMetadata().withLabels(labels.labels()).withGenerateName("topic-operator").withNamespace(namespace).endMetadata()
                    .withLastTimestamp(eventTime)
                    .withNewSource()
                    .withComponent(TopicOperator.class.getName())
                    .endSource();
            io.fabric8.kubernetes.api.model.Event event = evtb.build();
            switch (eventType) {
                case INFO:
                    LOGGER.info("{}", message);
                    break;
                case WARNING:
                    LOGGER.warn("{}", message);
                    break;
            }
            k8s.createEvent(event).setHandler(handler);
        }

        public String toString() {
            return "ErrorEvent(involvedObject=" + involvedObject + ", message=" + message + ")";
        }
    }

    private Future<KafkaTopic> createResource(LogContext logContext, Topic topic) {
        Future<KafkaTopic> result = Future.future();
        enqueue(new CreateResource(logContext, topic, result));
        return result;
    }

    /** Topic created in ZK */
    class CreateResource implements Handler<Void> {
        private final Topic topic;
        private final Handler<io.vertx.core.AsyncResult<KafkaTopic>> handler;
        private final LogContext logContext;

        public CreateResource(LogContext logContext, Topic topic, Handler<io.vertx.core.AsyncResult<KafkaTopic>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            KafkaTopic kafkaTopic = TopicSerialization.toTopicResource(this.topic, labels);
            k8s.createResource(kafkaTopic).setHandler(handler);
        }

        @Override
        public String toString() {
            return "CreateResource(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    private Future<Void> deleteResource(LogContext logContext, ResourceName resourceName) {
        Future<Void> result = Future.future();
        enqueue(new DeleteResource(logContext, resourceName, result));
        return result;
    }

    /** Topic deleted in ZK */
    class DeleteResource implements Handler<Void> {

        private final ResourceName resourceName;
        private final Handler<io.vertx.core.AsyncResult<Void>> handler;
        private final LogContext logContext;

        public DeleteResource(LogContext logContext, ResourceName resourceName, Handler<io.vertx.core.AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.resourceName = resourceName;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) {
            k8s.deleteResource(resourceName).setHandler(handler);
            statusUpdateGeneration.remove(resourceName.toString());
        }

        @Override
        public String toString() {
            return "DeleteResource(mapName=" + resourceName + ",ctx=" + logContext + ")";
        }
    }

    private Future<KafkaTopic> updateResource(LogContext logContext, Topic topic) {
        Future<KafkaTopic> result = Future.future();
        enqueue(new UpdateResource(logContext, topic, result));
        return result;
    }

    /** Topic config modified in ZK */
    class UpdateResource implements Handler<Void> {

        private final Topic topic;
        private final Handler<io.vertx.core.AsyncResult<KafkaTopic>> handler;
        private final LogContext logContext;

        public UpdateResource(LogContext logContext, Topic topic, Handler<AsyncResult<KafkaTopic>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) {
            KafkaTopic kafkaTopic = TopicSerialization.toTopicResource(this.topic, labels);
            k8s.updateResource(kafkaTopic).setHandler(handler);
        }

        @Override
        public String toString() {
            return "UpdateResource(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    private Future<Void> createKafkaTopic(LogContext logContext, Topic topic,
                                          HasMetadata involvedObject) {
        Future<Void> result = Future.future();
        enqueue(new CreateKafkaTopic(logContext, topic, involvedObject, result));
        return result;
    }

    /** Resource created in k8s */
    private class CreateKafkaTopic implements Handler<Void> {

        private final Topic topic;
        private final HasMetadata involvedObject;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        public CreateKafkaTopic(LogContext logContext, Topic topic,
                                HasMetadata involvedObject, Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.handler = handler;
            this.involvedObject = involvedObject;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            kafka.createTopic(topic).setHandler(ar -> {
                if (ar.succeeded()) {
                    LOGGER.debug("{}: Created topic '{}' for KafkaTopic '{}'",
                            logContext, topic.getTopicName(), topic.getResourceName());
                    handler.handle(ar);
                } else {
                    handler.handle(ar);
                    if (ar.cause() instanceof TopicExistsException) {
                        // TODO reconcile
                    } else if (ar.cause() instanceof InvalidReplicationFactorException) {
                        // error message is printed in the `reconcile` method
                    } else {
                        throw new OperatorException(involvedObject, ar.cause());
                    }
                }
            });
        }

        @Override
        public String toString() {
            return "CreateKafkaTopic(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    /** KafkaTopic modified in k8s */
    class UpdateKafkaConfig implements Handler<Void> {

        private final HasMetadata involvedObject;

        private final Topic topic;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        public UpdateKafkaConfig(LogContext logContext, Topic topic, HasMetadata involvedObject, Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            kafka.updateTopicConfig(topic).setHandler(ar -> {
                if (ar.failed()) {
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                }
                handler.handle(ar);
            });

        }

        @Override
        public String toString() {
            return "UpdateKafkaConfig(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    /** KafkaTopic modified in k8s */
    class IncreaseKafkaPartitions implements Handler<Void> {

        private final HasMetadata involvedObject;

        private final Topic topic;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        public IncreaseKafkaPartitions(LogContext logContext, Topic topic, HasMetadata involvedObject, Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            kafka.increasePartitions(topic).setHandler(ar -> {
                if (ar.failed()) {
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                }
                handler.handle(ar);
            });

        }

        @Override
        public String toString() {
            return "UpdateKafkaPartitions(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    /** KafkaTopic modified in k8s */
    class ChangeReplicationFactor implements Handler<Void> {

        private final HasMetadata involvedObject;

        private final Topic topic;
        private final Handler<AsyncResult<Void>> handler;

        public ChangeReplicationFactor(Topic topic, HasMetadata involvedObject, Handler<AsyncResult<Void>> handler) {
            this.topic = topic;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            kafka.changeReplicationFactor(topic).setHandler(ar -> {
                if (ar.failed()) {
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                }
                handler.handle(ar);
            });

        }

        @Override
        public String toString() {
            return "ChangeReplicationFactor(topicName=" + topic.getTopicName() + ")";
        }
    }

    private Future<Void> deleteKafkaTopic(LogContext logContext, TopicName topicName) {
        Future<Void> result = Future.future();
        enqueue(new DeleteKafkaTopic(logContext, topicName, result));
        return result;
    }

    /** KafkaTopic deleted in k8s */
    class DeleteKafkaTopic implements Handler<Void> {

        public final TopicName topicName;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        public DeleteKafkaTopic(LogContext logContext, TopicName topicName, Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topicName = topicName;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            LOGGER.info("{}: Deleting topic '{}'", logContext, topicName);
            kafka.deleteTopic(topicName).setHandler(handler);
        }

        @Override
        public String toString() {
            return "DeleteKafkaTopic(topicName=" + topicName + ",ctx=" + logContext + ")";
        }
    }

    public TopicOperator(Vertx vertx, Kafka kafka,
                         K8s k8s,
                         TopicStore topicStore,
                         Labels labels,
                         String namespace,
                         Config config) {
        this.kafka = kafka;
        this.k8s = k8s;
        this.vertx = vertx;
        this.labels = labels;
        this.topicStore = topicStore;
        this.namespace = namespace;
        this.config = config;
    }


    /**
     * Run the given {@code action} on the context thread,
     * immediately if there are currently no other actions with the given {@code key},
     * or when the other actions with the given {@code key} have completed.
     * When the given {@code action} is complete it must complete its argument future,
     * which will complete the returned future
     */
    public Future<Void> executeWithTopicLockHeld(LogContext logContext, TopicName key, Reconciliation action) {
        String lockName = key.toString();
        int timeoutMs = 30 * 1_000;
        Future<Void> result = Future.future();
        BiFunction<TopicName, Integer, Integer> decrement = (topicName, waiters) -> {
            if (waiters != null) {
                if (waiters == 1) {
                    LOGGER.debug("{}: Removing last waiter {}", logContext, action);
                    return null;
                } else {
                    LOGGER.debug("{}: Removing waiter {}, {} waiters left", logContext, action, waiters - 1);
                    return waiters - 1;
                }
            } else {
                LOGGER.error("{}: Assertion failure. topic {}, action {}", logContext, lockName, action);
                return null;
            }
        };
        LOGGER.debug("{}: Queuing action {} on topic {}", logContext, action, lockName);
        inflight.compute(key, (topicName, waiters) -> {
            if (waiters == null) {
                LOGGER.debug("{}: Adding first waiter {}", logContext, action);
                return 1;
            } else {
                LOGGER.debug("{}: Adding waiter {}: {}", logContext, action, waiters + 1);
                return waiters + 1;
            }
        });
        vertx.sharedData().getLockWithTimeout(lockName, timeoutMs, ar -> {
            if (ar.succeeded()) {
                LOGGER.debug("{}: Lock acquired", logContext);
                LOGGER.debug("{}: Executing action {} on topic {}", logContext, action, lockName);
                action.execute().setHandler(ar2 -> {
                    LOGGER.debug("{}: Executing handler for action {} on topic {}", logContext, action, lockName);
                    action.result = ar2;
                    // Update status with lock held so that event is ignored via statusUpdateGeneration
                    action.updateStatus(logContext).setHandler(ar3 -> {
                        if (ar3.failed()) {
                            LOGGER.error("{}: Error updating KafkaTopic.status for action {}", logContext, action,
                                    ar3.cause());
                        }
                        try {
                            result.handle(ar2);
                        } finally {
                            ar.result().release();
                            LOGGER.debug("{}: Lock released", logContext);
                            inflight.compute(key, decrement);
                        }
                    });
                });
            } else {
                LOGGER.warn("{}: Lock not acquired within {}ms: action {} will not be run", logContext, timeoutMs, action);
                try {
                    result.handle(Future.failedFuture("Failed to acquire lock for topic " + lockName + " after " + timeoutMs + "ms. Not executing action " + action));
                } finally {
                    inflight.compute(key, decrement);
                }
            }
        });
        return result;
    }

    /**
     * 0. Set up some persistent ZK nodes for us
     * 1. When updating KafkaTopic, we also update our ZK nodes
     * 2. When updating Kafka, we also update our ZK nodes
     * 3. When reconciling we get all three versions of the Topic, k8s, kafka and privateState
     *   - If privateState doesn't exist:
     *     - If k8s doesn't exist, we reason it's been created in kafka and we create it k8s from kafka
     *     - If kafka doesn't exist, we reason it's been created in k8s, and we create it in kafka from k8s
     *     - If both exist, and are the same: That's fine
     *     - If both exist, and are different: We use whichever has the most recent mtime.
     *     - In all above cases we create privateState
     *   - If privateState does exist:
     *     - If k8s doesn't exist, we reason it was deleted, and delete kafka
     *     - If kafka doesn't exist, we reason it was delete and we delete k8s
     *     - If neither exists, we delete privateState.
     *     - If both exist then all three exist, and we need to reconcile:
     *       - We compute diff privateState->k8s and privateState->kafka and we merge the two
     *         - If there are conflicts => error
     *         - Otherwise we apply the apply the merged diff to privateState, and use that for both k8s and kafka
     *     - In all above cases we update privateState
     * Topic identification should be by uid/cxid, not by name.
     * Topic identification should be by uid/cxid, not by name.
     */
    Future<Void> reconcile(Reconciliation reconciliation, final LogContext logContext, final HasMetadata involvedObject,
                   final Topic k8sTopic, final Topic kafkaTopic, final Topic privateTopic) {
        final Future<Void> reconciliationResultHandler;
        {
            TopicName topicName = k8sTopic != null ? k8sTopic.getTopicName() : kafkaTopic != null ? kafkaTopic.getTopicName() : privateTopic != null ? privateTopic.getTopicName() : null;
            LOGGER.info("{}: Reconciling topic {}, k8sTopic:{}, kafkaTopic:{}, privateTopic:{}", logContext, topicName, k8sTopic == null ? "null" : "nonnull", kafkaTopic == null ? "null" : "nonnull", privateTopic == null ? "null" : "nonnull");
        }
        if (privateTopic == null) {
            if (k8sTopic == null) {
                if (kafkaTopic == null) {
                    // All three null: This happens reentrantly when a topic or KafkaTopic is deleted
                    LOGGER.debug("{}: All three topics null during reconciliation.", logContext);
                    reconciliationResultHandler = Future.succeededFuture();
                } else {
                    // it's been created in Kafka => create in k8s and privateState
                    LOGGER.debug("{}: topic created in kafka, will create KafkaTopic in k8s and topicStore", logContext);
                    reconciliationResultHandler = createResource(logContext, kafkaTopic)
                            .compose(createdKt -> {
                                reconciliation.observedTopicFuture(createdKt);
                                return createInTopicStore(logContext, kafkaTopic, involvedObject);
                            });
                }
            } else if (kafkaTopic == null) {
                // it's been created in k8s => create in Kafka and privateState
                LOGGER.debug("{}: KafkaTopic created in k8s, will create topic in kafka and topicStore", logContext);
                reconciliationResultHandler = createKafkaTopic(logContext, k8sTopic, involvedObject)
                    .compose(ignore -> createInTopicStore(logContext, k8sTopic, involvedObject))
                    // Kafka will set the message.format.version, so we need to update the KafkaTopic to reflect
                    // that to avoid triggering another reconciliation
                    .compose(ignored -> getFromKafka(k8sTopic.getTopicName()))
                    .compose(kafkaTopic2 -> {
                        LOGGER.debug("Post-create kafka {}", kafkaTopic2);
                        return update3Way(reconciliation, logContext, involvedObject, k8sTopic, kafkaTopic2, k8sTopic);
                    });
                    //.compose(createdKafkaTopic -> update3Way(logContext, involvedObject, k8sTopic, createdKafkaTopic, k8sTopic));
            } else {
                reconciliationResultHandler = update2Way(reconciliation, logContext, involvedObject, k8sTopic, kafkaTopic);
            }
        } else {
            if (k8sTopic == null) {
                if (kafkaTopic == null) {
                    // delete privateState
                    LOGGER.debug("{}: KafkaTopic deleted in k8s and topic deleted in kafka => delete from topicStore", logContext);
                    reconciliationResultHandler = deleteFromTopicStore(logContext, involvedObject, privateTopic.getTopicName());
                } else {
                    // it was deleted in k8s so delete in kafka and privateState
                    LOGGER.debug("{}: KafkaTopic deleted in k8s => delete topic from kafka and from topicStore", logContext);
                    reconciliationResultHandler = deleteKafkaTopic(logContext, kafkaTopic.getTopicName())
                        .compose(ignored -> deleteFromTopicStore(logContext, involvedObject, privateTopic.getTopicName()));
                }
            } else if (kafkaTopic == null) {
                // it was deleted in kafka so delete in k8s and privateState
                LOGGER.debug("{}: topic deleted in kafkas => delete KafkaTopic from k8s and from topicStore", logContext);
                reconciliationResultHandler = deleteResource(logContext, privateTopic.getOrAsKubeName())
                        .compose(ignore -> deleteFromTopicStore(logContext, involvedObject, privateTopic.getTopicName()));
            } else {
                // all three exist
                LOGGER.debug("{}: 3 way diff", logContext);
                reconciliationResultHandler = update3Way(reconciliation, logContext, involvedObject,
                        k8sTopic, kafkaTopic, privateTopic);
            }
        }
        return reconciliationResultHandler;
    }

    private Future<Void> update2Way(Reconciliation reconciliation, LogContext logContext, HasMetadata involvedObject, Topic k8sTopic, Topic kafkaTopic) {
        final Future<Void> reconciliationResultHandler;
        TopicDiff diff = TopicDiff.diff(kafkaTopic, k8sTopic);
        if (diff.isEmpty()) {
            // they're the same => do nothing, but still create the private copy
            LOGGER.debug("{}: KafkaTopic created in k8s and topic created in kafka, but they're identical => just creating in topicStore", logContext);
            LOGGER.debug("{}: k8s and kafka versions of topic '{}' are the same", logContext, kafkaTopic.getTopicName());
            reconciliationResultHandler = createInTopicStore(logContext, kafkaTopic, involvedObject);
        } else if (!diff.changesReplicationFactor()
                && !diff.changesNumPartitions()
                && diff.changesConfig()
                && disjoint(kafkaTopic.getConfig().keySet(), k8sTopic.getConfig().keySet())) {
            LOGGER.debug("{}: KafkaTopic created in k8s and topic created in kafka, they differ only in topic config, and those configs are disjoint: Updating k8s and kafka, and creating in topic store", logContext);
            Map<String, String> mergedConfigs = new HashMap<>(kafkaTopic.getConfig());
            mergedConfigs.putAll(k8sTopic.getConfig());
            Topic mergedTopic = new Topic.Builder(kafkaTopic).withConfig(mergedConfigs).build();
            reconciliationResultHandler = updateResource(logContext, mergedTopic)
                    .compose(updatedResource -> {
                        reconciliation.observedTopicFuture(updatedResource);
                        Future<Void> x = Future.future();
                        enqueue(new UpdateKafkaConfig(logContext, mergedTopic, involvedObject, x));
                        return x.compose(ignore -> createInTopicStore(logContext, mergedTopic, involvedObject));
                    });
        } else {
            // Just use kafka version, but also create a warning event
            LOGGER.debug("{}: KafkaTopic created in k8s and topic created in kafka, and they are irreconcilably different => kafka version wins", logContext);
            Future<Void> eventFuture = Future.future();
            enqueue(new Event(involvedObject, "KafkaTopic is incompatible with the topic metadata. " +
                    "The topic metadata will be treated as canonical.", EventType.INFO, eventFuture));
            reconciliationResultHandler = eventFuture
                .compose(ignored ->
                    updateResource(logContext, kafkaTopic))
                .compose(updatedResource -> {
                    reconciliation.observedTopicFuture(updatedResource);
                    return createInTopicStore(logContext, kafkaTopic, involvedObject);
                });
        }
        return reconciliationResultHandler;
    }

    private Future<Void> update3Way(Reconciliation reconciliation, LogContext logContext, HasMetadata involvedObject, Topic k8sTopic, Topic kafkaTopic, Topic privateTopic) {
        final Future<Void> reconciliationResultHandler;
        if (!privateTopic.getResourceName().equals(k8sTopic.getResourceName())) {
            return Future.failedFuture(new OperatorException(involvedObject,
                    "Topic '" + kafkaTopic.getTopicName() + "' is already managed via KafkaTopic '" + privateTopic.getResourceName() + "' it cannot also be managed via the KafkaTopic '" + k8sTopic.getResourceName() + "'"));
        }
        TopicDiff oursKafka = TopicDiff.diff(privateTopic, kafkaTopic);
        LOGGER.debug("{}: topicStore->kafkaTopic: {}", logContext, oursKafka);
        TopicDiff oursK8s = TopicDiff.diff(privateTopic, k8sTopic);
        LOGGER.debug("{}: topicStore->k8sTopic: {}", logContext, oursK8s);
        String conflict = oursKafka.conflict(oursK8s);
        if (conflict != null) {
            final String message = "KafkaTopic resource and Kafka topic both changed in a conflicting way: " + conflict;
            LOGGER.error("{}: {}", logContext, message);
            enqueue(new Event(involvedObject, message, EventType.INFO, eventResult -> { }));
            reconciliationResultHandler = Future.failedFuture(new Exception(message));
        } else {
            TopicDiff merged = oursKafka.merge(oursK8s);
            LOGGER.debug("{}: Diffs do not conflict, merged diff: {}", logContext, merged);
            if (merged.isEmpty()) {
                LOGGER.info("{}: All three topics are identical", logContext);
                reconciliationResultHandler = Future.succeededFuture();
            } else {
                Topic result = merged.apply(privateTopic);
                int partitionsDelta = merged.numPartitionsDelta();
                if (partitionsDelta < 0) {
                    final String message = "Number of partitions cannot be decreased";
                    LOGGER.error("{}: {}", logContext, message);
                    enqueue(new Event(involvedObject, message, EventType.INFO, eventResult -> {
                    }));
                    reconciliationResultHandler = Future.failedFuture(new Exception(message));
                } else {
                    if (merged.changesReplicationFactor()) {
                        LOGGER.error("{}: Changes replication factor", logContext);
                        enqueue(new ChangeReplicationFactor(result, involvedObject, res -> LOGGER.error(
                                "Changing replication factor is not supported")));
                    }
                    // TODO What if we increase min.in.sync.replicas and the number of replicas,
                    // such that the old number of replicas < the new min isr? But likewise
                    // we could decrease, so order of tasks in the queue will need to change
                    // depending on what the diffs are.
                    LOGGER.debug("{}: Updating KafkaTopic, kafka topic and topicStore", logContext);
                    TopicDiff kubeDiff = TopicDiff.diff(k8sTopic, result);
                    Future<KafkaTopic> resourceFuture;
                    if (!kubeDiff.isEmpty()) {
                        LOGGER.debug("{}: Updating KafkaTopic with {}", logContext, kubeDiff);
                        resourceFuture = updateResource(logContext, result).map(updatedKafkaTopic -> {
                            reconciliation.observedTopicFuture(updatedKafkaTopic);
                            return updatedKafkaTopic;
                        });
                    } else {
                        LOGGER.debug("{}: No need to update KafkaTopic {}", logContext, kubeDiff);
                        resourceFuture = Future.succeededFuture();
                    }
                    reconciliationResultHandler = resourceFuture
                        .compose(updatedKafkaTopic -> {
                            Future<Void> configFuture;
                            TopicDiff kafkaDiff = TopicDiff.diff(kafkaTopic, result);
                            if (merged.changesConfig()
                                    && !kafkaDiff.isEmpty()) {
                                configFuture = Future.future();
                                LOGGER.debug("{}: Updating kafka config with {}", logContext, kafkaDiff);
                                enqueue(new UpdateKafkaConfig(logContext, result, involvedObject, configFuture));
                            } else {
                                LOGGER.debug("{}: No need to update kafka topic with {}", logContext, kafkaDiff);
                                configFuture = Future.succeededFuture();
                            }
                            return configFuture;
                        })
                        .compose(ignored -> {
                            if (partitionsDelta > 0
                                    // Kafka throws an error if we attempt a noop change #partitions
                                    && result.getNumPartitions() > kafkaTopic.getNumPartitions()) {
                                Future<Void> partitionsFuture = Future.future();
                                enqueue(new IncreaseKafkaPartitions(logContext, result, involvedObject, partitionsFuture));
                                return partitionsFuture;
                            } else {
                                return Future.succeededFuture();
                            }
                        }).compose(ignored -> {
                            Future<Void> topicStoreFuture = Future.future();
                            enqueue(new UpdateInTopicStore(logContext, result, involvedObject, topicStoreFuture));
                            return topicStoreFuture;
                        });
                }
            }
        }
        return reconciliationResultHandler;
    }

    void enqueue(Handler<Void> event) {
        LOGGER.debug("Enqueuing event {}", event);
        vertx.runOnContext(event);
    }


    /** Called when a topic znode is deleted in ZK */
    Future<Void> onTopicDeleted(LogContext logContext, TopicName topicName) {
        return executeWithTopicLockHeld(logContext, topicName,
            new Reconciliation("onTopicDeleted") {
                @Override
                public Future<Void> execute() {
                    return reconcileOnTopicChange(logContext, topicName, null, this);
                }
            });
    }

    private Map<String, Long> statusUpdateGeneration = new HashMap<>();

    /**
     * Called when ZK watch notifies of change to topic's config
     */
    Future<Void> onTopicConfigChanged(LogContext logContext, TopicName topicName) {
        return executeWithTopicLockHeld(logContext, topicName,
                new Reconciliation("onTopicConfigChanged") {
                    @Override
                    public Future<Void> execute() {
                        return kafka.topicMetadata(topicName)
                                .compose(metadata -> {
                                    Topic topic = TopicSerialization.fromTopicMetadata(metadata);
                                    return reconcileOnTopicChange(logContext, topicName, topic, this);
                                });
                    }
                });
    }

    /**
     * Called when ZK watch notifies of a change to the topic's partitions
     */
    Future<Void> onTopicPartitionsChanged(LogContext logContext, TopicName topicName) {
        Reconciliation action = new Reconciliation("onTopicPartitionsChanged") {
            @Override
            public Future<Void> execute() {
                Reconciliation self = this;
                Future<Void> fut = Future.future();
                // getting topic information from the private store
                topicStore.read(topicName).setHandler(topicResult -> {

                    TopicMetadataHandler handler = new TopicMetadataHandler(vertx, kafka, topicName, topicMetadataBackOff()) {
                        @Override
                        public void handle(AsyncResult<TopicMetadata> metadataResult) {
                            try {
                                if (metadataResult.succeeded()) {
                                    // getting topic metadata from Kafka
                                    Topic kafkaTopic = TopicSerialization.fromTopicMetadata(metadataResult.result());

                                    // if partitions aren't changed on Kafka yet, we retry with exponential backoff
                                    if (topicResult.result().getNumPartitions() == kafkaTopic.getNumPartitions()) {
                                        retry();
                                    } else {
                                        LOGGER.info("Topic {} partitions changed to {}", topicName, kafkaTopic.getNumPartitions());
                                        reconcileOnTopicChange(logContext, topicName, kafkaTopic, self)
                                            .setHandler(fut);
                                    }

                                } else {
                                    fut.fail(metadataResult.cause());
                                }
                            } catch (Throwable t) {
                                fut.fail(t);
                            }
                        }

                        @Override
                        public void onMaxAttemptsExceeded(MaxAttemptsExceededException e) {
                            // it's possible that the watched znode for partitions changes, is changed
                            // due to a reassignment if we don't observe a partition count change within the backoff
                            // no need for failing the future in this case
                            fut.complete();
                        }
                    };
                    kafka.topicMetadata(topicName).setHandler(handler);
                });
                return fut;
            }
        };
        return executeWithTopicLockHeld(logContext, topicName, action);
    }

    /**
     * Called when one of the ZK watches notifies of a change to the topic
     */
    private Future<Void> reconcileOnTopicChange(LogContext logContext, TopicName topicName, Topic kafkaTopic,
                                                Reconciliation reconciliation) {
        // Look up the private topic to discover the name of kube KafkaTopic
        return topicStore.read(topicName)
            .compose(storeTopic -> {
                ResourceName resourceName = storeTopic != null ? storeTopic.getResourceName() : topicName.asKubeName();
                return k8s.getFromName(resourceName).compose(topic -> {
                    reconciliation.observedTopicFuture(kafkaTopic != null ? topic : null);
                    Topic k8sTopic = TopicSerialization.fromTopicResource(topic);
                    return reconcile(reconciliation, logContext.withKubeTopic(topic), topic, k8sTopic, kafkaTopic, storeTopic);
                });
            });
    }

    /** Called when a topic znode is created in ZK */
    Future<Void> onTopicCreated(LogContext logContext, TopicName topicName) {
        // XXX currently runs on the ZK thread, requiring a synchronized inFlight
        // is it better to put this check in the topic deleted event?
        Reconciliation action = new Reconciliation("onTopicCreated") {
            @Override
            public Future<Void> execute() {
                Reconciliation self = this;
                Future<Void> fut = Future.future();
                TopicMetadataHandler handler = new TopicMetadataHandler(vertx, kafka, topicName, topicMetadataBackOff()) {

                    @Override
                    public void handle(AsyncResult<TopicMetadata> metadataResult) {

                        if (metadataResult.succeeded()) {
                            if (metadataResult.result() == null) {
                                // In this case it is most likely that we've been notified by ZK
                                // before Kafka has finished creating the topic, so we retry
                                // with exponential backoff.
                                retry();
                            } else {
                                // We now have the metadata we need to create the
                                // resource...
                                Topic kafkaTopic = TopicSerialization.fromTopicMetadata(metadataResult.result());
                                reconcileOnTopicChange(logContext, topicName, kafkaTopic, self)
                                        .setHandler(fut);
                            }
                        } else {
                            fut.fail(metadataResult.cause());
                        }
                    }

                    @Override
                    public void onMaxAttemptsExceeded(MaxAttemptsExceededException e) {
                        fut.fail(e);
                    }
                };
                kafka.topicMetadata(topicName).setHandler(handler);
                return fut;
            }
        };
        return executeWithTopicLockHeld(logContext, topicName, action);
    }

    abstract class Reconciliation {
        private final String name;
        public AsyncResult<Void> result;
        public volatile KafkaTopic topic;

        public Reconciliation(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        public abstract Future<Void> execute();

        protected void observedTopicFuture(KafkaTopic observedTopic) {
            topic = observedTopic;
        }

        private Future<Void> updateStatus(LogContext logContext) {
            try {
                KafkaTopic topic = this.topic;
                Future<Void> statusFuture;
                if (topic != null) {
                    // Get the existing status and if it's og == g and is has same status then don't update
                    LOGGER.debug("{}: There is a KafkaTopic to set status on, rv={}, generation={}",
                            logContext,
                            topic.getMetadata().getResourceVersion(),
                            topic.getMetadata().getGeneration());
                    KafkaTopicStatus kts = new KafkaTopicStatus();
                    StatusUtils.setStatusConditionAndObservedGeneration(topic, kts, result);

                    StatusDiff ksDiff = new StatusDiff(topic.getStatus(), kts);
                    if (!ksDiff.isEmpty()) {
                        statusFuture = Future.future();
                        k8s.updateResourceStatus(new KafkaTopicBuilder(topic).withStatus(kts).build()).setHandler(ar -> {
                            if (ar.succeeded() && ar.result() != null) {
                                ObjectMeta metadata = ar.result().getMetadata();
                                LOGGER.debug("{}: status was set rv={}, generation={}, observedGeneration={}",
                                        logContext,
                                        metadata.getResourceVersion(),
                                        metadata.getGeneration(),
                                        ar.result().getStatus().getObservedGeneration());
                                statusUpdateGeneration.put(
                                        metadata.getName(),
                                        metadata.getGeneration());
                            } else {
                                LOGGER.error("{}: Error setting resource status", logContext, ar.cause());
                            }
                            statusFuture.handle(ar.map((Void) null));
                        });
                    } else {
                        statusFuture = Future.succeededFuture();
                    }
                } else {
                    LOGGER.debug("{}: No KafkaTopic to set status", logContext);
                    statusFuture = Future.succeededFuture();
                }
                return result.failed() ? Future.failedFuture(result.cause()) : statusFuture;
            } catch (Throwable t) {
                LOGGER.error("{}", logContext, t);
                return Future.failedFuture(t);
            }
        }
    }

    /** Called when a resource is isModify in k8s */
    Future<Void> onResourceEvent(LogContext logContext, KafkaTopic modifiedTopic, Watcher.Action action) {
        return executeWithTopicLockHeld(logContext, new TopicName(modifiedTopic),
                new Reconciliation("onResourceEvent") {
                    @Override
                    public Future<Void> execute() {
                        return k8s.getFromName(new ResourceName(modifiedTopic))
                            .compose(mt ->  {
                                final Topic k8sTopic;
                                if (mt != null) {
                                    observedTopicFuture(mt);
                                    Long generation = statusUpdateGeneration.get(mt.getMetadata().getName());
                                    LOGGER.debug("{}: last updated generation={}", logContext, generation);
                                    if (mt.getMetadata() != null
                                            && mt.getMetadata().getGeneration() != null) {
                                        if (mt.getMetadata().getGeneration().equals(generation)) {
                                            // TODO we might also need some way to avoid statusUpdateGeneration getting too big
                                            // e.g. remove after 10 seconds, for example
                                            // Or do we not care and maintain this map to avoid unnecessary work always
                                            // It doesn't scale to many topics so well, but maybe that's not such a huge problem.
                                            LOGGER.debug("{}: Ignoring modification event caused by my own status update on {}",
                                                    logContext,
                                                    mt.getMetadata().getName());
                                            return Future.succeededFuture();
                                        } else {
                                            LOGGER.debug("{}: modifiedTopic.getMetadata().getGeneration()={}",
                                                    logContext, mt.getMetadata().getGeneration());
                                        }
                                    } else {
                                        LOGGER.debug("{}: modifiedTopic.getMetadata().getGeneration()=null", logContext);
                                    }

                                    try {
                                        k8sTopic = TopicSerialization.fromTopicResource(mt);
                                    } catch (InvalidTopicException e) {
                                        return Future.failedFuture(e);
                                    }
                                } else {
                                    observedTopicFuture(null);
                                    k8sTopic = null;
                                }
                                return reconcileOnResourceChange(this, logContext, mt != null ? mt : modifiedTopic, k8sTopic, action == Watcher.Action.MODIFIED);
                            });
                    }
                });
    }

    private Future<Void> reconcileOnResourceChange(Reconciliation reconciliation, LogContext logContext, KafkaTopic topicResource, Topic k8sTopic,
                                           boolean isModify) {
        TopicName topicName = new TopicName(topicResource);
        return CompositeFuture.all(getFromKafka(topicName), getFromTopicStore(topicName))
            .compose(compositeResult -> {
                Topic kafkaTopic = compositeResult.resultAt(0);
                Topic privateTopic = compositeResult.resultAt(1);
                Future<Void> result;
                if (kafkaTopic == null
                    && privateTopic == null
                    && isModify
                    && topicResource.getMetadata().getDeletionTimestamp() != null) {
                    // When processing a Kafka-side deletion then when we delete the KT
                    // We first receive a modify event (setting deletionTimestamp etc)
                    // then the deleted event. We need to ignore the modify event.
                    LOGGER.debug("Ignoring pre-delete modify event");
                    reconciliation.observedTopicFuture(null);
                    result = Future.succeededFuture();
                } else if (privateTopic == null
                        && isModify) {
                    result = Future.future();
                    enqueue(new Event(topicResource,
                            "Kafka topics cannot be renamed, but KafkaTopic's spec.topicName has changed.",
                            EventType.WARNING, result));
                } else {
                    result = reconcile(reconciliation, logContext, topicResource, k8sTopic, kafkaTopic, privateTopic);
                }
                return result;
            });
    }

    private class UpdateInTopicStore implements Handler<Void> {
        private final Topic topic;
        private final HasMetadata involvedObject;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        public UpdateInTopicStore(LogContext logContext, Topic topic, HasMetadata involvedObject, Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            topicStore.update(topic).setHandler(ar -> {
                if (ar.failed()) {
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                }
                handler.handle(ar);
            });
        }

        @Override
        public String toString() {
            return "UpdateInTopicStore(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }

    private Future<Void> createInTopicStore(LogContext logContext, Topic topic, HasMetadata involvedObject) {
        Future<Void> result = Future.future();
        enqueue(new CreateInTopicStore(logContext, topic, involvedObject, result));
        return result;
    }

    class CreateInTopicStore implements Handler<Void> {
        private final Topic topic;
        private final HasMetadata involvedObject;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        private CreateInTopicStore(LogContext logContext, Topic topic, HasMetadata involvedObject,
                                   Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topic = topic;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            LOGGER.debug("Executing {}", this);
            topicStore.create(topic).setHandler(ar -> {
                LOGGER.debug("Completing {}", this);
                if (ar.failed()) {
                    LOGGER.debug("{} failed", this);
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                } else {
                    LOGGER.debug("{} succeeded", this);
                }
                handler.handle(ar);
            });
        }

        @Override
        public String toString() {
            return "CreateInTopicStore(topicName=" + topic.getTopicName() + ",ctx=" + logContext + ")";
        }
    }


    private Future<Void> deleteFromTopicStore(LogContext logContext, HasMetadata involvedObject, TopicName topicName) {
        Future<Void> reconciliationResultHandler = Future.future();
        enqueue(new DeleteFromTopicStore(logContext, topicName, involvedObject, reconciliationResultHandler));
        return reconciliationResultHandler;
    }

    class DeleteFromTopicStore implements Handler<Void> {
        private final TopicName topicName;
        private final HasMetadata involvedObject;
        private final Handler<AsyncResult<Void>> handler;
        private final LogContext logContext;

        private DeleteFromTopicStore(LogContext logContext, TopicName topicName, HasMetadata involvedObject,
                                     Handler<AsyncResult<Void>> handler) {
            this.logContext = logContext;
            this.topicName = topicName;
            this.involvedObject = involvedObject;
            this.handler = handler;
        }

        @Override
        public void handle(Void v) throws OperatorException {
            topicStore.delete(topicName).setHandler(ar -> {
                if (ar.failed()) {
                    enqueue(new Event(involvedObject, ar.cause().toString(), EventType.WARNING, eventResult -> { }));
                }
                handler.handle(ar);
            });
        }

        @Override
        public String toString() {
            return "DeleteFromTopicStore(topicName=" + topicName + ",ctx=" + logContext + ")";
        }
    }

    public boolean isWorkInflight() {
        LOGGER.debug("Outstanding: {}", inflight);
        return inflight.size() > 0;
    }

    /**
     * @return a new instance of BackOff with configured topic metadata max attempts
     */
    private BackOff topicMetadataBackOff() {
        return new BackOff(config.get(Config.TOPIC_METADATA_MAX_ATTEMPTS));
    }

    /**
     * @param resource Resource instance to log
     * @return Resource representation as namespace/name for logging purposes
     */
    static String logTopic(HasMetadata resource) {
        return resource != null ? resource.getMetadata().getNamespace() + "/" + resource.getMetadata().getName() : null;
    }


    static class ReconcileState {
        private final Set<TopicName> succeeded;
        private final Set<TopicName> undetermined;
        private final Map<TopicName, Throwable> failed;
        private List<KafkaTopic> ktList;

        public ReconcileState(Set<TopicName> succeeded, Set<TopicName> undetermined, Map<TopicName, Throwable> failed) {
            this.succeeded = succeeded;
            this.undetermined = undetermined;
            this.failed = failed;
        }

        public void addKafkaTopics(List<KafkaTopic> ktList) {
            this.ktList = ktList;
        }
    }

    Future<?> reconcileAllTopics(String reconciliationType) {
        LOGGER.info("Starting {} reconciliation", reconciliationType);
        Future<Set<String>> listFut = Future.future();
        kafka.listTopics().setHandler(listFut);
        return listFut.recover(ex -> Future.failedFuture(
                new OperatorException("Error listing existing topics during " + reconciliationType + " reconciliation", ex)
        )).compose(topicNamesFromKafka ->
                // Reconcile the topic found in Kafka
                reconcileFromKafka(reconciliationType, topicNamesFromKafka.stream().map(TopicName::new).collect(Collectors.toList()))

        ).compose(reconcileState -> {
            Future<List<KafkaTopic>> ktFut = k8s.listResources();
            return ktFut.recover(ex -> Future.failedFuture(
                    new OperatorException("Error listing existing KafkaTopics during " + reconciliationType + " reconciliation", ex)
            )).map(ktList -> {
                reconcileState.addKafkaTopics(ktList);
                return reconcileState;
            });
        }).compose(reconcileState -> {
            List<Future> futs = new ArrayList<>();
            for (KafkaTopic kt : reconcileState.ktList) {
                LogContext logContext = LogContext.periodic(reconciliationType + "kube " + kt.getMetadata().getName()).withKubeTopic(kt);
                Topic topic = TopicSerialization.fromTopicResource(kt);
                TopicName topicName = topic.getTopicName();
                if (reconcileState.failed.containsKey(topicName)) {
                    // we already failed to reconcile this topic in reconcileFromKafka(), /
                    // don't bother trying again
                    LOGGER.trace("{}: Already failed to reconcile {}", logContext, topicName);
                } else if (reconcileState.succeeded.contains(topicName)) {
                    // we already succeeded in reconciling this topic in reconcileFromKafka()
                    LOGGER.trace("{}: Already successfully reconciled {}", logContext, topicName);
                } else if (reconcileState.undetermined.contains(topicName)) {
                    // The topic didn't exist in topicStore, but now we know which KT it corresponds to
                    futs.add(reconcileWithKubeTopic(logContext, kt, reconciliationType, new ResourceName(kt), topic.getTopicName()).compose(r -> {
                        // if success then remove from undetermined add to success
                        reconcileState.undetermined.remove(topicName);
                        reconcileState.succeeded.add(topicName);
                        return Future.succeededFuture(Boolean.TRUE);
                    }));
                } else {
                    // Topic exists in kube, but not in Kafka
                    LOGGER.debug("{}: Topic {} exists in Kafka, but not Kubernetes", logContext, topicName, logTopic(kt));
                    futs.add(reconcileWithKubeTopic(logContext, kt, reconciliationType, new ResourceName(kt), topic.getTopicName()).compose(r -> {
                        // if success then add to success
                        reconcileState.succeeded.add(topicName);
                        return Future.succeededFuture(Boolean.TRUE);
                    }));
                }
            }
            return CompositeFuture.join(futs).compose(joined -> {
                List<Future> futs2 = new ArrayList<>();
                for (Throwable exception : reconcileState.failed.values()) {
                    futs2.add(Future.failedFuture(exception));
                }
                // anything left in undetermined doesn't exist in topic store nor kube
                for (TopicName tn : reconcileState.undetermined) {
                    LogContext logContext = LogContext.periodic(reconciliationType + "-" + tn);
                    futs2.add(executeWithTopicLockHeld(logContext, tn, new Reconciliation("delete-remaining") {
                        @Override
                        public Future<Void> execute() {
                            observedTopicFuture(null);
                            return getKafkaAndReconcile(this, logContext, tn, null, null);
                        }
                    }));
                }
                return CompositeFuture.join(futs2);
            });
        });
    }


    /**
     * Reconcile all the topics in {@code foundFromKafka}, returning a ReconciliationState.
     */
    private Future<ReconcileState> reconcileFromKafka(String reconciliationType, List<TopicName> topicsFromKafka) {
        Set<TopicName> succeeded = new HashSet<>();
        Set<TopicName> undetermined = new HashSet<>();
        Map<TopicName, Throwable> failed = new HashMap<>();

        LOGGER.debug("Reconciling kafka topics {}", topicsFromKafka);

        final ReconcileState state = new ReconcileState(succeeded, undetermined, failed);
        if (topicsFromKafka.size() > 0) {
            List<Future<Void>> futures = new ArrayList<>();
            for (TopicName topicName : topicsFromKafka) {
                LogContext logContext = LogContext.periodic(reconciliationType + "kafka " + topicName);
                futures.add(executeWithTopicLockHeld(logContext, topicName, new Reconciliation("reconcile-from-kafka") {
                    @Override
                    public Future<Void> execute() {
                        return getFromTopicStore(topicName).recover(error -> {
                            failed.put(topicName,
                                    new OperatorException("Error getting KafkaTopic " + topicName + " during "
                                            + reconciliationType + " reconciliation", error));
                            return Future.succeededFuture();
                        }).compose(topic -> {
                            if (topic == null) {
                                undetermined.add(topicName);
                                return Future.succeededFuture();
                            } else {
                                LOGGER.debug("{}: Have private topic for topic {} in Kafka", logContext, topicName);
                                return reconcileWithPrivateTopic(logContext, topicName, topic, this)
                                    .otherwise(error -> {
                                        failed.put(topicName, error);
                                        return null;
                                    })
                                    .map(ignored -> {
                                        succeeded.add(topicName);
                                        return null;
                                    });
                            }
                        });
                    }
                }));
            }
            return join(futures).map(state);
        } else {
            return Future.succeededFuture(state);
        }


    }

    @SuppressWarnings("unchecked")
    private static <T> CompositeFuture join(List<T> futures) {
        return CompositeFuture.join((List) futures);
    }


    /**
     * Reconcile the given topic which has the given {@code privateTopic} in the topic store.
     */
    private Future<Void> reconcileWithPrivateTopic(LogContext logContext, TopicName topicName,
                                                   Topic privateTopic,
                                                   Reconciliation reconciliation) {
        return k8s.getFromName(privateTopic.getResourceName())
            .compose(kafkaTopicResource -> {
                reconciliation.observedTopicFuture(kafkaTopicResource);
                return getKafkaAndReconcile(reconciliation, logContext, topicName, privateTopic, kafkaTopicResource);
            })
            .recover(error -> {
                LOGGER.error("{}: Error getting KafkaTopic {} for topic {}",
                        logContext,
                        topicName.asKubeName(), topicName, error);
                return Future.failedFuture(new OperatorException("Error getting KafkaTopic " + topicName.asKubeName() + " during " + logContext.trigger() + " reconciliation", error));
            });
    }

    private Future<Void> getKafkaAndReconcile(Reconciliation reconciliation, LogContext logContext, TopicName topicName,
                                              Topic privateTopic, KafkaTopic kafkaTopicResource) {
        logContext.withKubeTopic(kafkaTopicResource);
        Future<Void> topicFuture = Future.future();
        try {
            Topic k8sTopic = kafkaTopicResource != null ? TopicSerialization.fromTopicResource(kafkaTopicResource) : null;
            kafka.topicMetadata(topicName)
                .compose(kafkaTopicMeta -> {
                    Topic topicFromKafka = TopicSerialization.fromTopicMetadata(kafkaTopicMeta);
                    return reconcile(reconciliation, logContext, kafkaTopicResource, k8sTopic, topicFromKafka, privateTopic);
                })
                .setHandler(ar -> {
                    if (ar.failed()) {
                        LOGGER.error("Error reconciling KafkaTopic {}", logTopic(kafkaTopicResource), ar.cause());
                    } else {
                        LOGGER.info("Success reconciling KafkaTopic {}", logTopic(kafkaTopicResource));
                    }
                    topicFuture.handle(ar);
                });
        } catch (InvalidTopicException e) {
            LOGGER.error("Error reconciling KafkaTopic {}: Invalid resource: ", logTopic(kafkaTopicResource), e.getMessage());
            topicFuture.fail(e);
        } catch (OperatorException e) {
            LOGGER.error("Error reconciling KafkaTopic {}", logTopic(kafkaTopicResource), e);
            topicFuture.fail(e);
        }
        return topicFuture;
    }

    Future<Topic> getFromKafka(TopicName topicName) {
        return kafka.topicMetadata(topicName).map(TopicSerialization::fromTopicMetadata);
    }

    Future<Topic> getFromTopicStore(TopicName topicName) {
        return topicStore.read(topicName);
    }

    private Future<Void> reconcileWithKubeTopic(LogContext logContext, HasMetadata involvedObject,
                                                String reconciliationType, ResourceName kubeName, TopicName topicName) {
        return executeWithTopicLockHeld(logContext, topicName, new Reconciliation("reconcile-with-kube") {
            @Override
            public Future<Void> execute() {
                Reconciliation self = this;
                return CompositeFuture.all(
                        k8s.getFromName(kubeName).map(kt -> {
                            observedTopicFuture(kt);
                            return kt;
                        }),
                        getFromKafka(topicName),
                        getFromTopicStore(topicName))
                    .compose(compositeResult -> {
                        KafkaTopic ktr = compositeResult.resultAt(0);
                        logContext.withKubeTopic(ktr);
                        Topic k8sTopic = TopicSerialization.fromTopicResource(ktr);
                        Topic kafkaTopic = compositeResult.resultAt(1);
                        Topic privateTopic = compositeResult.resultAt(2);
                        return reconcile(self, logContext, involvedObject, k8sTopic, kafkaTopic, privateTopic);
                    });
            }
        });
    }

}


/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class K8sImpl implements K8s {

    private final static Logger LOGGER = LogManager.getLogger(K8sImpl.class);

    private final Labels labels;
    private final String namespace;

    private final KubernetesClient client;
    private final CrdOperator<KubernetesClient, KafkaTopic, KafkaTopicList, DoneableKafkaTopic> crdOperator;

    private final Vertx vertx;

    public K8sImpl(Vertx vertx, KubernetesClient client, Labels labels, String namespace) {
        this.vertx = vertx;
        this.client = client;
        this.crdOperator = new CrdOperator<>(vertx, client, KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class);
        this.labels = labels;
        this.namespace = namespace;
    }

    @Override
    public Future<KafkaTopic> createResource(KafkaTopic topicResource) {
        Future<KafkaTopic> handler = Future.future();
        vertx.executeBlocking(future -> {
            try {
                KafkaTopic kafkaTopic = operation().inNamespace(namespace).create(topicResource);
                LOGGER.debug("KafkaTopic {} created with version {}->{}",
                        kafkaTopic.getMetadata().getName(),
                        topicResource.getMetadata() != null ? topicResource.getMetadata().getResourceVersion() : null,
                        kafkaTopic.getMetadata().getResourceVersion());
                future.complete(kafkaTopic);
            } catch (Exception e) {
                future.fail(e);
            }
        }, handler);
        return handler;
    }

    @Override
    public Future<KafkaTopic> updateResource(KafkaTopic topicResource) {
        Future<KafkaTopic> handler = Future.future();
        vertx.executeBlocking(future -> {
            try {
                KafkaTopic kafkaTopic = operation().inNamespace(namespace).withName(topicResource.getMetadata().getName()).patch(topicResource);
                LOGGER.debug("KafkaTopic {} updated with version {}->{}",
                        kafkaTopic.getMetadata().getName(),
                        topicResource.getMetadata() != null ? topicResource.getMetadata().getResourceVersion() : null,
                        kafkaTopic.getMetadata().getResourceVersion());
                future.complete(kafkaTopic);
            } catch (Exception e) {
                future.fail(e);
            }
        }, handler);
        return handler;
    }

    @Override
    public Future<KafkaTopic> updateResourceStatus(KafkaTopic topicResource) {
        return crdOperator.updateStatusAsync(topicResource);
    }

    @Override
    public Future<Void> deleteResource(ResourceName resourceName) {
        Future<Void> handler = Future.future();
        vertx.executeBlocking(future -> {
            try {
                // Delete the resource by the topic name, because neither ZK nor Kafka know the resource name
                operation().inNamespace(namespace).withName(resourceName.toString()).delete();
                LOGGER.debug("KafkaTopic {} deleted", resourceName.toString());
                future.complete();
            } catch (Exception e) {
                future.fail(e);
            }
        }, handler);
        return handler;
    }

    private MixedOperation<KafkaTopic, KafkaTopicList, DoneableKafkaTopic, Resource<KafkaTopic, DoneableKafkaTopic>> operation() {
        return client.customResources(Crds.topic(), KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class);
    }

    @Override
    public Future<List<KafkaTopic>> listResources() {
        return crdOperator.listAsync(namespace, io.strimzi.operator.common.model.Labels.fromMap(labels.labels()));
    }

    @Override
    public Future<KafkaTopic> getFromName(ResourceName resourceName) {
        return crdOperator.getAsync(namespace, resourceName.toString());
    }

    /**
     * Create the given k8s event
     */
    @Override
    public Future<Void> createEvent(Event event) {
        Future<Void> handler = Future.future();
        vertx.executeBlocking(future -> {
            try {
                try {
                    LOGGER.debug("Creating event {}", event);
                    client.events().inNamespace(namespace).create(event);
                } catch (KubernetesClientException e) {
                    LOGGER.error("Error creating event {}", event, e);
                }
                future.complete();
            } catch (Exception e) {
                future.fail(e);
            }
        }, handler);
        return handler;
    }
}

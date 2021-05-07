/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.KafkaConnectS2IList;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnectS2IBuilder;
import io.strimzi.test.TestUtils;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The main purpose of the Integration Tests for the operators is to test them against a real Kubernetes cluster.
 * Real Kubernetes cluster has often some quirks such as some fields being immutable, some fields in the spec section
 * being created by the Kubernetes API etc. These things are hard to test with mocks. These IT tests make it easy to
 * test them against real clusters.
 */
@ExtendWith(VertxExtension.class)
public class KafkaConnectS2ICrdOperatorIT extends AbstractCustomResourceOperatorIT<KubernetesClient, KafkaConnectS2I, KafkaConnectS2IList> {
    protected static final Logger log = LogManager.getLogger(KafkaConnectS2ICrdOperatorIT.class);

    @Override
    protected CrdOperator operator() {
        return new CrdOperator(vertx, client, KafkaConnectS2I.class, KafkaConnectS2IList.class, KafkaConnectS2I.RESOURCE_KIND);

    }

    @Override
    protected String getCrd() {
        return TestUtils.CRD_KAFKA_CONNECT_S2I;
    }

    @Override
    protected String getCrdName() {
        return KafkaConnectS2I.CRD_NAME;
    }

    @Override
    protected String getNamespace() {
        return "kafka-connects2i-crd-it-namespace";
    }

    @Override
    protected KafkaConnectS2I getResource(String resourceName) {
        return new KafkaConnectS2IBuilder()
                .withNewMetadata()
                    .withName(resourceName)
                    .withNamespace(getNamespace())
                .endMetadata()
                .withNewSpec()
                    .withNewBootstrapServers("localhost:9092")
                .endSpec()
                .withNewStatus()
                .endStatus()
                .build();
    }

    @Override
    protected KafkaConnectS2I getResourceWithModifications(KafkaConnectS2I resourceInCluster) {
        return new KafkaConnectS2IBuilder(resourceInCluster)
                .editSpec()
                    .withNewLivenessProbe()
                        .withInitialDelaySeconds(14)
                    .endLivenessProbe()
                .endSpec()
                .build();

    }

    @Override
    protected KafkaConnectS2I getResourceWithNewReadyStatus(KafkaConnectS2I resourceInCluster) {
        return new KafkaConnectS2IBuilder(resourceInCluster)
                .withNewStatus()
                    .withConditions(READY_CONDITION)
                .endStatus()
                .build();
    }

    @Override
    protected void assertReady(VertxTestContext context, KafkaConnectS2I resource) {
        context.verify(() -> assertThat(resource.getStatus()
                .getConditions()
                .get(0), is(READY_CONDITION)));
    }
}
/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.ResourceType;
import io.strimzi.operator.common.operator.MockCertManager;
import io.strimzi.test.TestUtils;
import io.strimzi.test.mockkube.MockKube;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.StringReader;
import java.util.Collections;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

@RunWith(VertxUnitRunner.class)
public class KafkaConnectAssemblyOperatorMockTest {

    private static final Logger LOGGER = LogManager.getLogger(KafkaConnectAssemblyOperatorMockTest.class);

    private static final KafkaVersion.Lookup VERSIONS = new KafkaVersion.Lookup(new StringReader(
            "2.0.0 default 2.0 2.0 1234567890abcdef 2.0.x"),
            emptyMap(), singletonMap("2.0.0", "strimzi/kafka-connect:latest-kafka-2.0.0"), emptyMap(), emptyMap()) { };

    private static final String NAMESPACE = "my-namespace";
    private static final String CLUSTER_NAME = "my-connect-cluster";

    private final int replicas = 3;

    private KubernetesClient mockClient;

    private Vertx vertx;
    private KafkaConnect cluster;

    @Before
    public void before() {
        this.vertx = Vertx.vertx();

        this.cluster = new KafkaConnectBuilder()
                .withMetadata(new ObjectMetaBuilder()
                    .withName(CLUSTER_NAME)
                    .withNamespace(NAMESPACE)
                    .withLabels(Labels.userLabels(TestUtils.map("foo", "bar")).toMap())
                    .build())
                .withNewSpec()
                    .withReplicas(replicas)
                .endSpec()
            .build();
        mockClient = new MockKube().withCustomResourceDefinition(Crds.kafkaConnect(), KafkaConnect.class, KafkaConnectList.class, DoneableKafkaConnect.class)
                .withInitialInstances(Collections.singleton(cluster)).end().build();
    }

    @After
    public void after() {
        this.vertx.close();
    }

    private KafkaConnectAssemblyOperator createConnectCluster(TestContext context) {
        PlatformFeaturesAvailability pfa = new PlatformFeaturesAvailability(true, KubernetesVersion.V1_9);
        ResourceOperatorSupplier supplier = new ResourceOperatorSupplier(this.vertx, this.mockClient, pfa, 60_000L);
        ClusterOperatorConfig config = ResourceUtils.dummyClusterOperatorConfig(VERSIONS);
        KafkaConnectAssemblyOperator kco = new KafkaConnectAssemblyOperator(vertx, pfa,
                new MockCertManager(),
                supplier,
                config);

        LOGGER.info("Reconciling initially -> create");
        Async createAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", ResourceType.CONNECT, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            context.assertNotNull(mockClient.apps().deployments().inNamespace(NAMESPACE).withName(KafkaConnectResources.deploymentName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.configMaps().inNamespace(NAMESPACE).withName(KafkaConnectResources.metricsAndLogConfigMapName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.services().inNamespace(NAMESPACE).withName(KafkaConnectResources.serviceName(CLUSTER_NAME)).get());
            context.assertNotNull(mockClient.policy().podDisruptionBudget().inNamespace(NAMESPACE).withName(KafkaConnectResources.deploymentName(CLUSTER_NAME)).get());
            createAsync.complete();
        });
        createAsync.await();
        return kco;
    }

    /** Create a cluster from a Kafka Cluster CM */
    @Test
    public void testCreateUpdate(TestContext context) {
        KafkaConnectAssemblyOperator kco = createConnectCluster(context);
        LOGGER.info("Reconciling again -> update");
        Async updateAsync = context.async();
        kco.reconcileAssembly(new Reconciliation("test-trigger", ResourceType.CONNECT, NAMESPACE, CLUSTER_NAME), ar -> {
            if (ar.failed()) ar.cause().printStackTrace();
            context.assertTrue(ar.succeeded());
            updateAsync.complete();
        });
        updateAsync.await();
    }
}

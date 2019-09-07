/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClusterException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertTrue;

/**
 * The purpose of this test is to confirm that we can create a
 * resource from the POJOs, serialize it and create the resource in K8S.
 * I.e. that such instance resources obtained from POJOs are valid according to the schema
 * validation done by K8S.
 */
public class KafkaCrdIT extends AbstractCrdIT {
    public static final String NAMESPACE = "kafkacrd-it";

    @Test
    void testKafkaV1alpha1() {
        assumeKube1_11Plus();
        createDelete(Kafka.class, "KafkaV1alpha1.yaml");
    }

    @Test
    void testKafkaV1Beta1() {
        createDelete(Kafka.class, "KafkaV1beta1.yaml");
    }

    @Test
    void testKafkaMinimal() {
        createDelete(Kafka.class, "Kafka-minimal.yaml");
    }

    @Test
    void testKafkaWithExtraProperty() {
        createDelete(Kafka.class, "Kafka-with-extra-property.yaml");
    }

    @Test
    void testKafkaWithMissingRequired() {
        try {
            createDelete(Kafka.class, "Kafka-with-missing-required-property.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.zookeeper in body is required"));
            assertTrue(e.getMessage().contains("spec.kafka in body is required"));
        }
    }

    @Test
    void testKafkaWithInvalidResourceMemory() {
        try {
            createDelete(Kafka.class, "Kafka-with-invalid-resource-memory.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.kafka.resources.limits.memory in body should match '[0-9]+([kKmMgGtTpPeE]i?)?$'"));
        }
    }

    @Test
    public void testKafkaWithEntityOperator() {
        createDelete(Kafka.class, "Kafka-with-entity-operator.yaml");
    }

    @Test
    public void testKafkaWithMaintenance() {
        createDelete(Kafka.class, "Kafka-with-maintenance.yaml");
    }

    @Test
    public void testKafkaWithNullMaintenance() {
        try {
            createDelete(Kafka.class, "Kafka-with-null-maintenance.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.maintenanceTimeWindows in body must be of type string: \"null\""));
        }
    }

    @Test
    public void testKafkaWithTemplate() {
        createDelete(Kafka.class, "Kafka-with-template.yaml");
    }

    @Test
    public void testKafkaWithTlsSidecarWithCustomConfiguration() {
        createDelete(Kafka.class, "Kafka-with-tls-sidecar-with-custom-configuration.yaml");
    }

    @Test
    public void testKafkaWithTlsSidecarWithInvalidLogLevel() {
        try {
            createDelete(Kafka.class, "Kafka-with-tls-sidecar-invalid-loglevel.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.kafka.tlsSidecar.logLevel in body should be one of [emerg alert crit err warning notice info debug]"));
        }
    }

    @Test
    public void testKafkaWithJbodStorage() {
        createDelete(Kafka.class, "Kafka-with-jbod-storage.yaml");
    }

    @Test
    public void testKafkaWithJbodStorageOnZookeeper() {
        try {
            createDelete(Kafka.class, "Kafka-with-jbod-storage-on-zookeeper.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.zookeeper.storage.type in body should be one of [ephemeral persistent-claim]"));
        }
    }

    @Test
    public void testKafkaWithInvalidStorage() {
        try {
            createDelete(Kafka.class, "Kafka-with-invalid-storage.yaml");
        } catch (KubeClusterException.InvalidResource e) {
            assertTrue(e.getMessage().contains("spec.kafka.storage.type in body should be one of [ephemeral persistent-claim jbod]"));
        }
    }

    @BeforeAll
    void setupEnvironment() {
        createNamespace(NAMESPACE);
        createCustomResources(TestUtils.CRD_KAFKA);
    }

    @AfterAll
    void teardownEnvironment() {
        deleteCustomResources();
        deleteNamespaces();
    }
}

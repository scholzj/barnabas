/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import java.time.Duration;

/**
 * Interface for keep global constants used across system tests.
 */
public interface Constants {
    long TIMEOUT_FOR_DEPLOYMENT_CONFIG_READINESS = Duration.ofMinutes(7).toMillis();
    long TIMEOUT_FOR_RESOURCE_CREATION = Duration.ofMinutes(5).toMillis();
    long TIMEOUT_FOR_SECRET_CREATION = Duration.ofMinutes(2).toMillis();
    long TIMEOUT_FOR_RESOURCE_READINESS = Duration.ofMinutes(12).toMillis();
    long TIMEOUT_FOR_CR_CREATION = Duration.ofMinutes(3).toMillis();
    long TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS = Duration.ofMinutes(7).toMillis();
    long TIMEOUT_FOR_MIRROR_JOIN_TO_GROUP = Duration.ofMinutes(2).toMillis();
    long TIMEOUT_FOR_TOPIC_CREATION = Duration.ofMinutes(1).toMillis();
    long POLL_INTERVAL_FOR_RESOURCE_CREATION = Duration.ofSeconds(3).toMillis();
    long POLL_INTERVAL_FOR_RESOURCE_READINESS = Duration.ofSeconds(1).toMillis();
    long WAIT_FOR_ROLLING_UPDATE_INTERVAL = Duration.ofSeconds(5).toMillis();
    long WAIT_FOR_ROLLING_UPDATE_TIMEOUT = Duration.ofMinutes(7).toMillis();

    long TIMEOUT_FOR_SEND_RECEIVE_MSG = Duration.ofSeconds(30).toMillis();
    long TIMEOUT_AVAILABILITY_TEST = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_SEND_MESSAGES = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_RECV_MESSAGES = Duration.ofMinutes(1).toMillis();

    long TIMEOUT_FOR_CLUSTER_STABLE = Duration.ofMinutes(20).toMillis();
    long TIMEOUT_FOR_ZK_CLUSTER_STABILIZATION = Duration.ofMinutes(7).toMillis();

    long GET_BROKER_API_TIMEOUT = Duration.ofMinutes(1).toMillis();
    long GET_BROKER_API_INTERVAL = Duration.ofSeconds(5).toMillis();
    long TIMEOUT_FOR_GET_SECRETS = Duration.ofMinutes(1).toMillis();
    long TIMEOUT_TEARDOWN = Duration.ofSeconds(10).toMillis();
    long GLOBAL_TIMEOUT = Duration.ofMinutes(5).toMillis();
    long GLOBAL_STATUS_TIMEOUT = Duration.ofMinutes(3).toMillis();
    long CONNECT_STATUS_TIMEOUT = Duration.ofMinutes(5).toMillis();
    long GLOBAL_POLL_INTERVAL = Duration.ofSeconds(1).toMillis();

    long CO_OPERATION_TIMEOUT_DEFAULT = TIMEOUT_FOR_RESOURCE_READINESS;
    long CO_OPERATION_TIMEOUT_SHORT = Duration.ofSeconds(30).toMillis();
    long CO_OPERATION_TIMEOUT_WAIT = CO_OPERATION_TIMEOUT_SHORT + Duration.ofSeconds(20).toMillis();
    long CO_OPERATION_TIMEOUT_POLL = Duration.ofSeconds(2).toMillis();

    String KAFKA_CLIENTS = "kafka-clients";
    String STRIMZI_DEPLOYMENT_NAME = "strimzi-cluster-operator";
    String IMAGE_PULL_POLICY = "Always";

    /**
     * Kafka Bridge JSON encoding with JSON embedded format
     */
    String KAFKA_BRIDGE_JSON_JSON = "application/vnd.kafka.json.v2+json";

    /**
     * Kafka Bridge JSON encoding
     */
    String KAFKA_BRIDGE_JSON = "application/vnd.kafka.v2+json";

    int HTTP_BRIDGE_DEFAULT_PORT = 8080;

    /**
     * Default value which allows execution of tests with any tags
     */
    String DEFAULT_TAG = "all";

    /**
     * Tag for acceptance tests, which are triggered for each push/pr/merge on travis-ci
     */
    String ACCEPTANCE = "acceptance";
    /**
     * Tag for regression tests which are stable.
     */
    String REGRESSION = "regression";
    /**
     * Tag for upgrade tests.
     */
    String UPGRADE = "upgrade";
    /**
     * Tag for acceptance tests executed during Travis builds.
     */
    String TRAVIS = "travis";
    /**
     * Tag for tests, which results are not 100% reliable on all testing environments.
     */
    String FLAKY = "flaky";
    /**
     * Tag for strimzi bridge tests.
     */
    String BRIDGE = "bridge";
    /**
     * Tag for scalability tests
     */
    String SCALABILITY = "scalability";
    /**
     * Tag for tests, which are working only on specific environment and we usually don't want to execute them on all environments.
     */
    String SPECIFIC = "specific";
    /**
     * Tag for tests, which are using NodePort.
     */
    String NODEPORT_SUPPORTED = "nodeport";
    /**
     * Tag for tests, which are using LoadBalancer.
     */
    String LOADBALANCER_SUPPORTED = "loadbalancer";
    /**
     * Tag for tests, which are using NetworkPolicies.
     */
    String NETWORKPOLICIES_SUPPORTED = "networkpolicies";
}

/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

/**
 * Encapsulates the naming scheme used for the resources which the Cluster Operator manages for a
 * {@code KafkaConnect} cluster.
 */
public class KafkaConnectResources {
    protected KafkaConnectResources() { }

    /**
     * Returns the name of the Kafka Connect {@code Deployment} for a {@code KafkaConnect} cluster of the given name.
     * @param clusterName  The {@code metadata.name} of the {@code KafkaConnect} resource.
     * @return The name of the corresponding Kafka Connect {@code Deployment}.
     */
    public static String deploymentName(String clusterName) {
        return clusterName + "-connect";
    }

    /**
     * Returns the name of the Kafka Connect {@code ServiceAccount} for a {@code KafkaConnect} cluster of the given name.
     * @param clusterName  The {@code metadata.name} of the {@code KafkaConnect} resource.
     * @return The name of the corresponding Kafka Connect {@code ServiceAccount}.
     */
    public static String serviceAccountName(String clusterName) {
        return deploymentName(clusterName);
    }

    /**
     * Returns the name of the HTTP REST {@code Service} for a {@code KafkaConnect} cluster of the given name.
     * @param clusterName  The {@code metadata.name} of the {@code KafkaConnect} resource.
     * @return The name of the corresponding {@code Service}.
     */
    public static String serviceName(String clusterName) {
        return clusterName + "-connect-api";
    }

    /**
     * Returns the name of the Kafka Connect metrics and log {@code ConfigMap} for a {@code KafkaConnect} cluster of the given name.
     * @param clusterName  The {@code metadata.name} of the {@code KafkaConnect} resource.
     * @return The name of the corresponding KafkaConnect metrics and log {@code ConfigMap}.
     */
    public static String metricsAndLogConfigMapName(String clusterName) {
        return clusterName + "-connect-config";
    }

    /**
     * Returns the URL of the Kafka Connect REST API for a {@code KafkaConnect} cluster of the given name.
     * @param clusterName  The {@code metadata.name} of the {@code KafkaConnect} resource.
     * @param namespace The namespace where {@code KafkaConnect} cluster is running.
     * @param port The port on which the {@code KafkaConnect} API is available.
     * @return The base URL of the {@code KafkaConnect} REST API.
     */
    public static String url(String clusterName, String namespace, int port) {
        return "http://" + serviceName(clusterName) + "." + namespace + ".svc:" + port;
    }
}

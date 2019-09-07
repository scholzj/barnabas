/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.systemtest.utils.StUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.NODEPORT_SUPPORTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(REGRESSION)
class AllNamespaceST extends AbstractNamespaceST {

    private static final Logger LOGGER = LogManager.getLogger(AllNamespaceST.class);
    private static final String THIRD_NAMESPACE = "third-namespace-test";
    private static Resources thirdNamespaceResources;

    /**
     * Test the case where the TO is configured to watch a different namespace that it is deployed in
     */
    @Test
    void testTopicOperatorWatchingOtherNamespace() {
        LOGGER.info("Deploying TO to watch a different namespace that it is deployed in");
        String previousNamespace = setNamespace(THIRD_NAMESPACE);
        List<String> topics = listTopicsUsingPodCLI(CLUSTER_NAME, 0);
        assertThat(topics, not(hasItems(TOPIC_NAME)));

        deployNewTopic(SECOND_NAMESPACE, THIRD_NAMESPACE, TOPIC_NAME);
        deleteNewTopic(SECOND_NAMESPACE, TOPIC_NAME);
        setNamespace(previousNamespace);
    }

    /**
     * Test the case when Kafka will be deployed in different namespace than CO
     */
    @Test
    @Tag(ACCEPTANCE)
    void testKafkaInDifferentNsThanClusterOperator() {
        LOGGER.info("Deploying Kafka cluster in different namespace than CO when CO watches all namespaces");
        checkKafkaInDiffNamespaceThanCO();
    }

    /**
     * Test the case when MirrorMaker will be deployed in different namespace than CO when CO watches all namespaces
     */
    @Test
    void testDeployMirrorMakerAcrossMultipleNamespace() {
        LOGGER.info("Deploying Kafka MirrorMaker in different namespace than CO when CO watches all namespaces");
        checkMirrorMakerForKafkaInDifNamespaceThanCO();
    }

    @Test
    void testDeployKafkaConnectInOtherNamespaceThanCO() {
        String previousNamespace = setNamespace(SECOND_NAMESPACE);
        // Deploy Kafka in other namespace than CO
        secondNamespaceResources.kafkaEphemeral(CLUSTER_NAME + "-second", 3).done();
        // Deploy Kafka Connect in other namespace than CO
        secondNamespaceResources.kafkaConnect(CLUSTER_NAME + "-second", 1).done();
        // Check that Kafka Connect was deployed
        StUtils.waitForDeploymentReady(kafkaConnectName(CLUSTER_NAME + "-second"), 1);
        setNamespace(previousNamespace);
    }

    @Test
    void testUOWatchingOtherNamespace() {
        String previousNamespace = setNamespace(SECOND_NAMESPACE);
        LOGGER.info("Creating user in other namespace than CO and Kafka cluster with UO");
        secondNamespaceResources.tlsUser(CLUSTER_NAME, USER_NAME).done();

        // Check that UO created a secret for new user
        cmdKubeClient().waitForResourceCreation("Secret", USER_NAME);
        setNamespace(previousNamespace);
    }

    @Test
    @Tag(NODEPORT_SUPPORTED)
    void testUserInDifferentNamespace() throws Exception {
        String startingNamespace = setNamespace(SECOND_NAMESPACE);
        secondNamespaceResources.tlsUser(CLUSTER_NAME, USER_NAME).done();

        StUtils.waitForSecretReady(USER_NAME);
        Condition kafkaCondition = secondNamespaceResources.kafkaUser().inNamespace(SECOND_NAMESPACE).withName(USER_NAME).get()
                .getStatus().getConditions().get(0);
        LOGGER.info("Kafka User condition status: {}", kafkaCondition.getStatus());
        LOGGER.info("Kafka User condition type: {}", kafkaCondition.getType());

        assertEquals("Ready", kafkaCondition.getType());

        List<Secret> secretsOfSecondNamespace = kubeClient(SECOND_NAMESPACE).listSecrets();

        for (Secret s : secretsOfSecondNamespace) {
            if (s.getMetadata().getName().equals(USER_NAME)) {
                LOGGER.info("Copying secret {} from namespace {} to namespace {}", s, SECOND_NAMESPACE, THIRD_NAMESPACE);
                copySecret(s, THIRD_NAMESPACE, USER_NAME);
            }
        }
        waitForClusterAvailabilityTls(USER_NAME, THIRD_NAMESPACE, CLUSTER_NAME);

        setNamespace(startingNamespace);
    }

    void copySecret(Secret sourceSecret, String targetNamespace, String targetName) {
        Secret s = new SecretBuilder(sourceSecret)
                    .withNewMetadata()
                        .withName(targetName)
                        .withNamespace(targetNamespace)
                    .endMetadata()
                    .build();
        kubeClient(targetNamespace).getClient().secrets().inNamespace(targetNamespace).createOrReplace(s);
    }

    private void deployTestSpecificResources() {

        thirdNamespaceResources.kafkaEphemeral(CLUSTER_NAME, 1)
            .editSpec()
                .editKafka()
                    .editListeners()
                        .withNewKafkaListenerExternalNodePort()
                        .endKafkaListenerExternalNodePort()
                    .endListeners()
                .endKafka()
                .editEntityOperator()
                    .editTopicOperator()
                        .withWatchedNamespace(SECOND_NAMESPACE)
                    .endTopicOperator()
                    .editUserOperator()
                        .withWatchedNamespace(SECOND_NAMESPACE)
                    .endUserOperator()
                .endEntityOperator()
            .endSpec()
            .done();
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(CO_NAMESPACE, Arrays.asList(CO_NAMESPACE, SECOND_NAMESPACE, THIRD_NAMESPACE));
        createTestClassResources();

        // Apply role bindings in CO namespace
        applyRoleBindings(CO_NAMESPACE);

        // Create ClusterRoleBindings that grant cluster-wide access to all OpenShift projects
        List<ClusterRoleBinding> clusterRoleBindingList = testClassResources().clusterRoleBindingsForAllNamespaces(CO_NAMESPACE);
        clusterRoleBindingList.forEach(clusterRoleBinding ->
                testClassResources().clusterRoleBinding(clusterRoleBinding, CO_NAMESPACE));

        LOGGER.info("Deploying CO to watch all namespaces");
        testClassResources().clusterOperator("*").done();

        String previousNamespace = setNamespace(THIRD_NAMESPACE);
        thirdNamespaceResources = new Resources(kubeClient());

        deployTestSpecificResources();

        setNamespace(previousNamespace);
    }

    @Override
    protected void tearDownEnvironmentAfterAll() {
        thirdNamespaceResources.deleteResources();
        testClassResources().deleteResources();
        teardownEnvForOperator();
    }

    @Override
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        super.recreateTestEnv(coNamespace, bindingsNamespaces);
        deployTestSpecificResources();
    }
}

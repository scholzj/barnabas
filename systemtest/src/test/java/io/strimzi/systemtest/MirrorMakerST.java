/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.PasswordSecretSource;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.listener.KafkaListenerTls;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@Tag(REGRESSION)
public class MirrorMakerST extends MessagingBaseST {

    private static final Logger LOGGER = LogManager.getLogger(MirrorMakerST.class);

    public static final String NAMESPACE = "mm-cluster-test";
    private static final String TOPIC_NAME = "test-topic";
    private final int messagesCount = 200;
    private String kafkaClusterSourceName = CLUSTER_NAME + "-source";
    private String kafkaClusterTargetName = CLUSTER_NAME + "-target";

    @Test
    void testMirrorMaker() throws Exception {
        Map<String, String> jvmOptionsXX = new HashMap<>();
        jvmOptionsXX.put("UseG1GC", "true");
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicSourceName = TOPIC_NAME + "-source" + "-" + rng.nextInt(Integer.MAX_VALUE);

        // Deploy source kafka
        testMethodResources().kafkaEphemeral(kafkaClusterSourceName, 1, 1).done();
        // Deploy target kafka
        testMethodResources().kafkaEphemeral(kafkaClusterTargetName, 1, 1).done();
        // Deploy Topic
        testMethodResources().topic(kafkaClusterSourceName, topicSourceName).done();

        testMethodResources().deployKafkaClients(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName);
        availabilityTest(messagesCount, kafkaClusterTargetName);

        // Deploy Mirror Maker
        testMethodResources().kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false).
                editSpec()
                .withResources(new ResourceRequirementsBuilder()
                        .addToLimits("memory", new Quantity("400M"))
                        .addToLimits("cpu", new Quantity("2"))
                        .addToRequests("memory", new Quantity("300M"))
                        .addToRequests("cpu", new Quantity("1"))
                        .build())
                .withNewJvmOptions()
                    .withXmx("200m")
                    .withXms("200m")
                    .withServer(true)
                    .withXx(jvmOptionsXX)
                .endJvmOptions()
                .endSpec().done();

        verifyLabelsOnPods(CLUSTER_NAME, "mirror-maker", null, "KafkaMirrorMaker");
        verifyLabelsForService(CLUSTER_NAME, "mirror-maker", "KafkaMirrorMaker");

        verifyLabelsForConfigMaps(kafkaClusterSourceName, null, kafkaClusterTargetName);
        verifyLabelsForServiceAccounts(kafkaClusterSourceName, null);

        String podName = kubeClient().listPods().stream().filter(n -> n.getMetadata().getName().startsWith(kafkaMirrorMakerName(CLUSTER_NAME))).findFirst().get().getMetadata().getName();
        assertResources(NAMESPACE, podName, CLUSTER_NAME.concat("-mirror-maker"),
                "400M", "2", "300M", "1");
        assertExpectedJavaOpts(podName, kafkaMirrorMakerName(CLUSTER_NAME),
                "-Xmx200m", "-Xms200m", "-server", "-XX:+UseG1GC");

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount,  kafkaClusterSourceName, false, topicSourceName, null, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicSourceName, null, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, false, topicSourceName, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    /**
     * Test mirroring messages by Mirror Maker over tls transport using mutual tls auth
     */
    @Test
    @Tag(ACCEPTANCE)
    void testMirrorMakerTlsAuthenticated() throws Exception {
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicSourceName = TOPIC_NAME + "-source" + "-" + rng.nextInt(Integer.MAX_VALUE);
        String kafkaSourceUserName = "my-user-source";
        String kafkaUserTargetName = "my-user-target";

        KafkaListenerAuthenticationTls auth = new KafkaListenerAuthenticationTls();
        KafkaListenerTls listenerTls = new KafkaListenerTls();
        listenerTls.setAuth(auth);

        // Deploy source kafka with tls listener and mutual tls auth
        testMethodResources().kafka(testMethodResources().defaultKafka(kafkaClusterSourceName, 1, 1)
                .editSpec()
                .editKafka()
                .withNewListeners()
                .withTls(listenerTls)
                .withNewTls()
                .endTls()
                .endListeners()
                .endKafka()
                .endSpec().build()).done();

        // Deploy target kafka with tls listener and mutual tls auth
        testMethodResources().kafka(testMethodResources().defaultKafka(kafkaClusterTargetName, 1, 1)
                .editSpec()
                .editKafka()
                .withNewListeners()
                .withTls(listenerTls)
                .withNewTls()
                .endTls()
                .endListeners()
                .endKafka()
                .endSpec().build()).done();

        // Deploy topic
        testMethodResources().topic(kafkaClusterSourceName, topicSourceName).done();

        // Create Kafka user
        KafkaUser userSource = testMethodResources().tlsUser(kafkaClusterSourceName, kafkaSourceUserName).done();
        StUtils.waitForSecretReady(kafkaSourceUserName);

        KafkaUser userTarget = testMethodResources().tlsUser(kafkaClusterTargetName, kafkaUserTargetName).done();
        StUtils.waitForSecretReady(kafkaUserTargetName);

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(clusterCaCertSecretName(kafkaClusterSourceName));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(clusterCaCertSecretName(kafkaClusterTargetName));

        testMethodResources().deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, userSource, userTarget).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName, true, "my-topic-test-1", userSource);
        availabilityTest(messagesCount, kafkaClusterTargetName, true, "my-topic-test-2", userTarget);

        // Deploy Mirror Maker with tls listener and mutual tls auth
        testMethodResources().kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, true)
                .editSpec()
                .editConsumer()
                .withNewTls()
                .withTrustedCertificates(certSecretSource)
                .endTls()
                .endConsumer()
                .editProducer()
                .withNewTls()
                .withTrustedCertificates(certSecretTarget)
                .endTls()
                .endProducer()
                .endSpec()
                .done();

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount,  kafkaClusterSourceName, true, topicSourceName, userSource, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, true, topicSourceName, userSource, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, true, topicSourceName, userTarget, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    /**
     * Test mirroring messages by Mirror Maker over tls transport using scram-sha auth
     */
    @Test
    void testMirrorMakerTlsScramSha() throws Exception {
        setOperationID(startTimeMeasuring(Operation.MM_DEPLOYMENT));
        String topicName = TOPIC_NAME + "-" + rng.nextInt(Integer.MAX_VALUE);
        String kafkaUserSource = "my-user-source";
        String kafkaUserTarget = "my-user-target";

        // Deploy source kafka with tls listener and SCRAM-SHA authentication
        testMethodResources().kafka(testMethodResources().defaultKafka(kafkaClusterSourceName, 1, 1)
                .editSpec()
                .editKafka()
                .withNewListeners()
                .withNewTls().withAuth(new KafkaListenerAuthenticationScramSha512()).endTls()
                .endListeners()
                .endKafka()
                .endSpec().build()).done();

        // Deploy target kafka with tls listener and SCRAM-SHA authentication
        testMethodResources().kafka(testMethodResources().defaultKafka(kafkaClusterTargetName, 1, 1)
                .editSpec()
                .editKafka()
                .withNewListeners()
                .withNewTls().withAuth(new KafkaListenerAuthenticationScramSha512()).endTls()
                .endListeners()
                .endKafka()
                .endSpec().build()).done();

        // Create Kafka user for source cluster
        KafkaUser userSource = testMethodResources().scramShaUser(kafkaClusterSourceName, kafkaUserSource).done();
        StUtils.waitForSecretReady(kafkaUserSource);

        // Create Kafka user for target cluster
        KafkaUser userTarget = testMethodResources().scramShaUser(kafkaClusterTargetName, kafkaUserTarget).done();
        StUtils.waitForSecretReady(kafkaUserTarget);

        // Initialize PasswordSecretSource to set this as PasswordSecret in Mirror Maker spec
        PasswordSecretSource passwordSecretSource = new PasswordSecretSource();
        passwordSecretSource.setSecretName(kafkaUserSource);
        passwordSecretSource.setPassword("password");

        // Initialize PasswordSecretSource to set this as PasswordSecret in Mirror Maker spec
        PasswordSecretSource passwordSecretTarget = new PasswordSecretSource();
        passwordSecretTarget.setSecretName(kafkaUserTarget);
        passwordSecretTarget.setPassword("password");

        // Initialize CertSecretSource with certificate and secret names for consumer
        CertSecretSource certSecretSource = new CertSecretSource();
        certSecretSource.setCertificate("ca.crt");
        certSecretSource.setSecretName(clusterCaCertSecretName(kafkaClusterSourceName));

        // Initialize CertSecretSource with certificate and secret names for producer
        CertSecretSource certSecretTarget = new CertSecretSource();
        certSecretTarget.setCertificate("ca.crt");
        certSecretTarget.setSecretName(clusterCaCertSecretName(kafkaClusterTargetName));

        // Deploy client
        testMethodResources().deployKafkaClients(true, CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS, userSource, userTarget).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName, true, "my-topic-test-1", userSource);
        availabilityTest(messagesCount, kafkaClusterTargetName, true, "my-topic-test-2", userTarget);

        // Deploy Mirror Maker with TLS and ScramSha512
        testMethodResources().kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, true)
                .editSpec()
                .editConsumer()
                .withNewKafkaMirrorMakerAuthenticationScramSha512()
                .withUsername(kafkaUserSource)
                .withPasswordSecret(passwordSecretSource)
                .endKafkaMirrorMakerAuthenticationScramSha512()
                .withNewTls()
                .withTrustedCertificates(certSecretSource)
                .endTls()
                .endConsumer()
                .editProducer()
                .withNewKafkaMirrorMakerAuthenticationScramSha512()
                .withUsername(kafkaUserTarget)
                .withPasswordSecret(passwordSecretTarget)
                .endKafkaMirrorMakerAuthenticationScramSha512()
                .withNewTls()
                .withTrustedCertificates(certSecretTarget)
                .endTls()
                .endProducer()
                .endSpec().done();

        // Deploy topic
        testMethodResources().topic(kafkaClusterSourceName, topicName).done();

        TimeMeasuringSystem.stopOperation(getOperationID());

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount, kafkaClusterSourceName, true, topicName, userSource, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, true, topicName, userSource, kafkaClientsPodName);

        TestUtils.waitFor("Waiting for Mirror Maker will copy messages from " + kafkaClusterSourceName + " to " + kafkaClusterTargetName,
            Constants.POLL_INTERVAL_FOR_RESOURCE_CREATION, Constants.TIMEOUT_FOR_MIRROR_MAKER_COPY_MESSAGES_BETWEEN_BROKERS,
            () -> sent == receiveMessages(messagesCount, kafkaClusterTargetName, true, topicName, userTarget, kafkaClientsPodName));

        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, true, topicName, userTarget, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);
    }

    @Test
    void testWhiteList() throws Exception {
        String topicName = "topic-example-1";
        String topicNotInWhitelist = "topic-example-2";

        LOGGER.info("Creating kafka source cluster {}", kafkaClusterSourceName);
        testMethodResources().kafkaEphemeral(kafkaClusterSourceName, 1, 1).done();

        LOGGER.info("Creating kafka target cluster {}", kafkaClusterTargetName);
        testMethodResources().kafkaEphemeral(kafkaClusterTargetName, 1, 1).done();

        testMethodResources().topic(kafkaClusterSourceName, topicName).done();
        testMethodResources().topic(kafkaClusterSourceName, topicNotInWhitelist).done();

        StUtils.waitForKafkaTopicCreation(topicName);
        StUtils.waitForKafkaTopicCreation(topicNotInWhitelist);

        testMethodResources().deployKafkaClients(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).done();

        // Check brokers availability
        availabilityTest(messagesCount, kafkaClusterSourceName);
        availabilityTest(messagesCount, kafkaClusterTargetName);

        testMethodResources().kafkaMirrorMaker(CLUSTER_NAME, kafkaClusterSourceName, kafkaClusterTargetName, "my-group" + rng.nextInt(Integer.MAX_VALUE), 1, false)
                .editSpec()
                    .withNewWhitelist(topicName)
                .endSpec()
                .done();

        final String kafkaClientsPodName = kubeClient().listPodsByPrefixInName(CLUSTER_NAME + "-" + Constants.KAFKA_CLIENTS).get(0).getMetadata().getName();

        int sent = sendMessages(messagesCount, kafkaClusterSourceName, false, topicName, null, kafkaClientsPodName);
        int receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicName, null, kafkaClientsPodName);
        int receivedTarget = receiveMessages(messagesCount, kafkaClusterTargetName, false, topicName, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertSentAndReceivedMessages(sent, receivedTarget);

        sent = sendMessages(messagesCount, kafkaClusterSourceName, false, topicNotInWhitelist, null, kafkaClientsPodName);
        receivedSource = receiveMessages(messagesCount, kafkaClusterSourceName, false, topicNotInWhitelist, null, kafkaClientsPodName);

        assertSentAndReceivedMessages(sent, receivedSource);
        assertThat("Received 0 messages in target kafka because topic " + topicNotInWhitelist + " is not in whitelist",
                receiveMessages(messagesCount, kafkaClusterTargetName, false, topicNotInWhitelist, null, kafkaClientsPodName), is(0));
    }

    @BeforeEach
    void createTestResources() throws Exception {
        createTestMethodResources();
    }

    @AfterEach
    void deleteTestResources() throws Exception {
        deleteTestMethodResources();
        waitForDeletion(Constants.TIMEOUT_TEARDOWN);
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(NAMESPACE);

        createTestClassResources();
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        testClassResources().clusterOperator(NAMESPACE).done();
    }

    @AfterAll
    void teardownEnvironment() {
        testClassResources().deleteResources();
        teardownEnvForOperator();
    }

}

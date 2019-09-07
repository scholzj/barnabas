/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaBridgeList;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.KafkaConnectS2IList;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.KafkaMirrorMakerList;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.KafkaUserList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.DoneableKafkaBridge;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.DoneableKafkaConnectS2I;
import io.strimzi.api.kafka.model.DoneableKafkaMirrorMaker;
import io.strimzi.api.kafka.model.DoneableKafkaTopic;
import io.strimzi.api.kafka.model.DoneableKafkaUser;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.systemtest.clients.lib.KafkaClient;
import io.strimzi.systemtest.interfaces.TestSeparator;
import io.strimzi.systemtest.utils.TestExecutionWatcher;
import io.strimzi.test.BaseITST;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.HelmClient;
import io.strimzi.test.k8s.KubeClusterException;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.strimzi.systemtest.matchers.Matchers.logHasNoUnexpectedErrors;
import static io.strimzi.test.TestUtils.entry;
import static io.strimzi.test.TestUtils.indent;
import static io.strimzi.test.TestUtils.waitFor;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("checkstyle:ClassFanOutComplexity")
@ExtendWith(TestExecutionWatcher.class)
public abstract class AbstractST extends BaseITST implements TestSeparator {

    static {
        Crds.registerCustomKinds();
    }

    private static final Logger LOGGER = LogManager.getLogger(AbstractST.class);
    protected static final String CLUSTER_NAME = "my-cluster";
    protected static final String ZK_IMAGE = "STRIMZI_DEFAULT_ZOOKEEPER_IMAGE";
    protected static final String KAFKA_IMAGE_MAP = "STRIMZI_KAFKA_IMAGES";
    protected static final String KAFKA_CONNECT_IMAGE_MAP = "STRIMZI_KAFKA_CONNECT_IMAGES";
    protected static final String TO_IMAGE = "STRIMZI_DEFAULT_TOPIC_OPERATOR_IMAGE";
    protected static final String UO_IMAGE = "STRIMZI_DEFAULT_USER_OPERATOR_IMAGE";
    protected static final String KAFKA_INIT_IMAGE = "STRIMZI_DEFAULT_KAFKA_INIT_IMAGE";
    protected static final String TLS_SIDECAR_ZOOKEEPER_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ZOOKEEPER_IMAGE";
    protected static final String TLS_SIDECAR_KAFKA_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_KAFKA_IMAGE";
    protected static final String TLS_SIDECAR_EO_IMAGE = "STRIMZI_DEFAULT_TLS_SIDECAR_ENTITY_OPERATOR_IMAGE";
    protected static final String TEST_TOPIC_NAME = "test-topic";
    private static final String CLUSTER_OPERATOR_PREFIX = "strimzi";

    public static final String TOPIC_CM = "../examples/topic/kafka-topic.yaml";
    public static final String HELM_CHART = "../helm-charts/strimzi-kafka-operator/";
    public static final String HELM_RELEASE_NAME = "strimzi-systemtests";
    public static final String REQUESTS_MEMORY = "512Mi";
    public static final String REQUESTS_CPU = "200m";
    public static final String LIMITS_MEMORY = "512Mi";
    public static final String LIMITS_CPU = "1000m";

    public static final String TEST_LOG_DIR = Environment.TEST_LOG_DIR;

    protected Resources testMethodResources;
    private static Resources testClassResources;
    private static String operationID;
    Random rng = new Random();

    protected HelmClient helmClient() {
        return kubeCluster().helmClient().namespace(getNamespace());
    }

    static String kafkaClusterName(String clusterName) {
        return KafkaResources.kafkaStatefulSetName(clusterName);
    }

    static String kafkaConnectName(String clusterName) {
        return clusterName + "-connect";
    }

    static String kafkaMirrorMakerName(String clusterName) {
        return clusterName + "-mirror-maker";
    }

    static String kafkaPodName(String clusterName, int podId) {
        return KafkaResources.kafkaPodName(clusterName, podId);
    }

    static String kafkaServiceName(String clusterName) {
        return KafkaResources.bootstrapServiceName(clusterName);
    }

    static String kafkaHeadlessServiceName(String clusterName) {
        return KafkaResources.brokersServiceName(clusterName);
    }

    static String kafkaMetricsConfigName(String clusterName) {
        return KafkaResources.kafkaMetricsAndLogConfigMapName(clusterName);
    }

    static String zookeeperClusterName(String clusterName) {
        return KafkaResources.zookeeperStatefulSetName(clusterName);
    }

    static String zookeeperPodName(String clusterName, int podId) {
        return KafkaResources.zookeeperPodName(clusterName, podId);
    }

    static String zookeeperServiceName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-client";
    }

    static String zookeeperHeadlessServiceName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-nodes";
    }

    static String zookeeperMetricsConfigName(String clusterName) {
        return KafkaResources.zookeeperMetricsAndLogConfigMapName(clusterName);
    }

    static String zookeeperPVCName(String clusterName, int podId) {
        return "data-" + zookeeperClusterName(clusterName) + "-" + podId;
    }

    static String entityOperatorDeploymentName(String clusterName) {
        return KafkaResources.entityOperatorDeploymentName(clusterName);
    }

    private <T extends CustomResource, L extends CustomResourceList<T>, D extends Doneable<T>>
        void replaceCrdResource(Class<T> crdClass, Class<L> listClass, Class<D> doneableClass, String resourceName, Consumer<T> editor) {
        Resource<T, D> namedResource = Crds.operation(kubeClient().getClient(), crdClass, listClass, doneableClass).inNamespace(kubeClient().getNamespace()).withName(resourceName);
        T resource = namedResource.get();
        editor.accept(resource);
        namedResource.replace(resource);
    }

    protected void replaceKafkaResource(String resourceName, Consumer<Kafka> editor) {
        replaceCrdResource(Kafka.class, KafkaList.class, DoneableKafka.class, resourceName, editor);
    }

    void replaceKafkaConnectResource(String resourceName, Consumer<KafkaConnect> editor) {
        replaceCrdResource(KafkaConnect.class, KafkaConnectList.class, DoneableKafkaConnect.class, resourceName, editor);
    }

    void replaceTopicResource(String resourceName, Consumer<KafkaTopic> editor) {
        replaceCrdResource(KafkaTopic.class, KafkaTopicList.class, DoneableKafkaTopic.class, resourceName, editor);
    }

    void replaceUserResource(String resourceName, Consumer<KafkaUser> editor) {
        replaceCrdResource(KafkaUser.class, KafkaUserList.class, DoneableKafkaUser.class, resourceName, editor);
    }

    void replaceMirrorMakerResource(String resourceName, Consumer<KafkaMirrorMaker> editor) {
        replaceCrdResource(KafkaMirrorMaker.class, KafkaMirrorMakerList.class, DoneableKafkaMirrorMaker.class, resourceName, editor);
    }

    void replaceBridgeResource(String resourceName, Consumer<KafkaBridge> editor) {
        replaceCrdResource(KafkaBridge.class, KafkaBridgeList.class, DoneableKafkaBridge.class, resourceName, editor);
    }

    void replaceConnectS2IResource(String resourceName, Consumer<KafkaConnectS2I> editor) {
        replaceCrdResource(KafkaConnectS2I.class, KafkaConnectS2IList.class, DoneableKafkaConnectS2I.class, resourceName, editor);
    }

    String getBrokerApiVersions(String podName) {
        AtomicReference<String> versions = new AtomicReference<>();
        waitFor("kafka-broker-api-versions.sh success", Constants.GET_BROKER_API_INTERVAL, Constants.GET_BROKER_API_TIMEOUT, () -> {
            try {
                String output = cmdKubeClient().execInPod(podName,
                        "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092").out();
                versions.set(output);
                return true;
            } catch (KubeClusterException e) {
                LOGGER.trace("/opt/kafka/bin/kafka-broker-api-versions.sh: {}", e.getMessage());
                return false;
            }
        });
        return versions.get();
    }

    void waitForZkMntr(Pattern pattern, int... podIndexes) {
        long timeoutMs = 120_000L;
        long pollMs = 1_000L;

        for (int podIndex : podIndexes) {
            String zookeeperPod = zookeeperPodName(CLUSTER_NAME, podIndex);
            String zookeeperPort = String.valueOf(2181 * 10 + podIndex);
            waitFor("mntr", pollMs, timeoutMs, () -> {
                try {
                    String output = cmdKubeClient().execInPod(zookeeperPod,
                        "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out();

                    if (pattern.matcher(output).find()) {
                        return true;
                    }
                } catch (KubeClusterException e) {
                    LOGGER.trace("Exception while waiting for ZK to become leader/follower, ignoring", e);
                }
                return false;
                },
                () -> LOGGER.info("zookeeper `mntr` output at the point of timeout does not match {}:{}{}",
                    pattern.pattern(),
                    System.lineSeparator(),
                    indent(cmdKubeClient().execInPod(zookeeperPod, "/bin/bash", "-c", "echo mntr | nc localhost " + zookeeperPort).out()))
            );
        }
    }

    /**
     * Translate key/value pairs fromatted like properties into a Map
     * @param keyValuePairs Pairs in key=value format; pairs are separated by newlines
     * @return THe map of key/values
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> loadProperties(String keyValuePairs) {
        try {
            Properties actual = new Properties();
            actual.load(new StringReader(keyValuePairs));
            return (Map) actual;
        } catch (IOException e) {
            throw new AssertionError("Invalid Properties definiton", e);
        }
    }

    /**
     * Get a Map of properties from an environment variable in json.
     * @param json The json from which to extract properties
     * @param envVar The environment variable name
     * @return The properties which the variable contains
     */
    static Map<String, String> getPropertiesFromJson(String json, String envVar) {
        List<String> array = JsonPath.parse(json).read(globalVariableJsonPathBuilder(envVar));
        return loadProperties(array.get(0));
    }

    /**
     * Get a jsonPath which can be used to extract envariable variables from a spec
     * @param envVar The environment variable name
     * @return The json path
     */
    static String globalVariableJsonPathBuilder(String envVar) {
        return "$.spec.containers[*].env[?(@.name=='" + envVar + "')].value";
    }

    public void sendMessages(String podName, String clusterName, String containerName, String topic, int messagesCount) {
        LOGGER.info("Sending messages");
        String command = "sh bin/kafka-verifiable-producer.sh --broker-list " +
                KafkaResources.plainBootstrapAddress(clusterName) + " --topic " + topic + " --max-messages " + messagesCount + "";

        LOGGER.info("Command for kafka-verifiable-producer.sh {}", command);

        cmdKubeClient().execInPod(podName, "/bin/bash", "-c", command);
    }

    protected void assertResources(String namespace, String podName, String containerName, String memoryLimit, String cpuLimit, String memoryRequest, String cpuRequest) {
        Pod po = kubeClient().getPod(podName);
        assertNotNull(po, "Not found an expected pod  " + podName + " in namespace " + namespace + " but found " +
                kubeClient().listPods().stream().map(p -> p.getMetadata().getName()).collect(Collectors.toList()));

        Optional optional = po.getSpec().getContainers().stream().filter(c -> c.getName().equals(containerName)).findFirst();
        assertTrue(optional.isPresent(), "Not found an expected container " + containerName);

        Container container = (Container) optional.get();
        Map<String, Quantity> limits = container.getResources().getLimits();
        assertEquals(memoryLimit, limits.get("memory").getAmount());
        assertEquals(cpuLimit, limits.get("cpu").getAmount());
        Map<String, Quantity> requests = container.getResources().getRequests();
        assertEquals(memoryRequest, requests.get("memory").getAmount());
        assertEquals(cpuRequest, requests.get("cpu").getAmount());
    }

    protected void assertExpectedJavaOpts(String podName, String containerName, String expectedXmx, String expectedXms, String expectedServer, String expectedXx) {
        List<List<String>> cmdLines = commandLines(podName, containerName, "java");
        assertEquals(1, cmdLines.size(), "Expected exactly 1 java process to be running");
        List<String> cmd = cmdLines.get(0);
        int toIndex = cmd.indexOf("-jar");
        if (toIndex != -1) {
            // Just consider arguments to the JVM, not the application running in it
            cmd = cmd.subList(0, toIndex);
            // We should do something similar if the class not -jar was given, but that's
            // hard to do properly.
        }
        assertCmdOption(cmd, expectedXmx);
        assertCmdOption(cmd, expectedXms);
        assertCmdOption(cmd, expectedServer);
        assertCmdOption(cmd, expectedXx);
    }

    private void assertCmdOption(List<String> cmd, String expectedXmx) {
        if (!cmd.contains(expectedXmx)) {
            fail("Failed to find argument matching " + expectedXmx + " in java command line " +
                    cmd.stream().collect(Collectors.joining("\n")));
        }
    }

    private List<List<String>> commandLines(String podName, String containerName, String cmd) {
        List<List<String>> result = new ArrayList<>();
        String output = cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "for pid in $(ps -C java -o pid h); do cat /proc/$pid/cmdline; done"
        ).out();
        for (String cmdLine : output.split("\n")) {
            result.add(asList(cmdLine.split("\0")));
        }
        return result;
    }

    void assertNoCoErrorsLogged(long sinceSeconds) {
        LOGGER.info("Search in strimzi-cluster-operator log for errors in last {} seconds", sinceSeconds);
        String clusterOperatorLog = cmdKubeClient().searchInLog("deploy", "strimzi-cluster-operator", sinceSeconds, "Exception", "Error", "Throwable");
        assertThat(clusterOperatorLog, logHasNoUnexpectedErrors());
    }

    public List<String> listTopicsUsingPodCLI(String clusterName, int zkPodId) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return Arrays.asList(cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --list --zookeeper localhost:" + port).out().split("\\s+"));
    }

    public String createTopicUsingPodCLI(String clusterName, int zkPodId, String topic, int replicationFactor, int partitions) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --create " + " --topic " + topic +
                        " --replication-factor " + replicationFactor + " --partitions " + partitions).out();
    }

    public String deleteTopicUsingPodCLI(String clusterName, int zkPodId, String topic) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --delete --topic " + topic).out();
    }

    public List<String>  describeTopicUsingPodCLI(String clusterName, int zkPodId, String topic) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return Arrays.asList(cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --describe --topic " + topic).out().split("\\s+"));
    }

    public String updateTopicPartitionsCountUsingPodCLI(String clusterName, int zkPodId, String topic, int partitions) {
        String podName = zookeeperPodName(clusterName, zkPodId);
        int port = 2181 * 10 + zkPodId;
        return cmdKubeClient().execInPod(podName, "/bin/bash", "-c",
                "bin/kafka-topics.sh --zookeeper localhost:" + port + " --alter --topic " + topic + " --partitions " + partitions).out();
    }

    public Map<String, String> getImagesFromConfig() {
        Map<String, String> images = new HashMap<>();
        for (Container c : kubeClient().getDeployment("strimzi-cluster-operator").getSpec().getTemplate().getSpec().getContainers()) {
            for (EnvVar envVar : c.getEnv()) {
                images.put(envVar.getName(), envVar.getValue());
            }
        }
        return images;
    }

    public String getContainerImageNameFromPod(String podName) {
        return kubeClient().getPod(podName).getSpec().getContainers().get(0).getImage();
    }

    public String getContainerImageNameFromPod(String podName, String containerName) {
        return kubeClient().getPod(podName).getSpec().getContainers().stream()
                .filter(c -> c.getName().equals(containerName))
                .findFirst().get().getImage();
    }

    public String  getInitContainerImageName(String podName) {
        return kubeClient().getPod(podName).getSpec().getInitContainers().stream()
                .findFirst().get().getImage();
    }

    public  void createTestMethodResources() {
        LOGGER.info("Creating resources before the test");
        testMethodResources = new Resources(kubeClient());
    }

    public  static void createTestClassResources() {
        LOGGER.info("Creating test class resources");
        testClassResources = new Resources(kubeClient());
    }

    public  void deleteTestMethodResources() {
        if (testMethodResources != null) {
            testMethodResources.deleteResources();
            testMethodResources = null;
        }
    }

    public Resources testMethodResources() {
        return testMethodResources;
    }

    public static Resources testClassResources() {
        return testClassResources;
    }

    public static String getOperationID() {
        return operationID;
    }

    public static void setOperationID(String operationID) {
        AbstractST.operationID = operationID;
    }

    public static void setTestClassResources(Resources testClassResources) {
        AbstractST.testClassResources = testClassResources;
    }

    String startTimeMeasuring(Operation operation) {
        TimeMeasuringSystem.setTestName(testClass, testName);
        return TimeMeasuringSystem.startOperation(operation);
    }

    public String clusterCaCertSecretName(String cluster) {
        return cluster + "-cluster-ca-cert";
    }

    void verifyLabelsForKafkaCluster(String clusterName, String appName) {
        verifyLabelsForKafkaOrZkPods(clusterName, "zookeeper", appName);
        verifyLabelsForKafkaOrZkPods(clusterName, "kafka", appName);
        verifyLabelsOnCOPod();
        verifyLabelsOnPods(clusterName, "entity-operator", appName, "Kafka");
        verifyLabelsForCRDs();
        verifyLabelsForKafkaAndZKServices(clusterName, appName);
        verifyLabelsForSecrets(clusterName, appName);
        verifyLabelsForConfigMaps(clusterName, appName, "");
        verifyLabelsForRoleBindings(clusterName, appName);
        verifyLabelsForServiceAccounts(clusterName, appName);
    }

    void verifyLabelsForKafkaOrZkPods(String clusterName, String podType, String appName) {
        LOGGER.info("Verifying labels for {}", podType);

        kubeClient().listPodsByPrefixInName(clusterName + "-" + podType).forEach(
            pod -> {
                String podName = pod.getMetadata().getName();
                LOGGER.info("Verifying labels for pod {}", podName);
                Map<String, String> labels = pod.getMetadata().getLabels();
                assertEquals(appName, labels.get("app"));
                assertTrue(labels.get("controller-revision-hash").matches("openshift-my-cluster-" + podType + "-.+"));
                assertEquals(podName, labels.get("statefulset.kubernetes.io/pod-name"));
                assertEquals(clusterName, labels.get("strimzi.io/cluster"));
                assertEquals("Kafka", labels.get("strimzi.io/kind"));
                assertEquals(clusterName.concat("-").concat(podType), labels.get("strimzi.io/name"));
            }
        );
    }


    void verifyLabelsOnCOPod() {
        LOGGER.info("Verifying labels for cluster-operator pod");

        Map<String, String> coLabels = kubeClient().listPods("name", "strimzi-cluster-operator").get(0).getMetadata().getLabels();
        assertEquals("strimzi-cluster-operator", coLabels.get("name"));
        assertTrue(coLabels.get("pod-template-hash").matches("\\d+"));
        assertEquals("cluster-operator", coLabels.get("strimzi.io/kind"));
    }

    protected void verifyLabelsOnPods(String clusterName, String podType, String appName, String kind) {
        LOGGER.info("Verifying labels on pod type {}", podType);
        kubeClient().listPods().stream()
            .filter(pod -> pod.getMetadata().getName().startsWith(clusterName.concat("-" + podType)))
            .forEach(pod -> {
                LOGGER.info("Verifying labels for pod: " + pod.getMetadata().getName());
                assertEquals(appName, pod.getMetadata().getLabels().get("app"));
                assertTrue(pod.getMetadata().getLabels().get("pod-template-hash").matches("\\d+"));
                assertEquals(clusterName, pod.getMetadata().getLabels().get("strimzi.io/cluster"));
                assertEquals(kind, pod.getMetadata().getLabels().get("strimzi.io/kind"));
                assertEquals(clusterName.concat("-" + podType), pod.getMetadata().getLabels().get("strimzi.io/name"));
            });
    }

    void verifyLabelsForCRDs() {
        LOGGER.info("Verifying labels for CRDs");
        kubeClient().listCustomResourceDefinition().stream()
            .filter(crd -> crd.getMetadata().getName().startsWith("kafka"))
            .forEach(crd -> {
                LOGGER.info("Verifying labels for custom resource {]", crd.getMetadata().getName());
                assertEquals("strimzi", crd.getMetadata().getLabels().get("app"));
            });
    }

    void verifyLabelsForKafkaAndZKServices(String clusterName, String appName) {
        LOGGER.info("Verifying labels for Services");
        List<String> servicesList = new ArrayList<>();
        servicesList.add(clusterName + "-kafka-bootstrap");
        servicesList.add(clusterName + "-kafka-brokers");
        servicesList.add(clusterName + "-zookeeper-nodes");
        servicesList.add(clusterName + "-zookeeper-client");

        for (String serviceName : servicesList) {
            kubeClient().listServices().stream()
                .filter(service -> service.getMetadata().getName().equals(serviceName))
                .forEach(service -> {
                    LOGGER.info("Verifying labels for service {}", serviceName);
                    assertEquals(appName, service.getMetadata().getLabels().get("app"));
                    assertEquals(clusterName, service.getMetadata().getLabels().get("strimzi.io/cluster"));
                    assertEquals("Kafka", service.getMetadata().getLabels().get("strimzi.io/kind"));
                    assertEquals(serviceName, service.getMetadata().getLabels().get("strimzi.io/name"));
                });
        }
    }

    protected void verifyLabelsForService(String clusterName, String serviceToTest, String kind) {
        LOGGER.info("Verifying labels for Kafka Connect Services");

        String serviceName = clusterName.concat("-").concat(serviceToTest);
        kubeClient().listServices().stream()
            .filter(service -> service.getMetadata().getName().equals(serviceName))
            .forEach(service -> {
                LOGGER.info("Verifying labels for service {}", service.getMetadata().getName());
                assertEquals(clusterName, service.getMetadata().getLabels().get("strimzi.io/cluster"));
                assertEquals(kind, service.getMetadata().getLabels().get("strimzi.io/kind"));
                assertEquals(serviceName, service.getMetadata().getLabels().get("strimzi.io/name"));
            }
        );
    }

    void verifyLabelsForSecrets(String clusterName, String appName) {
        LOGGER.info("Verifying labels for secrets");
        kubeClient().listSecrets().stream()
            .filter(p -> p.getMetadata().getName().matches("(" + clusterName + ")-(clients|cluster|(entity))(-operator)?(-ca)?(-certs?)?"))
            .forEach(p -> {
                LOGGER.info("Verifying secret {}", p.getMetadata().getName());
                assertEquals(appName, p.getMetadata().getLabels().get("app"));
                assertEquals("Kafka", p.getMetadata().getLabels().get("strimzi.io/kind"));
                assertEquals(clusterName, p.getMetadata().getLabels().get("strimzi.io/cluster"));
            }
        );
    }

    void verifyLabelsForConfigMaps(String clusterName, String appName, String additionalClusterName) {
        LOGGER.info("Verifying labels for Config maps");

        kubeClient().listConfigMaps()
            .forEach(cm -> {
                LOGGER.info("Verifying labels for CM {}", cm.getMetadata().getName());
                if (cm.getMetadata().getName().equals(clusterName.concat("-connect-config"))) {
                    assertNull(cm.getMetadata().getLabels().get("app"));
                    assertEquals("KafkaConnect", cm.getMetadata().getLabels().get("strimzi.io/kind"));
                } else if (cm.getMetadata().getName().contains("-mirror-maker-config")) {
                    assertNull(cm.getMetadata().getLabels().get("app"));
                    assertEquals("KafkaMirrorMaker", cm.getMetadata().getLabels().get("strimzi.io/kind"));
                } else {
                    assertEquals(appName, cm.getMetadata().getLabels().get("app"));
                    assertEquals("Kafka", cm.getMetadata().getLabels().get("strimzi.io/kind"));
                    assertTrue(cm.getMetadata().getLabels().get("strimzi.io/cluster").equals(clusterName) ||
                            cm.getMetadata().getLabels().get("strimzi.io/cluster").equals(additionalClusterName));
                }
            }
        );
    }

    void verifyLabelsForServiceAccounts(String clusterName, String appName) {
        LOGGER.info("Verifying labels for Service Accounts");

        kubeClient().listServiceAccounts().stream()
            .filter(sa -> sa.getMetadata().getName().equals("strimzi-cluster-operator"))
            .forEach(sa -> {
                LOGGER.info("Verifying labels for service account {}", sa.getMetadata().getName());
                assertEquals("strimzi", sa.getMetadata().getLabels().get("app"));
            }
        );

        kubeClient().listServiceAccounts().stream()
            .filter(sa -> sa.getMetadata().getName().startsWith(clusterName))
            .forEach(sa -> {
                LOGGER.info("Verifying labels for service account {}", sa.getMetadata().getName());
                if (sa.getMetadata().getName().equals(clusterName.concat("-connect"))) {
                    assertNull(sa.getMetadata().getLabels().get("app"));
                    assertEquals("KafkaConnect", sa.getMetadata().getLabels().get("strimzi.io/kind"));
                } else if (sa.getMetadata().getName().equals(clusterName.concat("-mirror-maker"))) {
                    assertNull(sa.getMetadata().getLabels().get("app"));
                    assertEquals("KafkaMirrorMaker", sa.getMetadata().getLabels().get("strimzi.io/kind"));
                } else {
                    assertEquals(appName, sa.getMetadata().getLabels().get("app"));
                    assertEquals("Kafka", sa.getMetadata().getLabels().get("strimzi.io/kind"));
                }
                assertEquals(clusterName, sa.getMetadata().getLabels().get("strimzi.io/cluster"));
            }
        );
    }

    void verifyLabelsForRoleBindings(String clusterName, String appName) {
        LOGGER.info("Verifying labels for Cluster Role bindings");
        kubeClient().listRoleBindings().stream()
            .filter(rb -> rb.getMetadata().getName().startsWith("strimzi-cluster-operator"))
            .forEach(rb -> {
                LOGGER.info("Verifying labels for cluster role {}", rb.getMetadata().getName());
                assertEquals("strimzi", rb.getMetadata().getLabels().get("app"));
            });

        kubeClient().listRoleBindings().stream()
            .filter(rb -> rb.getMetadata().getName().startsWith("strimzi-".concat(clusterName)))
            .forEach(rb -> {
                LOGGER.info("Verifying labels for cluster role {}", rb.getMetadata().getName());
                assertEquals(appName, rb.getMetadata().getLabels().get("app"));
                assertEquals(clusterName, rb.getMetadata().getLabels().get("strimzi.io/cluster"));
                assertEquals("Kafka", rb.getMetadata().getLabels().get("strimzi.io/kind"));
            }
        );
    }

    /**
     * Wait till all pods in specific namespace being deleted and recreate testing environment in case of some pods cannot be deleted.
     * @param time timeout in miliseconds
     * @throws Exception exception
     */
    void waitForDeletion(long time) throws Exception {
        List<Pod> pods = kubeClient().listPods().stream().filter(
            p -> !p.getMetadata().getName().startsWith(CLUSTER_OPERATOR_PREFIX)).collect(Collectors.toList());
        // Delete pods in case of kubernetes keep them up
        pods.forEach(p -> kubeClient().deletePod(p));

        LOGGER.info("Wait for {} ms after cleanup to make sure everything is deleted", time);
        Thread.sleep(time);

        // Collect pods again after proper removal
        pods = kubeClient().listPods().stream().filter(
            p -> !p.getMetadata().getName().startsWith(CLUSTER_OPERATOR_PREFIX)).collect(Collectors.toList());
        long podCount = pods.size();

        StringBuilder nonTerminated = new StringBuilder();
        if (podCount > 0) {
            pods.forEach(
                p -> nonTerminated.append("\n").append(p.getMetadata().getName()).append(" - ").append(p.getStatus().getPhase())
            );
            throw new Exception("There are some unexpected pods! Cleanup is not finished properly!" + nonTerminated);
        }
    }

    /**
     * Recreate namespace and CO after test failure
     * @param coNamespace namespace where CO will be deployed to
     * @param bindingsNamespaces array of namespaces where Bindings should be deployed to.
     */
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        recreateTestEnv(coNamespace, bindingsNamespaces, Constants.CO_OPERATION_TIMEOUT_DEFAULT);
    }

    /**
     * Recreate namespace and CO after test failure
     * @param coNamespace namespace where CO will be deployed to
     * @param bindingsNamespaces array of namespaces where Bindings should be deployed to.
     * @param operationTimeout timeout for CO operations
     */
    protected void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces, long operationTimeout) {
        testClassResources.deleteResources();

        deleteClusterOperatorInstallFiles();
        deleteNamespaces();

        createNamespaces(coNamespace, bindingsNamespaces);
        applyClusterOperatorInstallFiles();

        setTestClassResources(new Resources(kubeClient()));

        applyRoleBindings(coNamespace, bindingsNamespaces);
        // 050-Deployment
        testClassResources().clusterOperator(coNamespace, operationTimeout).done();
    }

    /**
     * Method for apply Strimzi cluster operator specific Role and ClusterRole bindings for specific namespaces.
     * @param namespace namespace where CO will be deployed to
     * @param bindingsNamespaces list of namespaces where Bindings should be deployed to
     */
    public  static void applyRoleBindings(String namespace, List<String> bindingsNamespaces) {
        for (String bindingsNamespace : bindingsNamespaces) {
            // 020-RoleBinding
            testClassResources.roleBinding("../install/cluster-operator/020-RoleBinding-strimzi-cluster-operator.yaml", namespace, bindingsNamespace);
            // 021-ClusterRoleBinding
            testClassResources.clusterRoleBinding("../install/cluster-operator/021-ClusterRoleBinding-strimzi-cluster-operator.yaml", namespace, bindingsNamespace);
            // 030-ClusterRoleBinding
            testClassResources.clusterRoleBinding("../install/cluster-operator/030-ClusterRoleBinding-strimzi-cluster-operator-kafka-broker-delegation.yaml", namespace, bindingsNamespace);
            // 031-RoleBinding
            testClassResources.roleBinding("../install/cluster-operator/031-RoleBinding-strimzi-cluster-operator-entity-operator-delegation.yaml", namespace, bindingsNamespace);
            // 032-RoleBinding
            testClassResources.roleBinding("../install/cluster-operator/032-RoleBinding-strimzi-cluster-operator-topic-operator-delegation.yaml", namespace, bindingsNamespace);
        }
    }

    /**
     * Method for apply Strimzi cluster operator specific Role and ClusterRole bindings for specific namespaces.
     * @param namespace namespace where CO will be deployed to
     */
    public static void applyRoleBindings(String namespace) {
        applyRoleBindings(namespace, Collections.singletonList(namespace));
    }

    /**
     * Method for apply Strimzi cluster operator specific Role and ClusterRole bindings for specific namespaces.
     * @param namespace namespace where CO will be deployed to
     * @param bindingsNamespaces array of namespaces where Bindings should be deployed to
     */
    public static void applyRoleBindings(String namespace, String... bindingsNamespaces) {
        applyRoleBindings(namespace, Arrays.asList(bindingsNamespaces));
    }

    /**
     * Deploy CO via helm chart. Using config file stored in test resources.
     */
    public void deployClusterOperatorViaHelmChart() {
        String dockerOrg = Environment.STRIMZI_ORG;
        String dockerTag = Environment.STRIMZI_TAG;

        Map<String, String> values = Collections.unmodifiableMap(Stream.of(
                entry("imageRepositoryOverride", dockerOrg),
                entry("imageTagOverride", dockerTag),
                entry("image.pullPolicy", Constants.IMAGE_PULL_POLICY),
                entry("resources.requests.memory", REQUESTS_MEMORY),
                entry("resources.requests.cpu", REQUESTS_CPU),
                entry("resources.limits.memory", LIMITS_MEMORY),
                entry("resources.limits.cpu", LIMITS_CPU),
                entry("logLevel", Environment.STRIMZI_LOG_LEVEL))
                .collect(TestUtils.entriesToMap()));

        LOGGER.info("Creating cluster operator with Helm Chart before test class {}", testClass);
        Path pathToChart = new File(HELM_CHART).toPath();
        String oldNamespace = setNamespace("kube-system");
        InputStream helmAccountAsStream = getClass().getClassLoader().getResourceAsStream("helm/helm-service-account.yaml");
        String helmServiceAccount = TestUtils.readResource(helmAccountAsStream);
        cmdKubeClient().applyContent(helmServiceAccount);
        helmClient().init();
        setNamespace(oldNamespace);
        helmClient().install(pathToChart, HELM_RELEASE_NAME, values);
    }

    /**
     * Delete CO deployed via helm chart.
     */
    public  void deleteClusterOperatorViaHelmChart() {
        LOGGER.info("Deleting cluster operator with Helm Chart after test class {}", testClass);
        helmClient().delete(HELM_RELEASE_NAME);
    }

    /**
     * Wait for cluster availability, check availability of external routes with TLS
     * @param userName user name
     * @param namespace cluster namespace
     * @param clusterName cluster name
     * @throws Exception exception
     */
    public void waitForClusterAvailabilityTls(String userName, String namespace, String clusterName) throws Exception {
        int messageCount = 50;
        String topicName = "test-topic-" + new Random().nextInt(Integer.MAX_VALUE);

        KafkaClient testClient = new KafkaClient();
        try {
            Future producer = testClient.sendMessagesTls(topicName, namespace, clusterName, userName, messageCount, "SSL");
            Future consumer = testClient.receiveMessagesTls(topicName, namespace, clusterName, userName, messageCount, "SSL");

            assertThat("Producer produced all messages", producer.get(1, TimeUnit.MINUTES), is(messageCount));
            assertThat("Consumer consumed all messages", consumer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    /**
     * Wait for cluster availability, check availability of external routes without TLS
     * @param namespace cluster namespace
     * @throws Exception
     */
    public void waitForClusterAvailability(String namespace) throws Exception {
        String topicName = "test-topic-" + new Random().nextInt(Integer.MAX_VALUE);
        waitForClusterAvailability(namespace, topicName);
    }


    /**
     * Wait for cluster availability, check availability of external routes without TLS
     * @param namespace cluster namespace
     * @param topicName topic name
     * @throws Exception
     */
    public void waitForClusterAvailability(String namespace, String topicName) throws Exception {
        int messageCount = 50;

        KafkaClient testClient = new KafkaClient();
        try {
            Future producer = testClient.sendMessages(topicName, namespace, CLUSTER_NAME, messageCount);
            Future consumer = testClient.receiveMessages(topicName, namespace, CLUSTER_NAME, messageCount);

            assertThat("Producer produced all messages", producer.get(1, TimeUnit.MINUTES), is(messageCount));
            assertThat("Consumer consumed all messages", consumer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void sendMessagesExternal(String namespace, String topicName, int messageCount) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future producer = testClient.sendMessages(topicName, namespace, CLUSTER_NAME, messageCount);

            assertThat("Producer produced all messages", producer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void sendMessagesExternalTls(String namespace, String topicName, int messageCount, String userName) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future producer = testClient.sendMessagesTls(topicName, namespace, CLUSTER_NAME, userName, messageCount, "SSL");

            assertThat("Producer produced all messages", producer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void sendMessagesExternalScramSha(String namespace, String topicName, int messageCount, String userName) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future producer = testClient.sendMessagesTls(topicName, namespace, CLUSTER_NAME, userName, messageCount, "SASL_SSL");

            assertThat("Producer produced all messages", producer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void receiveMessagesExternal(String namespace, String topicName, int messageCount) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future consumer = testClient.receiveMessages(topicName, namespace, CLUSTER_NAME, messageCount);

            assertThat("Consumer consumed all messages", consumer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void receiveMessagesExternalTls(String namespace, String topicName, int messageCount, String userName) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future consumer = testClient.receiveMessagesTls(topicName, namespace, CLUSTER_NAME, userName, messageCount, "SSL");

            assertThat("Consumer consumed all messages", consumer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    public void receiveMessagesExternalScramSha(String namespace, String topicName, int messageCount, String userName) throws Exception {

        KafkaClient testClient = new KafkaClient();
        try {
            Future consumer = testClient.receiveMessagesTls(topicName, namespace, CLUSTER_NAME, userName, messageCount, "SASL_SSL");

            assertThat("Consumer consumed all messages", consumer.get(1, TimeUnit.MINUTES), is(messageCount));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            throw e;
        } finally {
            testClient.close();
        }
    }

    protected void tearDownEnvironmentAfterEach() throws Exception {
        deleteTestMethodResources();
    }

    protected void tearDownEnvironmentAfterAll() {
        testClassResources.deleteResources();
    }

    @AfterEach
    void teardownEnvironmentMethod(ExtensionContext context) throws Exception {
        if (Environment.SKIP_TEARDOWN == null) {
            if (context.getExecutionException().isPresent()) {
                LOGGER.info("Test execution contains exception, going to recreate test environment");
                context.getExecutionException().get().printStackTrace();
                recreateTestEnv(clusterOperatorNamespace, bindingsNamespaces);
                LOGGER.info("Env recreated.");
            }
            tearDownEnvironmentAfterEach();
        }
    }

    @AfterAll
    void teardownEnvironmentClass() {
        if (Environment.SKIP_TEARDOWN == null) {
            tearDownEnvironmentAfterAll();
            teardownEnvForOperator();
        }
    }

    /**
     * Verifies container configuration by environment key
     * @param podNamePrefix Name of pod where container is located
     * @param containerName The container where verifying is expected
     * @param configKey Expected configuration key
     * @param config Expected configuration
     */
    void checkContainerConfiguration(String podNamePrefix, String containerName, String configKey, String config) {
        LOGGER.info("Getting pods by prefix in name {}", podNamePrefix);
        List<Pod> pods = kubeClient().listPodsByPrefixInName(podNamePrefix);

        if (pods.size() != 0) {
            LOGGER.info("Testing configuration for container {}", containerName);
            pods.forEach(pod -> {
                pod.getSpec().getContainers().stream().filter(c -> c.getName().equals(containerName))
                        .forEach(container -> {
                            container.getEnv().stream().filter(envVar -> envVar.getName().equals(configKey))
                                    .forEach(envVar -> assertEquals(config, envVar.getValue()));
                        });
            });
        } else {
            fail("Pod with prefix " + podNamePrefix + " in name, not found");
        }
    }
}
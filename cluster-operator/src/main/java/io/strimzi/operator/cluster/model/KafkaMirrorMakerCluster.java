/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateDeploymentBuilder;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.strimzi.api.kafka.model.CertAndKeySecretSource;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMakerAuthenticationPlain;
import io.strimzi.api.kafka.model.KafkaMirrorMakerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.KafkaMirrorMakerAuthenticationTls;
import io.strimzi.api.kafka.model.KafkaMirrorMakerClientSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMakerConsumerSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMakerProducerSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaMirrorMakerSpec;
import io.strimzi.api.kafka.model.PasswordSecretSource;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.template.KafkaMirrorMakerTemplate;
import io.strimzi.api.kafka.model.tracing.JaegerTracing;
import io.strimzi.api.kafka.model.tracing.Tracing;
import io.strimzi.operator.common.model.Labels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class KafkaMirrorMakerCluster extends AbstractModel {
    protected static final String TLS_CERTS_VOLUME_MOUNT_CONSUMER = "/opt/kafka/consumer-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT_CONSUMER = "/opt/kafka/consumer-password/";
    protected static final String TLS_CERTS_VOLUME_MOUNT_PRODUCER = "/opt/kafka/producer-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT_PRODUCER = "/opt/kafka/producer-password/";

    // Configuration defaults
    protected static final int DEFAULT_REPLICAS = 3;
    private static final int DEFAULT_HEALTHCHECK_DELAY = 60;
    private static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    private static final int DEFAULT_HEALTHCHECK_PERIOD = 10;
    public static final Probe READINESS_PROBE_OPTIONS = new ProbeBuilder().withTimeoutSeconds(DEFAULT_HEALTHCHECK_TIMEOUT).withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY).build();
    protected static final boolean DEFAULT_KAFKA_MIRRORMAKER_METRICS_ENABLED = false;

    // Kafka Mirror Maker configuration keys (EnvVariables)
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_METRICS_ENABLED = "KAFKA_MIRRORMAKER_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER = "KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_CONSUMER = "KAFKA_MIRRORMAKER_TLS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER = "KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_CONSUMER = "KAFKA_MIRRORMAKER_TLS_AUTH_CERT_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_CONSUMER = "KAFKA_MIRRORMAKER_TLS_AUTH_KEY_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_CONSUMER = "KAFKA_MIRRORMAKER_SASL_MECHANISM_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_CONSUMER = "KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_CONSUMER = "KAFKA_MIRRORMAKER_SASL_USERNAME_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_GROUPID_CONSUMER = "KAFKA_MIRRORMAKER_GROUPID_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER = "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER = "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER";

    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER = "KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_PRODUCER = "KAFKA_MIRRORMAKER_TLS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER = "KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_PRODUCER = "KAFKA_MIRRORMAKER_TLS_AUTH_CERT_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_PRODUCER = "KAFKA_MIRRORMAKER_TLS_AUTH_KEY_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_PRODUCER = "KAFKA_MIRRORMAKER_SASL_MECHANISM_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_PRODUCER = "KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_PRODUCER = "KAFKA_MIRRORMAKER_SASL_USERNAME_PRODUCER";

    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_WHITELIST = "KAFKA_MIRRORMAKER_WHITELIST";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_NUMSTREAMS = "KAFKA_MIRRORMAKER_NUMSTREAMS";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL = "KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE = "KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE";

    protected static final String ENV_VAR_STRIMZI_READINESS_PERIOD = "STRIMZI_READINESS_PERIOD";
    protected static final String ENV_VAR_STRIMZI_LIVENESS_PERIOD = "STRIMZI_LIVENESS_PERIOD";
    protected static final String ENV_VAR_STRIMZI_TRACING = "STRIMZI_TRACING";

    protected String whitelist;
    protected Tracing tracing;

    protected KafkaMirrorMakerProducerSpec producer;
    protected CertAndKeySecretSource producerTlsAuthCertAndKey;
    private String producerSaslMechanism;
    private String producerUsername;
    private PasswordSecretSource producerPasswordSecret;
    protected KafkaMirrorMakerConsumerSpec consumer;
    protected CertAndKeySecretSource consumerTlsAuthCertAndKey;
    private String consumerSaslMechanism;
    private String consumerUsername;
    private PasswordSecretSource consumerPasswordSecret;
    protected List<ContainerEnvVar> templateContainerEnvVars;

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Kafka Mirror Maker cluster resources are going to be created
     * @param cluster   overall cluster name
     * @param labels    labels to add to the cluster
     */
    protected KafkaMirrorMakerCluster(String namespace, String cluster, Labels labels) {
        super(namespace, cluster, labels);
        this.name = KafkaMirrorMakerResources.deploymentName(cluster);
        this.serviceName = KafkaMirrorMakerResources.serviceName(cluster);
        this.ancillaryConfigName = KafkaMirrorMakerResources.metricsAndLogConfigMapName(cluster);
        this.replicas = DEFAULT_REPLICAS;
        this.readinessPath = "/";
        this.readinessProbeOptions = READINESS_PROBE_OPTIONS;
        this.livenessPath = "/";
        this.livenessProbeOptions = READINESS_PROBE_OPTIONS;
        this.isMetricsEnabled = DEFAULT_KAFKA_MIRRORMAKER_METRICS_ENABLED;

        this.mountPath = "/var/lib/kafka";
        this.logAndMetricsConfigVolumeName = "kafka-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";
    }

    private static void setClientAuth(KafkaMirrorMakerCluster kafkaMirrorMakerCluster, KafkaMirrorMakerClientSpec client) {
        if (client != null && client.getAuthentication() != null) {
            if (client.getAuthentication() instanceof KafkaMirrorMakerAuthenticationTls) {
                KafkaMirrorMakerAuthenticationTls clientAuth = (KafkaMirrorMakerAuthenticationTls) client.getAuthentication();
                CertAndKeySecretSource clientCertificateAndKey = clientAuth.getCertificateAndKey();
                if (clientCertificateAndKey != null) {
                    if (client.getTls() == null) {
                        log.warn("TLS configuration missing: related TLS client authentication will not work properly");
                    }
                    if (client instanceof KafkaMirrorMakerConsumerSpec)
                        kafkaMirrorMakerCluster.setConsumerTlsAuthCertAndKey(clientCertificateAndKey);
                    else if (client instanceof KafkaMirrorMakerProducerSpec)
                        kafkaMirrorMakerCluster.setProducerTlsAuthCertAndKey(clientCertificateAndKey);
                } else {
                    log.warn("TLS Client authentication selected, but no certificate and key configured.");
                    throw new InvalidResourceException("TLS Client authentication selected, but no certificate and key configured.");
                }
            } else if (client.getAuthentication() instanceof KafkaMirrorMakerAuthenticationScramSha512) {
                KafkaMirrorMakerAuthenticationScramSha512 auth = (KafkaMirrorMakerAuthenticationScramSha512) client.getAuthentication();
                if (auth.getUsername() != null && auth.getPasswordSecret() != null) {
                    if (client instanceof KafkaMirrorMakerConsumerSpec)
                        kafkaMirrorMakerCluster.setConsumerUsernameAndPassword(auth.getType(), auth.getUsername(), auth.getPasswordSecret());
                    else if (client instanceof KafkaMirrorMakerProducerSpec)
                        kafkaMirrorMakerCluster.setProducerUsernameAndPassword(auth.getType(), auth.getUsername(), auth.getPasswordSecret());
                } else {
                    log.warn("SCRAM-SHA-512 authentication selected, but no username and password configured.");
                    throw new InvalidResourceException("SCRAM-SHA-512 authentication selected, but no username and password configured.");
                }
            } else if (client.getAuthentication() instanceof KafkaMirrorMakerAuthenticationPlain) {
                KafkaMirrorMakerAuthenticationPlain auth = (KafkaMirrorMakerAuthenticationPlain) client.getAuthentication();
                if (auth.getUsername() != null && auth.getPasswordSecret() != null) {
                    if (client instanceof KafkaMirrorMakerConsumerSpec)
                        kafkaMirrorMakerCluster.setConsumerUsernameAndPassword(auth.getType(), auth.getUsername(), auth.getPasswordSecret());
                    else if (client instanceof KafkaMirrorMakerProducerSpec)
                        kafkaMirrorMakerCluster.setProducerUsernameAndPassword(auth.getType(), auth.getUsername(), auth.getPasswordSecret());
                } else {
                    log.warn("PLAIN authentication selected, but no username and password configured.");
                    throw new InvalidResourceException("PLAIN authentication selected, but no username and password configured.");
                }
            }
        }
    }

    public static KafkaMirrorMakerCluster fromCrd(KafkaMirrorMaker kafkaMirrorMaker, KafkaVersion.Lookup versions) {
        KafkaMirrorMakerCluster kafkaMirrorMakerCluster = new KafkaMirrorMakerCluster(kafkaMirrorMaker.getMetadata().getNamespace(),
                kafkaMirrorMaker.getMetadata().getName(),
                Labels.fromResource(kafkaMirrorMaker).withKind(kafkaMirrorMaker.getKind()));

        KafkaMirrorMakerSpec spec = kafkaMirrorMaker.getSpec();
        kafkaMirrorMakerCluster.setReplicas(spec != null && spec.getReplicas() > 0 ? spec.getReplicas() : DEFAULT_REPLICAS);
        if (spec != null) {
            kafkaMirrorMakerCluster.setResources(spec.getResources());

            if (spec.getReadinessProbe() != null) {
                kafkaMirrorMakerCluster.setReadinessProbe(spec.getReadinessProbe());
            }

            if (spec.getLivenessProbe() != null) {
                kafkaMirrorMakerCluster.setLivenessProbe(spec.getLivenessProbe());
            }

            kafkaMirrorMakerCluster.setWhitelist(spec.getWhitelist());
            kafkaMirrorMakerCluster.setProducer(spec.getProducer());
            kafkaMirrorMakerCluster.setConsumer(spec.getConsumer());

            kafkaMirrorMakerCluster.setImage(versions.kafkaMirrorMakerImage(spec.getImage(), spec.getVersion()));

            kafkaMirrorMakerCluster.setLogging(spec.getLogging());
            kafkaMirrorMakerCluster.setGcLoggingEnabled(spec.getJvmOptions() == null ? true : spec.getJvmOptions().isGcLoggingEnabled());
            kafkaMirrorMakerCluster.setJvmOptions(spec.getJvmOptions());

            Map<String, Object> metrics = spec.getMetrics();
            if (metrics != null) {
                kafkaMirrorMakerCluster.setMetricsEnabled(true);
                kafkaMirrorMakerCluster.setMetricsConfig(metrics.entrySet());
            }

            setClientAuth(kafkaMirrorMakerCluster, spec.getConsumer());
            setClientAuth(kafkaMirrorMakerCluster, spec.getProducer());

            if (spec.getTemplate() != null) {
                KafkaMirrorMakerTemplate template = spec.getTemplate();

                if (template.getDeployment() != null && template.getDeployment().getMetadata() != null) {
                    kafkaMirrorMakerCluster.templateDeploymentLabels = template.getDeployment().getMetadata().getLabels();
                    kafkaMirrorMakerCluster.templateDeploymentAnnotations = template.getDeployment().getMetadata().getAnnotations();
                }

                if (template.getMirrorMakerContainer() != null && template.getMirrorMakerContainer().getEnv() != null) {
                    kafkaMirrorMakerCluster.templateContainerEnvVars = template.getMirrorMakerContainer().getEnv();
                }

                ModelUtils.parsePodTemplate(kafkaMirrorMakerCluster, template.getPod());
                ModelUtils.parsePodDisruptionBudgetTemplate(kafkaMirrorMakerCluster, template.getPodDisruptionBudget());
            }

            kafkaMirrorMakerCluster.setUserAffinity(affinity(spec));
            kafkaMirrorMakerCluster.setTolerations(tolerations(spec));
            kafkaMirrorMakerCluster.tracing = spec.getTracing();
        }

        kafkaMirrorMakerCluster.setOwnerReference(kafkaMirrorMaker);

        return kafkaMirrorMakerCluster;
    }

    @SuppressWarnings("deprecation")
    static List<Toleration> tolerations(KafkaMirrorMakerSpec spec) {
        if (spec.getTemplate() != null
                && spec.getTemplate().getPod() != null
                && spec.getTemplate().getPod().getTolerations() != null) {
            if (spec.getTolerations() != null) {
                log.warn("Tolerations given on both spec.tolerations and spec.template.deployment.tolerations; latter takes precedence");
            }
            return spec.getTemplate().getPod().getTolerations();
        } else {
            return spec.getTolerations();
        }
    }

    @SuppressWarnings("deprecation")
    static Affinity affinity(KafkaMirrorMakerSpec spec) {
        if (spec.getTemplate() != null
                && spec.getTemplate().getPod() != null
                && spec.getTemplate().getPod().getAffinity() != null) {
            if (spec.getAffinity() != null) {
                log.warn("Affinity given on both spec.affinity and spec.template.deployment.affinity; latter takes precedence");
            }
            return spec.getTemplate().getPod().getAffinity();
        } else {
            return spec.getAffinity();
        }
    }

    public Service generateService() {
        List<ServicePort> ports = new ArrayList<>(1);
        if (isMetricsEnabled()) {
            ports.add(createServicePort(METRICS_PORT_NAME, METRICS_PORT, METRICS_PORT, "TCP"));
            return createService("ClusterIP", ports, getPrometheusAnnotations());
        } else {
            return null;
        }
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(1);
        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    protected List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(1);
        volumeList.add(createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigName));

        createClientSecretVolume(producer, producerTlsAuthCertAndKey, producerPasswordSecret, volumeList, isOpenShift);
        createClientSecretVolume(consumer, consumerTlsAuthCertAndKey, consumerPasswordSecret, volumeList, isOpenShift);

        return volumeList;
    }

    protected void createClientSecretVolume(KafkaMirrorMakerClientSpec client, CertAndKeySecretSource clientTlsAuthCertAndKey, PasswordSecretSource clientPasswordSecret, List<Volume> volumeList, boolean isOpenShift) {
        if (client.getTls() != null && client.getTls().getTrustedCertificates() != null && client.getTls().getTrustedCertificates().size() > 0) {
            for (CertSecretSource certSecretSource: client.getTls().getTrustedCertificates()) {
                // skipping if a volume with same Secret name was already added
                if (!volumeList.stream().anyMatch(v -> v.getName().equals(certSecretSource.getSecretName()))) {
                    volumeList.add(createSecretVolume(certSecretSource.getSecretName(), certSecretSource.getSecretName(), isOpenShift));
                }
            }
        }

        if (clientTlsAuthCertAndKey != null) {
            if (!volumeList.stream().anyMatch(v -> v.getName().equals(clientTlsAuthCertAndKey.getSecretName()))) {
                volumeList.add(createSecretVolume(clientTlsAuthCertAndKey.getSecretName(), clientTlsAuthCertAndKey.getSecretName(), isOpenShift));
            }
        } else if (clientPasswordSecret != null)  {
            volumeList.add(createSecretVolume(clientPasswordSecret.getSecretName(), clientPasswordSecret.getSecretName(), isOpenShift));
        }
    }

    protected List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(1);
        volumeMountList.add(createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));

        /** producer auth*/
        if (producer.getTls() != null && producer.getTls().getTrustedCertificates() != null && producer.getTls().getTrustedCertificates().size() > 0) {
            for (CertSecretSource certSecretSource: producer.getTls().getTrustedCertificates()) {
                // skipping if a volume mount with same Secret name was already added
                if (!volumeMountList.stream().anyMatch(vm -> vm.getName().equals(certSecretSource.getSecretName()))) {
                    volumeMountList.add(createVolumeMount(certSecretSource.getSecretName(),
                            TLS_CERTS_VOLUME_MOUNT_PRODUCER + certSecretSource.getSecretName()));
                }
            }
        }
        if (producerTlsAuthCertAndKey != null) {
            // skipping if a volume mount with same Secret name was already added
            if (!volumeMountList.stream().anyMatch(vm -> vm.getName().equals(producerTlsAuthCertAndKey.getSecretName()))) {
                volumeMountList.add(createVolumeMount(producerTlsAuthCertAndKey.getSecretName(),
                        TLS_CERTS_VOLUME_MOUNT_PRODUCER + producerTlsAuthCertAndKey.getSecretName()));
            }
        } else if (producerPasswordSecret != null)  {
            volumeMountList.add(createVolumeMount(producerPasswordSecret.getSecretName(),
                    PASSWORD_VOLUME_MOUNT_PRODUCER + producerPasswordSecret.getSecretName()));
        }

        /** consumer auth*/
        if (consumer.getTls() != null && consumer.getTls().getTrustedCertificates() != null && consumer.getTls().getTrustedCertificates().size() > 0) {
            for (CertSecretSource certSecretSource: consumer.getTls().getTrustedCertificates()) {
                // skipping if a volume mount with same Secret name was already added
                if (!volumeMountList.stream().anyMatch(vm -> vm.getName().equals(certSecretSource.getSecretName()))) {
                    volumeMountList.add(createVolumeMount(certSecretSource.getSecretName(),
                            TLS_CERTS_VOLUME_MOUNT_CONSUMER + certSecretSource.getSecretName()));
                }
            }
        }
        if (consumerTlsAuthCertAndKey != null) {
            // skipping if a volume mount with same Secret name was already added
            if (!volumeMountList.stream().anyMatch(vm -> vm.getName().equals(consumerTlsAuthCertAndKey.getSecretName()))) {
                volumeMountList.add(createVolumeMount(consumerTlsAuthCertAndKey.getSecretName(),
                        TLS_CERTS_VOLUME_MOUNT_CONSUMER + consumerTlsAuthCertAndKey.getSecretName()));
            }
        } else if (consumerPasswordSecret != null)  {
            volumeMountList.add(createVolumeMount(consumerPasswordSecret.getSecretName(),
                    PASSWORD_VOLUME_MOUNT_CONSUMER + consumerPasswordSecret.getSecretName()));
        }

        return volumeMountList;
    }

    public Deployment generateDeployment(Map<String, String> annotations, boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        DeploymentStrategy updateStrategy = new DeploymentStrategyBuilder()
                .withType("RollingUpdate")
                .withRollingUpdate(new RollingUpdateDeploymentBuilder()
                        .withMaxSurge(new IntOrString(1))
                        .withMaxUnavailable(new IntOrString(0))
                        .build())
                .build();

        return createDeployment(
                updateStrategy,
                Collections.emptyMap(),
                annotations,
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                getVolumes(isOpenShift),
                imagePullSecrets);
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        List<Container> containers = new ArrayList<>();

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(getImage())
                .withCommand("/opt/kafka/kafka_mirror_maker_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ModelUtils.newProbeBuilder(livenessProbeOptions)
                        .withNewExec()
                        .withCommand("/opt/kafka/kafka_mirror_maker_liveness.sh")
                        .endExec().build())
                .withReadinessProbe(ModelUtils.newProbeBuilder(readinessProbeOptions)
                        .withNewExec()
                        // The mirror-maker-agent will create /tmp/mirror-maker-ready in the container
                        .withCommand("test", "-f", "/tmp/mirror-maker-ready")
                        .endExec().build())
                .withVolumeMounts(getVolumeMounts())
                .withResources(getResources())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .build();

        containers.add(container);

        return containers;
    }

    private KafkaMirrorMakerConsumerConfiguration getConsumerConfiguration()    {
        KafkaMirrorMakerConsumerConfiguration config = new KafkaMirrorMakerConsumerConfiguration(consumer.getConfig().entrySet());

        if (tracing != null && JaegerTracing.TYPE_JAEGER.equals(tracing.getType())) {
            config.setConfigOption("interceptor.classes", "io.opentracing.contrib.kafka.TracingConsumerInterceptor");
        }

        return config;
    }

    private KafkaMirrorMakerProducerConfiguration getProducerConfiguration()    {
        KafkaMirrorMakerProducerConfiguration config = new KafkaMirrorMakerProducerConfiguration(producer.getConfig().entrySet());

        if (tracing != null && JaegerTracing.TYPE_JAEGER.equals(tracing.getType())) {
            config.setConfigOption("interceptor.classes", "io.opentracing.contrib.kafka.TracingProducerInterceptor");
        }

        return config;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER,
                getConsumerConfiguration().getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER,
                getProducerConfiguration().getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER, consumer.getBootstrapServers()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER, producer.getBootstrapServers()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_WHITELIST, whitelist));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_GROUPID_CONSUMER, consumer.getGroupId()));
        if (consumer.getNumStreams() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_NUMSTREAMS, Integer.toString(consumer.getNumStreams())));
        }
        if (consumer.getOffsetCommitInterval() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL, Integer.toString(consumer.getOffsetCommitInterval())));
        }
        if (producer.getAbortOnSendFailure() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE, Boolean.toString(producer.getAbortOnSendFailure())));
        }
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        if (tracing != null) {
            varList.add(buildEnvVar(ENV_VAR_STRIMZI_TRACING, tracing.getType()));
        }

        heapOptions(varList, 1.0, 0L);
        jvmPerformanceOptions(varList);

        /** consumer */
        addConsumerEnvVars(varList);

        /** producer */
        addProducerEnvVars(varList);

        varList.add(buildEnvVar(ENV_VAR_STRIMZI_LIVENESS_PERIOD,
                String.valueOf(livenessProbeOptions.getPeriodSeconds() != null ? livenessProbeOptions.getPeriodSeconds() : DEFAULT_HEALTHCHECK_PERIOD)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_READINESS_PERIOD,
                String.valueOf(readinessProbeOptions.getPeriodSeconds() != null ? readinessProbeOptions.getPeriodSeconds() : DEFAULT_HEALTHCHECK_PERIOD)));

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        return varList;
    }

    /**
     * Sets the consumer related environment variables in the provided List.
     *
     * @param varList   List with environment variables
     */
    private void addConsumerEnvVars(List<EnvVar> varList) {
        if (consumer.getTls() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_CONSUMER, "true"));

            if (consumer.getTls().getTrustedCertificates() != null && consumer.getTls().getTrustedCertificates().size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : consumer.getTls().getTrustedCertificates()) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER, sb.toString()));
            }
        }

        if (consumerTlsAuthCertAndKey != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_CONSUMER,
                    String.format("%s/%s", consumerTlsAuthCertAndKey.getSecretName(), consumerTlsAuthCertAndKey.getCertificate())));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_CONSUMER,
                    String.format("%s/%s", consumerTlsAuthCertAndKey.getSecretName(), consumerTlsAuthCertAndKey.getKey())));
        } else if (consumerPasswordSecret != null)  {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_CONSUMER, consumerSaslMechanism));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_CONSUMER, consumerUsername));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_CONSUMER,
                    String.format("%s/%s", consumerPasswordSecret.getSecretName(), consumerPasswordSecret.getPassword())));
        }
    }

    /**
     * Sets the producer related environment variables in the provided List.
     *
     * @param varList   List with environment variables
     */
    private void addProducerEnvVars(List<EnvVar> varList) {
        if (producer.getTls() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_PRODUCER, "true"));

            if (producer.getTls().getTrustedCertificates() != null && producer.getTls().getTrustedCertificates().size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : producer.getTls().getTrustedCertificates()) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER, sb.toString()));
            }
        }

        if (producerTlsAuthCertAndKey != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_PRODUCER,
                    String.format("%s/%s", producerTlsAuthCertAndKey.getSecretName(), producerTlsAuthCertAndKey.getCertificate())));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_PRODUCER,
                    String.format("%s/%s", producerTlsAuthCertAndKey.getSecretName(), producerTlsAuthCertAndKey.getKey())));
        } else if (producerPasswordSecret != null)  {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_PRODUCER, producerSaslMechanism));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_PRODUCER, producerUsername));
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_PRODUCER,
                    String.format("%s/%s", producerPasswordSecret.getSecretName(), producerPasswordSecret.getPassword())));
        }
    }

    /**
     * Generates the PodDisruptionBudget.
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return createPodDisruptionBudget();
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "mirrorMakerDefaultLoggingProperties";
    }

    public void setWhitelist(String whitelist) {
        this.whitelist = whitelist;
    }

    public void setProducer(KafkaMirrorMakerProducerSpec producer) {
        this.producer = producer;
    }

    public void setConsumer(KafkaMirrorMakerConsumerSpec consumer) {
        this.consumer = consumer;
    }

    public void setConsumerTlsAuthCertAndKey(CertAndKeySecretSource consumerTlsAuthCertAndKey) {
        this.consumerTlsAuthCertAndKey = consumerTlsAuthCertAndKey;
    }

    public void setProducerTlsAuthCertAndKey(CertAndKeySecretSource producerTlsAuthCertAndKey) {
        this.producerTlsAuthCertAndKey = producerTlsAuthCertAndKey;
    }

    private void setConsumerUsernameAndPassword(String saslMechanism, String username, PasswordSecretSource passwordSecret) {
        this.consumerSaslMechanism = saslMechanism;
        this.consumerUsername = username;
        this.consumerPasswordSecret = passwordSecret;
    }

    private void setProducerUsernameAndPassword(String saslMechanism, String username, PasswordSecretSource passwordSecret) {
        this.producerSaslMechanism = saslMechanism;
        this.producerUsername = username;
        this.producerPasswordSecret = passwordSecret;
    }

    protected String getWhitelist() {
        return whitelist;
    }

    @Override
    protected String getServiceAccountName() {
        return KafkaMirrorMakerResources.serviceAccountName(cluster);
    }
}

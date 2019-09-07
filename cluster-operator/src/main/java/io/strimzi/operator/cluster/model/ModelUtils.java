/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.strimzi.api.kafka.model.CertificateAuthority;
import io.strimzi.api.kafka.model.storage.JbodStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.TlsSidecar;
import io.strimzi.api.kafka.model.TlsSidecarLogLevel;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.PodTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.KafkaUpgradeException;
import io.strimzi.operator.common.model.Labels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public class ModelUtils {

    public static final io.strimzi.api.kafka.model.Probe DEFAULT_TLS_SIDECAR_PROBE = new io.strimzi.api.kafka.model.ProbeBuilder()
            .withInitialDelaySeconds(TlsSidecar.DEFAULT_HEALTHCHECK_DELAY)
            .withTimeoutSeconds(TlsSidecar.DEFAULT_HEALTHCHECK_TIMEOUT)
            .build();

    private ModelUtils() {}

    protected static final Logger log = LogManager.getLogger(ModelUtils.class.getName());

    public static final String KUBERNETES_SERVICE_DNS_DOMAIN =
            System.getenv().getOrDefault("KUBERNETES_SERVICE_DNS_DOMAIN", "cluster.local");

    /**
     * @param certificateAuthority The CA configuration.
     * @return The cert validity.
     */
    public static int getCertificateValidity(CertificateAuthority certificateAuthority) {
        int validity = CertificateAuthority.DEFAULT_CERTS_VALIDITY_DAYS;
        if (certificateAuthority != null
                && certificateAuthority.getValidityDays() > 0) {
            validity = certificateAuthority.getValidityDays();
        }
        return validity;
    }

    /**
     * @param certificateAuthority The CA configuration.
     * @return The renewal days.
     */
    public static int getRenewalDays(CertificateAuthority certificateAuthority) {
        int renewalDays = CertificateAuthority.DEFAULT_CERTS_RENEWAL_DAYS;

        if (certificateAuthority != null
                && certificateAuthority.getRenewalDays() > 0) {
            renewalDays = certificateAuthority.getRenewalDays();
        }

        return renewalDays;
    }

    /**
     * Generate labels used by entity-operators to find the resources related to given cluster
     *
     * @param cluster   Name of the cluster
     * @return  Map with label definition
     */
    public static String defaultResourceLabels(String cluster) {
        return String.format("%s=%s",
                Labels.STRIMZI_CLUSTER_LABEL, cluster);
    }

    /**
     * Parse a map from String.
     * For example a map of images {@code 2.0.0=strimzi/kafka:latest-kafka-2.0.0, 2.1.0=strimzi/kafka:latest-kafka-2.1.0}
     * or a map with labels / annotations {@code key1=value1 key2=value2}.
     *
     * @param str The string to parse.
     *
     * @return The parsed map.
     */
    public static Map<String, String> parseMap(String str) {
        if (str != null) {
            StringTokenizer tok = new StringTokenizer(str, ", \t\n\r");
            HashMap<String, String> map = new HashMap<>();
            while (tok.hasMoreTokens()) {
                String record = tok.nextToken();
                int endIndex = record.indexOf('=');
                String key = record.substring(0, endIndex);
                String value = record.substring(endIndex + 1);
                map.put(key.trim(), value.trim());
            }
            return Collections.unmodifiableMap(map);
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * @param ss The StatefulSet
     * @return The environment of the Kafka container in the SS.
     */
    public static Map<String, String> getKafkaContainerEnv(StatefulSet ss) {
        for (Container container : ss.getSpec().getTemplate().getSpec().getContainers()) {
            if ("kafka".equals(container.getName())) {
                LinkedHashMap<String, String> map = new LinkedHashMap<>(container.getEnv() == null ? 2 : container.getEnv().size());
                if (container.getEnv() != null) {
                    for (EnvVar envVar : container.getEnv()) {
                        map.put(envVar.getName(), envVar.getValue());
                    }
                }
                return map;
            }
        }
        throw new KafkaUpgradeException("Could not find 'kafka' container in StatefulSet " + ss.getMetadata().getName());
    }

    public static List<EnvVar> envAsList(Map<String, String> env) {
        ArrayList<EnvVar> result = new ArrayList<>(env.size());
        for (Map.Entry<String, String> entry : env.entrySet()) {
            result.add(new EnvVar(entry.getKey(), entry.getValue(), null));
        }
        return result;
    }

    protected static ProbeBuilder newProbeBuilder(io.strimzi.api.kafka.model.Probe userProbe) {
        return new ProbeBuilder()
                .withInitialDelaySeconds(userProbe.getInitialDelaySeconds())
                .withTimeoutSeconds(userProbe.getTimeoutSeconds())
                .withPeriodSeconds(userProbe.getPeriodSeconds())
                .withSuccessThreshold(userProbe.getSuccessThreshold())
                .withFailureThreshold(userProbe.getFailureThreshold());
    }


    protected static Probe createTcpSocketProbe(int port, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = ModelUtils.newProbeBuilder(userProbe)
                .withNewTcpSocket()
                .withNewPort()
                .withIntVal(port)
                .endPort()
                .endTcpSocket()
                .build();
        log.trace("Created TCP socket probe {}", probe);
        return probe;
    }

    protected static Probe createHttpProbe(String path, String port, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = ModelUtils.newProbeBuilder(userProbe).withNewHttpGet()
                .withPath(path)
                .withNewPort(port)
                .endHttpGet()
                .build();
        log.trace("Created http probe {}", probe);
        return probe;
    }

    static Probe createExecProbe(List<String> command, io.strimzi.api.kafka.model.Probe userProbe) {
        Probe probe = newProbeBuilder(userProbe).withNewExec()
                .withCommand(command)
                .endExec()
                .build();
        AbstractModel.log.trace("Created exec probe {}", probe);
        return probe;
    }

    static Probe tlsSidecarReadinessProbe(TlsSidecar tlsSidecar) {
        io.strimzi.api.kafka.model.Probe tlsSidecarReadinessProbe;
        if (tlsSidecar != null && tlsSidecar.getReadinessProbe() != null) {
            tlsSidecarReadinessProbe = tlsSidecar.getReadinessProbe();
        } else {
            tlsSidecarReadinessProbe = DEFAULT_TLS_SIDECAR_PROBE;
        }
        return createExecProbe(Arrays.asList("/opt/stunnel/stunnel_healthcheck.sh", "2181"), tlsSidecarReadinessProbe);
    }

    static Probe tlsSidecarLivenessProbe(TlsSidecar tlsSidecar) {
        io.strimzi.api.kafka.model.Probe tlsSidecarLivenessProbe;
        if (tlsSidecar != null && tlsSidecar.getLivenessProbe() != null) {
            tlsSidecarLivenessProbe = tlsSidecar.getLivenessProbe();
        } else {
            tlsSidecarLivenessProbe = DEFAULT_TLS_SIDECAR_PROBE;
        }
        return createExecProbe(Arrays.asList("/opt/stunnel/stunnel_healthcheck.sh", "2181"), tlsSidecarLivenessProbe);
    }

    public static final String TLS_SIDECAR_LOG_LEVEL = "TLS_SIDECAR_LOG_LEVEL";

    static EnvVar tlsSidecarLogEnvVar(TlsSidecar tlsSidecar) {
        return AbstractModel.buildEnvVar(TLS_SIDECAR_LOG_LEVEL,
                (tlsSidecar != null && tlsSidecar.getLogLevel() != null ?
                        tlsSidecar.getLogLevel() : TlsSidecarLogLevel.NOTICE).toValue());
    }

    public static Secret buildSecret(ClusterCa clusterCa, Secret secret, String namespace, String secretName, String commonName, String keyCertName, Labels labels, OwnerReference ownerReference) {
        Map<String, String> data = new HashMap<>();
        if (secret == null || clusterCa.certRenewed()) {
            log.debug("Generating certificates");
            try {
                log.debug(keyCertName + " certificate to generate");
                CertAndKey eoCertAndKey = clusterCa.generateSignedCert(commonName, Ca.IO_STRIMZI);
                data.put(keyCertName + ".key", eoCertAndKey.keyAsBase64String());
                data.put(keyCertName + ".crt", eoCertAndKey.certAsBase64String());
            } catch (IOException e) {
                log.warn("Error while generating certificates", e);
            }
            log.debug("End generating certificates");
        } else {
            data.put(keyCertName + ".key", secret.getData().get(keyCertName + ".key"));
            data.put(keyCertName + ".crt", secret.getData().get(keyCertName + ".crt"));
        }
        return createSecret(secretName, namespace, labels, ownerReference, data);
    }

    public static Secret createSecret(String name, String namespace, Labels labels, OwnerReference ownerReference, Map<String, String> data) {
        if (ownerReference == null) {
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .endMetadata()
                    .withData(data).build();
        } else {
            return new SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withOwnerReferences(ownerReference)
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .endMetadata()
                    .withData(data).build();
        }
    }

    /**
     * Parses the values from the PodDisruptionBudgetTemplate in CRD model into the component model
     *
     * @param model AbstractModel class where the values from the PodDisruptionBudgetTemplate should be set
     * @param pdb PodDisruptionBudgetTemplate with the values form the CRD
     */
    public static void parsePodDisruptionBudgetTemplate(AbstractModel model, PodDisruptionBudgetTemplate pdb)   {
        if (pdb != null)  {
            if (pdb.getMetadata() != null) {
                model.templatePodDisruptionBudgetLabels = pdb.getMetadata().getLabels();
                model.templatePodDisruptionBudgetAnnotations = pdb.getMetadata().getAnnotations();
            }

            model.templatePodDisruptionBudgetMaxUnavailable = pdb.getMaxUnavailable();
        }
    }

    /**
     * Parses the values from the PodTemplate in CRD model into the component model
     *
     * @param model AbstractModel class where the values from the PodTemplate should be set
     * @param pod PodTemplate with the values form the CRD
     */

    public static void parsePodTemplate(AbstractModel model, PodTemplate pod)   {
        if (pod != null)  {
            if (pod.getMetadata() != null) {
                model.templatePodLabels = pod.getMetadata().getLabels();
                model.templatePodAnnotations = pod.getMetadata().getAnnotations();
            }

            model.templateTerminationGracePeriodSeconds = pod.getTerminationGracePeriodSeconds();
            model.templateImagePullSecrets = pod.getImagePullSecrets();
            model.templateSecurityContext = pod.getSecurityContext();
            model.templatePodPriorityClassName = pod.getPriorityClassName();
        }
    }

    /**
     * Returns whether the given {@code Storage} instance is a persistent claim one or
     * a JBOD containing at least one persistent volume.
     *
     * @param storage the Storage instance to check
     * @return Whether the give Storage contains any persistent storage.
     */
    public static boolean containsPersistentStorage(Storage storage) {
        boolean isPersistentClaimStorage = storage instanceof PersistentClaimStorage;

        if (!isPersistentClaimStorage && storage instanceof JbodStorage) {
            isPersistentClaimStorage |= ((JbodStorage) storage).getVolumes()
                    .stream().anyMatch(volume -> volume instanceof PersistentClaimStorage);
        }
        return isPersistentClaimStorage;
    }

    /**
     * Returns the prefix used for volumes and persistent volume claims
     *
     * @param id identification number of the persistent storage
     * @return The volume prefix.
     */
    public static String getVolumePrefix(Integer id) {
        return id == null ? AbstractModel.VOLUME_NAME : AbstractModel.VOLUME_NAME + "-" + id;
    }

    public static Storage decodeStorageFromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, Storage.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String encodeStorageToJson(Storage storage) {
        try {
            return new ObjectMapper().writeValueAsString(storage);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets a map with custom labels or annotations from an environment variable
     *
     * @param envVarName Name of the environment variable which should be used as input
     *
     * @return A map with labels or annotations
     */
    public static Map<String, String> getCustomLabelsOrAnnotations(String envVarName)   {
        return parseMap(System.getenv().get(envVarName));
    }
}

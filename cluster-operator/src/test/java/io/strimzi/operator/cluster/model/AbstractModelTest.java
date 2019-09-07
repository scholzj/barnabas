/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.strimzi.api.kafka.model.JvmOptions;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.test.TestUtils;

import io.fabric8.kubernetes.api.model.OwnerReference;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AbstractModelTest {

    private static JvmOptions jvmOptions(String xmx, String xms) {
        JvmOptions result = new JvmOptions();
        result.setXms(xms);
        result.setXmx(xmx);
        return result;
    }

    @Test
    public void testJvmMemoryOptionsExplicit() {
        Map<String, String> env = getStringStringMap("4", "4",
                0.5, 4_000_000_000L, null);
        assertEquals("-Xms4 -Xmx4", env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }

    private Map<String, String> getStringStringMap(String xmx, String xms, double dynamicFraction, long dynamicMax,
                                                   ResourceRequirements resources) {
        AbstractModel am = new AbstractModel(null, null, Labels.forCluster("foo")) {
            @Override
            protected String getDefaultLogConfigFileName() {
                return "";
            }

            @Override
            protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
                return emptyList();
            }
        };
        am.setJvmOptions(jvmOptions(xmx, xms));
        am.setResources(resources);
        List<EnvVar> envVars = new ArrayList<>(1);
        am.heapOptions(envVars, dynamicFraction, dynamicMax);
        return envVars.stream().collect(Collectors.toMap(e -> e.getName(), e -> e.getValue()));
    }

    @Test
    public void testJvmMemoryOptionsXmsOnly() {
        Map<String, String> env = getStringStringMap(null, "4",
                0.5, 5_000_000_000L, null);
        assertEquals("-Xms4", env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }

    @Test
    public void testJvmMemoryOptionsXmxOnly() {
        Map<String, String> env = getStringStringMap("4", null,
                0.5, 5_000_000_000L, null);
        assertEquals("-Xmx4", env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }


    @Test
    public void testJvmMemoryOptionsDefaultWithNoMemoryLimitOrJvmOptions() {
        Map<String, String> env = getStringStringMap(null, null,
                0.5, 5_000_000_000L, null);
        assertEquals("-Xms" + AbstractModel.DEFAULT_JVM_XMS, env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals(null, env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }

    private ResourceRequirements getResourceLimit() {
        return new ResourceRequirementsBuilder()
                .addToLimits("memory", new Quantity("16000000000")).build();
    }

    @Test
    public void testJvmMemoryOptionsDefaultWithMemoryLimit() {
        Map<String, String> env = getStringStringMap(null, "4",
                0.5, 5_000_000_000L, getResourceLimit());
        assertEquals("-Xms4", env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals("0.5", env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals("5000000000", env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }

    @Test
    public void testJvmMemoryOptionsMemoryRequest() {
        Map<String, String> env = getStringStringMap(null, null,
                0.7, 10_000_000_000L, getResourceLimit());
        assertEquals(null, env.get(AbstractModel.ENV_VAR_KAFKA_HEAP_OPTS));
        assertEquals("0.7", env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_FRACTION));
        assertEquals("10000000000", env.get(AbstractModel.ENV_VAR_DYNAMIC_HEAP_MAX));
    }

    @Test
    public void testJvmPerformanceOptions() {
        JvmOptions opts = TestUtils.fromJson("{}", JvmOptions.class);

        assertNull(getPerformanceOptions(opts));

        opts = TestUtils.fromJson("{" +
                "  \"-server\": \"true\"" +
                "}", JvmOptions.class);

        assertEquals("-server", getPerformanceOptions(opts));

        opts = TestUtils.fromJson("{" +
                "    \"-XX\":" +
                "            {\"key1\": \"value1\"," +
                "            \"key2\": \"true\"," +
                "            \"key3\": false," +
                "            \"key4\": 10}" +
                "}", JvmOptions.class);

        assertEquals("-XX:key1=value1 -XX:+key2 -XX:-key3 -XX:key4=10", getPerformanceOptions(opts));
    }

    private String getPerformanceOptions(JvmOptions opts) {
        AbstractModel am = new AbstractModel(null, null, Labels.forCluster("foo")) {
            @Override
            protected String getDefaultLogConfigFileName() {
                return "";
            }

            @Override
            protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
                return emptyList();
            }
        };
        am.setJvmOptions(opts);
        List<EnvVar> envVars = new ArrayList<>(1);
        am.jvmPerformanceOptions(envVars);

        if (!envVars.isEmpty()) {
            return envVars.get(0).getValue();
        } else {
            return null;
        }
    }

    @Test
    public void testOwnerReference()    {
        Kafka kafka = new KafkaBuilder()
                .withNewMetadata()
                    .withName("my-cluster")
                .withNamespace("my-namespace")
                .endMetadata()
                .build();

        AbstractModel am = new AbstractModel(kafka.getMetadata().getNamespace(), kafka.getMetadata().getName(), Labels.forCluster("foo")) {
            @Override
            protected String getDefaultLogConfigFileName() {
                return "";
            }

            @Override
            protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
                return emptyList();
            }
        };
        am.setOwnerReference(kafka);

        OwnerReference ref = am.createOwnerReference();

        assertEquals(kafka.getApiVersion(), ref.getApiVersion());
        assertEquals(kafka.getKind(), ref.getKind());
        assertEquals(kafka.getMetadata().getName(), ref.getName());
        assertEquals(kafka.getMetadata().getUid(), ref.getUid());
    }

    @Test
    public void testDetermineImagePullPolicy()  {
        AbstractModel am = new AbstractModel("my-namespace", "my-cluster", Labels.forCluster("my-cluster")) {
            @Override
            protected String getDefaultLogConfigFileName() {
                return "";
            }

            @Override
            protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
                return emptyList();
            }
        };

        Assert.assertEquals(ImagePullPolicy.ALWAYS.toString(), am.determineImagePullPolicy(ImagePullPolicy.ALWAYS, "docker.io/repo/image:tag"));
        Assert.assertEquals(ImagePullPolicy.IFNOTPRESENT.toString(), am.determineImagePullPolicy(ImagePullPolicy.IFNOTPRESENT, "docker.io/repo/image:tag"));
        Assert.assertEquals(ImagePullPolicy.NEVER.toString(), am.determineImagePullPolicy(ImagePullPolicy.NEVER, "docker.io/repo/image:tag"));
        Assert.assertEquals(ImagePullPolicy.ALWAYS.toString(), am.determineImagePullPolicy(null, "docker.io/repo/image:latest"));
        Assert.assertEquals(ImagePullPolicy.IFNOTPRESENT.toString(), am.determineImagePullPolicy(null, "docker.io/repo/image:not-so-latest"));
    }
}

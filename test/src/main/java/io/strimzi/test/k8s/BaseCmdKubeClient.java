/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import io.strimzi.test.TestUtils;
import io.strimzi.test.executor.Exec;
import io.strimzi.test.executor.ExecResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.join;
import static java.util.Arrays.asList;

public abstract class BaseCmdKubeClient<K extends BaseCmdKubeClient<K>> implements KubeCmdClient<K> {

    private static final Logger LOGGER = LogManager.getLogger(BaseCmdKubeClient.class);

    private static final String CREATE = "create";
    private static final String APPLY = "apply";
    private static final String DELETE = "delete";

    public static final String DEPLOYMENT = "deployment";
    public static final String STATEFUL_SET = "statefulset";
    public static final String SERVICE = "service";
    public static final String CM = "cm";
    String namespace = defaultNamespace();

    protected abstract String cmd();

    @Override
    @SuppressWarnings("unchecked")
    public K deleteByName(String resourceType, String resourceName) {
        Exec.exec(namespacedCommand(DELETE, resourceType, resourceName));
        return (K) this;
    }

    protected static class Context implements AutoCloseable {
        @Override
        public void close() {

        }
    }

    private static final Context NOOP = new Context();


    @Override
    public abstract K clientWithAdmin();

    protected Context defaultContext() {
        return NOOP;
    }

    protected Context adminContext() {
        return defaultContext();
    }

    protected List<String> namespacedCommand(String... rest) {
        return namespacedCommand(asList(rest));
    }

    private List<String> namespacedCommand(List<String> rest) {
        List<String> result = new ArrayList<>();
        result.add(cmd());
        result.add("--namespace");
        result.add(namespace);
        result.addAll(rest);
        return result;
    }

    @Override
    public String get(String resource, String resourceName) {
        return Exec.exec(namespacedCommand("get", resource, resourceName, "-o", "yaml")).out();
    }

    @Override
    public String getEvents() {
        return Exec.exec(namespacedCommand("get", "events")).out();
    }

    @Override
    @SuppressWarnings("unchecked")
    public K create(File... files) {
        try (Context context = defaultContext()) {
            KubeClusterException error = execRecursive(CREATE, files, Comparator.comparing(File::getName));
            if (error != null) {
                throw error;
            }
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K apply(File... files) {
        try (Context context = defaultContext()) {
            KubeClusterException error = execRecursive(APPLY, files, Comparator.comparing(File::getName));
            if (error != null) {
                throw error;
            }
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K delete(File... files) {
        try (Context context = defaultContext()) {
            KubeClusterException error = execRecursive(DELETE, files, Comparator.comparing(File::getName).reversed());
            if (error != null) {
                throw error;
            }
            return (K) this;
        }
    }

    private KubeClusterException execRecursive(String subcommand, File[] files, Comparator<File> cmp) {
        KubeClusterException error = null;
        for (File f : files) {
            if (f.isFile()) {
                if (f.getName().endsWith(".yaml")) {
                    try {
                        Exec.exec(namespacedCommand(subcommand, "-f", f.getAbsolutePath()));
                    } catch (KubeClusterException e) {
                        if (error == null) {
                            error = e;
                        }
                    }
                }
            } else if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) {
                    Arrays.sort(children, cmp);
                    KubeClusterException e = execRecursive(subcommand, children, cmp);
                    if (error == null) {
                        error = e;
                    }
                }
            } else if (!f.exists()) {
                throw new RuntimeException(new NoSuchFileException(f.getPath()));
            }
        }
        return error;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K replace(File... files) {
        try (Context context = defaultContext()) {
            execRecursive("replace", files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K replaceContent(String yamlContent) {
        try (Context context = defaultContext()) {
            Exec.exec(yamlContent, namespacedCommand("replace", "-f", "-"));
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K applyContent(String yamlContent) {
        try (Context context = defaultContext()) {
            Exec.exec(yamlContent, namespacedCommand(APPLY, "-f", "-"));
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K deleteContent(String yamlContent) {
        try (Context context = defaultContext()) {
            Exec.exec(yamlContent, namespacedCommand(DELETE, "-f", "-"));
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K createNamespace(String name) {
        try (Context context = adminContext()) {
            Exec.exec(namespacedCommand(CREATE, "namespace", name));
        }
        return (K) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K deleteNamespace(String name) {
        try (Context context = adminContext()) {
            Exec.exec(namespacedCommand(DELETE, "namespace", name));
        }
        return (K) this;
    }

    @Override
    public ExecResult execInPod(String pod, String... command) {
        List<String> cmd = namespacedCommand("exec", pod, "--");
        cmd.addAll(asList(command));
        return Exec.exec(cmd);
    }

    @Override
    public ExecResult execInPodContainer(String pod, String container, String... command) {
        List<String> cmd = namespacedCommand("exec", pod, "-c", container, "--");
        cmd.addAll(asList(command));
        return Exec.exec(cmd);
    }

    @Override
    public ExecResult exec(String... command) {
        return Exec.exec(asList(command));
    }

    enum ExType {
        BREAK,
        CONTINUE,
        THROW
    }

    @SuppressWarnings("unchecked")
    private K waitFor(String resource, String name, Predicate<JsonNode> ready) {
        long timeoutMs = 570_000L;
        long pollMs = 1_000L;
        ObjectMapper mapper = new ObjectMapper();
        TestUtils.waitFor(resource + " " + name, pollMs, timeoutMs, () -> {
            try {
                String jsonString = Exec.exec(namespacedCommand("get", resource, name, "-o", "json")).out();
                LOGGER.trace("{}", jsonString);
                JsonNode actualObj = mapper.readTree(jsonString);
                return ready.test(actualObj);
            } catch (KubeClusterException.NotFound e) {
                return false;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return (K) this;
    }

    @Override
    public K waitForDeployment(String name, int expected) {
        return waitFor("deployment", name, actualObj -> {
            JsonNode replicasNode = actualObj.get("status").get("replicas");
            JsonNode readyReplicasName = actualObj.get("status").get("readyReplicas");
            return replicasNode != null && readyReplicasName != null
                    && replicasNode.asInt() == readyReplicasName.asInt() && replicasNode.asInt() == expected;

        });
    }

    @Override
    public K waitForDeploymentConfig(String name) {
        return waitFor("deploymentConfig", name, actualObj -> {
            JsonNode replicasNode = actualObj.get("status").get("replicas");
            JsonNode readyReplicasName = actualObj.get("status").get("readyReplicas");
            return replicasNode != null && readyReplicasName != null
                    && replicasNode.asInt() == readyReplicasName.asInt();
        });
    }

    @Override
    public K waitForResourceCreation(String resourceType, String resourceName) {
        // wait when resource to be created
        return waitFor(resourceType, resourceName,
            actualObj -> true
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public K waitForResourceDeletion(String resourceType, String resourceName) {
        TestUtils.waitFor(resourceType + " " + resourceName + " removal",
            1_000L, 480_000L, () -> {
                try {
                    get(resourceType, resourceName);
                    return false;
                } catch (KubeClusterException.NotFound e) {
                    return true;
                }
            });
        return (K) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K waitForResourceUpdate(String resourceType, String resourceName, Date startTime) {

        TestUtils.waitFor(resourceType + " " + resourceName + " update",
                1_000L, 240_000L, () -> {
                try {
                    return startTime.before(getResourceCreateTimestamp(resourceType, resourceName));
                } catch (KubeClusterException.NotFound e) {
                    return false;
                }
            });
        return (K) this;
    }

    @Override
    public Date getResourceCreateTimestamp(String resourceType, String resourceName) {
        DateFormat df = new SimpleDateFormat("yyyyMMdd'T'kkmmss'Z'");
        Date parsedDate = null;
        try {
            parsedDate = df.parse(JsonPath.parse(getResourceAsJson(resourceType, resourceName)).
                    read("$.metadata.creationTimestamp").toString().replaceAll("\\p{P}", ""));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return parsedDate;
    }

    @Override
    public String toString() {
        return cmd();
    }

    @Override
    public List<String> list(String resourceType) {
        return asList(Exec.exec(namespacedCommand("get", resourceType, "-o", "jsonpath={range .items[*]}{.metadata.name} ")).out().trim().split(" +")).stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    @Override
    public String getResourceAsJson(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("get", resourceType, resourceName, "-o", "json")).out();
    }

    @Override
    public String getResourceAsYaml(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("get", resourceType, resourceName, "-o", "yaml")).out();
    }

    @Override
    public String describe(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("describe", resourceType, resourceName)).out();
    }

    @Override
    public String logs(String pod, String container) {
        String[] args;
        if (container != null) {
            args = new String[]{"logs", pod, "-c", container};
        } else {
            args = new String[]{"logs", pod};
        }
        return Exec.exec(namespacedCommand(args)).out();
    }

    @Override
    public String searchInLog(String resourceType, String resourceName, long sinceSeconds, String... grepPattern) {
        try {
            return Exec.exec("bash", "-c", join(" ", namespacedCommand("logs", resourceType + "/" + resourceName, "--since=" + sinceSeconds + "s",
                    "|", "grep", " -e " + join(" -e ", grepPattern), "-B", "1"))).out();
        } catch (KubeClusterException e) {
            if (e.result != null && e.result.exitStatus() == 1) {
                LOGGER.info("{} not found", grepPattern);
            } else {
                LOGGER.error("Caught exception while searching {} in logs", grepPattern);
            }
        }
        return "";
    }

    public List<String> listResourcesByLabel(String resourceType, String label) {
        return asList(Exec.exec(namespacedCommand("get", resourceType, "-l", label, "-o", "jsonpath={range .items[*]}{.metadata.name} ")).out().split("\\s+"));
    }
}

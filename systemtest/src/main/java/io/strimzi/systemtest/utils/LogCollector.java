/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.strimzi.test.k8s.KubeClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

import static io.strimzi.test.BaseITST.cmdKubeClient;
import static io.strimzi.test.TestUtils.writeFile;

public class LogCollector {
    private static final Logger LOGGER = LogManager.getLogger(LogCollector.class);

    private KubeClient kubeClient;
    private String namespace;
    private File logDir;
    private File configMapDir;
    private File eventsDir;

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    public LogCollector(KubeClient kubeClient, File logDir) {
        this.kubeClient = kubeClient;
        this.namespace = kubeClient.getNamespace();
        this.logDir = logDir;
        this.eventsDir = new File(logDir + "/events");
        this.configMapDir = new File(logDir + "/configMaps");
        logDir.mkdirs();

        if (!eventsDir.exists()) {
            eventsDir.mkdirs();
        }
        if (!configMapDir.exists()) {
            configMapDir.mkdirs();
        }
    }

    public void collectLogsFromPods() {
        LOGGER.info("Collecting logs for pods in namespace {}", namespace);

        try {
            kubeClient.listPods().forEach(pod -> {
                String podName = pod.getMetadata().getName();
                pod.getStatus().getContainerStatuses().forEach(containerStatus -> {
                    String log = kubeClient.getPodResource(podName).inContainer(containerStatus.getName()).getLog();
                    // Write logs from containers to files
                    writeFile(logDir + "/" + "logs-pod-" + podName + "-container-" + containerStatus.getName() + ".log", log);
                    // Describe all pods
                    String describe = cmdKubeClient().describe("pod", podName);
                    writeFile(logDir + "/" + "describe-pod-" + podName + "-container-" + containerStatus.getName() + ".log", describe);
                });
            });
        } catch (Exception allExceptions) {
            LOGGER.warn("Searching for logs in all pods failed! Some of the logs will not be stored.");
        }
    }

    public void collectEvents() {
        LOGGER.info("Collecting events in namespace {}", namespace);
        String events = cmdKubeClient().getEvents();
        // Write events to file
        writeFile(eventsDir + "/" + "events-in-namespace" + kubeClient.getNamespace() + ".log", events);
    }

    public void collectConfigMaps() {
        LOGGER.info("Collecting configmaps in namespace {}", namespace);
        kubeClient.listConfigMaps().forEach(configMap -> {
            writeFile(configMapDir + "/" + configMap.getMetadata().getName() + "-" + namespace + ".log", configMap.toString());
        });
    }
}

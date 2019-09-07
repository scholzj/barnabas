/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.k8s;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.strimzi.test.executor.Exec;

public class Minishift implements KubeCluster {

    private static final String CMD = "minishift";
    public static final Config CONFIG = Config.autoConfigure(System.getenv().getOrDefault("TEST_CLUSTER_CONTEXT", null));

    @Override
    public boolean isAvailable() {
        return Exec.isExecutableOnPath(CMD);
    }

    @Override
    public void clusterUp() {
        Exec.exec(CMD, "start");
    }

    @Override
    public void clusterDown() {
        Exec.exec(CMD, "stop");
    }

    @Override
    public boolean isClusterUp() {
        try {
            String output = Exec.exec(CMD, "status").out();
            return output.contains("Minishift:  Running")
                    && output.contains("OpenShift:  Running");
        } catch (KubeClusterException e) {
            return false;
        }
    }

    @Override
    public KubeCmdClient defaultCmdClient() {
        return new Oc();
    }

    @Override
    public KubeClient defaultClient() {
        return new KubeClient(new DefaultOpenShiftClient(CONFIG), "myproject");
    }

    public String toString() {
        return CMD;
    }
}

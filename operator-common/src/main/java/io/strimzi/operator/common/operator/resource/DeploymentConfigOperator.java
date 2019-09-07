/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.DoneableDeploymentConfig;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.dsl.DeployableScalableResource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Operations for {@code DeploymentConfigs}s.
 */
public class DeploymentConfigOperator extends AbstractScalableResourceOperator<OpenShiftClient, DeploymentConfig, DeploymentConfigList, DoneableDeploymentConfig, DeployableScalableResource<DeploymentConfig, DoneableDeploymentConfig>> {
    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     */
    public DeploymentConfigOperator(Vertx vertx, OpenShiftClient client) {
        super(vertx, client, "DeploymentConfig");
    }

    @Override
    protected MixedOperation<DeploymentConfig, DeploymentConfigList, DoneableDeploymentConfig, DeployableScalableResource<DeploymentConfig, DoneableDeploymentConfig>> operation() {
        return client.deploymentConfigs();
    }

    @Override
    protected Integer currentScale(String namespace, String name) {
        DeploymentConfig deploymentConfig = get(namespace, name);
        if (deploymentConfig != null) {
            return deploymentConfig.getSpec().getReplicas();
        } else {
            return null;
        }
    }

    @Override
    protected Future<ReconcileResult<DeploymentConfig>> internalPatch(String namespace, String name, DeploymentConfig current, DeploymentConfig desired) {
        desired.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(current.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        return super.internalPatch(namespace, name, current, desired);
    }

    /**
     * Asynchronously polls the deployment configuration until either the observed generation matches the desired
     * generation sequence number or timeout.
     *
     * @param namespace The namespace.
     * @param name The resource name.
     * @param pollIntervalMs The polling interval
     * @param timeoutMs The timeout
     * @return  A future which completes when the observed generation of the deployment configuration matches the
     * generation sequence number of the desired state.
     */
    public Future<Void> waitForObserved(String namespace, String name, long pollIntervalMs, long timeoutMs) {
        return waitFor(namespace, name, pollIntervalMs, timeoutMs, this::isObserved);
    }

    /**
     * Check if a deployment configuration has been observed.
     *
     * @param namespace The namespace.
     * @param name The resource name.
     * @return Whether the deployment has been observed.
     */
    private boolean isObserved(String namespace, String name) {
        DeploymentConfig dep = get(namespace, name);
        if (dep != null)   {
            return dep.getMetadata().getGeneration().equals(dep.getStatus().getObservedGeneration());
        } else {
            return false;
        }
    }
}

/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.storage.DoneableStorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

public class StorageClassOperator extends AbstractNonNamespacedResourceOperator<KubernetesClient,
        StorageClass, StorageClassList, DoneableStorageClass, Resource<StorageClass, DoneableStorageClass>> {

    /**
     * Constructor.
     * @param vertx The Vertx instance.
     * @param client The Kubernetes client.
     * @param operationTimeoutMs The timeout in milliseconds.
     */

    public StorageClassOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs) {
        super(vertx, client, "StorageClass", operationTimeoutMs);
    }

    @Override
    protected MixedOperation<StorageClass, StorageClassList, DoneableStorageClass,
            Resource<StorageClass, DoneableStorageClass>> operation() {
        return client.storage().storageClasses();
    }
}

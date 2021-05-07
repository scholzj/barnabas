/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.zjsonpatch.JsonDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Pattern;

import static io.fabric8.kubernetes.client.internal.PatchUtils.patchMapper;

class ResourceDiff<T extends HasMetadata> extends AbstractJsonDiff {
    private static final Logger log = LogManager.getLogger(ResourceDiff.class.getName());

    private final boolean isEmpty;

    public ResourceDiff(String resourceKind, String resourceName, T current, T desired, Pattern ignorableFields) {
        JsonNode source = patchMapper().valueToTree(current == null ? "{}" : current);
        JsonNode target = patchMapper().valueToTree(desired == null ? "{}" : desired);
        JsonNode diff = JsonDiff.asJson(source, target);

        int num = 0;

        for (JsonNode d : diff) {
            String pathValue = d.get("path").asText();

            if (ignorableFields.matcher(pathValue).matches()) {
                log.debug("Ignoring {} {} diff {}", resourceKind, resourceName, d);
                continue;
            }

            if (log.isDebugEnabled()) {
                log.debug("{} {} differs: {}", resourceKind, resourceName, d);
                log.debug("Current {} {} path {} has value {}", resourceKind, resourceName, pathValue, lookupPath(source, pathValue));
                log.debug("Desired {} {} path {} has value {}", resourceKind, resourceName, pathValue, lookupPath(target, pathValue));
            }

            num++;
            break;
        }

        this.isEmpty = num == 0;
    }

    @Override
    public boolean isEmpty() {
        return isEmpty;
    }
}

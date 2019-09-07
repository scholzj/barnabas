/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "bootstrapServers", "abortOnSendFailure", "logging"})
@EqualsAndHashCode(callSuper = true)
public class KafkaMirrorMakerProducerSpec extends KafkaMirrorMakerClientSpec {
    private static final long serialVersionUID = 1L;

    private Boolean abortOnSendFailure;

    public static final String FORBIDDEN_PREFIXES = "ssl., bootstrap.servers, sasl., security., interceptor.classes";
    public static final String FORBIDDEN_PREFIX_EXCEPTIONS = "ssl.endpoint.identification.algorithm";

    @Description("Flag to set the Mirror Maker to exit on a failed send. Default value is `true`.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean getAbortOnSendFailure() {
        return abortOnSendFailure;
    }

    public void setAbortOnSendFailure(Boolean abortOnSendFailure) {
        this.abortOnSendFailure = abortOnSendFailure;
    }

    @Override
    @Description("The Mirror Maker producer config. Properties with the following prefixes cannot be set: " + FORBIDDEN_PREFIXES)
    public Map<String, Object> getConfig() {
        return config;
    }

}

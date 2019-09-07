/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.Minimum;
import io.sundr.builder.annotations.Buildable;
import io.vertx.core.cli.annotations.DefaultValue;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * A representation of the HTTP configuration.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class KafkaBridgeHttpConfig implements UnknownPropertyPreserving, Serializable {

    private static final long serialVersionUID = 1L;

    public static final int HTTP_DEFAULT_PORT = 8080;
    public static final String HTTP_DEFAULT_HOST = "0.0.0.0";
    private int port = HTTP_DEFAULT_PORT;
    private Map<String, Object> additionalProperties = new HashMap<>(0);

    public KafkaBridgeHttpConfig() {
    }

    public KafkaBridgeHttpConfig(int port) {
        this.port = port;
    }

    @Description("The port which is the server listening on.")
    @DefaultValue("8080")
    @Minimum(1023)
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}

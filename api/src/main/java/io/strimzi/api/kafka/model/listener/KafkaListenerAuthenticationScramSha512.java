/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model.listener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

/**
 * Configures a listener to use SASL SCRAM-SHA-512 for authentication.
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public class KafkaListenerAuthenticationScramSha512 extends KafkaListenerAuthentication {

    private static final long serialVersionUID = 1L;

    public static final String SCRAM_SHA_512 = "scram-sha-512";

    @Description("Must be `" + SCRAM_SHA_512 + "`")
    @Override
    public String getType() {
        return SCRAM_SHA_512;
    }
}

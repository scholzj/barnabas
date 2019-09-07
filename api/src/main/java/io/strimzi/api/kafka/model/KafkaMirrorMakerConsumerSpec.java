/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.strimzi.crdgenerator.annotations.Minimum;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"numStreams", "offsetCommitInterval", "groupId", "bootstrapServers", "logging"})
@EqualsAndHashCode(callSuper = true)
public class KafkaMirrorMakerConsumerSpec extends KafkaMirrorMakerClientSpec {
    private static final long serialVersionUID = 1L;

    public static final String FORBIDDEN_PREFIXES = "ssl., bootstrap.servers, group.id, sasl., security., interceptor.classes";
    public static final String FORBIDDEN_PREFIX_EXCEPTIONS = "ssl.endpoint.identification.algorithm";

    private Integer numStreams;

    private String groupId;

    private Integer offsetCommitInterval;

    @Override
    @Description("The Mirror Maker consumer config. Properties with the following prefixes cannot be set: " + FORBIDDEN_PREFIXES)
    public Map<String, Object> getConfig() {
        return config;
    }

    @Description("Specifies the number of consumer stream threads to create.")
    @Minimum(1)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Integer getNumStreams() {
        return numStreams;
    }

    public void setNumStreams(Integer numStreams) {
        this.numStreams = numStreams;
    }

    @Description("A unique string that identifies the consumer group this consumer belongs to.")
    @JsonProperty(required = true)
    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    @Description("Specifies the offset auto-commit interval in ms. Default value is 60000.")
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public Integer getOffsetCommitInterval() {
        return offsetCommitInterval;
    }

    public void setOffsetCommitInterval(Integer offsetCommitInterval) {
        this.offsetCommitInterval = offsetCommitInterval;
    }
}

/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KafkaCrdOperatorTest extends AbstractResourceOperatorTest<KubernetesClient, Kafka, KafkaList, DoneableKafka, Resource<Kafka, DoneableKafka>> {

    @Override
    protected Class<KubernetesClient> clientType() {
        return KubernetesClient.class;
    }

    @Override
    protected Class<Resource> resourceType() {
        return Resource.class;
    }

    @Override
    protected Kafka resource() {
        return new KafkaBuilder()
                .withApiVersion("kafka.strimzi.io/v1beta1")
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withNewKafka()
                .withReplicas(1)
                .withNewListeners()
                .withNewPlain()
                .endPlain()
                .endListeners()
                .withNewEphemeralStorage()
                .endEphemeralStorage()
                .endKafka()
                .withNewZookeeper()
                .withReplicas(1)
                .withNewEphemeralStorage()
                .endEphemeralStorage()
                .endZookeeper()
                .endSpec()
                .withNewStatus()
                .endStatus()
                .build();
    }

    @Override
    protected void mocker(KubernetesClient mockClient, MixedOperation op) {
        when(mockClient.customResources(any(), any(), any(), any())).thenReturn(op);
    }

    @Override
    protected CrdOperator createResourceOperations(Vertx vertx, KubernetesClient mockClient) {
        return new CrdOperator(vertx, mockClient, Kafka.class, KafkaList.class, DoneableKafka.class);
    }

    @Test
    public void testUpdateStatusAsync(TestContext context) throws IOException {
        KubernetesClient mockClient = mock(KubernetesClient.class);

        OkHttpClient mockOkHttp = mock(OkHttpClient.class);
        when(mockClient.adapt(eq(OkHttpClient.class))).thenReturn(mockOkHttp);
        URL fakeUrl = new URL("http", "my-host", 9443, "/");
        when(mockClient.getMasterUrl()).thenReturn(fakeUrl);
        Call mockCall = mock(Call.class);
        when(mockOkHttp.newCall(any(Request.class))).thenReturn(mockCall);
        ResponseBody body = ResponseBody.create(OperationSupport.JSON, "{ }");
        Response response = new Response.Builder().code(200).request(new Request.Builder().url(fakeUrl).build()).body(body).message("Created").protocol(Protocol.HTTP_1_1).build();
        when(mockCall.execute()).thenReturn(response);

        Async async = context.async();
        CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> op = createResourceOperations(vertx, mockClient);
        op.updateStatusAsync(resource()).setHandler(res -> {
            context.assertTrue(res.succeeded());
            async.complete();
        });

        async.awaitSuccess();
    }

    @Test
    public void testHttp422AfterUpgrade(TestContext context) throws IOException {
        KubernetesClient mockClient = mock(KubernetesClient.class);

        OkHttpClient mockOkHttp = mock(OkHttpClient.class);
        when(mockClient.adapt(eq(OkHttpClient.class))).thenReturn(mockOkHttp);
        URL fakeUrl = new URL("http", "my-host", 9443, "/");
        when(mockClient.getMasterUrl()).thenReturn(fakeUrl);
        Call mockCall = mock(Call.class);
        when(mockOkHttp.newCall(any(Request.class))).thenReturn(mockCall);
        ResponseBody body = ResponseBody.create(OperationSupport.JSON, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"metadata\":{},\"status\":\"Failure\",\"message\":\"Kafka.kafka.strimzi.io \\\"my-cluster\\\" is invalid: apiVersion: Invalid value: \\\"kafka.strimzi.io/v1alpha1\\\": must be kafka.strimzi.io/v1beta1\",\"reason\":\"Invalid\",\"details\":{\"name\":\"my-cluster\",\"group\":\"kafka.strimzi.io\",\"kind\":\"Kafka\",\"causes\":[{\"reason\":\"FieldValueInvalid\",\"message\":\"Invalid value: \\\"kafka.strimzi.io/v1alpha1\\\": must be kafka.strimzi.io/v1beta1\",\"field\":\"apiVersion\"}]},\"code\":422}");
        Response response = new Response.Builder().code(422).request(new Request.Builder().url(fakeUrl).build()).body(body).message("Unprocessable Entity").protocol(Protocol.HTTP_1_1).build();
        when(mockCall.execute()).thenReturn(response);

        Async async = context.async();
        CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> op = createResourceOperations(vertx, mockClient);
        op.updateStatusAsync(resource()).setHandler(res -> {
            context.assertTrue(res.succeeded());
            async.complete();
        });
    }

    @Test
    public void testHttp422DifferentError(TestContext context) throws IOException {
        KubernetesClient mockClient = mock(KubernetesClient.class);

        OkHttpClient mockOkHttp = mock(OkHttpClient.class);
        when(mockClient.adapt(eq(OkHttpClient.class))).thenReturn(mockOkHttp);
        URL fakeUrl = new URL("http", "my-host", 9443, "/");
        when(mockClient.getMasterUrl()).thenReturn(fakeUrl);
        Call mockCall = mock(Call.class);
        when(mockOkHttp.newCall(any(Request.class))).thenReturn(mockCall);
        ResponseBody body = ResponseBody.create(OperationSupport.JSON, "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"metadata\":{},\"status\":\"Failure\",\"message\":\"Kafka.kafka.strimzi.io \\\"my-cluster\\\" is invalid: apiVersion: Invalid value: \\\"kafka.strimzi.io/v1alpha1\\\": must be kafka.strimzi.io/v1beta1\",\"reason\":\"Invalid\",\"details\":{\"name\":\"my-cluster\",\"group\":\"kafka.strimzi.io\",\"kind\":\"Kafka\",\"causes\":[{\"reason\":\"FieldValueInvalid\",\"message\":\"Invalid value: \\\"kafka.strimzi.io/v1alpha1\\\": must be kafka.strimzi.io/v1beta1\",\"field\":\"someOtherField\"}]},\"code\":422}");
        Response response = new Response.Builder().code(422).request(new Request.Builder().url(fakeUrl).build()).body(body).message("Unprocessable Entity").protocol(Protocol.HTTP_1_1).build();
        when(mockCall.execute()).thenReturn(response);

        Async async = context.async();
        CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> op = createResourceOperations(vertx, mockClient);
        op.updateStatusAsync(resource()).setHandler(res -> {
            context.assertFalse(res.succeeded());
            async.complete();
        });
    }

    @Test
    public void testHttp422NoBody(TestContext context) throws IOException {
        KubernetesClient mockClient = mock(KubernetesClient.class);

        OkHttpClient mockOkHttp = mock(OkHttpClient.class);
        when(mockClient.adapt(eq(OkHttpClient.class))).thenReturn(mockOkHttp);
        URL fakeUrl = new URL("http", "my-host", 9443, "/");
        when(mockClient.getMasterUrl()).thenReturn(fakeUrl);
        Call mockCall = mock(Call.class);
        when(mockOkHttp.newCall(any(Request.class))).thenReturn(mockCall);
        ResponseBody body = ResponseBody.create(OperationSupport.JSON, "{ }");
        Response response = new Response.Builder().code(422).request(new Request.Builder().url(fakeUrl).build()).message("Unprocessable Entity").protocol(Protocol.HTTP_1_1).build();
        when(mockCall.execute()).thenReturn(response);

        Async async = context.async();
        CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> op = createResourceOperations(vertx, mockClient);
        op.updateStatusAsync(resource()).setHandler(res -> {
            context.assertFalse(res.succeeded());
            async.complete();
        });
    }

    @Test
    public void testHttp409(TestContext context) throws IOException {
        KubernetesClient mockClient = mock(KubernetesClient.class);

        OkHttpClient mockOkHttp = mock(OkHttpClient.class);
        when(mockClient.adapt(eq(OkHttpClient.class))).thenReturn(mockOkHttp);
        URL fakeUrl = new URL("http", "my-host", 9443, "/");
        when(mockClient.getMasterUrl()).thenReturn(fakeUrl);
        Call mockCall = mock(Call.class);
        when(mockOkHttp.newCall(any(Request.class))).thenReturn(mockCall);
        ResponseBody body = ResponseBody.create(OperationSupport.JSON, "{ }");
        Response response = new Response.Builder().code(409).request(new Request.Builder().url(fakeUrl).build()).body(body).message("Conflict").protocol(Protocol.HTTP_1_1).build();
        when(mockCall.execute()).thenReturn(response);

        Async async = context.async();
        CrdOperator<KubernetesClient, Kafka, KafkaList, DoneableKafka> op = createResourceOperations(vertx, mockClient);
        op.updateStatusAsync(resource()).setHandler(res -> {
            context.assertFalse(res.succeeded());
            async.complete();
        });
    }
}

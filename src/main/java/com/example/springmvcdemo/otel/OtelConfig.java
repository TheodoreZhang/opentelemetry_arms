package com.example.springmvcdemo.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Component
public class OtelConfig {

    @Value("${otel.endpoint}")
    private String endpoint;

    @Value("${otel.token}")
    private String token;

    @Value("${otel.service-name}")
    private String serviceName;

    private Tracer tracer;
    private SdkTracerProvider tracerProvider;

    public OtelConfig() {}

    public OtelConfig(String endpoint, String token, String serviceName) {
        this.endpoint = endpoint;
        this.token = token;
        this.serviceName = serviceName;
    }

    @PostConstruct
    public void init() {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName)));

        OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authentication", token)
                .setTimeout(10, TimeUnit.SECONDS)
                .build();

        tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setScheduleDelay(100, TimeUnit.MILLISECONDS)
                        .setMaxExportBatchSize(512)
                        .build())
                .build();

        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                        W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();

        tracer = openTelemetry.getTracer(serviceName, "1.0.0");
        System.out.println("OpenTelemetry initialized, traces endpoint: " + endpoint);
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("OpenTelemetry shutdown, flushing spans...");
        if (tracerProvider != null) {
            tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    public Tracer getTracer() {
        return tracer;
    }
}

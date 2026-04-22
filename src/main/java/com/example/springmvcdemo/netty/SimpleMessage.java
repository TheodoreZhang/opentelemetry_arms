package com.example.springmvcdemo.netty;

import io.netty.util.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

public class SimpleMessage {
    public static final AttributeKey<Span> SERVER_SPAN_KEY = AttributeKey.valueOf("NETTY_SERVER_SPAN");

    private String content;
    private String traceparent;

    public SimpleMessage() {}

    public SimpleMessage(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public void setTraceparent(String traceparent) {
        this.traceparent = traceparent;
    }

    private static final TextMapSetter<Map<String, String>> SETTER =
            (carrier, key, value) -> carrier.put(key, value);

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    };

    public void injectTraceContext() {
        Map<String, String> carrier = new HashMap<>();
        OpenTelemetry global = GlobalOpenTelemetry.get();
        global.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, SETTER);
        this.traceparent = carrier.get("traceparent");
    }

    public Context extractTraceContext() {
        Map<String, String> carrier = new HashMap<>();
        if (this.traceparent != null) {
            carrier.put("traceparent", this.traceparent);
        }
        OpenTelemetry global = GlobalOpenTelemetry.get();
        return global.getPropagators().getTextMapPropagator().extract(Context.current(), carrier, GETTER);
    }

    @Override
    public String toString() {
        return "SimpleMessage{content='" + content + "', traceparent='" + traceparent + "'}";
    }
}

package com.example.springmvcdemo.netty;

import io.netty.channel.ChannelHandlerContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

final class NettyTracingSupport {

    private NettyTracingSupport() {
    }

    static void runInternalSpan(ChannelHandlerContext ctx,
                                String spanName,
                                boolean endServerSpan,
                                SpanAction action) {
        Span serverSpan = ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).get();
        Span span = GlobalOpenTelemetry.get().getTracer("netty-demo", "1.0.0")
                .spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            action.run(span, serverSpan);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            if (serverSpan != null) {
                serverSpan.setStatus(StatusCode.ERROR, e.getMessage());
                serverSpan.recordException(e);
            }
            throw e;
        } finally {
            span.end();
            if (endServerSpan && serverSpan != null) {
                serverSpan.end();
                ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).set(null);
            }
        }
    }

    @FunctionalInterface
    interface SpanAction {
        void run(Span span, Span serverSpan);
    }
}

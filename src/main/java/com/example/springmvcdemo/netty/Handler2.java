package com.example.springmvcdemo.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public class Handler2 extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        SimpleMessage request = (SimpleMessage) msg;
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("netty-demo", "1.0.0");

        Span span = tracer.spanBuilder("netty.handler2.enrich")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("handler.name", "handler2");
            span.setAttribute("handler.phase", "enrich");
            span.setAttribute("message.content.before", request.getContent());
            sleepOneSecond();

            request.setContent(request.getContent() + " [handler2]");
            span.setAttribute("message.content.after", request.getContent());
            span.addEvent("handler2.sleep.completed");
            ctx.fireChannelRead(request);
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            Span serverSpan = ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).get();
            if (serverSpan != null) {
                serverSpan.setStatus(StatusCode.ERROR, e.getMessage());
                serverSpan.recordException(e);
            }
            throw e;
        } finally {
            span.end();
        }
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("handler2 interrupted", e);
        }
    }
}

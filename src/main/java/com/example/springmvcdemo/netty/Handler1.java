package com.example.springmvcdemo.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

public class Handler1 extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        SimpleMessage request = (SimpleMessage) msg;
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("netty-demo", "1.0.0");

        Context parentContext = request.extractTraceContext();
        Span serverSpan = tracer.spanBuilder("netty.server.process")
                .setSpanKind(SpanKind.SERVER)
                .setParent(parentContext)
                .startSpan();
        ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).set(serverSpan);

        try (Scope serverScope = serverSpan.makeCurrent()) {
            serverSpan.setAttribute("rpc.system", "custom-netty");
            serverSpan.setAttribute("rpc.service", "SimpleMessageService");
            serverSpan.setAttribute("rpc.method", "echo");

            Span handlerSpan = tracer.spanBuilder("netty.handler1.validate")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            try (Scope handlerScope = handlerSpan.makeCurrent()) {
                handlerSpan.setAttribute("handler.name", "handler1");
                handlerSpan.setAttribute("handler.phase", "validate");
                handlerSpan.setAttribute("message.content", request.getContent());
                sleepOneSecond();
                handlerSpan.addEvent("handler1.sleep.completed");
                ctx.fireChannelRead(msg);
            } catch (RuntimeException e) {
                handlerSpan.setStatus(StatusCode.ERROR, e.getMessage());
                handlerSpan.recordException(e);
                serverSpan.setStatus(StatusCode.ERROR, e.getMessage());
                serverSpan.recordException(e);
                ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).set(null);
                serverSpan.end();
                throw e;
            } finally {
                handlerSpan.end();
            }
        }
    }

    private void sleepOneSecond() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("handler1 interrupted", e);
        }
    }
}

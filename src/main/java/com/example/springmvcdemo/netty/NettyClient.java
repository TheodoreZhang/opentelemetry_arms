package com.example.springmvcdemo.netty;

import com.example.springmvcdemo.otel.OtelConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

public class NettyClient {

    private static OtelConfig otelConfig;

    public static void main(String[] args) throws Exception {
        otelConfig = new OtelConfig(
                "http://tracing-analysis-dc-hz.aliyuncs.com/adapt_gw152brq2f@4c18cfc0b9731c2_gw152brq2f@53df7ad2afe8301/api/otlp/traces",
                "adapt_gw152brq2f@4c18cfc0b9731c2_gw152brq2f@53df7ad2afe8301",
                "netty-demo-client");
        otelConfig.init();

        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                .addLast(new SimpleMessageDecoder())
                                .addLast(new SimpleMessageEncoder())
                                .addLast(new SimpleClientHandler());
                        }
                    });

            ChannelFuture future = bootstrap.connect("127.0.0.1", 9090).sync();
            Channel channel = future.channel();

            sendMessage(channel, "Hello Netty!");
            sendMessage(channel, "Second message");

            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
            otelConfig.shutdown();
        }
    }

    private static void sendMessage(Channel channel, String content) {
        Span span = otelConfig.getTracer()
                .spanBuilder("netty.client.send")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("message.content", content);
            span.setAttribute("server.address", "127.0.0.1:9090");

            SimpleMessage msg = new SimpleMessage(content);
            msg.injectTraceContext();
            channel.writeAndFlush(msg).sync();

            System.out.println("Client sent: " + content + " | traceId=" + span.getSpanContext().getTraceId());
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
        } finally {
            span.end();
        }
    }

    static class SimpleClientHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            SimpleMessage reply = (SimpleMessage) msg;

            Span span = otelConfig.getTracer()
                    .spanBuilder("netty.client.receive")
                    .setSpanKind(SpanKind.CLIENT)
                    .setParent(reply.extractTraceContext())
                    .startSpan();

            try (Scope scope = span.makeCurrent()) {
                span.setAttribute("message.content", reply.getContent());
                System.out.println("Client received: " + reply.getContent() + " | traceId=" + span.getSpanContext().getTraceId());
            } finally {
                span.end();
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
    }
}

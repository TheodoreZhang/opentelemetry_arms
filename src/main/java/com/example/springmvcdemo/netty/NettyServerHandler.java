package com.example.springmvcdemo.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.text.SimpleDateFormat;
import java.util.Date;

public class NettyServerHandler extends ChannelInboundHandlerAdapter {

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        SimpleMessage request = (SimpleMessage) msg;
        NettyTracingSupport.runInternalSpan(ctx, "netty.handler3.echo", true, (span, serverSpan) -> {
            span.setAttribute("handler.name", "handler3");
            span.setAttribute("handler.phase", "echo");
            span.setAttribute("message.content", request.getContent());
            span.setAttribute("client.remoteAddress", ctx.channel().remoteAddress().toString());

            System.out.println("[" + DATE_FMT.format(new Date()) + "] Server received: " + request.getContent());

            String reply = "Echo: " + request.getContent();
            SimpleMessage response = new SimpleMessage(reply);
            response.injectTraceContext();
            ctx.writeAndFlush(response);
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}

package com.example.springmvcdemo.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

public class SimpleMessageEncoder extends MessageToByteEncoder<SimpleMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, SimpleMessage msg, ByteBuf out) {
        byte[] contentBytes = msg.getContent().getBytes(StandardCharsets.UTF_8);
        byte[] traceBytes = msg.getTraceparent() != null
                ? msg.getTraceparent().getBytes(StandardCharsets.UTF_8)
                : new byte[0];

        int totalLength = traceBytes.length + contentBytes.length;
        out.writeInt(totalLength);
        out.writeShort(traceBytes.length);
        if (traceBytes.length > 0) {
            out.writeBytes(traceBytes);
        }
        out.writeBytes(contentBytes);
    }
}

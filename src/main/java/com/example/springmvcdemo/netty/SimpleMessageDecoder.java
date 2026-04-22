package com.example.springmvcdemo.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class SimpleMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }
        in.markReaderIndex();
        int totalLength = in.readInt();
        if (totalLength < 0 || totalLength > 1024 * 1024) {
            in.resetReaderIndex();
            ctx.close();
            return;
        }
        if (in.readableBytes() < totalLength + 2) {
            in.resetReaderIndex();
            return;
        }

        int traceLen = in.readShort();
        String traceparent = null;
        if (traceLen > 0) {
            byte[] traceBytes = new byte[traceLen];
            in.readBytes(traceBytes);
            traceparent = new String(traceBytes, StandardCharsets.UTF_8);
        }

        int contentLen = totalLength - traceLen;
        byte[] contentBytes = new byte[contentLen];
        in.readBytes(contentBytes);

        SimpleMessage msg = new SimpleMessage();
        msg.setTraceparent(traceparent);
        msg.setContent(new String(contentBytes, StandardCharsets.UTF_8));
        out.add(msg);
    }
}

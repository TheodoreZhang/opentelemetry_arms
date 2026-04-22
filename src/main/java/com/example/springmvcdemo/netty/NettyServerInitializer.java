package com.example.springmvcdemo.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
            .addLast(new SimpleMessageDecoder())
            .addLast(new SimpleMessageEncoder())
            .addLast(new Handler1())
            .addLast(new Handler2())
            .addLast(new NettyServerHandler());
    }
}

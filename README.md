# opentelemetry_arms
查看使用 OpenTelemetry 上报应用链路数据所需的接入点信息。


# Netty 项目接入 OpenTelemetry 自定义埋点说明

本文面向“一个已经存在的 Java 项目”，目标是在不改动整体业务架构的前提下，为 Netty 服务端调用链接入 OpenTelemetry Java SDK，并将链路数据上报到阿里云 ARMS。

## 目标

接入完成后，服务端应具备以下能力：

- 为每次 Netty 请求创建一条服务端调用链
- 在多个 Netty Handler 中增加自定义埋点
- 在 Handler 之间保持同一条 Trace 上下文
- 将调用链上报到 ARMS

本文默认你的项目已经有：

- JDK 8+
- Maven
- Netty 服务端
- 阿里云 ARMS 的 OTLP 接入地址和 Token

## 一、引入依赖

在 `pom.xml` 中增加以下依赖：

```xml
<properties>
    <opentelemetry.version>1.40.0</opentelemetry.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>${opentelemetry.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-api</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-sdk</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
</dependencies>
```

如果你的项目本身不是 Spring Boot，也可以直接使用这些依赖，和框架无强绑定。

## 二、增加 OTel 初始化

建议在应用启动时统一初始化 OpenTelemetry，核心点是：

- 设置 `service.name`
- 配置 OTLP HTTP exporter
- 调用 `buildAndRegisterGlobal()`
- 应用退出时调用 `shutdown()` 做 flush

示例：

```java
@Component
public class OtelConfig {

    @Value("${otel.endpoint}")
    private String endpoint;

    @Value("${otel.token}")
    private String token;

    @Value("${otel.service-name}")
    private String serviceName;

    private SdkTracerProvider tracerProvider;

    @PostConstruct
    public void init() {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), serviceName)));

        OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authentication", token)
                .build();

        tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }

    @PreDestroy
    public void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }
}
```

配置示例：

```yaml
otel:
  endpoint: http://your-arms-endpoint/api/otlp/traces
  token: your-token
  service-name: your-netty-service
```

## 三、定义消息级 Trace 透传

如果你的 Netty 协议不是 HTTP，而是自定义二进制协议或私有消息体，需要自己传递 `traceparent`。

建议在消息对象中增加一个字段，例如：

```java
private String traceparent;
```

发送请求前，把当前上下文注入到消息里：

```java
public void injectTraceContext() {
    Map<String, String> carrier = new HashMap<>();
    GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
            .inject(Context.current(), carrier, Map::put);
    this.traceparent = carrier.get("traceparent");
}
```

服务端收到消息后，再从消息里提取上下文：

```java
public Context extractTraceContext() {
    Map<String, String> carrier = new HashMap<>();
    if (traceparent != null) {
        carrier.put("traceparent", traceparent);
    }
    return GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
            .extract(Context.current(), carrier, getter);
}
```

这一步很关键。没有它，客户端和服务端会变成两条独立 Trace。

## 四、在第一个 Handler 创建服务端根 Span

推荐在 Netty pipeline 的第一个业务 Handler 中创建服务端根 Span。

示例：

```java
Context parentContext = request.extractTraceContext();
Tracer tracer = GlobalOpenTelemetry.get().getTracer("your-service", "1.0.0");

Span serverSpan = tracer.spanBuilder("netty.server.process")
        .setSpanKind(SpanKind.SERVER)
        .setParent(parentContext)
        .startSpan();
```

建议同时把这个 `serverSpan` 放到 channel attribute 中，供后续 Handler 复用：

```java
ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).set(serverSpan);
```

然后在当前作用域中设置基础属性：

```java
try (Scope scope = serverSpan.makeCurrent()) {
    serverSpan.setAttribute("rpc.system", "custom-netty");
    serverSpan.setAttribute("rpc.service", "YourService");
    serverSpan.setAttribute("rpc.method", "echo");
    ctx.fireChannelRead(msg);
}
```

## 五、在各个业务 Handler 中增加自定义埋点

每个 Handler 可以增加一个 `INTERNAL` span，用于表示当前业务步骤。

例如 `handler1`：

```java
Span span = tracer.spanBuilder("netty.handler1.validate")
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();

try (Scope scope = span.makeCurrent()) {
    span.setAttribute("handler.name", "handler1");
    span.setAttribute("handler.phase", "validate");
    span.setAttribute("message.content", request.getContent());
    ctx.fireChannelRead(msg);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

例如 `handler2`：

```java
Span span = tracer.spanBuilder("netty.handler2.enrich")
        .setSpanKind(SpanKind.INTERNAL)
        .startSpan();
```

这样在 ARMS 中就能看到一条调用链下的多个子步骤。

## 六、建议抽取公共埋点工具

如果项目里有多个 Handler，建议把重复逻辑统一封装，例如：

- 创建内部 span
- `makeCurrent()`
- 异常打点
- 统一结束 `serverSpan`

可以抽出类似下面的公共方法：

```java
static void runInternalSpan(ChannelHandlerContext ctx,
                            String spanName,
                            boolean endServerSpan,
                            SpanAction action)
```

业务 Handler 只保留自己的处理代码，避免每个类都重复写一套 `try/catch/finally`。

## 七、在最后一个 Handler 回包并结束服务端根 Span

一般建议在最后一个业务 Handler 中：

- 处理最终业务逻辑
- 返回响应
- 结束服务端根 Span

示例：

```java
Span serverSpan = ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).get();
```

如果是最后一个步骤，可以在内部 span 结束后顺带结束 `serverSpan`：

```java
if (serverSpan != null) {
    serverSpan.end();
    ctx.channel().attr(SimpleMessage.SERVER_SPAN_KEY).set(null);
}
```

注意：

- 不要在第一个 Handler 里过早 `end()` 掉服务端根 Span
- 应确保所有内部 Handler 都执行完成后，再结束服务端根 Span

## 八、客户端如何接入

如果你的项目同时有 Netty 客户端，客户端也建议创建一个 `CLIENT` span：

```java
Span span = tracer.spanBuilder("netty.client.send")
        .setSpanKind(SpanKind.CLIENT)
        .startSpan();
```

然后在发送消息前：

```java
msg.injectTraceContext();
channel.writeAndFlush(msg).sync();
```

这样服务端拿到 `traceparent` 后，就能挂接到客户端 Trace 上。

## 九、Spring Boot 项目的启动建议

如果你的项目只需要 Netty，不需要 Spring MVC/Tomcat，建议关闭 Web 模式：

```yaml
spring:
  main:
    web-application-type: none
```

如果 Netty 依赖 OTel 初始化完成后再启动，可以给 Netty 启动类加：

```java
@DependsOn("otelConfig")
```

避免 Netty 提前收包时 OTel 尚未初始化。

## 十、上线前检查项

接入完成后，建议至少检查以下内容：

1. 应用日志中能看到 OTel 初始化成功
2. `service.name` 与 ARMS 中查找的服务名完全一致
3. 客户端请求中确实写入了 `traceparent`
4. 服务端第一个 Handler 能正确提取上下文
5. 每个自定义 span 都有 `span.end()`
6. 应用退出时有执行 `tracerProvider.shutdown()`
7. ARMS 中能看到服务端根 span 和各个 Handler span

## 十一、接入后的典型链路结构

一个完整的 Netty 调用链通常会长这样：

- `netty.client.send`
- `netty.server.process`
- `netty.handler1.validate`
- `netty.handler2.enrich`
- `netty.handler3.echo`

其中：

- `netty.server.process` 是服务端根 span
- `netty.handler1.validate`、`netty.handler2.enrich`、`netty.handler3.echo` 是各个业务步骤的自定义 span

## 十二、常见问题

### 1. ARMS 中看不到服务

优先检查：

- `service.name` 是否配置正确
- exporter 是否真的发到了正确的 ARMS OTLP 地址
- `Authentication` header 是否带上了 token
- 应用退出前是否做了 `shutdown/flush`

### 2. 能看到客户端 span，看不到服务端 span

优先检查：

- 服务端项目是否真的初始化了 OTel
- 服务端是否设置了和客户端不同的 `service.name`
- 服务端 Netty Handler 是否真正创建了 `SERVER` span

### 3. 每个 Handler 都有 span，但不在同一条 Trace 里

通常是因为：

- 消息没有透传 `traceparent`
- 服务端没有 `extractTraceContext()`
- 内部 span 创建时没有处于服务端根 span 的当前上下文中

---

如果你要把这篇文档进一步改成“你们团队内部规范版”，建议再补三项：

- 统一的 span 命名规范
- 统一的 attribute 字段规范
- 统一的错误码和异常打点规范

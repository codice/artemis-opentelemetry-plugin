package org.codice.opentelemetry.tracing.artemis.plugin;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

public class OpenTelemetryPlugin implements ActiveMQServerPlugin {

    private static final String INSTRUMENTATION_NAME = OpenTelemetryPlugin.class.getName();

    private static final String HOSTNAME = getHostName();

    private final Tracer tracer;

    private final TextMapPropagator textMapPropagator;

    private static final TextMapSetter<Message> CONTEXT_SETTER = (message, k, v) -> {
        assert message != null;
        message.putStringProperty(k, v);
    };

    private static final TextMapGetter<Message> CONTEXT_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Message message) {
            return message.getPropertyNames()
                    .stream()
                    .map(SimpleString::toString)
                    .toList();
        }

        @Override
        public String get(Message message, String key) {
            assert message != null;
            return message.getStringProperty(key);
        }
    };

    public OpenTelemetryPlugin() {
        this(AutoConfiguredOpenTelemetrySdk.initialize()
                .getOpenTelemetrySdk());
    }

    OpenTelemetryPlugin(OpenTelemetry otel) {
        this.tracer = otel.getTracer(INSTRUMENTATION_NAME);
        this.textMapPropagator = otel.getPropagators()
                .getTextMapPropagator();
    }

    @Override
    public void beforeSend(ServerSession session, Transaction tx, Message message, boolean direct,
            boolean noAutoCreateQueue) {
        Span span = initSpanWithParentContext(message.getAddress() + " process", message);
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(SemanticAttributes.MESSAGING_SYSTEM, "artemis")
                    .setAttribute(SemanticAttributes.MESSAGING_OPERATION, "process")
                    .setAttribute(SemanticAttributes.MESSAGING_MESSAGE_ID,
                            String.valueOf(message.getMessageID()))
                    .setAttribute(SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES,
                            message.getEncodeSize())
                    .setAttribute(SemanticAttributes.NET_PROTOCOL_NAME, message.getProtocolName())
                    .setAttribute(SemanticAttributes.NET_HOST_NAME, HOSTNAME)
                    .setAttribute(SemanticAttributes.MESSAGING_DESTINATION_NAME,
                            message.getAddress())
                    .setAttribute(SemanticAttributes.MESSAGING_MESSAGE_CONVERSATION_ID,
                            String.valueOf(message.getCorrelationID()))
                    .setAttribute(CustomAttributeKeys.TIMESTAMP, message.getTimestamp())
                    .setAttribute(CustomAttributeKeys.CONNECTION_ID, message.getConnectionID())
                    .setAttribute(CustomAttributeKeys.REPLY_TO,
                            String.valueOf(message.getReplyTo()))
                    .setAttribute(CustomAttributeKeys.SESSION_NAME, session.getName())
                    .setAttribute(CustomAttributeKeys.SESSION_USER, session.getUsername());

            textMapPropagator.inject(Context.current(), message, CONTEXT_SETTER);
        } finally {
            span.end();
        }

        // TODO: Might need to call reencode on the message, but try to avoid that if possible
        //  as it seems expensive. See
        // https://github.com/apache/activemq-artemis/blob/main/examples/features/standard/broker-plugin/src/main/java/org/apache/activemq/artemis/jms/example/BrokerPlugin.java
        // Might be able to get away with not propagating spans on a journal reload.
    }

    @Override
    public void onSendException(ServerSession session, Transaction tx, Message message,
            boolean direct, boolean noAutoCreateQueue, Exception e) {
        Span span = initSpanWithParentContext("failed send", message);
        try (Scope ignored = span.makeCurrent()) {
            span.setStatus(StatusCode.ERROR)
                    .recordException(e)
                    .end();
        } finally {
            span.end();
        }
    }

    private Span initSpanWithParentContext(String spanName, Message message) {
        Context parentContext = textMapPropagator.extract(Context.current(),
                message,
                CONTEXT_GETTER);
        return tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
    }

    private static String getHostName() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null) {
            return hostname;
        }
        return "unknown";
    }
}

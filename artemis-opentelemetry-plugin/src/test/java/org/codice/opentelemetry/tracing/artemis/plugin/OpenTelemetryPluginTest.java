package org.codice.opentelemetry.tracing.artemis.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

@ExtendWith(MockitoExtension.class)
public class OpenTelemetryPluginTest {

    private static final String traceparent =
            "00-936fcf09cb507815bd2891cdf9536743-1a7fd3432a0bfd5b-01";

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private OpenTelemetryPlugin plugin;

    @Mock
    private Transaction tx;

    @Mock
    private Message message;

    @Mock
    private ServerSession session;

    @BeforeEach
    public void setUp() {
        when(message.getStringProperty("traceparent")).thenReturn(traceparent);

        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        plugin = new OpenTelemetryPlugin(otel);
    }

    @AfterEach
    public void cleanUp() {
        exporter.reset();
    }

    @Test
    public void testBeforeSendWithoutParentContext() {
        when(message.getStringProperty("traceparent")).thenReturn(null);
        when(message.getAddress()).thenReturn("send-address");
        when(message.getMessageID()).thenReturn(123L);
        when(message.getEncodeSize()).thenReturn(1000);
        when(message.getReplyTo()).thenReturn(SimpleString.toSimpleString("return-address"));
        when(session.getUsername()).thenReturn("testuser");

        plugin.beforeSend(session, tx, message, true, true);

        SpanData spanData = exporter.getFinishedSpanItems()
                .get(0);
        Attributes attrs = spanData.getAttributes();

        assertEquals(spanData.getName(), "send-address process");
        assertEquals(spanData.getParentSpanId(), "0000000000000000");

        assertEquals("artemis", attrs.get(SemanticAttributes.MESSAGING_SYSTEM));
        assertEquals(getHostName(), attrs.get(SemanticAttributes.NET_HOST_NAME));
        assertEquals("123", attrs.get(SemanticAttributes.MESSAGING_MESSAGE_ID));
        assertEquals(1000, attrs.get(SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES));
        assertEquals("return-address", attrs.get(CustomAttributeKeys.REPLY_TO));
        assertEquals("testuser", attrs.get(CustomAttributeKeys.SESSION_USER));

        verifyContextInjection(message, spanData);
    }

    @Test
    public void testBeforeSendIncludesParentContext() {
        when(message.getAddress()).thenReturn("send-address");

        plugin.beforeSend(session, tx, message, true, true);

        SpanData spanData = exporter.getFinishedSpanItems()
                .get(0);

        assertEquals(spanData.getName(), "send-address process");
        assertContextPropagation(spanData);

        verifyContextInjection(message, spanData);
    }

    @Test
    public void testOnSendException() {
        Exception ex = new RuntimeException();
        plugin.onSendException(session, tx, message, true, true, ex);

        SpanData spanData = exporter.getFinishedSpanItems()
                .get(0);

        String exceptionType = spanData.getEvents()
                .get(0)
                .getAttributes()
                .get(AttributeKey.stringKey("exception.type"));

        assertContextPropagation(spanData);
        assertEquals("failed send", spanData.getName());
        assertEquals(ex.getClass()
                .getName(), exceptionType);
        assertEquals(StatusCode.ERROR,
                spanData.getStatus()
                        .getStatusCode());

    }

    private void assertContextPropagation(SpanData spanData) {
        String[] parts = traceparent.split("-");
        assertEquals(spanData.getTraceId(), parts[1]);
        assertEquals(spanData.getParentSpanId(), parts[2]);
    }

    private void verifyContextInjection(Message message, SpanData spanData) {
        verify((message), times(1)).putStringProperty(eq("traceparent"),
                eq(String.format("00-%s-%s-01", spanData.getTraceId(), spanData.getSpanId())));
    }

    private static String getHostName() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname != null) {
            return hostname;
        }
        return "unknown";
    }
}

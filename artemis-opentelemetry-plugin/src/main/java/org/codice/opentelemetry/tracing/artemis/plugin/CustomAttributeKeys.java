package org.codice.opentelemetry.tracing.artemis.plugin;

import io.opentelemetry.api.common.AttributeKey;

public class CustomAttributeKeys {

    static final AttributeKey<Long> TIMESTAMP = AttributeKey.longKey("message.artemis.timestamp");

    static final AttributeKey<String> CONNECTION_ID = AttributeKey.stringKey(
            "messaging.artemis.connectionID");

    static final AttributeKey<String> REPLY_TO =
            AttributeKey.stringKey("messaging.artemis.reply-to");

    static final AttributeKey<String> SESSION_NAME =
            AttributeKey.stringKey("messaging.session.name");

    static final AttributeKey<String> SESSION_USER =
            AttributeKey.stringKey("messaging.session.user");

    static final AttributeKey<String> ROUTING_STATUS = AttributeKey.stringKey(
            "messaging.artemis.routing.status");
}

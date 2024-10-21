#!/bin/bash

echo "Running custom post-config.sh"

echo "Configuring OpenTelemetry plugin..."

echo "Copy OpenTelemetry jar to lib"
cp /amq/opentelemetry-plugin/opentelemetry-plugin*.jar "${CONFIG_INSTANCE_DIR}/lib"

echo "Add plugin config to broker.xml"

OTEL_PLUGIN_CONFIG='<broker-plugins>\n        <broker-plugin class-name="org.codice.opentelemetry.tracing.artemis.plugin.OpenTelemetryPlugin"/>\n      </broker-plugins>\n\n'
sed -i "s|<persistence-enabled>|${OTEL_PLUGIN_CONFIG}      <persistence-enabled>|g" "${CONFIG_INSTANCE_DIR}/etc/broker.xml"

echo "Finished configuring OpenTelemetry plugin"

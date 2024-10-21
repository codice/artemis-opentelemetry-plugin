ARG ARTEMIS_VERSION
ARG OPENTELEMETRY_PLUGIN_JAR

FROM quay.io/artemiscloud/activemq-artemis-broker-init:artemis.$ARTEMIS_VERSION

COPY target/$OPENTELEMETRY_PLUGIN_JAR /amq/opentelemetry-plugin/
COPY scripts /amq/scripts

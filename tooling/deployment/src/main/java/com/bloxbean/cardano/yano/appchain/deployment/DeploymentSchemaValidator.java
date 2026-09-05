package com.bloxbean.cardano.yano.appchain.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

/** Validates deployment documents against the packaged source-of-truth schema. */
final class DeploymentSchemaValidator {
    private static final String RESOURCE = "/yano-cluster-deployment.schema.json";

    private final JsonSchema schema;

    DeploymentSchemaValidator() {
        try (InputStream input = DeploymentSchemaValidator.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("packaged deployment schema is missing");
            }
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input);
        } catch (IOException failure) {
            throw new IllegalStateException("packaged deployment schema cannot be read", failure);
        }
    }

    List<String> validate(JsonNode document) {
        return schema.validate(document).stream()
                .sorted(Comparator.comparing(message -> message.getInstanceLocation().toString()))
                .map(message -> "schema " + message.getInstanceLocation() + ": " + message.getMessage())
                .distinct()
                .toList();
    }
}

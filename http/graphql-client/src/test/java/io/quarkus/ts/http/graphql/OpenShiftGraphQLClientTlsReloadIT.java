package io.quarkus.ts.http.graphql;

import org.junit.jupiter.api.Tag;

import io.quarkus.test.scenarios.OpenShiftScenario;
import io.quarkus.test.scenarios.annotations.DisabledOnNative;

@Tag("QUARKUS-7864")
@OpenShiftScenario
@DisabledOnNative(reason = "Enable this when we using RHEL9 as runners")
public class OpenShiftGraphQLClientTlsReloadIT extends GraphQLClientTlsReloadIT {
}
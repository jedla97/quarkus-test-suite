package io.quarkus.ts.http.graphql;

import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.quarkus.test.bootstrap.Protocol;
import io.quarkus.test.bootstrap.RestService;
import io.quarkus.test.bootstrap.inject.OpenShiftClient;
import io.quarkus.test.scenarios.QuarkusScenario;
import io.quarkus.test.services.QuarkusApplication;
import io.quarkus.ts.http.graphql.tls.TlsGraphQLClient;
import io.quarkus.ts.http.graphql.tls.TlsGraphQLClientResource;
import io.quarkus.ts.http.graphql.tls.TlsGraphQLEndpoint;
import io.quarkus.ts.http.graphql.tls.TlsReloadResource;

@Tag("QUARKUS-7864")
@QuarkusScenario
public class GraphQLClientTlsReloadIT {
    private static final String TLS_CONFIGURATION = "graphql-client";

    @QuarkusApplication(ssl = true, properties = "server-tls.properties", classes = { TlsGraphQLEndpoint.class })
    static final RestService server = new RestService()
            .setAutoStart(false);

    @Inject
    static OpenShiftClient openShiftClient;

    @QuarkusApplication(properties = "client-tls.properties", classes = { TlsGraphQLClient.class,
            TlsGraphQLClientResource.class, TlsReloadResource.class })
    static final RestService client = new RestService()
            .setAutoStart(false)
            .withProperty("quarkus.smallrye-graphql-client.tls-client.url", () -> server.getURI(Protocol.HTTPS) + "/graphql")
            .withProperty("quarkus.smallrye-graphql-client.tls-client.tls-configuration-name", TLS_CONFIGURATION)
            .withProperty("quarkus.smallrye-graphql-client.tls-dynamic.url", () -> server.getURI(Protocol.HTTPS) + "/graphql")
            .withProperty("quarkus.smallrye-graphql-client.tls-dynamic.tls-configuration-name", TLS_CONFIGURATION);

    @AfterEach
    public void stopApplication() {
        client.stop();
        server.stop();
    }

    @Test
    public void typesafeClientShouldUseReloadCertificate() throws IOException, InterruptedException {
        Path certificateDirectory = client.getServiceFolder().toAbsolutePath();

        Path badClient = certificateDirectory.resolve("client-bad.p12");
        Path goodClient = certificateDirectory.resolve("client-good.p12");
        Path activeClient = certificateDirectory.resolve("client-active.p12");

        copyFile(badClient, activeClient);

        server.start();
        client.withProperty("quarkus.tls.graphql-client.key-store.p12.path", "/test/client-active.p12")
                .withProperty("afdas", "secret_with_destination::/test/|client-active.p12")
                .start();

        client.given()
                .when()
                .get("/client/tls/typesafe")
                .then()
                .statusCode(502);

        copyFile(goodClient, activeClient);

        // get secret name as when using auto creatio add the numbers to prevent collision
        String secretName = openShiftClient.getFabric8Client().secrets()
                .inNamespace(openShiftClient.getFabric8Client().getNamespace())
                .list()
                .getItems()
                .stream()
                .filter(s -> s.getMetadata().getName() != null && s.getMetadata().getName().endsWith("client-active-p12"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No secret matching prefix found"))
                .getMetadata()
                .getName();

        // update keystore
        String namespace = openShiftClient.getFabric8Client().getNamespace();
        String fileName = activeClient.getFileName().toString();
        String fileContent = Base64.getEncoder().encodeToString(Files.readAllBytes(goodClient.toAbsolutePath()));

        var secret = new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .endMetadata()
                .addToData(fileName, fileContent)
                .build();

        openShiftClient.getFabric8Client().secrets().inNamespace(openShiftClient.getFabric8Client().getNamespace())
                .resource(secret).unlock()
                .createOr(NonDeletingOperation::patch);

        // it take some time before the change go through on openshift side
        Thread.sleep(60000);

        reloadTls();

        client.given()
                .when()
                .get("/client/tls/typesafe")
                .then()
                .statusCode(200)
                .body(is("Hello"));
    }

    @Test
    public void dynamicClientShouldUseReloadedCertificate() {
        Path certificateDirectory = client.getServiceFolder().resolve("certificates").toAbsolutePath();

        Path badClient = certificateDirectory.resolve("client-bad.p12");
        Path goodClient = certificateDirectory.resolve("client-good.p12");
        Path activeClient = certificateDirectory.resolve("client-active.p12");

        copyFile(badClient, activeClient);

        server.start();
        client.withProperty("quarkus.tls.graphql-client.key-store.p12.path", activeClient.toString()).start();

        client.given()
                .when()
                .get("/client/tls/dynamic")
                .then()
                .statusCode(502);

        copyFile(goodClient, activeClient);

        reloadTls();

        client.given()
                .when()
                .get("/client/tls/dynamic")
                .then()
                .statusCode(200)
                .body(is("Hello"));
    }

    private static void reloadTls() {
        client.given()
                .when()
                .post("/tls/reload/" + TLS_CONFIGURATION)
                .then()
                .statusCode(204);
    }

    private static void copyFile(Path source, Path destination) {
        try {
            Files.copy(source.toAbsolutePath(), destination.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy file from " + source + " to " + destination, e);
        }
    }
}

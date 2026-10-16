package io.github.sanskar121543.payments.cluster;

import io.github.sanskar121543.payments.support.ApiClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The compose topology rebuilt in code: Postgres, N app containers running the jar Maven just built,
 * and optionally nginx with the repo's real nginx.conf. Tests talk to it like any outside client.
 */
public final class LedgerTopology implements AutoCloseable {

    public static final String TEST_KEY_HASH = "62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef";
    private static final long APP_MEMORY_BYTES = 768L * 1024 * 1024;

    private static final ImageFromDockerfile APP_IMAGE = new ImageFromDockerfile("payments-ledger-it", true)
            .withFileFromPath("app.jar", packagedJar())
            .withDockerfileFromBuilder(builder -> builder
                    .from("eclipse-temurin:21-jre-noble")
                    .copy("app.jar", "/app/app.jar")
                    .entryPoint("sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar")
                    .build());

    private final Network network = Network.newNetwork();
    private final PostgreSQLContainer postgres;
    private final Map<String, GenericContainer<?>> apps = new LinkedHashMap<>();
    private final List<GenericContainer<?>> extraApps = new ArrayList<>();
    private GenericContainer<?> nginx;

    private LedgerTopology() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=100",
                        "-c", "client_connection_check_interval=1s",
                        "-c", "tcp_keepalives_idle=30", "-c", "tcp_keepalives_interval=10",
                        "-c", "tcp_keepalives_count=3");
    }

    public static LedgerTopology start(List<String> appNames, boolean withNginx) {
        return start(appNames, withNginx, Map.of());
    }

    public static LedgerTopology start(List<String> appNames, boolean withNginx, Map<String, String> extraEnv) {
        LedgerTopology topology = new LedgerTopology();
        topology.postgres.start();
        for (String name : appNames) {
            topology.apps.put(name, topology.newApp(name, extraEnv));
        }
        Startables.deepStart(topology.apps.values()).join();
        if (withNginx) {
            topology.nginx = new GenericContainer<>(DockerImageName.parse("nginx:1.30.6-alpine"))
                    .withNetwork(topology.network)
                    .withNetworkAliases("nginx")
                    .withCopyFileToContainer(MountableFile.forHostPath(Path.of("nginx", "nginx.conf")),
                            "/etc/nginx/nginx.conf")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200));
            topology.nginx.start();
        }
        return topology;
    }

    /** Starts one more app container against the same database (e.g. "the app after a restart"). */
    public GenericContainer<?> startExtraApp(String name, Map<String, String> extraEnv) {
        GenericContainer<?> app = newApp(name, extraEnv);
        app.start();
        extraApps.add(app);
        return app;
    }

    public ApiClient viaNginx() {
        return new ApiClient("http://" + nginx.getHost() + ":" + nginx.getMappedPort(80), ApiClient.TEST_KEY);
    }

    public ApiClient direct(String appName) {
        return direct(apps.get(appName));
    }

    public static ApiClient direct(GenericContainer<?> app) {
        return new ApiClient("http://" + app.getHost() + ":" + app.getMappedPort(8080), ApiClient.TEST_KEY);
    }

    public GenericContainer<?> app(String name) {
        return apps.get(name);
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(), postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()));
    }

    public String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    public String dbUser() {
        return postgres.getUsername();
    }

    public String dbPassword() {
        return postgres.getPassword();
    }

    /** SIGKILL, like a crash or the OOM killer: no shutdown hooks, no graceful drain. */
    public static void kill(GenericContainer<?> container) {
        DockerClientFactory.instance().client().killContainerCmd(container.getContainerId()).exec();
    }

    @Override
    public void close() {
        Stream.concat(Stream.concat(Stream.ofNullable(nginx), extraApps.stream()), apps.values().stream())
                .forEach(Startable::stop);
        postgres.stop();
        network.close();
    }

    private GenericContainer<?> newApp(String name, Map<String, String> extraEnv) {
        GenericContainer<?> app = new GenericContainer<>(APP_IMAGE)
                .withNetwork(network)
                .withNetworkAliases(name)
                .withEnv("INSTANCE_ID", name)
                .withEnv("DB_URL", "jdbc:postgresql://postgres:5432/" + postgres.getDatabaseName())
                .withEnv("DB_USER", postgres.getUsername())
                .withEnv("DB_PASSWORD", postgres.getPassword())
                .withEnv("API_KEY_HASHES", "test:" + TEST_KEY_HASH)
                .withEnv("JAVA_OPTS", "-Xms256m -Xmx256m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError")
                .withEnv(extraEnv)
                .withExposedPorts(8080)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withMemory(APP_MEMORY_BYTES)
                        .withMemorySwap(APP_MEMORY_BYTES))
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        app.dependsOn(postgres);
        return app;
    }

    private static Path packagedJar() {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("payments-ledger-service-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jar in target/: run ./mvnw verify, not just the test"));
        } catch (IOException e) {
            throw new IllegalStateException("No target/ directory: run ./mvnw verify", e);
        }
    }
}

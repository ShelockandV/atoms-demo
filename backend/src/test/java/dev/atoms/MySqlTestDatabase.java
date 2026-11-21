package dev.atoms;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public abstract class MySqlTestDatabase {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        var source = create();
        if (Boolean.getBoolean("atoms.test.temporal.enabled")) {
            String queue = "atoms-test-" + UUID.randomUUID();
            properties.add("atoms.temporal.task-queue", () -> queue);
            properties.add("atoms.temporal.target",
                    () -> System.getProperty("atoms.test.temporal.target", "127.0.0.1:7233"));
        } else {
            properties.add("atoms.temporal.enabled", () -> false);
        }
        properties.add("spring.datasource.url", source::getUrl);
        properties.add("spring.datasource.username", source::getUsername);
        properties.add("spring.datasource.password", source::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    public static DriverManagerDataSource create() {
        String database = "atoms_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = source("/");
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("无法创建隔离的 MySQL 测试库", failure);
        }
        return source("/" + database);
    }

    private static DriverManagerDataSource source(String database) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + Container.PORT + database
                + "?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectTimeout=1000",
                "root", Container.PASSWORD);
    }

    private static final class Container {
        private static final String PASSWORD = UUID.randomUUID().toString();
        private static final int PORT = start();

        private static int start() {
            String name = "atoms-test-mysql-" + UUID.randomUUID();
            String image = System.getenv().getOrDefault("ATOMS_MYSQL_TEST_IMAGE", "mysql:8.4");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> remove(name)));
            docker("run", "-d", "--name", name, "-p", "127.0.0.1::3306",
                    "-e", "MYSQL_ROOT_PASSWORD=" + PASSWORD, "-e", "MYSQL_ROOT_HOST=%", image);
            try {
                String address = docker("port", name, "3306/tcp");
                int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
                var probe = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                        + "/?allowPublicKeyRetrieval=true&sslMode=DISABLED&connectTimeout=1000", "root", PASSWORD);
                var deadline = Instant.now().plus(Duration.ofSeconds(90));
                while (Instant.now().isBefore(deadline)) {
                    try (var ignored = probe.getConnection()) {
                        return port;
                    } catch (java.sql.SQLException starting) {
                        Thread.sleep(400);
                    }
                }
                throw new IllegalStateException("隔离的 MySQL 测试容器未能启动");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 MySQL 测试容器时被中断", interrupted);
            }
        }

        private static void remove(String name) {
            try {
                docker("rm", "-fv", name);
            } catch (IllegalStateException ignored) {
                // Docker may already have removed the container.
            }
        }

    }

    private static String docker(String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(java.util.List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Docker MySQL 测试命令超时");
            }
            String result = output.join();
            if (process.exitValue() != 0) throw new IllegalStateException("Docker MySQL 测试命令失败：" + result);
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("数据库测试需要运行中的 Docker", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker MySQL 测试命令被中断", interrupted);
        }
    }
}

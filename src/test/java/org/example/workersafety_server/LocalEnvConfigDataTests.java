package org.example.workersafety_server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalEnvConfigDataTests {
    @Test
    void localProfileLoadsUtf8EnvAndEnvironmentOverridesIt() throws IOException {
        Path directory = configDirectory();
        Files.writeString(directory.resolve(".env"),
                "ADMIN_LOGIN_ID=file-admin\nADMIN_INITIAL_PASSWORD=비밀번호-한글-12자\nSERVER_PORT=8181\n",
                StandardCharsets.UTF_8);
        String original = System.getProperty("ADMIN_LOGIN_ID");
        System.setProperty("ADMIN_LOGIN_ID", "environment-admin");
        try (ConfigurableApplicationContext context = run("local", directory)) {
            assertThat(context.getEnvironment().getProperty("ADMIN_LOGIN_ID")).isEqualTo("environment-admin");
            assertThat(context.getEnvironment().getProperty("ADMIN_INITIAL_PASSWORD")).isEqualTo("비밀번호-한글-12자");
            assertThat(context.getEnvironment().getProperty("SERVER_PORT")).isEqualTo("8181");
        } finally {
            if (original == null) System.clearProperty("ADMIN_LOGIN_ID");
            else System.setProperty("ADMIN_LOGIN_ID", original);
            deleteDirectory(directory);
        }
    }

    @Test
    void missingEnvIsOptionalAndOnlyLocalProfileImportsIt() throws IOException {
        Path directory = configDirectory();
        try (ConfigurableApplicationContext local = run("local", directory)) {
            assertThat(local.getEnvironment().getProperty("ADMIN_LOGIN_ID")).isNull();
        }
        Files.writeString(directory.resolve(".env"), "ADMIN_LOGIN_ID=local-only\n", StandardCharsets.UTF_8);
        try (ConfigurableApplicationContext production = run("prod", directory);
             ConfigurableApplicationContext test = run("test", directory)) {
            assertThat(production.getEnvironment().getProperty("ADMIN_LOGIN_ID")).isNull();
            assertThat(test.getEnvironment().getProperty("ADMIN_LOGIN_ID")).isNull();
        } finally {
            deleteDirectory(directory);
        }
    }

    private static Path configDirectory() throws IOException {
        Path directory = Files.createTempDirectory("workersafety-config-");
        try (var input = LocalEnvConfigDataTests.class.getResourceAsStream("/application-local.properties")) {
            if (input == null) throw new IOException("application-local.properties is missing from the test classpath");
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String envLocation = directory.resolve(".env").toUri().toString();
            config = config.replace("optional:file:./.env", "optional:" + envLocation);
            Files.writeString(directory.resolve("application-local.properties"), config, StandardCharsets.UTF_8);
        }
        return directory;
    }

    private static ConfigurableApplicationContext run(String profile, Path directory) {
        return new SpringApplicationBuilder(ConfigProbe.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .run("--spring.config.location=" + directory.toUri());
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConfigProbe {}
}

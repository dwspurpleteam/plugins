package ru.itmo.plugins.attendance.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ProfilesTest {
    @ParameterizedTest
    @CsvSource({
        "prod, nginx:19090, true, 0.0.0.0, 8081, 4s",
        "stage, nginx-stage:19090, true, 0.0.0.0, 8081, 4s",
        "local, calls.vareonix.cc:443, false, 127.0.0.1, 18081, 2500ms"
    })
    void loadsCommonAndEnvironmentSettings(String profile, String target, boolean plaintext,
            String address, int port, String activationTimeout) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ProfileProperties.class)
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var streamApi = context.getBean(StreamApiProperties.class);
                    assertThat(streamApi.target()).isEqualTo(target);
                    assertThat(streamApi.plaintext()).isEqualTo(plaintext);
                    assertThat(streamApi.metadata()).isEmpty();
                    assertThat(context.getBean(TrackingProperties.class).maxCalls()).isEqualTo(100);
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("server.address")).isEqualTo(address);
                    assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(port);
                    assertThat(environment.getProperty("attendance.activation.start-timeout"))
                            .isEqualTo(activationTimeout);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({StreamApiProperties.class, TrackingProperties.class})
    static class ProfileProperties {
    }
}

package com.rushi.walletledger.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rushi.walletledger.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 500 path: a bug in the application must look like a plain failure to the client, while the
 * real exception is logged where an operator can find it.
 *
 * <p>The failing endpoint exists only in this test, behind a profile that no other test activates,
 * so nothing that can throw on purpose is part of the application.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("unexpected-error-fixture")
@Import(UnexpectedErrorApiTests.FailingEndpointConfig.class)
class UnexpectedErrorApiTests extends ApiIntegrationTest {

    @Test
    void anUnexpectedExceptionIsAGeneric500ThatLeaksNothing() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        ApiResponse response;
        try {
            response = getJson("/api/v1/test-only/failure");
        } finally {
            logger.detachAppender(appender);
        }

        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.textField("message")).isEqualTo("an unexpected error occurred");

        // Nothing from the throwable may reach the client: no class name, no message, no frames.
        String raw = response.body().toString();
        assertThat(raw)
                .doesNotContain("IllegalStateException")
                .doesNotContain("fixture-secret-detail")
                .doesNotContain("java.lang")
                .doesNotContain("com.rushi")
                .doesNotContain("\tat ");
        assertThat(response.body().get("stackTrace")).isNull();
        assertThat(response.body().get("trace")).isNull();
        assertThat(response.body().get("exception")).isNull();

        // The real exception is still recorded, with its cause, server-side.
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("ERROR");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo("java.lang.IllegalStateException");
        });
    }

    /**
     * Registers the fixture explicitly instead of relying on component scanning: a class in the
     * test sources is not part of the application's component scan, and this endpoint must exist in
     * exactly one test context.
     */
    @TestConfiguration
    static class FailingEndpointConfig {

        @Bean
        FailingEndpoint failingEndpoint() {
            return new FailingEndpoint();
        }
    }

    /** Test-only: the one endpoint in the system that throws on purpose. */
    @RestController
    @Profile("unexpected-error-fixture")
    static class FailingEndpoint {

        @GetMapping("/api/v1/test-only/failure")
        void fail() {
            throw new IllegalStateException("fixture-secret-detail");
        }
    }
}

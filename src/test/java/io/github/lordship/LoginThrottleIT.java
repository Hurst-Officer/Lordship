package io.github.lordship;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The throttle wired end to end: service, exception, handler, filter. The counting
 * rules themselves are pinned in LoginThrottleTest.
 *
 * <p>Its own pair limit means its own Spring context. Each test signs in from its own
 * made-up address, so the tests cannot trip each other's counters.
 */
@Transactional
@TestPropertySource(properties = "lordship.login.pair-limit=3")
public class LoginThrottleIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    private ResultActions signIn(String address, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/agents/auth")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "workEmail": "%s", "password": "%s" }
                        """.formatted(email, password)));
    }

    @Test
    void theRightPassword_isRefused_onceThePairHasReachedItsLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            signIn("10.20.0.1", rootEmail, "not-the-password").andExpect(status().isUnauthorized());
        }

        signIn("10.20.0.1", rootEmail, rootPassword)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("auth.too_many_attempts"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anotherAddress_canStillSignIn() throws Exception {
        for (int i = 0; i < 3; i++) {
            signIn("10.20.0.2", rootEmail, "not-the-password").andExpect(status().isUnauthorized());
        }

        signIn("10.20.0.3", rootEmail, rootPassword).andExpect(status().isOk());
    }

    @Test
    void unknownEmail_andWrongPassword_getTheSameAnswer() throws Exception {
        String unknown = signIn("10.20.0.4", "nobody@example.test", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("auth.bad_credentials"))
                .andReturn().getResponse().getContentAsString();

        String wrong = signIn("10.20.0.5", rootEmail, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(unknown, wrong);
    }

    @Test
    void emailCase_doesNotMatter() throws Exception {
        signIn("10.20.0.6", rootEmail.toUpperCase(), rootPassword).andExpect(status().isOk());
    }

    @Test
    void anOversizedBody_isTurnedAwayBeforeItIsRead() throws Exception {
        String padding = "x".repeat(5000);

        signIn("10.20.0.7", rootEmail, padding).andExpect(status().is(413));
    }
}

package io.github.lordship.access.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.lordship.access.LoginRefused;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Spring, and a clock the test moves by hand -- nothing here sleeps.
 */
public class LoginThrottleTest {

    private static final String IP = "10.0.0.1";
    private static final String EMAIL = "bob@hurst.test";

    private final FakeTicker clock = new FakeTicker();

    // address 10, pair 5, 15-minute window; the bucket is out of the way unless a test sets it
    private LoginThrottle throttle() {
        return new LoginThrottle(10, 5, 20, Duration.ofMinutes(15), 100_000, 100_000, clock);
    }

    private static void admitTimes(LoginThrottle throttle, int times, String ip, String email) {
        for (int i = 0; i < times; i++) {
            throttle.admit(ip, email);
        }
    }

    // ---- the pair ----------------------------------------------------------

    @Test
    void pair_isRefusedOnceItReachesItsLimit() {
        // Arrange
        LoginThrottle throttle = throttle();
        admitTimes(throttle, 5, IP, EMAIL);

        // Act
        LoginRefused e = assertThrows(LoginRefused.class, () -> throttle.admit(IP, EMAIL));

        // Assert
        assertTrue(e.isThrottled());
        assertEquals("auth.too_many_attempts", e.problem().code());
    }

    @Test
    void pair_limitDoesNotSpillOntoAnotherEmailOrAnotherAddress() {
        // Arrange
        LoginThrottle throttle = throttle();
        admitTimes(throttle, 5, IP, EMAIL);

        // Act + Assert
        assertDoesNotThrow(() -> throttle.admit(IP, "alice@hurst.test"));
        assertDoesNotThrow(() -> throttle.admit("10.0.0.2", EMAIL));
    }

    @Test
    void success_clearsThePair() {
        // Arrange
        LoginThrottle throttle = throttle();
        admitTimes(throttle, 4, IP, EMAIL);
        throttle.admit(IP, EMAIL);
        throttle.succeeded(IP, EMAIL);

        // Act + Assert -- five more would have been nine without the clear
        assertDoesNotThrow(() -> admitTimes(throttle, 5, IP, EMAIL));
    }

    @Test
    void email_caseAndSpaces_doNotMakeANewPair() {
        // Arrange
        LoginThrottle throttle = throttle();
        for (String spelling : List.of("Bob@Hurst.test", " bob@hurst.test", "BOB@HURST.TEST", "bob@hurst.test ", EMAIL)) {
            throttle.admit(IP, spelling);
        }

        // Act + Assert
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, "bOb@hUrSt.TeSt"));
    }

    // ---- the address -------------------------------------------------------

    @Test
    void address_isRefusedOnceItReachesItsLimit_whateverTheEmail() {
        // Arrange
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 10; i++) {
            throttle.admit(IP, "guess" + i + "@hurst.test");
        }

        // Act + Assert
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, "someone.new@hurst.test"));
    }

    // Otherwise an attacker holding one real account could sign in with it to wipe
    // the address's count between rounds of guessing.
    @Test
    void success_refundsOnlyItsOwnAttemptAtTheAddress() {
        // Arrange
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 9; i++) {
            throttle.admit(IP, "guess" + i + "@hurst.test");
        }
        throttle.admit(IP, EMAIL);
        throttle.succeeded(IP, EMAIL);

        // Act + Assert -- back to nine, so one more fits and the next does not
        assertDoesNotThrow(() -> throttle.admit(IP, "guess9@hurst.test"));
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, "guess10@hurst.test"));
    }

    @Test
    void ipv6_addressesInOneSlash64_countAsOneAddress() {
        assertEquals(LoginThrottle.addressKey("2001:db8:1:2::1"), LoginThrottle.addressKey("2001:db8:1:2:ffff::9"));
        assertNotEquals(LoginThrottle.addressKey("2001:db8:1:2::1"), LoginThrottle.addressKey("2001:db8:1:3::1"));
    }

    @Test
    void ipv4WrittenAsIpv6_isTheIpv4Address() {
        assertEquals("10.0.0.1", LoginThrottle.addressKey("::ffff:10.0.0.1"));
    }

    // ---- the window --------------------------------------------------------

    // expireAfterWrite would restart the window on every attempt, and someone who
    // never stopped trying would never be let go.
    @Test
    void window_opensAtTheFirstAttempt_andDoesNotSlide() {
        // Arrange
        LoginThrottle throttle = throttle();
        admitTimes(throttle, 4, IP, EMAIL);
        clock.advance(Duration.ofMinutes(14));
        throttle.admit(IP, EMAIL);
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, EMAIL));

        // Act
        clock.advance(Duration.ofSeconds(61));

        // Assert
        assertDoesNotThrow(() -> throttle.admit(IP, EMAIL));
    }

    @Test
    void retryAfter_isWhatIsLeftOfTheWindow() {
        // Arrange
        LoginThrottle throttle = throttle();
        admitTimes(throttle, 5, IP, EMAIL);
        clock.advance(Duration.ofMinutes(5));

        // Act
        LoginRefused e = assertThrows(LoginRefused.class, () -> throttle.admit(IP, EMAIL));

        // Assert
        assertEquals(600, e.retryAfterSeconds());
        assertEquals(List.of(10L), e.problem().args());
    }

    // ---- the server-wide bucket --------------------------------------------

    @Test
    void bucket_allowsTheBurst_thenOnePerSecond() {
        // Arrange
        LoginThrottle throttle = new LoginThrottle(100, 100, 100, Duration.ofMinutes(15), 1, 5, clock);
        for (int i = 0; i < 5; i++) {
            throttle.admit("10.0.1." + i, EMAIL);
        }

        // Act
        LoginRefused e = assertThrows(LoginRefused.class, () -> throttle.admit("10.0.1.9", EMAIL));

        // Assert
        assertEquals(1, e.retryAfterSeconds());
        assertEquals(List.of(0L), e.problem().args());

        clock.advance(Duration.ofSeconds(1));
        assertDoesNotThrow(() -> throttle.admit("10.0.1.10", EMAIL));
        assertThrows(LoginRefused.class, () -> throttle.admit("10.0.1.11", EMAIL));
    }

    @Test
    void refusedAddress_doesNotSpendTheBucket() {
        // Arrange -- three tokens, and an address allowed two attempts
        LoginThrottle throttle = new LoginThrottle(2, 100, 100, Duration.ofMinutes(15), 1, 3, clock);
        throttle.admit(IP, "a@hurst.test");
        throttle.admit(IP, "b@hurst.test");

        // Act -- refused at the address, before the bucket
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, "c@hurst.test"));
        assertThrows(LoginRefused.class, () -> throttle.admit(IP, "d@hurst.test"));

        // Assert -- the third token is still there for someone else
        assertDoesNotThrow(() -> throttle.admit("10.0.0.2", EMAIL));
    }

    private static final class FakeTicker implements Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration by) {
            nanos += by.toNanos();
        }
    }
}

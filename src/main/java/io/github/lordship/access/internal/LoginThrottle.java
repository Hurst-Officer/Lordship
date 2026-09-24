package io.github.lordship.access.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.lordship.access.LoginRefused;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;

/**
 * Decides whether a sign-in attempt may reach the password check at all. Checks, in
 * order: the address, the address + email pair, then the server-wide bucket -- so an
 * address already refused never spends a token everyone else needs.
 *
 * <p>Attempts are counted up front and refunded on success. Counting after a failure
 * would let a burst of simultaneous guesses slip past the limit.
 *
 * <p>No hard lock on an account alone, or anyone could lock the boss out. The
 * per-account count only logs a warning.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final int MAX_TRACKED = 50_000;
    private static final Duration BUCKET_WAIT = Duration.ofSeconds(1);

    private final int ipLimit;
    private final int pairLimit;
    private final int accountAlert;
    private final Duration window;

    // server-wide bucket; guarded by this
    private final int perSecond;
    private final int burst;
    private final Ticker ticker;
    private double tokens;
    private long lastRefill;

    // "ip:..", "pair:..", "account:.." -> attempts in the current window
    private final Cache<String, Integer> attempts;

    @Autowired
    public LoginThrottle(@Value("${lordship.login.ip-limit:10}") int ipLimit,
                         @Value("${lordship.login.pair-limit:5}") int pairLimit,
                         @Value("${lordship.login.account-alert:20}") int accountAlert,
                         @Value("${lordship.login.window-minutes:15}") int windowMinutes,
                         @Value("${lordship.login.per-second:1}") int perSecond,
                         @Value("${lordship.login.burst:5}") int burst) {
        this(ipLimit, pairLimit, accountAlert, Duration.ofMinutes(windowMinutes), perSecond, burst, Ticker.systemTicker());
    }

    LoginThrottle(int ipLimit, int pairLimit, int accountAlert, Duration window,
                  int perSecond, int burst, Ticker ticker) {
        this.ipLimit = ipLimit;
        this.pairLimit = pairLimit;
        this.accountAlert = accountAlert;
        this.window = window;
        this.perSecond = perSecond;
        this.burst = burst;
        this.ticker = ticker;
        this.tokens = burst;
        this.lastRefill = ticker.read();
        this.attempts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED)
                .expireAfter(fixedWindow(window))
                .ticker(ticker)
                .build();
    }

    /**
     * Lets the attempt through, or throws LoginRefused with how long to wait.
     * Call before looking anything up.
     */
    public synchronized void admit(String ipAddress, String workEmail) {
        String ip = "ip:" + addressKey(ipAddress);
        String pair = "pair:" + addressKey(ipAddress) + "|" + emailKey(workEmail);
        String account = "account:" + emailKey(workEmail);

        refuseIfAtLimit(ip, ipLimit);
        refuseIfAtLimit(pair, pairLimit);
        takeToken();

        if (count(ip) == ipLimit) {
            log.warn("Sign-in attempts from {} reached {}; refusing it until the window closes", ipAddress, ipLimit);
        }
        if (count(pair) == pairLimit) {
            log.warn("Sign-in attempts at {} from {} reached {}; refusing that pair until the window closes",
                    workEmail, ipAddress, pairLimit);
        }
        if (count(account) == accountAlert) {
            log.warn("Sign-in attempts at {} reached {} across all addresses -- possible guessing from many places",
                    workEmail, accountAlert);
        }
    }

    /** The password was right: forget this pair, and give back what the attempt cost. */
    public synchronized void succeeded(String ipAddress, String workEmail) {
        attempts.invalidate("pair:" + addressKey(ipAddress) + "|" + emailKey(workEmail));
        refund("ip:" + addressKey(ipAddress));
        refund("account:" + emailKey(workEmail));
    }

    private void refuseIfAtLimit(String key, int limit) {
        Integer seen = attempts.getIfPresent(key);
        if (seen != null && seen >= limit) {
            Duration left = attempts.policy().expireVariably()
                    .flatMap(p -> p.getExpiresAfter(key))
                    .orElse(window);
            throw LoginRefused.tooManyAttempts(left);
        }
    }

    private void takeToken() {
        long now = ticker.read();
        double earned = (now - lastRefill) / 1_000_000_000.0 * perSecond;
        tokens = Math.min(burst, tokens + earned);
        lastRefill = now;

        if (tokens < 1) {
            throw LoginRefused.tooManyAttempts(BUCKET_WAIT);
        }
        tokens -= 1;
    }

    private int count(String key) {
        return attempts.asMap().merge(key, 1, Integer::sum);
    }

    private void refund(String key) {
        attempts.asMap().computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null);
    }

    // IPv6 hands a single machine a whole /64, so the /64 is what counts as one address.
    static String addressKey(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "unknown";
        }
        if (ipAddress.indexOf(':') < 0) {
            return ipAddress;
        }
        try {
            InetAddress parsed = InetAddress.getByName(ipAddress); // a literal, so no DNS lookup
            if (parsed instanceof Inet6Address) {
                byte[] b = parsed.getAddress();
                return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
            }
            return parsed.getHostAddress(); // an IPv4 address written as IPv6
        } catch (UnknownHostException e) {
            return ipAddress;
        }
    }

    static String emailKey(String workEmail) {
        return workEmail == null ? "" : workEmail.strip().toLowerCase(Locale.ROOT);
    }

    // The window opens at the first attempt and does not move. expireAfterWrite would
    // restart it on every attempt, so someone who never stopped would never be let go.
    private static Expiry<String, Integer> fixedWindow(Duration window) {
        long nanos = window.toNanos();
        return new Expiry<>() {
            @Override
            public long expireAfterCreate(String key, Integer value, long currentTime) {
                return nanos;
            }

            @Override
            public long expireAfterUpdate(String key, Integer value, long currentTime, long currentDuration) {
                return currentDuration;
            }

            @Override
            public long expireAfterRead(String key, Integer value, long currentTime, long currentDuration) {
                return currentDuration;
            }
        };
    }
}

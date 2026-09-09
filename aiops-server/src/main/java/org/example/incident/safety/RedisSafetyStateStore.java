package org.example.incident.safety;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisSafetyStateStore {

    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSafetyStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Lease> acquireServiceLease(String serviceId, Duration ttl) {
        String key = "aiops:lock:service:" + serviceId;
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? Optional.of(new Lease(key, token)) : Optional.empty();
        } catch (DataAccessException ex) {
            return Optional.empty();
        }
    }

    public void release(Lease lease) {
        try {
            redis.execute(RELEASE_LOCK, List.of(lease.key()), lease.token());
        } catch (DataAccessException ignored) {
            // The lease expires by TTL; a release failure must not replay a completed action.
        }
    }

    public boolean renew(Lease lease, Duration ttl) {
        try {
            Long renewed = redis.execute(
                    RENEW_LOCK,
                    List.of(lease.key()),
                    lease.token(),
                    Long.toString(ttl.toMillis()));
            return renewed != null && renewed == 1;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public SafetySnapshot snapshot(String serviceId, Duration window) {
        try {
            String writesKey = "aiops:writes:" + serviceId;
            String lastWrite = redis.opsForValue().get("aiops:last-write:" + serviceId);
            String writes = redis.opsForValue().get(writesKey);
            boolean circuitOpen = Boolean.TRUE.equals(redis.hasKey("aiops:circuit:" + serviceId));
            int recentWrites = writes == null ? 0 : Integer.parseInt(writes);
            Duration sinceLastWrite = lastWrite == null
                    ? Duration.ofDays(365)
                    : Duration.between(Instant.parse(lastWrite), Instant.now());
            return new SafetySnapshot(true, recentWrites, sinceLastWrite, circuitOpen);
        } catch (DataAccessException ex) {
            return SafetySnapshot.unavailable();
        }
    }

    public boolean recordWrite(String serviceId, Duration window) {
        try {
            String writesKey = "aiops:writes:" + serviceId;
            Long count = redis.opsForValue().increment(writesKey);
            if (count != null && count == 1) {
                redis.expire(writesKey, window);
            }
            redis.opsForValue().set("aiops:last-write:" + serviceId, Instant.now().toString(), window);
            return true;
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public void openCircuit(String serviceId, Duration ttl) {
        redis.opsForValue().set("aiops:circuit:" + serviceId, "open", ttl);
    }

    public record Lease(String key, String token) {
    }

    public record SafetySnapshot(
            boolean available,
            int recentWrites,
            Duration sinceLastWrite,
            boolean circuitOpen) {

        public static SafetySnapshot unavailable() {
            return new SafetySnapshot(false, 0, Duration.ZERO, true);
        }
    }
}

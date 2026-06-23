package me.projects.pushpage.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitService {

    @Value("${app.rate-limit.user-requests-per-minute:10}")
    private int userRequestsPerMinute;

    @Value("${app.rate-limit.guest-requests-per-minute:5}")
    private int guestRequestsPerMinute;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ConsumptionProbe tryConsume(String key, int capacity) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket(capacity));
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    public int getUserRequestsPerMinute() {
        return userRequestsPerMinute;
    }

    public int getGuestRequestsPerMinute() {
        return guestRequestsPerMinute;
    }

    public void clearBuckets() {
        buckets.clear();
    }

    private Bucket newBucket(int capacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}

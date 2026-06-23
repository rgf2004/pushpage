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

    @Value("${app.rate-limit.requests-per-minute:10}")
    private int requestsPerMinute;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ConsumptionProbe tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void clearBuckets() {
        buckets.clear();
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}

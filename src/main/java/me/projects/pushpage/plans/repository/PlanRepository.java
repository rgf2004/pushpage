package me.projects.pushpage.plans.repository;

import me.projects.pushpage.plans.ConditionalOnPlansEnabled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@ConditionalOnPlansEnabled
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String findPlanByUserId(String userId) {
        List<String> result = jdbc.query(
                "SELECT plan FROM users WHERE id = ?",
                (rs, i) -> rs.getString("plan"),
                userId
        );
        return result.isEmpty() ? "tier1" : result.get(0);
    }

    public boolean changePlan(String userId, String plan) {
        int rows = jdbc.update("UPDATE users SET plan = ? WHERE id = ?", plan, userId);
        return rows > 0;
    }

    /** Counts pages published today (UTC) for the user, including deleted ones. */
    public long countTodayPagesByUser(String userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pages WHERE user_id = ? AND created_at >= date_trunc('day', NOW() AT TIME ZONE 'UTC')",
                Long.class, userId
        );
        return count != null ? count : 0;
    }

    /** Returns a userId → plan map for the given user IDs (for bulk enrichment). */
    public Map<String, String> findPlansByUserIds(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbc.query(
                "SELECT id, plan FROM users WHERE id IN (" + placeholders + ")",
                (rs, i) -> Map.entry(rs.getString("id"), rs.getString("plan")),
                userIds.toArray()
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

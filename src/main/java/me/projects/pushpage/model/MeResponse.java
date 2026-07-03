package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record MeResponse(
        String id,
        String email,
        boolean admin,
        @JsonInclude(JsonInclude.Include.NON_NULL) String plan,
        @JsonProperty("daily_usage") long dailyUsage,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("daily_limit") Integer dailyLimit
) {}

package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RotateTokenResponse(@JsonProperty("api_key") String apiKey) {}

package me.projects.pushpage.model;

public record PublishRequest(String html, String title, Boolean permanent) {

    public PublishRequest(String html, String title) {
        this(html, title, null);
    }
}

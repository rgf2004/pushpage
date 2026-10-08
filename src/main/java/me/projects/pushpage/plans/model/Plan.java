package me.projects.pushpage.plans.model;

public enum Plan {
    tier1(30, 7),
    tier2(100, 30),
    tier3(500, 90);

    public final int dailyLimit;
    public final int retentionDays;

    Plan(int dailyLimit, int retentionDays) {
        this.dailyLimit = dailyLimit;
        this.retentionDays = retentionDays;
    }
}

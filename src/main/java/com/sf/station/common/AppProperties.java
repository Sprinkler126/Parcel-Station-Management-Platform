package com.sf.station.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务参数，全部外置到 application.yml，便于演示时调参。 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Overdue overdue = new Overdue();
    private Inbound inbound = new Inbound();
    private Seed seed = new Seed();

    public static class Overdue {
        private int warnHours = 48;
        private int alertHours = 72;

        public int getWarnHours() {
            return warnHours;
        }

        public void setWarnHours(int warnHours) {
            this.warnHours = warnHours;
        }

        public int getAlertHours() {
            return alertHours;
        }

        public void setAlertHours(int alertHours) {
            this.alertHours = alertHours;
        }
    }

    public static class Inbound {
        private int maxRetry = 3;

        public int getMaxRetry() {
            return maxRetry;
        }

        public void setMaxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
        }
    }

    public static class Seed {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public Overdue getOverdue() {
        return overdue;
    }

    public void setOverdue(Overdue overdue) {
        this.overdue = overdue;
    }

    public Inbound getInbound() {
        return inbound;
    }

    public void setInbound(Inbound inbound) {
        this.inbound = inbound;
    }

    public Seed getSeed() {
        return seed;
    }

    public void setSeed(Seed seed) {
        this.seed = seed;
    }
}

package com.sf.station.common;

import com.sf.station.code.domain.CooldownConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 业务参数，全部外置到 application.yml，便于演示时调参。 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Cooldown cooldown = new Cooldown();
    private Overdue overdue = new Overdue();
    private Inbound inbound = new Inbound();
    private Seed seed = new Seed();

    public CooldownConfig toCooldownConfig() {
        return new CooldownConfig(cooldown.minDays, cooldown.maxDays, cooldown.bufferDays,
                cooldown.defaultDays, cooldown.tightThreshold, cooldown.emergencyThreshold,
                cooldown.ewmaAlpha);
    }

    public static class Cooldown {
        private int minDays = 3;
        private int maxDays = 90;
        private int bufferDays = 3;
        private int defaultDays = 7;
        private double tightThreshold = 0.30;
        private double emergencyThreshold = 0.10;
        private double ewmaAlpha = 0.3;
        private int statWindowDays = 14;

        public int getMinDays() {
            return minDays;
        }

        public void setMinDays(int minDays) {
            this.minDays = minDays;
        }

        public int getMaxDays() {
            return maxDays;
        }

        public void setMaxDays(int maxDays) {
            this.maxDays = maxDays;
        }

        public int getBufferDays() {
            return bufferDays;
        }

        public void setBufferDays(int bufferDays) {
            this.bufferDays = bufferDays;
        }

        public int getDefaultDays() {
            return defaultDays;
        }

        public void setDefaultDays(int defaultDays) {
            this.defaultDays = defaultDays;
        }

        public double getTightThreshold() {
            return tightThreshold;
        }

        public void setTightThreshold(double tightThreshold) {
            this.tightThreshold = tightThreshold;
        }

        public double getEmergencyThreshold() {
            return emergencyThreshold;
        }

        public void setEmergencyThreshold(double emergencyThreshold) {
            this.emergencyThreshold = emergencyThreshold;
        }

        public double getEwmaAlpha() {
            return ewmaAlpha;
        }

        public void setEwmaAlpha(double ewmaAlpha) {
            this.ewmaAlpha = ewmaAlpha;
        }

        public int getStatWindowDays() {
            return statWindowDays;
        }

        public void setStatWindowDays(int statWindowDays) {
            this.statWindowDays = statWindowDays;
        }
    }

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

    public Cooldown getCooldown() {
        return cooldown;
    }

    public void setCooldown(Cooldown cooldown) {
        this.cooldown = cooldown;
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

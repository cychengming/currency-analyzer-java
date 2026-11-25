package com.currencyanalyzer.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.ai")
public class AiAssistantProperties {

    private String provider = "deepseek";
    private String model = "deepseek-flash";
    private int maxInputChars = 8000;
    private int maxConcurrentPerUser = 2;
    private int maxConcurrentGlobal = 32;
    private Knowledge knowledge = new Knowledge();
    private Usage usage = new Usage();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null || provider.isBlank() ? "deepseek" : provider.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model.trim();
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = Math.max(1, maxInputChars);
    }

    public int getMaxConcurrentPerUser() {
        return maxConcurrentPerUser;
    }

    public void setMaxConcurrentPerUser(int maxConcurrentPerUser) {
        this.maxConcurrentPerUser = Math.max(1, maxConcurrentPerUser);
    }

    public int getMaxConcurrentGlobal() {
        return maxConcurrentGlobal;
    }

    public void setMaxConcurrentGlobal(int maxConcurrentGlobal) {
        this.maxConcurrentGlobal = Math.max(1, maxConcurrentGlobal);
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public void setKnowledge(Knowledge knowledge) {
        this.knowledge = knowledge == null ? new Knowledge() : knowledge;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage == null ? new Usage() : usage;
    }

    public static class Knowledge {
        private boolean enabled = true;
        private int maxNotes = 4;
        private int maxChars = 4000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxNotes() {
            return maxNotes;
        }

        public void setMaxNotes(int maxNotes) {
            this.maxNotes = Math.max(1, maxNotes);
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = Math.max(80, maxChars);
        }
    }

    public static class Usage {
        private int dailyRequestLimit;
        private int dailyTokenLimit;
        private BigDecimal dailyUserCostLimitUsd = BigDecimal.ZERO;
        private BigDecimal dailyGlobalCostLimitUsd = BigDecimal.ZERO;
        private BigDecimal inputCostPerMillionUsd = new BigDecimal("0.27");
        private BigDecimal outputCostPerMillionUsd = new BigDecimal("1.10");

        public int getDailyRequestLimit() {
            return dailyRequestLimit;
        }

        public void setDailyRequestLimit(int dailyRequestLimit) {
            this.dailyRequestLimit = Math.max(0, dailyRequestLimit);
        }

        public int getDailyTokenLimit() {
            return dailyTokenLimit;
        }

        public void setDailyTokenLimit(int dailyTokenLimit) {
            this.dailyTokenLimit = Math.max(0, dailyTokenLimit);
        }

        public BigDecimal getDailyUserCostLimitUsd() {
            return dailyUserCostLimitUsd;
        }

        public void setDailyUserCostLimitUsd(BigDecimal dailyUserCostLimitUsd) {
            this.dailyUserCostLimitUsd = nonNegative(dailyUserCostLimitUsd);
        }

        public BigDecimal getDailyGlobalCostLimitUsd() {
            return dailyGlobalCostLimitUsd;
        }

        public void setDailyGlobalCostLimitUsd(BigDecimal dailyGlobalCostLimitUsd) {
            this.dailyGlobalCostLimitUsd = nonNegative(dailyGlobalCostLimitUsd);
        }

        public BigDecimal getInputCostPerMillionUsd() {
            return inputCostPerMillionUsd;
        }

        public void setInputCostPerMillionUsd(BigDecimal inputCostPerMillionUsd) {
            this.inputCostPerMillionUsd = nonNegative(inputCostPerMillionUsd);
        }

        public BigDecimal getOutputCostPerMillionUsd() {
            return outputCostPerMillionUsd;
        }

        public void setOutputCostPerMillionUsd(BigDecimal outputCostPerMillionUsd) {
            this.outputCostPerMillionUsd = nonNegative(outputCostPerMillionUsd);
        }

        private static BigDecimal nonNegative(BigDecimal value) {
            if (value == null || value.signum() < 0) {
                return BigDecimal.ZERO;
            }
            return value;
        }
    }
}

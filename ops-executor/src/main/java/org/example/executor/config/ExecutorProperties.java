package org.example.executor.config;

import org.example.ops.contract.ActionType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "aiops.executor")
public class ExecutorProperties {

    private String keyId = "local-control-plane";
    private String sharedSecret;
    private long maxClockSkewSeconds = 60;
    private Map<String, Target> targets = new LinkedHashMap<>();

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public long getMaxClockSkewSeconds() {
        return maxClockSkewSeconds;
    }

    public void setMaxClockSkewSeconds(long maxClockSkewSeconds) {
        this.maxClockSkewSeconds = maxClockSkewSeconds;
    }

    public Map<String, Target> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, Target> targets) {
        this.targets = targets;
    }

    public static class Target {
        private String containerName;
        private String stableImage;
        private String healthUrl;
        private Set<ActionType> allowedActions = Set.of();

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public String getStableImage() {
            return stableImage;
        }

        public void setStableImage(String stableImage) {
            this.stableImage = stableImage;
        }

        public String getHealthUrl() {
            return healthUrl;
        }

        public void setHealthUrl(String healthUrl) {
            this.healthUrl = healthUrl;
        }

        public Set<ActionType> getAllowedActions() {
            return allowedActions;
        }

        public void setAllowedActions(Set<ActionType> allowedActions) {
            this.allowedActions = allowedActions;
        }
    }
}

package org.example.executor.security;

import org.example.executor.config.ExecutorProperties;
import org.example.executor.core.ExecutorActionJournal;
import org.example.ops.contract.ActionRequest;
import org.example.ops.contract.HmacRequestSigner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
@Component
public class ExecutorRequestAuthenticator {

    private final ExecutorProperties properties;
    private final ExecutorActionJournal journal;

    public ExecutorRequestAuthenticator(
            ExecutorProperties properties,
            ExecutorActionJournal journal) {
        this.properties = properties;
        this.journal = journal;
    }

    public boolean authenticate(ActionRequest request) {
        Instant now = Instant.now();
        Duration skew = Duration.between(request.timestamp(), now).abs();
        if (properties.getSharedSecret() == null
                || properties.getSharedSecret().length() < 32
                || !properties.getKeyId().equals(request.keyId())
                || skew.toSeconds() > properties.getMaxClockSkewSeconds()
                || !HmacRequestSigner.verify(request, properties.getSharedSecret())) {
            return false;
        }
        return journal.claimNonce(
                request.nonce(),
                now.plusSeconds(properties.getMaxClockSkewSeconds() * 2));
    }
}

package org.example.ops.contract;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public final class HmacRequestSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacRequestSigner() {
    }

    public static String sign(ActionRequest request, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical(request).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign executor request", ex);
        }
    }

    public static boolean verify(ActionRequest request, String secret) {
        if (request.signature() == null) {
            return false;
        }
        byte[] expected = sign(request, secret).getBytes(StandardCharsets.UTF_8);
        byte[] actual = request.signature().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String canonical(ActionRequest request) {
        StringBuilder value = new StringBuilder();
        part(value, request.requestId());
        part(value, request.incidentId());
        part(value, request.timestamp().toString());
        part(value, request.nonce());
        part(value, request.keyId());
        part(value, request.action().name());
        part(value, request.target());
        for (Map.Entry<String, String> entry : new TreeMap<>(request.parameters()).entrySet()) {
            part(value, entry.getKey());
            part(value, entry.getValue());
        }
        return value.toString();
    }

    private static void part(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('\n');
    }
}

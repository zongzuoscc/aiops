package org.example.security;

/**
 * 请求上下文，用于在拦截器和服务层透传鉴权身份。
 */
public final class AuthContext {

    private static final ThreadLocal<String> CURRENT_OWNER_ID = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void setCurrentOwnerId(String ownerId) {
        CURRENT_OWNER_ID.set(ownerId);
    }

    public static String getCurrentOwnerId() {
        String ownerId = CURRENT_OWNER_ID.get();
        return ownerId == null || ownerId.isBlank() ? "anonymous" : ownerId;
    }

    public static void clear() {
        CURRENT_OWNER_ID.remove();
    }
}

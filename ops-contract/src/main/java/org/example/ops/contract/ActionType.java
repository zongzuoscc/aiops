package org.example.ops.contract;

public enum ActionType {
    GET_CONTAINER_STATE(false),
    GET_CONTAINER_LOGS(false),
    GET_CONTAINER_STATS(false),
    CHECK_HTTP_HEALTH(false),
    RESTART_CONTAINER(true),
    RECREATE_WITH_STABLE_IMAGE(true),
    RESTORE_SNAPSHOT(true),
    DISCARD_SNAPSHOT(true);

    private final boolean write;

    ActionType(boolean write) {
        this.write = write;
    }

    public boolean isWrite() {
        return write;
    }
}

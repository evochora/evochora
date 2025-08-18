package org.evochora.server.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UserLoadRegistry {
    private static final Map<String, int[]> programIdToStartPos = new ConcurrentHashMap<>();

    private UserLoadRegistry() {}

    public static void registerDesiredStart(String programId, int[] startPos) {
        if (programId != null && startPos != null) {
            programIdToStartPos.put(programId, startPos.clone());
        }
    }

    public static int[] getDesiredStart(String programId) {
        int[] pos = programIdToStartPos.get(programId);
        return pos == null ? null : pos.clone();
    }

    public static void clearAll() {
        programIdToStartPos.clear();
    }
}



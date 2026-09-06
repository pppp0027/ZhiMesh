package com.pppp.zhimesh.common.util;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Public avatar paths bundled with user-web.
 */
public final class UserAvatarPool {

    public static final int AVATAR_COUNT = 13;
    private static final String AVATAR_PATH_TEMPLATE = "/avatars/users/avatar-%02d.png";
    private static final List<String> AVATARS = IntStream.rangeClosed(1, AVATAR_COUNT)
            .mapToObj(index -> AVATAR_PATH_TEMPLATE.formatted(index))
            .toList();

    private UserAvatarPool() {
    }

    public static String randomAvatar() {
        return AVATARS.get(ThreadLocalRandom.current().nextInt(AVATARS.size()));
    }

    public static String avatarForSeed(String seed) {
        String effectiveSeed = seed == null || seed.isBlank() ? "zhimesh" : seed;
        return AVATARS.get(Math.floorMod(effectiveSeed.hashCode(), AVATARS.size()));
    }

    static List<String> avatars() {
        return AVATARS;
    }
}

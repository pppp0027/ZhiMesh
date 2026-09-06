package com.pppp.zhimesh.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAvatarPoolTest {

    @Test
    void exposesEveryBundledAvatarPath() {
        assertEquals(UserAvatarPool.AVATAR_COUNT, new HashSet<>(UserAvatarPool.avatars()).size());
        assertEquals("/avatars/users/avatar-01.png", UserAvatarPool.avatars().get(0));
        assertEquals("/avatars/users/avatar-13.png", UserAvatarPool.avatars().get(UserAvatarPool.AVATAR_COUNT - 1));
    }

    @Test
    void randomAvatarAlwaysComesFromPool() {
        for (int i = 0; i < 100; i++) {
            assertTrue(UserAvatarPool.avatars().contains(UserAvatarPool.randomAvatar()));
        }
    }

    @Test
    void seededAvatarIsStableAndComesFromPool() {
        String first = UserAvatarPool.avatarForSeed("user-uuid-123");
        assertEquals(first, UserAvatarPool.avatarForSeed("user-uuid-123"));
        assertTrue(UserAvatarPool.avatars().contains(first));
    }
}

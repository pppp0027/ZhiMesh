package com.pppp.zhimesh.common.service;

import com.pppp.zhimesh.common.dto.LoginReq;
import com.pppp.zhimesh.common.dto.LoginResp;
import com.pppp.zhimesh.common.entity.User;
import com.pppp.zhimesh.common.enums.UserStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceAvatarPersistenceTest {

    private static final String EMAIL = "avatar-test@zhimesh.test";
    private static final String PASSWORD = "avatar-password";
    private static final String AVATAR = "/avatars/users/avatar-07.png";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private User persistedUser;
    private final UserService userService = new UserService() {
        @Override
        public User getByEmail(String email) {
            assertThat(email).isEqualTo(EMAIL);
            return persistedUser;
        }
    };

    @BeforeEach
    void setUp() {
        persistedUser = persistedUser();
        ReflectionTestUtils.setField(userService, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void repeatedLoginReturnsThePersistedAvatar() {
        LoginReq request = loginRequest();

        LoginResp firstLogin = userService.login(request);
        LoginResp secondLogin = userService.login(request);

        assertThat(firstLogin.getAvatar()).isEqualTo(AVATAR);
        assertThat(secondLogin.getAvatar()).isEqualTo(AVATAR);
        assertThat(secondLogin.getAvatar()).isEqualTo(firstLogin.getAvatar());
        assertThat(persistedUser.getAvatar()).isEqualTo(AVATAR);
    }

    private LoginReq loginRequest() {
        LoginReq request = new LoginReq();
        request.setEmail(EMAIL);
        request.setPassword(PASSWORD);
        return request;
    }

    private User persistedUser() {
        User user = new User();
        user.setId(1L);
        user.setUuid("avataruser00000000000000000000001");
        user.setName("avatar-test");
        user.setEmail(EMAIL);
        user.setPassword(BCrypt.hashpw(PASSWORD, BCrypt.gensalt()));
        user.setAvatar(AVATAR);
        user.setUserStatus(UserStatusEnum.NORMAL);
        user.setQuotaByTokenDaily(1);
        user.setQuotaByTokenMonthly(1);
        user.setQuotaByRequestDaily(1);
        user.setQuotaByRequestMonthly(1);
        user.setQuotaByImageDaily(1);
        user.setQuotaByImageMonthly(1);
        return user;
    }
}

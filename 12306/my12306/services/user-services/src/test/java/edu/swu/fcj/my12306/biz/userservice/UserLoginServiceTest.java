package edu.swu.fcj.my12306.biz.userservice;

import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserLoginReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserLoginRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.UserLoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录/注册服务逻辑测试（Mock Redis + Redisson）：
 * 覆盖身份识别、BCrypt、JWT、错误分支，以及 hasUsername 的布隆+复用集合判定
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
class UserLoginServiceTest {

    @Autowired
    private UserLoginService userLoginService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @MockBean
    private RBloomFilter<String> userRegisterCachePenetrationBloomFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String prefix = "loginmock_" + System.currentTimeMillis();
    /** 测试产生的用户名：清理时按分片键等值删除（单分片路由，避免全路由扫所有分片表） */
    private final List<String> registeredNames = new ArrayList<>();

    @BeforeEach
    void stubDeps() {
        // Redis：value 读写（登录缓存）+ set 操作（注册 remove/复用集合判断）
        when(stringRedisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(stringRedisTemplate.opsForSet()).thenReturn(mock(SetOperations.class));
        // Redisson：分布式锁 mock；布隆过滤器默认 contains=false（可用）
        RLock lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @AfterEach
    void cleanUp() {
        registeredNames.forEach(name -> {
            jdbcTemplate.update("DELETE FROM t_user WHERE username = ?", name);
            jdbcTemplate.update("DELETE FROM t_user_phone WHERE username = ?", name);
            jdbcTemplate.update("DELETE FROM t_user_mail WHERE username = ?", name);
            jdbcTemplate.update("DELETE FROM t_user_reuse WHERE username = ?", name);
        });
    }

    private void register(String username, String phone, String mail) {
        UserRegisterReqDTO dto = new UserRegisterReqDTO();
        dto.setUsername(username);
        dto.setPassword("123456");
        dto.setRealName("登录逻辑测试");
        dto.setIdType(1);
        dto.setIdCard("110101199001011234");
        dto.setPhone(phone);
        dto.setMail(mail);
        userLoginService.register(dto);
        registeredNames.add(username);
    }

    private UserLoginRespDTO login(String account, String password) {
        return userLoginService.login(new UserLoginReqDTO(account, password));
    }

    @Test
    void hasUsername_usesBloomAndReuseSet() {
        String name = prefix + "_z";
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);

        // 布隆不含 → 一定没注册过 → 可用
        when(userRegisterCachePenetrationBloomFilter.contains(name)).thenReturn(false);
        assertTrue(userLoginService.hasUsername(name));

        // 布隆含 + 复用集合含 → 已注销可复用 → 可用
        when(userRegisterCachePenetrationBloomFilter.contains(name)).thenReturn(true);
        when(setOps.isMember(anyString(), anyString())).thenReturn(true);
        assertTrue(userLoginService.hasUsername(name));

        // 布隆含 + 复用集合不含 → 在用 → 不可用
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);
        assertFalse(userLoginService.hasUsername(name));
    }

    @Test
    void login_byUsername_success() {
        String username = prefix + "_a";
        register(username, "13900000021", username + "@test.com");

        UserLoginRespDTO resp = login(username, "123456");
        assertEquals(username, resp.getUsername());
        assertEquals("登录逻辑测试", resp.getRealName());
        assertNotNull(resp.getUserId());
        assertNotNull(resp.getAccessToken());
        assertTrue(resp.getAccessToken().startsWith("Bearer "));
        assertEquals(username, edu.swu.fcj.my12306.biz.userservice.common.JwtUtil
                .parseJwtToken(resp.getAccessToken()).getUsername());
    }

    @Test
    void login_byPhoneAndMail_success() {
        String username = prefix + "_b";
        register(username, "13900000022", username + "@test.com");

        assertEquals(username, login("13900000022", "123456").getUsername());
        assertEquals(username, login(username + "@test.com", "123456").getUsername());
    }

    @Test
    void login_wrongPassword() {
        String username = prefix + "_c";
        register(username, "13900000023", username + "@test.com");

        ServiceException ex = assertThrows(ServiceException.class, () -> login(username, "wrong"));
        assertTrue(ex.getMessage().contains("账号不存在或密码错误"));
    }

    @Test
    void login_userNotExist() {
        ServiceException ex = assertThrows(ServiceException.class, () -> login(prefix + "_nobody", "123456"));
        assertTrue(ex.getMessage().contains("账号不存在或密码错误"));
    }

    @Test
    void login_mailNotExist() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> login("nobody_" + prefix + "@test.com", "123456"));
        assertTrue(ex.getMessage().contains("用户名/手机号/邮箱不存在"));
    }
}

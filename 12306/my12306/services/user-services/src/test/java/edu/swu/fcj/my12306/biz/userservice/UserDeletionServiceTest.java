package edu.swu.fcj.my12306.biz.userservice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.userservice.common.UserContext;
import edu.swu.fcj.my12306.biz.userservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserReuseDO;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserReuseMapper;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserDeletionReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 注销服务冒烟测试（Mock Redis + Redisson）：
 * 预置 UserContext 当前用户 → deletion 主链路（登记 + 三表置位 + 写复用记录）
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
class UserDeletionServiceTest {

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

    @Autowired
    private UserReuseMapper userReuseMapper;

    private final String prefix = "deletion_" + System.currentTimeMillis();
    private final String username = prefix + "_smoke";
    private final String TOKEN = "Bearer test-token";

    @BeforeEach
    void stubDeps() {
        when(stringRedisTemplate.opsForSet()).thenReturn(mock(SetOperations.class));
        RLock lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @AfterEach
    void cleanUp() {
        UserContext.removeUser();
        // 按分片键等值删除（单分片路由）
        jdbcTemplate.update("DELETE FROM t_user WHERE username = ?", username);
        jdbcTemplate.update("DELETE FROM t_user_phone WHERE username = ?", username);
        jdbcTemplate.update("DELETE FROM t_user_mail WHERE username = ?", username);
        jdbcTemplate.update("DELETE FROM t_user_reuse WHERE username = ?", username);
        jdbcTemplate.update("DELETE FROM t_user_deletion WHERE id_card = '110101199001011234'");
    }

    private void register(String name) {
        UserRegisterReqDTO dto = new UserRegisterReqDTO();
        dto.setUsername(name);
        dto.setPassword("123456");
        dto.setRealName("注销冒烟");
        dto.setIdType(1);
        dto.setIdCard("110101199001011234");
        dto.setPhone("13900000031");
        dto.setMail(name + "@test.com");
        userLoginService.register(dto);
    }

    @Test
    void deletion_success() {
        register(username);
        // v2：注销从 UserContext 取当前登录用户（真实环境由过滤器写入，测试手动预置）
        UserContext.setUser(UserInfoDTO.builder()
                .userId("1")
                .username(username)
                .realName("注销冒烟")
                .token(TOKEN)
                .build());

        UserDeletionReqDTO dto = new UserDeletionReqDTO();
        dto.setUsername(username);
        userLoginService.deletion(dto);

        // 主表已置位：del_flag=1、deletion_time>0（逻辑删除查询会过滤，故 SQL 直查）
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT del_flag, deletion_time FROM t_user WHERE username = ?", username);
        Object delFlag = row.get("del_flag");
        assertTrue(Boolean.TRUE.equals(delFlag) || "1".equals(String.valueOf(delFlag)));
        assertTrue(((Number) row.get("deletion_time")).longValue() > 0);

        // 用户名复用记录已写入（DB 落库，为 v2 复用集合的可靠数据源）
        Long reuseCount = userReuseMapper.selectCount(Wrappers.lambdaQuery(UserReuseDO.class)
                .eq(UserReuseDO::getUsername, username));
        assertTrue(reuseCount > 0);
    }
}

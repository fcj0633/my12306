package edu.swu.fcj.my12306.biz.userservice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserMailDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserPhoneDO;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserMailMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserPhoneMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册接口集成测试（MOCK 环境，不启动真实 Tomcat，连本地 12306_user 库）
 * <p>
 * v2 注册依赖真实 Redis/Redisson（布隆过滤器 + 分布式锁 + 复用集合），
 * 需通过系统属性开关显式启用：mvn test -Dredis.it=true
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "redis.it", matches = "true")
class UserRegisterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserPhoneMapper userPhoneMapper;

    @Autowired
    private UserMailMapper userMailMapper;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试数据统一前缀，避免与真实数据冲突，也便于 @AfterEach 清理 */
    private final String prefix = "test_" + System.currentTimeMillis();

    private String buildBody(String username, String phone, String mail) {
        return "{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"测试用户\",\"idType\":1,"
                + "\"idCard\":\"110101199001011234\",\"phone\":\"" + phone + "\",\"mail\":\"" + mail + "\"}";
    }

    @AfterEach
    void cleanUp() {
        // 必须物理删除测试数据：逻辑删除（del_flag=1）不会释放联合唯一索引
        // (phone, deletion_time) / (mail, deletion_time)，下次运行同手机号/邮箱注册会撞索引失败
        jdbcTemplate.update("DELETE FROM t_user WHERE username LIKE ?", prefix + "%");
        jdbcTemplate.update("DELETE FROM t_user_phone WHERE username LIKE ?", prefix + "%");
        jdbcTemplate.update("DELETE FROM t_user_mail WHERE username LIKE ?", prefix + "%");
    }

    @Test
    void register_success() throws Exception {
        String username = prefix + "_a";
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(username, "13900000001", username + "@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.username").value(username));

        // 校验落库结果：主表 + 两个映射表各一条、del_flag=0、密码为 BCrypt 密文
        UserDO user = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username));
        assertNotNull(user);
        assertEquals(0, user.getDelFlag());
        assertNotEquals("123456", user.getPassword());
        assertTrue(passwordEncoder.matches("123456", user.getPassword()));
        assertNotNull(userPhoneMapper.selectOne(
                Wrappers.lambdaQuery(UserPhoneDO.class).eq(UserPhoneDO::getUsername, username)));
        assertNotNull(userMailMapper.selectOne(
                Wrappers.lambdaQuery(UserMailDO.class).eq(UserMailDO::getUsername, username)));
    }

    @Test
    void register_duplicateUsername() throws Exception {
        String username = prefix + "_b";
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(username, "13900000002", username + "@test.com")))
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(username, "13900000003", "other@test.com")))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void register_duplicatePhone() throws Exception {
        String username1 = prefix + "_c1";
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(username1, "13900000004", username1 + "@test.com")))
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(prefix + "_c2", "13900000004", prefix + "c2@test.com")))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("手机号已注册"));
    }

    @Test
    void register_duplicateMail() throws Exception {
        String username1 = prefix + "_d1";
        String mail = prefix + "_dup@test.com";
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(username1, "13900000005", mail)))
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildBody(prefix + "_d2", "13900000006", mail)))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("邮箱已注册"));
    }

    @Test
    void register_missingUsername() throws Exception {
        String body = "{\"password\":\"123456\",\"realName\":\"测试用户\",\"idType\":1,"
                + "\"idCard\":\"110101199001011234\",\"phone\":\"13900000007\",\"mail\":\"x@test.com\"}";
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void register_badJson() throws Exception {
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }
}

package edu.swu.fcj.my12306.biz.userservice;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口集成测试（MOCK 环境，连本地 MySQL + 远程 Redis）
 * <p>
 * 注意：依赖真实 Redis（Lettuce/Netty），在某些受限环境（如沙箱）Netty 无法创建事件循环，
 * 需通过系统属性开关显式启用：mvn test -Dredis.it=true
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "redis.it", matches = "true")
class UserLoginTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserPhoneMapper userPhoneMapper;

    @Autowired
    private UserMailMapper userMailMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试数据统一前缀 */
    private final String prefix = "login_" + System.currentTimeMillis();

    /** 记录测试产生的 token，@AfterEach 清理 Redis */
    private final List<String> tokens = new ArrayList<>();

    private String registerBody(String username, String phone, String mail) {
        return "{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"登录测试\",\"idType\":1,"
                + "\"idCard\":\"110101199001011234\",\"phone\":\"" + phone + "\",\"mail\":\"" + mail + "\"}";
    }

    private void register(String username, String phone, String mail) throws Exception {
        mockMvc.perform(post("/api/user-service/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, phone, mail)))
                .andExpect(jsonPath("$.code").value("0"));
    }

    private String loginAndGetToken(String account, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"" + account + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
        tokens.add(token);
        return token;
    }

    @AfterEach
    void cleanUp() {
        // 清理 Redis 登录态
        tokens.forEach(stringRedisTemplate::delete);
        // 物理删除测试数据：逻辑删除不释放联合唯一索引，会导致下次运行注册撞索引失败
        jdbcTemplate.update("DELETE FROM t_user WHERE username LIKE ?", prefix + "%");
        jdbcTemplate.update("DELETE FROM t_user_phone WHERE username LIKE ?", prefix + "%");
        jdbcTemplate.update("DELETE FROM t_user_mail WHERE username LIKE ?", prefix + "%");
    }

    @Test
    void login_byUsername_success() throws Exception {
        String username = prefix + "_a";
        register(username, "13900000011", username + "@test.com");

        MvcResult result = mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();

        // token 已写入 Redis（登录态生效）
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
        tokens.add(token);
        assertNotNull(stringRedisTemplate.opsForValue().get(token));
    }

    @Test
    void login_byPhoneAndMail_success() throws Exception {
        String username = prefix + "_b";
        register(username, "13900000012", username + "@test.com");

        // 手机号登录
        loginAndGetToken("13900000012", "123456");
        // 邮箱登录
        loginAndGetToken(username + "@test.com", "123456");
        assertTrue(true);
    }

    @Test
    void login_wrongPassword() throws Exception {
        String username = prefix + "_c";
        register(username, "13900000013", username + "@test.com");

        mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"" + username + "\",\"password\":\"wrong\"}"))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("账号不存在或密码错误"));
    }

    @Test
    void login_userNotExist() throws Exception {
        mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"" + prefix + "_nobody\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("账号不存在或密码错误"));
    }

    @Test
    void login_mailNotExist() throws Exception {
        mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"nobody_" + prefix + "@test.com\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("用户名/手机号/邮箱不存在"));
    }

    @Test
    void login_deletedUser_shouldFail() throws Exception {
        String username = prefix + "_d";
        register(username, "13900000014", username + "@test.com");
        // 模拟注销：逻辑删除该用户（UPDATE del_flag=1）
        userMapper.delete(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username));

        mockMvc.perform(post("/api/user-service/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usernameOrMailOrPhone\":\"" + username + "\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("账号不存在或密码错误"));
    }

    @Test
    void checkLogin_afterLogin() throws Exception {
        String username = prefix + "_e";
        register(username, "13900000015", username + "@test.com");
        String token = loginAndGetToken(username, "123456");

        mockMvc.perform(get("/api/user-service/check-login").param("accessToken", token))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void logout_thenCheckLoginNull() throws Exception {
        String username = prefix + "_f";
        register(username, "13900000016", username + "@test.com");
        String token = loginAndGetToken(username, "123456");

        mockMvc.perform(get("/api/user-service/logout").param("accessToken", token))
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/user-service/check-login").param("accessToken", token))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }
}

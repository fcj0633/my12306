package edu.swu.fcj.my12306.biz.userservice;

import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.common.UserContext;
import edu.swu.fcj.my12306.biz.userservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerRemoveReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.PassengerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 乘车人服务冒烟测试（Mock Redis/Redisson，连真实分片库）：
 * 新增/查重/列表脱敏/内部明文查询/修改/移除
 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
class PassengerServiceTest {

    @Autowired
    private PassengerService passengerService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @MockBean
    private RBloomFilter<String> userRegisterCachePenetrationBloomFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String prefix = "passenger_" + System.currentTimeMillis();
    private String username;

    @BeforeEach
    void setUp() {
        username = prefix + "_u";
        // 列表缓存 no-op；写锁永远拿得到
        when(stringRedisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        RLock lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        // 预置当前登录用户（真实环境由 UserTransmitFilter 写入）
        UserContext.setUser(UserInfoDTO.builder().userId("1").username(username).realName("测试用户").build());
    }

    @AfterEach
    void cleanUp() {
        UserContext.removeUser();
        // username 即分片键：按用户名精确删除，单分片路由
        if (username != null) {
            jdbcTemplate.update("DELETE FROM t_passenger WHERE username = ?", username);
        }
    }

    private PassengerReqDTO buildReq(String phone, String idCard) {
        PassengerReqDTO req = new PassengerReqDTO();
        req.setRealName("张三");
        req.setIdType(1);
        req.setIdCard(idCard);
        req.setPhone(phone);
        return req;
    }

    @Test
    void save_duplicate_listMasked_andInnerActual() {
        String idCard = "110101199001011237";
        passengerService.savePassenger(buildReq("13900009911", idCard));

        // 对外列表：证件/手机号已脱敏
        List<PassengerRespDTO> list = passengerService.listPassengerQueryByUsername(username);
        assertEquals(1, list.size());
        PassengerRespDTO resp = list.get(0);
        assertEquals("张三", resp.getRealName());
        assertEquals(Integer.valueOf(1), resp.getVerifyStatus());
        assertTrue(resp.getIdCard().startsWith("1101") && resp.getIdCard().endsWith("1237"));
        assertTrue(resp.getIdCard().contains("****"));
        assertEquals("139****9911", resp.getPhone());

        // 内部接口：按 ID 返回明文
        List<PassengerActualRespDTO> actualList = passengerService.listPassengerQueryByIds(
                username, Collections.singletonList(Long.parseLong(resp.getId())));
        assertEquals(1, actualList.size());
        assertEquals(idCard, actualList.get(0).getIdCard());
        assertEquals("13900009911", actualList.get(0).getPhone());

        // 同用户名同证件号二次新增被拒
        ServiceException ex = assertThrows(ServiceException.class,
                () -> passengerService.savePassenger(buildReq("13900009912", idCard)));
        assertEquals("乘车人已存在", ex.getMessage());
    }

    @Test
    void update_success_and_notFound() {
        passengerService.savePassenger(buildReq("13900009922", "110101199001011237"));
        List<PassengerRespDTO> list = passengerService.listPassengerQueryByUsername(username);
        String id = list.get(0).getId();

        // 修改手机号成功
        PassengerReqDTO update = buildReq("13900009933", "110101199001011237");
        update.setId(id);
        passengerService.updatePassenger(update);
        List<PassengerRespDTO> afterUpdate = passengerService.listPassengerQueryByUsername(username);
        assertEquals("139****9933", afterUpdate.get(0).getPhone());

        // 修改不存在的乘车人
        PassengerReqDTO notFound = buildReq("13900009944", "110101199001011237");
        notFound.setId("9999999999999999999");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> passengerService.updatePassenger(notFound));
        assertEquals("乘车人数据不存在", ex.getMessage());
    }

    @Test
    void remove_makesPassengerInvisible() {
        passengerService.savePassenger(buildReq("13900009955", "110101199001011237"));
        List<PassengerRespDTO> list = passengerService.listPassengerQueryByUsername(username);
        assertEquals(1, list.size());

        PassengerRemoveReqDTO remove = new PassengerRemoveReqDTO();
        remove.setId(list.get(0).getId());
        passengerService.removePassenger(remove);

        assertTrue(passengerService.listPassengerQueryByUsername(username).isEmpty());
        // 移除不存在的乘车人
        PassengerRemoveReqDTO notFound = new PassengerRemoveReqDTO();
        notFound.setId("9999999999999999999");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> passengerService.removePassenger(notFound));
        assertEquals("乘车人数据不存在", ex.getMessage());
    }
}

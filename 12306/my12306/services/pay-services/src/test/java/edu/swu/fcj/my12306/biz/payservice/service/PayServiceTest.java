package edu.swu.fcj.my12306.biz.payservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.Result;
import edu.swu.fcj.my12306.biz.payservice.common.Results;
import edu.swu.fcj.my12306.biz.payservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.payservice.common.UserContext;
import edu.swu.fcj.my12306.biz.payservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayNotifyStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayMapper;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCreateReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayInfoRespDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayRespDTO;
import edu.swu.fcj.my12306.biz.payservice.remote.OrderRemoteService;
import edu.swu.fcj.my12306.biz.payservice.remote.TicketRemoteService;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.OrderPayCallbackRemoteReqDTO;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketPayCallbackRemoteReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 支付服务测试：真实 MySQL（12306_pay）+ Mock Redis/Feign，关闭 Nacos 与补偿定时任务
 * <p>
 * 订单与票务都是"被调用的下游"，所以这里把它们 Mock 掉，
 * 只验证支付服务自己该做的事：金额怎么算、状态怎么推、通知结果怎么记录。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
                "my12306.pay.notify-compensate-enabled=false"})
@Transactional
class PayServiceTest {

    private static final Long TEST_USER_ID = 1001L;

    private static final String TEST_USERNAME = "pay-test-user";

    @Autowired
    private PayService payService;

    @Autowired
    private PayMapper payMapper;

    @MockBean
    private OrderRemoteService orderRemoteService;

    @MockBean
    private TicketRemoteService ticketRemoteService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 支付服务本身不用 Redisson，但 starter 会自动装配一个需要真实连接的 RedissonClient；
     * Mock 掉它，测试才能真正做到"不依赖 Redis"。
     */
    @MockBean
    private RedissonClient redissonClient;

    private String orderSn;

    @BeforeEach
    void setUp() {
        UserContext.setUser(UserInfoDTO.builder().userId(String.valueOf(TEST_USER_ID)).username(TEST_USERNAME).build());
        orderSn = "P2-PAY-TEST-" + System.nanoTime();
        // 默认：订单存在、属于当前用户、待支付，明细两条各 53300 分
        stubOrder(0, 53300, 53300);
        // 默认：下游通知成功
        when(orderRemoteService.payCallbackOrder(any(OrderPayCallbackRemoteReqDTO.class))).thenReturn(Results.success(true));
        when(ticketRemoteService.payCallback(any(TicketPayCallbackRemoteReqDTO.class))).thenReturn(Results.success(true));
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void createPay_amountIsSummedFromOrderDetails() {
        PayRespDTO response = payService.createPay(buildCreateRequest());

        assertEquals(106600, response.getTotalAmount());
        assertEquals(PayStatusEnum.WAIT_PAY.getCode(), response.getStatus());
        assertTrue(response.getPayUrl().contains(response.getPaySn()));
    }

    @Test
    void createPay_sameOrderReusesExistingPayOrder() {
        PayRespDTO first = payService.createPay(buildCreateRequest());
        PayRespDTO second = payService.createPay(buildCreateRequest());

        assertEquals(first.getPaySn(), second.getPaySn());
        assertEquals(1, countPayOrders());
    }

    @Test
    void createPay_cancelledOrderIsRejected() {
        stubOrder(30, 53300);

        ServiceException exception = assertThrows(ServiceException.class, () -> payService.createPay(buildCreateRequest()));

        assertEquals("订单已取消，无法支付", exception.getMessage());
    }

    @Test
    void payCallback_amountMismatchIsRejected() {
        PayRespDTO pay = payService.createPay(buildCreateRequest());
        PayCallbackReqDTO requestParam = buildCallbackRequest(pay.getPaySn(), 1);

        ServiceException exception = assertThrows(ServiceException.class, () -> payService.payCallback(requestParam));

        assertEquals("支付金额与应付金额不一致", exception.getMessage());
        assertEquals(PayStatusEnum.WAIT_PAY.getCode(), currentPayStatus(pay.getPaySn()));
    }

    @Test
    void payCallback_successNotifiesDownstreamAndMarksNotified() {
        PayRespDTO pay = payService.createPay(buildCreateRequest());

        boolean notified = payService.payCallback(buildCallbackRequest(pay.getPaySn(), 106600));

        assertTrue(notified);
        assertEquals(PayStatusEnum.PAID.getCode(), currentPayStatus(pay.getPaySn()));
        assertEquals(PayNotifyStatusEnum.NOTIFIED.getCode(), currentNotifyStatus(pay.getPaySn()));
        PayInfoRespDTO payInfo = payService.getPayInfoByPaySn(pay.getPaySn());
        assertEquals(106600, payInfo.getPayAmount());
    }

    @Test
    void payCallback_downstreamFailureKeepsNotifiedPendingThenCompensationRetries() {
        PayRespDTO pay = payService.createPay(buildCreateRequest());
        // 第一次通知：订单侧返回失败
        when(orderRemoteService.payCallbackOrder(any(OrderPayCallbackRemoteReqDTO.class)))
                .thenReturn(new Result<Boolean>().setCode("500").setMessage("订单服务异常"));

        boolean notified = payService.payCallback(buildCallbackRequest(pay.getPaySn(), 106600));

        // 钱已经收到这个事实不能回滚，只是"还没通知到下游"
        assertTrue(!notified);
        assertEquals(PayStatusEnum.PAID.getCode(), currentPayStatus(pay.getPaySn()));
        assertEquals(PayNotifyStatusEnum.NOT_NOTIFIED.getCode(), currentNotifyStatus(pay.getPaySn()));

        // 补偿任务重推：下游恢复正常后，通知状态应变为已完成
        when(orderRemoteService.payCallbackOrder(any(OrderPayCallbackRemoteReqDTO.class))).thenReturn(Results.success(true));
        assertTrue(payService.notifyPayResult(pay.getPaySn()));
        assertEquals(PayNotifyStatusEnum.NOTIFIED.getCode(), currentNotifyStatus(pay.getPaySn()));
    }

    @Test
    void closePayByOrderSn_isIdempotent() {
        PayRespDTO pay = payService.createPay(buildCreateRequest());

        assertTrue(payService.closePayByOrderSn(orderSn));
        assertEquals(PayStatusEnum.CLOSED.getCode(), currentPayStatus(pay.getPaySn()));
        // 重复关闭：已经没有"待支付"的支付单可关，返回 false 但不报错
        assertTrue(!payService.closePayByOrderSn(orderSn));
    }

    private PayCreateReqDTO buildCreateRequest() {
        PayCreateReqDTO requestParam = new PayCreateReqDTO();
        requestParam.setOrderSn(orderSn);
        requestParam.setChannel("MOCK_PAY");
        requestParam.setTradeType("WEB");
        return requestParam;
    }

    private PayCallbackReqDTO buildCallbackRequest(String paySn, Integer payAmount) {
        PayCallbackReqDTO requestParam = new PayCallbackReqDTO();
        requestParam.setPaySn(paySn);
        requestParam.setPayAmount(payAmount);
        requestParam.setChannel("MOCK_PAY");
        requestParam.setTradeNo("MOCK-TRADE-" + paySn);
        requestParam.setGmtPayment(new Date());
        return requestParam;
    }

    private void stubOrder(Integer status, Integer... amounts) {
        TicketOrderDetailRespDTO detail = new TicketOrderDetailRespDTO();
        detail.setOrderSn(orderSn);
        detail.setUserId(TEST_USER_ID);
        detail.setUsername(TEST_USERNAME);
        detail.setTrainId(1L);
        detail.setTrainNumber("G35");
        detail.setDeparture("北京南");
        detail.setArrival("南京南");
        detail.setStatus(status);
        detail.setPassengerDetails(List.of(buildPassenger(amounts[0]), buildPassenger(amounts.length > 1 ? amounts[1] : amounts[0])));
        when(orderRemoteService.queryTicketOrderByOrderSn(anyString())).thenReturn(Results.success(detail));
    }

    private TicketOrderPassengerDetailRespDTO buildPassenger(Integer amount) {
        TicketOrderPassengerDetailRespDTO passenger = new TicketOrderPassengerDetailRespDTO();
        passenger.setRealName("测试乘车人");
        passenger.setSeatType(2);
        passenger.setCarriageNumber("09");
        passenger.setSeatNumber("03A");
        passenger.setAmount(amount);
        return passenger;
    }

    private long countPayOrders() {
        return payMapper.selectCount(Wrappers.lambdaQuery(PayDO.class).eq(PayDO::getOrderSn, orderSn));
    }

    private Integer currentPayStatus(String paySn) {
        return selectPay(paySn).getStatus();
    }

    private Integer currentNotifyStatus(String paySn) {
        return selectPay(paySn).getNotifyStatus();
    }

    private PayDO selectPay(String paySn) {
        return payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class).eq(PayDO::getPaySn, paySn));
    }
}

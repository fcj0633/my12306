package edu.swu.fcj.my12306.biz.orderservice.service;

import edu.swu.fcj.my12306.biz.orderservice.common.Results;
import edu.swu.fcj.my12306.biz.orderservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.orderservice.common.UserContext;
import edu.swu.fcj.my12306.biz.orderservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import edu.swu.fcj.my12306.biz.orderservice.common.enums.OrderStatusEnum;
import edu.swu.fcj.my12306.biz.orderservice.dao.entity.OrderDO;
import edu.swu.fcj.my12306.biz.orderservice.dao.entity.OrderItemDO;
import edu.swu.fcj.my12306.biz.orderservice.dao.mapper.OrderItemMapper;
import edu.swu.fcj.my12306.biz.orderservice.dao.mapper.OrderMapper;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCloseReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderCreateReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderItemCreateReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.req.TicketOrderPayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.remote.PayRemoteService;
import edu.swu.fcj.my12306.biz.orderservice.remote.TicketRemoteService;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderCancelTicketRemoteReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderClosePayRemoteReqDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务测试：真实 MySQL（12306_order）+ Mock Redis/Redisson/Feign，关闭 Nacos 与定时任务
 * <p>
 * 事务注解让每个用例结束后自动回滚，测试之间互不污染。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
                "my12306.order.delay-close-enabled=false", "my12306.order.fallback-scan-enabled=false"})
@Transactional
class OrderServiceTest {

    private static final Long TEST_USER_ID = 1001L;

    private static final String TEST_USERNAME = "order-test-user";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @MockBean
    private PayRemoteService payRemoteService;

    @MockBean
    private TicketRemoteService ticketRemoteService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        UserContext.setUser(UserInfoDTO.builder().userId(String.valueOf(TEST_USER_ID)).username(TEST_USERNAME).build());
        // 下游通知默认成功：失败分支由单独的用例覆盖
        when(payRemoteService.closePay(any(OrderClosePayRemoteReqDTO.class))).thenReturn(Results.success(true));
        when(ticketRemoteService.cancelCallback(any(OrderCancelTicketRemoteReqDTO.class))).thenReturn(Results.success(true));
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void createAndQueryTicketOrder() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());
        assertNotNull(orderSn);

        List<OrderItemDO> orderItems = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn));
        assertEquals(1, orderItems.size());
        assertEquals(OrderItemStatusEnum.PENDING_PAYMENT.getStatus(), orderItems.get(0).getStatus());

        TicketOrderDetailRespDTO detail = orderService.queryTicketOrderByOrderSn(orderSn);
        assertEquals(orderSn, detail.getOrderSn());
        assertEquals(OrderStatusEnum.PENDING_PAYMENT.getStatus(), detail.getStatus());
        assertEquals(1, detail.getPassengerDetails().size());
        assertTrue("测试乘车人".equals(detail.getPassengerDetails().get(0).getRealName()));
    }

    @Test
    void createTicketOrder_sameOrderNumberIsIdempotent() {
        TicketOrderCreateReqDTO request = buildCreateRequest();

        String first = orderService.createTicketOrder(request);
        String second = orderService.createTicketOrder(request);

        assertEquals(first, second);
        assertEquals(1, orderMapper.selectCount(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, first)));
        assertEquals(1, orderItemMapper.selectCount(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, first)));
    }

    @Test
    void createTicketOrder_sameOrderNumberWithDifferentSeatIsRejected() {
        TicketOrderCreateReqDTO original = buildCreateRequest();
        orderService.createTicketOrder(original);

        TicketOrderCreateReqDTO conflicting = buildCreateRequest();
        conflicting.setOrderSn(original.getOrderSn());
        conflicting.getTicketOrderItems().getFirst().setSeatNumber("03B");

        ServiceException exception = assertThrows(ServiceException.class,
                () -> orderService.createTicketOrder(conflicting));
        assertEquals("订单号已被其他购票请求占用", exception.getMessage());
        assertEquals(1, orderItemMapper.selectCount(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, original.getOrderSn())));
    }

    @Test
    void queryOrderStatus_missingOrderReturnsConfirmedAbsence() {
        var status = orderService.queryOrderStatus("ORDER-NOT-EXISTS-" + System.nanoTime());

        assertFalse(status.getExists());
        assertNull(status.getStatus());
    }

    @Test
    void closeTicketOrder_pendingOrderIsClosedAndDownstreamIsNotified() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());

        TicketOrderCloseReqDTO requestParam = new TicketOrderCloseReqDTO();
        requestParam.setOrderSn(orderSn);
        orderService.closeTicketOrder(requestParam);

        assertEquals(OrderStatusEnum.CLOSED.getStatus(), currentOrderStatus(orderSn));
        assertEquals(OrderItemStatusEnum.CLOSED.getStatus(), currentItemStatus(orderSn));
        // 先关订单再通知下游：支付单要作废，座位要回滚
        verify(payRemoteService).closePay(any(OrderClosePayRemoteReqDTO.class));
        verify(ticketRemoteService).cancelCallback(any(OrderCancelTicketRemoteReqDTO.class));
    }

    @Test
    void closeTicketOrder_paidOrderIsRejected() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());
        orderService.payCallbackOrder(buildPayCallbackRequest(orderSn));

        TicketOrderCloseReqDTO requestParam = new TicketOrderCloseReqDTO();
        requestParam.setOrderSn(orderSn);
        ServiceException exception = assertThrows(ServiceException.class,
                () -> orderService.closeTicketOrder(requestParam));

        assertEquals("已支付订单不支持自助取消，请使用退票", exception.getMessage());
        assertEquals(OrderStatusEnum.ALREADY_PAID.getStatus(), currentOrderStatus(orderSn));
        verify(ticketRemoteService, never()).cancelCallback(any(OrderCancelTicketRemoteReqDTO.class));
    }

    @Test
    void closeTimeoutOrder_isIdempotentAndSelfHealing() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());

        orderService.closeTimeoutOrder(orderSn);
        // 模拟"第一次关单成功、但下游通知失败"后的重投：已取消的订单仍要把后两步补齐
        orderService.closeTimeoutOrder(orderSn);

        assertEquals(OrderStatusEnum.CLOSED.getStatus(), currentOrderStatus(orderSn));
        assertEquals(OrderItemStatusEnum.CLOSED.getStatus(), currentItemStatus(orderSn));
        verify(payRemoteService, times(2)).closePay(any(OrderClosePayRemoteReqDTO.class));
        verify(ticketRemoteService, times(2)).cancelCallback(any(OrderCancelTicketRemoteReqDTO.class));
    }

    @Test
    void closeTimeoutOrder_paidOrderIsNeverClosed() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());
        orderService.payCallbackOrder(buildPayCallbackRequest(orderSn));

        orderService.closeTimeoutOrder(orderSn);

        assertEquals(OrderStatusEnum.ALREADY_PAID.getStatus(), currentOrderStatus(orderSn));
        assertEquals(OrderItemStatusEnum.ALREADY_PAID.getStatus(), currentItemStatus(orderSn));
        verify(payRemoteService, never()).closePay(any(OrderClosePayRemoteReqDTO.class));
        verify(ticketRemoteService, never()).cancelCallback(any(OrderCancelTicketRemoteReqDTO.class));
    }

    @Test
    void payCallbackOrder_repeatedCallbackIsIdempotent() {
        String orderSn = orderService.createTicketOrder(buildCreateRequest());

        orderService.payCallbackOrder(buildPayCallbackRequest(orderSn));
        orderService.payCallbackOrder(buildPayCallbackRequest(orderSn));

        assertEquals(OrderStatusEnum.ALREADY_PAID.getStatus(), currentOrderStatus(orderSn));
        assertEquals(OrderItemStatusEnum.ALREADY_PAID.getStatus(), currentItemStatus(orderSn));
        OrderDO orderDO = orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class).eq(OrderDO::getOrderSn, orderSn));
        assertNotNull(orderDO.getPayTime());
    }

    private TicketOrderCreateReqDTO buildCreateRequest() {
        TicketOrderItemCreateReqDTO item = new TicketOrderItemCreateReqDTO();
        item.setAmount(53300);
        item.setCarriageNumber("09");
        item.setSeatNumber("03A");
        item.setRealName("测试乘车人");
        item.setIdType(1);
        item.setIdCard("110101199001011234");
        item.setPhone("13800000000");
        item.setSeatType(2);
        item.setTicketType(0);

        TicketOrderCreateReqDTO requestParam = new TicketOrderCreateReqDTO();
        requestParam.setOrderSn("ORDER-TEST-" + System.nanoTime());
        requestParam.setUserId(TEST_USER_ID);
        requestParam.setUsername(TEST_USERNAME);
        requestParam.setTrainId(1L);
        requestParam.setDeparture("北京南");
        requestParam.setArrival("南京南");
        requestParam.setSource(0);
        requestParam.setOrderTime(new Date());
        requestParam.setRidingDate(new Date());
        requestParam.setTrainNumber("G35");
        requestParam.setDepartureTime(new Date());
        requestParam.setArrivalTime(new Date());
        requestParam.setTicketOrderItems(List.of(item));
        return requestParam;
    }

    private TicketOrderPayCallbackReqDTO buildPayCallbackRequest(String orderSn) {
        TicketOrderPayCallbackReqDTO requestParam = new TicketOrderPayCallbackReqDTO();
        requestParam.setOrderSn(orderSn);
        requestParam.setPaySn("PAY-" + orderSn);
        requestParam.setPayType("MOCK_PAY");
        requestParam.setPayTime(new Date());
        return requestParam;
    }

    private Integer currentOrderStatus(String orderSn) {
        return orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)).getStatus();
    }

    private Integer currentItemStatus(String orderSn) {
        return orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn)).get(0).getStatus();
    }
}

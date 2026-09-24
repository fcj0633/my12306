package edu.swu.fcj.my12306.biz.orderservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.orderservice.common.Result;
import edu.swu.fcj.my12306.biz.orderservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.orderservice.common.UserContext;
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
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.OrderStatusQueryRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import edu.swu.fcj.my12306.biz.orderservice.mq.OrderDelayCloseProducer;
import edu.swu.fcj.my12306.biz.orderservice.remote.PayRemoteService;
import edu.swu.fcj.my12306.biz.orderservice.remote.TicketRemoteService;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderCancelSeatRemoteDTO;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderCancelTicketRemoteReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.remote.dto.OrderClosePayRemoteReqDTO;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderService;
import edu.swu.fcj.my12306.biz.orderservice.service.OrderStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 订单服务实现
 * <p>
 * P1 只管"创建待支付订单"；P2 补齐三个能力：
 * <ul>
 *   <li>支付结果回调：待支付 -> 已支付（幂等）；</li>
 *   <li>超时关单：待支付 -> 已取消，并通知支付服务作废支付单、票务服务回滚座位；</li>
 *   <li>用户主动取消：与超时关单复用同一套幂等逻辑，只是多了登录态与归属校验。</li>
 * </ul>
 * 状态推进全部靠"条件更新 + 影响行数"，因此重复调用只会被识别为"已处理"，不会造成二次破坏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /**
     * 模拟渠道在订单侧记录的支付方式编码。
     * <p>
     * 【待确认】真实项目应对齐支付渠道字典表，本期沿用参考项目的简单映射。
     */
    private static final int PAY_TYPE_MOCK = 1;

    private final OrderMapper orderMapper;

    private final OrderItemMapper orderItemMapper;

    private final OrderStateService orderStateService;

    private final PayRemoteService payRemoteService;

    private final TicketRemoteService ticketRemoteService;

    /**
     * 延迟关单投递器。用 ObjectProvider 而不是直接注入：
     * 它是 {@code @ConditionalOnProperty} 条件 Bean，测试环境关闭延迟队列后仍然要能启动上下文。
     */
    private final ObjectProvider<OrderDelayCloseProducer> orderDelayCloseProducerProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        if (requestParam == null || StrUtil.isBlank(requestParam.getOrderSn())
                || requestParam.getUserId() == null || CollUtil.isEmpty(requestParam.getTicketOrderItems())) {
            throw new ServiceException("订单参数不完整");
        }
        String orderSn = requestParam.getOrderSn();
        OrderDO existing = selectByOrderSn(orderSn);
        if (existing != null) {
            assertSameCreateRequest(existing, requestParam);
            return orderSn;
        }
        Date now = new Date();
        OrderDO orderDO = OrderDO.builder()
                .orderSn(orderSn)
                .userId(requestParam.getUserId())
                .username(requestParam.getUsername())
                .trainId(requestParam.getTrainId())
                .trainNumber(requestParam.getTrainNumber())
                .ridingDate(requestParam.getRidingDate())
                .departure(requestParam.getDeparture())
                .arrival(requestParam.getArrival())
                .departureTime(requestParam.getDepartureTime())
                .arrivalTime(requestParam.getArrivalTime())
                .source(requestParam.getSource())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .orderTime(requestParam.getOrderTime() == null ? now : requestParam.getOrderTime())
                .build();
        orderDO.setCreateTime(now);
        orderDO.setUpdateTime(now);
        orderDO.setDelFlag(0);
        try {
            orderMapper.insert(orderDO);
        } catch (DuplicateKeyException ex) {
            // A retry may race the original request. The unique order number serializes both
            // attempts; only an identical request is allowed to reuse the committed order.
            OrderDO concurrentlyCreated = selectByOrderSn(orderSn);
            if (concurrentlyCreated == null) {
                throw ex;
            }
            assertSameCreateRequest(concurrentlyCreated, requestParam);
            return orderSn;
        }

        for (TicketOrderItemCreateReqDTO each : requestParam.getTicketOrderItems()) {
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .orderSn(orderSn)
                    .userId(requestParam.getUserId())
                    .username(requestParam.getUsername())
                    .trainId(requestParam.getTrainId())
                    .carriageNumber(each.getCarriageNumber())
                    .seatType(each.getSeatType())
                    .seatNumber(each.getSeatNumber())
                    .realName(each.getRealName())
                    .idType(each.getIdType())
                    .idCard(each.getIdCard())
                    .ticketType(each.getTicketType())
                    .phone(each.getPhone())
                    .status(OrderItemStatusEnum.PENDING_PAYMENT.getStatus())
                    .amount(each.getAmount())
                    .build();
            orderItemDO.setCreateTime(now);
            orderItemDO.setUpdateTime(now);
            orderItemDO.setDelFlag(0);
            orderItemMapper.insert(orderItemDO);
        }
        // 投递"20 分钟后检查是否已支付"的延迟任务。
        // send 内部会等到事务提交后才真正投递，避免回滚后留下关一笔不存在订单的脏任务。
        OrderDelayCloseProducer producer = orderDelayCloseProducerProvider.getIfAvailable();
        if (producer != null) {
            producer.send(orderSn);
        }
        return orderSn;
    }

    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        OrderDO orderDO = selectByOrderSn(orderSn);
        if (orderDO == null) {
            throw new ServiceException("订单不存在");
        }
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn));
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = new ArrayList<>();
        for (OrderItemDO each : orderItemDOList) {
            TicketOrderPassengerDetailRespDTO detail = new TicketOrderPassengerDetailRespDTO();
            detail.setId(each.getId());
            detail.setRealName(each.getRealName());
            detail.setIdType(each.getIdType());
            detail.setIdCard(each.getIdCard());
            detail.setPhone(each.getPhone());
            detail.setSeatType(each.getSeatType());
            detail.setCarriageNumber(each.getCarriageNumber());
            detail.setSeatNumber(each.getSeatNumber());
            detail.setTicketType(each.getTicketType());
            detail.setAmount(each.getAmount());
            detail.setStatus(each.getStatus());
            passengerDetails.add(detail);
        }
        TicketOrderDetailRespDTO result = new TicketOrderDetailRespDTO();
        result.setOrderSn(orderDO.getOrderSn());
        result.setUserId(orderDO.getUserId());
        result.setUsername(orderDO.getUsername());
        result.setTrainId(orderDO.getTrainId());
        result.setTrainNumber(orderDO.getTrainNumber());
        result.setRidingDate(orderDO.getRidingDate());
        result.setDeparture(orderDO.getDeparture());
        result.setArrival(orderDO.getArrival());
        result.setDepartureTime(orderDO.getDepartureTime());
        result.setArrivalTime(orderDO.getArrivalTime());
        result.setSource(orderDO.getSource());
        result.setStatus(orderDO.getStatus());
        result.setOrderTime(orderDO.getOrderTime());
        result.setPassengerDetails(passengerDetails);
        return result;
    }

    @Override
    public OrderStatusQueryRespDTO queryOrderStatus(String orderSn) {
        if (StrUtil.isBlank(orderSn)) {
            throw new ServiceException("订单号不能为空");
        }
        OrderDO orderDO = selectByOrderSn(orderSn);
        return OrderStatusQueryRespDTO.builder()
                .exists(orderDO != null)
                .status(orderDO == null ? null : orderDO.getStatus())
                .build();
    }

    @Override
    public void closeTicketOrder(TicketOrderCloseReqDTO requestParam) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(userId)) {
            throw new ServiceException("请先登录");
        }
        if (requestParam == null || StrUtil.isBlank(requestParam.getOrderSn())) {
            throw new ServiceException("订单号不能为空");
        }
        OrderDO orderDO = selectByOrderSn(requestParam.getOrderSn());
        if (orderDO == null) {
            throw new ServiceException("订单不存在");
        }
        if (orderDO.getUserId() == null || !orderDO.getUserId().equals(Long.valueOf(userId))) {
            // 不区分"订单不存在"与"不属于当前用户"，避免被用来探测他人订单
            throw new ServiceException("订单不存在或不属于当前用户");
        }
        if (OrderStatusEnum.ALREADY_PAID.getStatus().equals(orderDO.getStatus())) {
            // 【待确认】已支付订单的自助取消/退票口径属 P3，本期明确不支持
            throw new ServiceException("已支付订单不支持自助取消，请使用退票");
        }
        // 复用与超时关单完全相同的幂等业务方法
        closeTimeoutOrder(requestParam.getOrderSn());
    }

    @Override
    public boolean payCallbackOrder(TicketOrderPayCallbackReqDTO requestParam) {
        if (requestParam == null || StrUtil.isBlank(requestParam.getOrderSn())) {
            throw new ServiceException("订单号不能为空");
        }
        OrderDO orderDO = selectByOrderSn(requestParam.getOrderSn());
        if (orderDO == null) {
            throw new ServiceException("订单不存在");
        }
        if (OrderStatusEnum.ALREADY_PAID.getStatus().equals(orderDO.getStatus())) {
            // 重复回调：状态已是已支付，直接返回"已处理"，让支付服务停止重推
            return true;
        }
        if (OrderStatusEnum.CLOSED.getStatus().equals(orderDO.getStatus())) {
            // 订单已关闭却又收到支付成功：属于需要人工介入的异常（真实场景要退款）。
            // 这里返回 true 表示"收到了、不会再重推"，具体退款处理属 P3。
            log.error("订单已关闭却收到支付成功回调，需人工核对，orderSn={}，paySn={}",
                    requestParam.getOrderSn(), requestParam.getPaySn());
            return true;
        }
        boolean paid = orderStateService.markOrderPaid(requestParam.getOrderSn(),
                resolvePayType(requestParam.getPayType()), requestParam.getPayTime());
        if (!paid) {
            // 并发下被其他线程先推进（例如重复回调），同样视为已处理
            log.info("订单状态推进未生效，视为已处理，orderSn={}", requestParam.getOrderSn());
        }
        return true;
    }

    @Override
    public void closeTimeoutOrder(String orderSn) {
        if (StrUtil.isBlank(orderSn)) {
            throw new ServiceException("订单号不能为空");
        }
        OrderDO orderDO = selectByOrderSn(orderSn);
        if (orderDO == null) {
            // 订单不存在（例如延迟消息被重复投递、订单已被清理）：幂等直接返回
            log.warn("关单任务对应的订单不存在，跳过，orderSn={}", orderSn);
            return;
        }
        if (OrderStatusEnum.ALREADY_PAID.getStatus().equals(orderDO.getStatus())) {
            // 已支付：绝不能关单，也不能回滚座位
            return;
        }
        if (OrderStatusEnum.PENDING_PAYMENT.getStatus().equals(orderDO.getStatus())) {
            boolean closed = orderStateService.closePendingOrder(orderSn);
            log.info("超时关单推进本地状态，orderSn={}，是否本次生效={}", orderSn, closed);
        }
        // 无论本次是否真正关单（可能是重投、也可能上一步下游失败后重试），都要把后两步补齐：
        // 关闭支付单与回滚座位都是幂等的，重复执行不会造成二次伤害，但能保证"最终一致"。
        closePayOrder(orderSn);
        rollbackTicketSeats(orderDO, orderSn);
    }

    /**
     * 通知支付服务把待支付支付单置为交易关闭（幂等）
     */
    private void closePayOrder(String orderSn) {
        try {
            Result<Boolean> result = payRemoteService.closePay(OrderClosePayRemoteReqDTO.builder()
                    .orderSn(orderSn)
                    .build());
            if (result == null || !Result.SUCCESS_CODE.equals(result.getCode())) {
                throw new ServiceException("通知支付服务关闭支付单失败");
            }
        } catch (Throwable ex) {
            log.error("通知支付服务关闭支付单失败，orderSn={}，等待重投", orderSn, ex);
            throw new ServiceException("通知支付服务关闭支付单失败");
        }
    }

    /**
     * 通知票务服务把"已锁定"的座位放回"可售"、车票置为已取消（幂等）
     * <p>
     * 座位坐标必须与占座时使用的坐标一致（车厢 + 座号 + 席别），因此这里直接读订单明细。
     */
    private void rollbackTicketSeats(OrderDO orderDO, String orderSn) {
        List<OrderItemDO> orderItems = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn));
        if (CollUtil.isEmpty(orderItems)) {
            log.warn("订单没有明细，无法回滚座位，orderSn={}", orderSn);
            return;
        }
        List<OrderCancelSeatRemoteDTO> seats = new ArrayList<>();
        for (OrderItemDO each : orderItems) {
            seats.add(OrderCancelSeatRemoteDTO.builder()
                    .carriageNumber(each.getCarriageNumber())
                    .seatNumber(each.getSeatNumber())
                    .seatType(each.getSeatType())
                    .build());
        }
        try {
            Result<Boolean> result = ticketRemoteService.cancelCallback(OrderCancelTicketRemoteReqDTO.builder()
                    .orderSn(orderSn)
                    .trainId(orderDO.getTrainId())
                    .departure(orderDO.getDeparture())
                    .arrival(orderDO.getArrival())
                    .seats(seats)
                    .build());
            if (result == null || !Result.SUCCESS_CODE.equals(result.getCode())) {
                throw new ServiceException("通知票务服务回滚座位失败");
            }
        } catch (Throwable ex) {
            log.error("通知票务服务回滚座位失败，orderSn={}，等待重投", orderSn, ex);
            throw new ServiceException("通知票务服务回滚座位失败");
        }
    }

    /**
     * 支付渠道名 -> 订单表支付方式编码
     */
    private Integer resolvePayType(String payType) {
        if (StrUtil.isBlank(payType)) {
            return PAY_TYPE_MOCK;
        }
        if (StrUtil.isNumeric(payType)) {
            return Integer.valueOf(payType);
        }
        return PAY_TYPE_MOCK;
    }

    private OrderDO selectByOrderSn(String orderSn) {
        return orderMapper.selectOne(Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn));
    }

    /**
     * An idempotency key may only replay the same purchase fact. Checking the stable ownership,
     * route and seat coordinates prevents an internal caller from attaching different tickets to
     * an existing order number.
     */
    private void assertSameCreateRequest(OrderDO existing, TicketOrderCreateReqDTO requestParam) {
        boolean sameHeader = Objects.equals(existing.getUserId(), requestParam.getUserId())
                && Objects.equals(existing.getUsername(), requestParam.getUsername())
                && Objects.equals(existing.getTrainId(), requestParam.getTrainId())
                && Objects.equals(existing.getDeparture(), requestParam.getDeparture())
                && Objects.equals(existing.getArrival(), requestParam.getArrival());
        List<OrderItemDO> existingItems = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, existing.getOrderSn()));
        boolean sameItems = existingItems.size() == requestParam.getTicketOrderItems().size()
                && requestParam.getTicketOrderItems().stream().allMatch(requestItem -> existingItems.stream()
                .anyMatch(existingItem -> Objects.equals(existingItem.getCarriageNumber(), requestItem.getCarriageNumber())
                        && Objects.equals(existingItem.getSeatNumber(), requestItem.getSeatNumber())
                        && Objects.equals(existingItem.getSeatType(), requestItem.getSeatType())
                        && Objects.equals(existingItem.getIdCard(), requestItem.getIdCard())
                        && Objects.equals(existingItem.getAmount(), requestItem.getAmount())));
        if (!sameHeader || !sameItems) {
            throw new ServiceException("订单号已被其他购票请求占用");
        }
    }
}

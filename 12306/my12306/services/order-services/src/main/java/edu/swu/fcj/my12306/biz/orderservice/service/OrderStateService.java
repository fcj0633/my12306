package edu.swu.fcj.my12306.biz.orderservice.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import edu.swu.fcj.my12306.biz.orderservice.common.enums.OrderStatusEnum;
import edu.swu.fcj.my12306.biz.orderservice.dao.entity.OrderDO;
import edu.swu.fcj.my12306.biz.orderservice.dao.entity.OrderItemDO;
import edu.swu.fcj.my12306.biz.orderservice.dao.mapper.OrderItemMapper;
import edu.swu.fcj.my12306.biz.orderservice.dao.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

/**
 * 订单本地状态推进器
 * <p>
 * 为什么要单独抽一个类：<b>本地状态必须比跨服务通知先提交</b>。
 * <p>
 * 关单链路是"先关订单 → 再关支付单 → 最后回滚座位"三步，其中后两步是跨服务调用，可能超时、可能失败。
 * 如果把三步放进同一个事务，下游一失败本地关单就被回滚，重试时要从头再来；
 * 更要紧的是，座位一旦释放而订单状态还没落定，就可能出现"座位已释放、订单还能支付"的超卖窗口。
 * <p>
 * 所以这里把"改本地状态"做成独立事务方法：它先提交，之后才允许通知下游。
 * 重试时通过"条件更新 + 影响行数"判断是否已经推进过，天然幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateService {

    private final OrderMapper orderMapper;

    private final OrderItemMapper orderItemMapper;

    /**
     * 关单：订单与明细一起 待支付(0) -> 已取消(30)
     *
     * @return true 表示本次调用真正完成了关单；false 表示当前不是待支付状态（已关过或已支付）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean closePendingOrder(String orderSn) {
        Date now = new Date();
        OrderDO updateOrder = new OrderDO();
        updateOrder.setStatus(OrderStatusEnum.CLOSED.getStatus());
        updateOrder.setUpdateTime(now);
        int affectedRows = orderMapper.update(updateOrder, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        if (affectedRows == 0) {
            return false;
        }
        OrderItemDO updateItem = new OrderItemDO();
        updateItem.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
        updateItem.setUpdateTime(now);
        orderItemMapper.update(updateItem, Wrappers.lambdaUpdate(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn)
                .eq(OrderItemDO::getStatus, OrderItemStatusEnum.PENDING_PAYMENT.getStatus()));
        return true;
    }

    /**
     * 支付成功：订单与明细一起 待支付(0) -> 已支付(10)
     *
     * @return true 表示本次调用真正推进了状态；false 表示订单不是待支付（重复回调或订单已取消）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markOrderPaid(String orderSn, Integer payType, Date payTime) {
        Date now = new Date();
        OrderDO updateOrder = new OrderDO();
        updateOrder.setStatus(OrderStatusEnum.ALREADY_PAID.getStatus());
        updateOrder.setPayType(payType);
        updateOrder.setPayTime(payTime == null ? now : payTime);
        updateOrder.setUpdateTime(now);
        int affectedRows = orderMapper.update(updateOrder, Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getStatus()));
        if (affectedRows == 0) {
            return false;
        }
        OrderItemDO updateItem = new OrderItemDO();
        updateItem.setStatus(OrderItemStatusEnum.ALREADY_PAID.getStatus());
        updateItem.setUpdateTime(now);
        orderItemMapper.update(updateItem, Wrappers.lambdaUpdate(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn)
                .eq(OrderItemDO::getStatus, OrderItemStatusEnum.PENDING_PAYMENT.getStatus()));
        return true;
    }
}

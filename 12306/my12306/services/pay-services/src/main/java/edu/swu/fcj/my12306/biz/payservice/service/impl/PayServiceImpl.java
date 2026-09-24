package edu.swu.fcj.my12306.biz.payservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.Result;
import edu.swu.fcj.my12306.biz.payservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.payservice.common.UserContext;
import edu.swu.fcj.my12306.biz.payservice.common.enums.OrderStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayChannelEnum;
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
import edu.swu.fcj.my12306.biz.payservice.remote.dto.TicketSeatRemoteDTO;
import edu.swu.fcj.my12306.biz.payservice.service.PayService;
import edu.swu.fcj.my12306.biz.payservice.service.channel.PayChannelHandler;
import edu.swu.fcj.my12306.biz.payservice.service.channel.PayChannelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 支付服务实现
 * <p>
 * 设计要点：
 * 1. 金额只由服务端根据订单明细汇总，前端传的金额一律不信；
 * 2. 所有状态推进都用"条件更新 + 影响行数"实现幂等，不依赖 Redis，因此离线也能测；
 * 3. 支付成功后的下游通知失败不回滚支付单，只把通知状态置为未完成，由补偿任务重推。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayServiceImpl implements PayService {

    private final PayMapper payMapper;

    /** 支付回调的事务内部分。单独成 Bean，好让事务在它返回时完整结束，远程调用才落在事务之外。 */
    private final PayCallbackTxService payCallbackTxService;

    private final PayChannelFactory payChannelFactory;

    private final OrderRemoteService orderRemoteService;

    private final TicketRemoteService ticketRemoteService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayRespDTO createPay(PayCreateReqDTO requestParam) {
        String userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(username)) {
            throw new ServiceException("请先登录");
        }
        if (requestParam == null || StrUtil.isBlank(requestParam.getOrderSn())) {
            throw new ServiceException("订单号不能为空");
        }
        String orderSn = requestParam.getOrderSn();
        TicketOrderDetailRespDTO orderDetail = loadOrderDetail(orderSn);
        if (orderDetail.getUserId() == null || !orderDetail.getUserId().equals(Long.valueOf(userId))) {
            throw new ServiceException("订单不存在或不属于当前用户");
        }
        checkOrderCanPay(orderDetail.getStatus());
        int totalAmount = sumAmount(orderDetail);

        PayDO existing = selectByOrderSn(orderSn);
        if (existing != null) {
            // 幂等：同一订单已有支付单时直接复用，避免重复点"去支付"产生多笔支付单
            if (PayStatusEnum.PAID.getCode().equals(existing.getStatus())) {
                throw new ServiceException("订单已支付，无需重复支付");
            }
            if (PayStatusEnum.CLOSED.getCode().equals(existing.getStatus())) {
                throw new ServiceException("支付单已关闭，无法支付");
            }
            return buildPayResp(existing, payChannelFactory.getHandler(existing.getChannel()));
        }

        String channel = StrUtil.isBlank(requestParam.getChannel())
                ? PayChannelEnum.MOCK_PAY.getName() : requestParam.getChannel();
        PayChannelHandler handler = payChannelFactory.getHandler(channel);
        Date now = new Date();
        PayDO payDO = PayDO.builder()
                .paySn(IdUtil.getSnowflakeNextIdStr())
                .orderSn(orderSn)
                .userId(orderDetail.getUserId())
                .username(orderDetail.getUsername())
                .channel(channel)
                .tradeType(requestParam.getTradeType())
                .subject(buildSubject(orderDetail))
                .totalAmount(totalAmount)
                .status(PayStatusEnum.WAIT_PAY.getCode())
                .notifyStatus(PayNotifyStatusEnum.NOT_NOTIFIED.getCode())
                .build();
        payDO.setCreateTime(now);
        payDO.setUpdateTime(now);
        payDO.setDelFlag(0);
        payMapper.insert(payDO);
        return buildPayResp(payDO, handler);
    }

    @Override
    public PayInfoRespDTO getPayInfoByOrderSn(String orderSn) {
        if (StrUtil.isBlank(orderSn)) {
            throw new ServiceException("订单号不能为空");
        }
        PayDO payDO = selectByOrderSn(orderSn);
        if (payDO == null) {
            throw new ServiceException("支付单不存在");
        }
        return buildPayInfoResp(payDO);
    }

    @Override
    public PayInfoRespDTO getPayInfoByPaySn(String paySn) {
        return buildPayInfoResp(selectByPaySn(paySn));
    }

    /**
     * 支付回调入口。
     * <p>
     * 注意本方法【不带】{@code @Transactional}：事务边界在 {@link PayCallbackTxService#markPaid}，
     * 它返回时事务已完整结束（提交 + 归还连接 + 清理 ThreadLocal），下游通知在那之后才执行，
     * 因此 {@link #notifyPayResult} 里的 3 次远程调用不再被圈进事务 —— 这是 P1 段1 修的边界问题。
     * <p>
     * 返回值语义是"支付是否已入账"，不再是"下游是否已通知"：渠道只关心我们记下了这笔支付；
     * 下游通知失败不回滚支付单，由 {@code PayNotifyCompensateJob} 重推（见本类注释第 3 条）。
     */
    @Override
    public boolean payCallback(PayCallbackReqDTO requestParam) {
        if (requestParam == null || StrUtil.isBlank(requestParam.getPaySn())) {
            throw new ServiceException("支付流水号不能为空");
        }
        if (requestParam.getPayAmount() == null) {
            throw new ServiceException("支付金额不能为空");
        }
        boolean recorded = payCallbackTxService.markPaid(requestParam);
        // 事务已结束，这时才通知下游。
        // 注意：这里是同线程同步调用，所以本次 HTTP 响应仍要等两次 Feign 返回 ——
        // 真正把响应时间降下来的异步化在 P1 段2（改投 MQ）完成，本段只修事务边界。
        if (recorded) {
            notifyPayResult(requestParam.getPaySn());
        }
        return recorded;
    }

    @Override
    public boolean closePayByOrderSn(String orderSn) {
        if (StrUtil.isBlank(orderSn)) {
            throw new ServiceException("订单号不能为空");
        }
        PayDO updateDO = new PayDO();
        updateDO.setStatus(PayStatusEnum.CLOSED.getCode());
        updateDO.setUpdateTime(new Date());
        int affectedRows = payMapper.update(updateDO, Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getOrderSn, orderSn)
                .eq(PayDO::getStatus, PayStatusEnum.WAIT_PAY.getCode()));
        // 影响行数为 0 有两种可能：该订单从未创建支付单，或支付单已不是待支付。
        // 对调用方来说两者都属于"没有需要关闭的东西"，是幂等的正常结果。
        return affectedRows > 0;
    }

    @Override
    public boolean notifyPayResult(String paySn) {
        PayDO payDO = selectByPaySn(paySn);
        if (!PayStatusEnum.PAID.getCode().equals(payDO.getStatus())) {
            log.warn("支付单未处于支付成功状态，跳过下游通知，paySn={}，status={}", paySn, payDO.getStatus());
            return false;
        }
        if (PayNotifyStatusEnum.NOTIFIED.getCode().equals(payDO.getNotifyStatus())) {
            return true;
        }
        // P1 段1 的不变量断言：下面的 3 次远程调用必须落在事务之外。
        // 期望恒为 false；若打印 true，说明 notifyPayResult 又被事务包裹了
        // （例如有人把 @Transactional 加回 payCallback），远程调用会重新被圈进事务、
        // 让 t_pay 的行锁与数据库连接横跨三次远程 I/O。
        log.info("支付结果通知开始，事务活动状态={}（期望 false），paySn={}",
                TransactionSynchronizationManager.isActualTransactionActive(), paySn);
        try {
            TicketOrderDetailRespDTO orderDetail = loadOrderDetail(payDO.getOrderSn());
            // 顺序固定为"先订单、后票务"：
            // 万一第二步失败，订单已支付就不会被超时任务关掉；而座位仍是"已锁定"，
            // 与"已售"在可售性上等价，不会造成超卖，补偿重推即可修复。
            Result<Boolean> orderResult = orderRemoteService.payCallbackOrder(OrderPayCallbackRemoteReqDTO.builder()
                    .orderSn(payDO.getOrderSn())
                    .paySn(payDO.getPaySn())
                    .payType(payDO.getChannel())
                    .payTime(payDO.getGmtPayment())
                    .build());
            if (orderResult == null || !orderResult.isSuccess()) {
                throw new ServiceException("通知订单服务失败");
            }
            Result<Boolean> ticketResult = ticketRemoteService.payCallback(TicketPayCallbackRemoteReqDTO.builder()
                    .orderSn(payDO.getOrderSn())
                    .trainId(orderDetail.getTrainId())
                    .departure(orderDetail.getDeparture())
                    .arrival(orderDetail.getArrival())
                    .seats(buildSeats(orderDetail))
                    .build());
            if (ticketResult == null || !ticketResult.isSuccess()) {
                throw new ServiceException("通知票务服务失败");
            }
        } catch (Throwable ex) {
            log.error("支付结果通知下游失败，paySn={}，保留未完成状态等待补偿重推", paySn, ex);
            return false;
        }
        PayDO updateDO = new PayDO();
        updateDO.setNotifyStatus(PayNotifyStatusEnum.NOTIFIED.getCode());
        updateDO.setUpdateTime(new Date());
        int affectedRows = payMapper.update(updateDO, Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getPaySn, paySn)
                .eq(PayDO::getStatus, PayStatusEnum.PAID.getCode()));
        return affectedRows > 0;
    }

    private TicketOrderDetailRespDTO loadOrderDetail(String orderSn) {
        Result<TicketOrderDetailRespDTO> orderResult;
        try {
            orderResult = orderRemoteService.queryTicketOrderByOrderSn(orderSn);
        } catch (Throwable ex) {
            log.error("订单服务远程调用失败，orderSn={}", orderSn, ex);
            throw new ServiceException("订单服务调用失败");
        }
        if (orderResult == null || !orderResult.isSuccess() || orderResult.getData() == null) {
            throw new ServiceException("订单不存在");
        }
        return orderResult.getData();
    }

    private void checkOrderCanPay(Integer orderStatus) {
        if (OrderStatusEnum.PENDING_PAYMENT.getStatus().equals(orderStatus)) {
            return;
        }
        if (OrderStatusEnum.ALREADY_PAID.getStatus().equals(orderStatus)) {
            throw new ServiceException("订单已支付，无需重复支付");
        }
        throw new ServiceException("订单已取消，无法支付");
    }

    /**
     * 应付金额 = 逐条明细金额之和（分）。金额缺失一律视为数据异常，绝不猜。
     */
    private int sumAmount(TicketOrderDetailRespDTO orderDetail) {
        if (CollUtil.isEmpty(orderDetail.getPassengerDetails())) {
            throw new ServiceException("订单金额数据异常");
        }
        int total = 0;
        for (TicketOrderPassengerDetailRespDTO each : orderDetail.getPassengerDetails()) {
            if (each.getAmount() == null || each.getAmount() <= 0) {
                throw new ServiceException("订单金额数据异常");
            }
            total += each.getAmount();
        }
        return total;
    }

    private String buildSubject(TicketOrderDetailRespDTO orderDetail) {
        return StrUtil.join(" ", orderDetail.getTrainNumber(), orderDetail.getDeparture() + "-" + orderDetail.getArrival());
    }

    private List<TicketSeatRemoteDTO> buildSeats(TicketOrderDetailRespDTO orderDetail) {
        List<TicketSeatRemoteDTO> seats = new ArrayList<>();
        for (TicketOrderPassengerDetailRespDTO each : orderDetail.getPassengerDetails()) {
            seats.add(TicketSeatRemoteDTO.builder()
                    .carriageNumber(each.getCarriageNumber())
                    .seatNumber(each.getSeatNumber())
                    .seatType(each.getSeatType())
                    .build());
        }
        return seats;
    }

    private PayRespDTO buildPayResp(PayDO payDO, PayChannelHandler handler) {
        return PayRespDTO.builder()
                .paySn(payDO.getPaySn())
                .orderSn(payDO.getOrderSn())
                .totalAmount(payDO.getTotalAmount())
                .payUrl(handler.buildCashierUrl(payDO))
                .status(payDO.getStatus())
                .build();
    }

    private PayInfoRespDTO buildPayInfoResp(PayDO payDO) {
        return PayInfoRespDTO.builder()
                .paySn(payDO.getPaySn())
                .orderSn(payDO.getOrderSn())
                .userId(payDO.getUserId())
                .username(payDO.getUsername())
                .channel(payDO.getChannel())
                .tradeType(payDO.getTradeType())
                .subject(payDO.getSubject())
                .totalAmount(payDO.getTotalAmount())
                .payAmount(payDO.getPayAmount())
                .tradeNo(payDO.getTradeNo())
                .gmtPayment(payDO.getGmtPayment())
                .status(payDO.getStatus())
                .notifyStatus(payDO.getNotifyStatus())
                .build();
    }

    private PayDO selectByOrderSn(String orderSn) {
        return payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class).eq(PayDO::getOrderSn, orderSn));
    }

    private PayDO selectByPaySn(String paySn) {
        if (StrUtil.isBlank(paySn)) {
            throw new ServiceException("支付流水号不能为空");
        }
        PayDO payDO = payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class).eq(PayDO::getPaySn, paySn));
        if (payDO == null) {
            throw new ServiceException("支付单不存在");
        }
        return payDO;
    }
}

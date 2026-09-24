package edu.swu.fcj.my12306.biz.payservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayMapper;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCallbackReqDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;

/**
 * 支付回调的【事务内】部分：只做本地状态推进。
 * <p>
 * 为什么事务要单独放在一个 Bean 里（P1 段1）：
 * {@code PayServiceImpl.payCallback} 原本自己带 {@code @Transactional}，并在事务内调用
 * {@code notifyPayResult}，而后者有 3 次远程调用（查订单 + 回调订单 + 回调票务）。
 * Spring 事务沿调用栈传播，所以那 3 次远程调用【全部被圈进事务】：
 * t_pay 的行锁要等三次远程 I/O 都返回才随提交释放，数据库连接也被占着。
 * <p>
 * 抽成独立 Bean 后，调用方在【本方法返回之后】才去调远程 —— 那时事务已提交、连接已归还、
 * ThreadLocal 已清理，远程调用彻底落在事务之外。
 * 这与 D4 为购票链路建立的 {@code PurchaseTicketTxService} 是同一个模式。
 * <p>
 * ⚠️ 不要在本类里加任何远程调用或 Redis 操作，否则又会把它们圈回事务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayCallbackTxService {

    private final PayMapper payMapper;

    /**
     * 把支付单由"待支付"条件推进到"已支付"。幂等：重复回调返回 true 且不改数据。
     *
     * @return 支付是否已入账（重复回调同样返回 true）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markPaid(PayCallbackReqDTO requestParam) {
        // 与 PayServiceImpl.notifyPayResult 里的那行日志配对，用来夹住事务边界：
        // 这里必须是 true（在事务内），那里必须是 false（在事务外）。
        // 两行一起看，就证明了下游的 3 次远程调用确实落在事务之外。
        log.info("支付单状态推进（事务内）事务活动状态={}（期望 true），paySn={}",
                TransactionSynchronizationManager.isActualTransactionActive(), requestParam.getPaySn());
        PayDO payDO = payMapper.selectOne(Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getPaySn, requestParam.getPaySn()));
        if (payDO == null) {
            throw new ServiceException("支付单不存在");
        }
        if (!payDO.getTotalAmount().equals(requestParam.getPayAmount())) {
            // 金额不符一律拒绝：这是模拟渠道唯一的"防篡改"手段
            throw new ServiceException("支付金额与应付金额不一致");
        }
        if (PayStatusEnum.PAID.getCode().equals(payDO.getStatus())) {
            // 渠道重复回调：状态已推进过，不再写库。下游是否已通知交给补偿任务兜底。
            return true;
        }
        PayDO updateDO = new PayDO();
        updateDO.setStatus(PayStatusEnum.PAID.getCode());
        updateDO.setPayAmount(requestParam.getPayAmount());
        updateDO.setTradeNo(requestParam.getTradeNo());
        updateDO.setGmtPayment(requestParam.getGmtPayment() == null ? new Date() : requestParam.getGmtPayment());
        if (StrUtil.isNotBlank(requestParam.getChannel())) {
            updateDO.setChannel(requestParam.getChannel());
        }
        updateDO.setUpdateTime(new Date());
        int affectedRows = payMapper.update(updateDO, Wrappers.lambdaUpdate(PayDO.class)
                .eq(PayDO::getPaySn, requestParam.getPaySn())
                .eq(PayDO::getStatus, PayStatusEnum.WAIT_PAY.getCode()));
        if (affectedRows == 0) {
            // 并发下被别的线程先推进，或支付单已被关闭。
            // 两种情况下"重复回调视为已处理"的语义都不变，由调用方决定是否还要通知下游。
            log.warn("支付单状态推进未生效（已被并发处理或已关闭），paySn={}", requestParam.getPaySn());
        }
        return true;
    }
}

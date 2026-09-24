package edu.swu.fcj.my12306.biz.payservice.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayNotifyStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayMapper;
import edu.swu.fcj.my12306.biz.payservice.service.PayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付结果通知补偿任务
 * <p>
 * 只扫"支付成功 + 通知未完成"的支付单，重推给订单与票务。
 * 之所以只扫未完成：扫描范围越小越便宜，已完成的单没有任何需要补的动作。
 * <p>
 * 注意：这里不改变"支付成功"这个事实，只是把还没传播到位的结果补发出去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-compensate-enabled", havingValue = "true", matchIfMissing = true)
public class PayNotifyCompensateJob {

    private final PayMapper payMapper;

    private final PayService payService;

    @Scheduled(fixedDelayString = "${my12306.pay.notify-compensate-interval-ms:60000}")
    public void compensate() {
        List<PayDO> payList = payMapper.selectList(Wrappers.lambdaQuery(PayDO.class)
                .eq(PayDO::getStatus, PayStatusEnum.PAID.getCode())
                .eq(PayDO::getNotifyStatus, PayNotifyStatusEnum.NOT_NOTIFIED.getCode()));
        if (payList.isEmpty()) {
            return;
        }
        for (PayDO each : payList) {
            boolean success = payService.notifyPayResult(each.getPaySn());
            log.info("支付结果通知补偿，paySn={}，orderSn={}，结果={}", each.getPaySn(), each.getOrderSn(), success);
        }
    }
}

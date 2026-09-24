package edu.swu.fcj.my12306.biz.payservice.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.payservice.common.PayNotifyMqConstants;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayNotifyMessageStatusEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayNotifyMessageDO;
import edu.swu.fcj.my12306.biz.payservice.dao.mapper.PayNotifyMessageMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 把本地消息表里"待发送"的消息投递到 MQ。
 * <p>
 * 它只负责【投递】，不负责构造消息内容 —— 内容在事务里就已经落库了（见 PayCallbackTxService）。
 * 这样做的好处是排查时能直接看 t_pay_notify_message.payload 的原文，不依赖中间件。
 * <p>
 * 投递结果分两种处置：
 * - 成功（SendResult.SendStatus == SEND_OK）→ 条件更新 status 0→10，这条消息不再被扫描任务捞起；
 * - 失败 → retry_count+1 并设置退避时间，交给 PayNotifyMessageScanJob 重发。
 * <p>
 * 注意 send() 用的是【同步发送】：在 broker 配了 SYNC_FLUSH 的前提下，
 * 拿到 SEND_OK 才代表消息已落盘 —— 这正是"生产端确认"这一端的答案素材。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "my12306.pay.notify-mode", havingValue = "mq")
public class PayNotifyMessageSender {

    private static final int MAX_BACKOFF_SECONDS = 60;

    private final PayNotifyMessageMapper messageMapper;

    private final MeterRegistry meterRegistry;

    /**
     * 用 ObjectProvider 而不是直接注入 DefaultMQProducer：
     * 与项目里 OrderDelayCloseProducer 的取用方式一致，也避免生产者 Bean 不存在时启动即失败。
     */
    private final ObjectProvider<DefaultMQProducer> producerProvider;

    @Value("${my12306.pay.notify-message-scan-batch-size:100}")
    private int batchSize;

    /**
     * 事务提交后由业务侧调用：按 paySn 找到刚在事务里落下的那条消息并投递。
     * <p>
     * 这里只是"尽早发出去"以降低通知延迟；即使它失败或进程当场挂掉，
     * PayNotifyMessageScanJob 也会把这条 status 仍为 0 的消息补发。
     * 也就是说：低延迟靠这里，可靠性靠扫描任务。
     */
    public boolean sendByPaySn(String paySn) {
        PayNotifyMessageDO message = messageMapper.selectOne(Wrappers.lambdaQuery(PayNotifyMessageDO.class)
                .eq(PayNotifyMessageDO::getPaySn, paySn)
                .eq(PayNotifyMessageDO::getEventType, PayNotifyMqConstants.TAG_PAY_SUCCESS));
        if (message == null) {
            log.warn("没有找到待发送的支付结果消息，paySn={}", paySn);
            return false;
        }
        if (PayNotifyMessageStatusEnum.SENT.getCode().equals(message.getStatus())) {
            // 已投递过（例如并发回调），直接返回成功，不重复发
            return true;
        }
        return sendPending(message);
    }

    /**
     * 投递一条待发送消息。返回是否投递成功。
     */
    public boolean sendPending(PayNotifyMessageDO message) {
        DefaultMQProducer producer = producerProvider.getIfAvailable();
        if (producer == null) {
            log.warn("MQ 生产者不可用，消息保持待发送状态等待重发。paySn={}", message.getPaySn());
            return false;
        }
        try {
            // keys 用 paySn：控制台里可以按业务主键直接检索这条消息，排查时不用翻表
            Message mqMessage = new Message(PayNotifyMqConstants.TOPIC,
                    PayNotifyMqConstants.TAG_PAY_SUCCESS,
                    message.getPaySn(),
                    message.getPayload().getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(mqMessage);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("MQ 未返回 SEND_OK：" + (result == null ? "null" : result.getSendStatus()));
            }
            markSent(message.getId());
            meterRegistry.counter("my12306.pay.notify.send.success").increment();
            log.info("支付结果事件已投递，paySn={}，msgId={}，event={}",
                    message.getPaySn(), result.getMsgId(), message.getEventType());
            return true;
        } catch (Throwable ex) {
            meterRegistry.counter("my12306.pay.notify.send.fail").increment();
            markRetry(message);
            log.error("支付结果事件投递失败，保留待发送状态等待扫描任务重发。paySn={}，retryCount={}",
                    message.getPaySn(), message.getRetryCount(), ex);
            return false;
        }
    }

    /**
     * 条件更新 0→10：只有仍是"待发送"时才改，避免与并发的扫描任务重复改。
     */
    private void markSent(Long id) {
        PayNotifyMessageDO update = new PayNotifyMessageDO();
        update.setStatus(PayNotifyMessageStatusEnum.SENT.getCode());
        update.setUpdateTime(new Date());
        messageMapper.update(update, Wrappers.lambdaUpdate(PayNotifyMessageDO.class)
                .eq(PayNotifyMessageDO::getId, id)
                .eq(PayNotifyMessageDO::getStatus, PayNotifyMessageStatusEnum.PENDING.getCode()));
    }

    /**
     * 记录一次失败：retry_count 自增，并设置下次可重试时间（指数退避，上限 60 秒）。
     * <p>
     * 退避是必要的：下游持续不可用时，不退避会让扫描任务每轮都白跑一遍并刷满日志。
     */
    private void markRetry(PayNotifyMessageDO message) {
        int retryCount = message.getRetryCount() == null ? 0 : message.getRetryCount();
        long backoffSeconds = Math.min(1L << Math.min(retryCount, 6), MAX_BACKOFF_SECONDS);
        messageMapper.update(null, Wrappers.<PayNotifyMessageDO>lambdaUpdate()
                .setSql("retry_count = retry_count + 1")
                .set(PayNotifyMessageDO::getNextRetryTime, new Date(System.currentTimeMillis() + backoffSeconds * 1000L))
                .set(PayNotifyMessageDO::getUpdateTime, new Date())
                .eq(PayNotifyMessageDO::getId, message.getId()));
    }
}

package edu.swu.fcj.my12306.biz.payservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 支付结果通知的本地消息表实体。
 * <p>
 * 落库时机与 t_pay 的状态推进【同一事务】，所以"支付已入账"与"待通知下游"两件事必然同时成立。
 * 事务提交后才真正发 MQ；发送失败或漏发的由 PayNotifyMessageScanJob 兜底重发。
 * <p>
 * 表上的 UNIQUE(pay_sn, event_type) 是幂等的最后一道防线：渠道重复回调导致重复插入时会被挡掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_notify_message")
public class PayNotifyMessageDO extends BaseDO {

    private Long id;

    private String paySn;

    private String orderSn;

    /**
     * 事件类型，如 PAY_SUCCESS
     */
    private String eventType;

    /**
     * 消息体（JSON）。落库是为了排查时能直接看到发了什么，不依赖中间件。
     */
    private String payload;

    /**
     * 0 待发送 10 已发送
     */
    private Integer status;

    private Integer retryCount;

    /**
     * 下次可重试的时间，用于退避
     */
    private Date nextRetryTime;
}

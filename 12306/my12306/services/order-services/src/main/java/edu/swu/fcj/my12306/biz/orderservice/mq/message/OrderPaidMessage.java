package edu.swu.fcj.my12306.biz.orderservice.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ORDER_PAID 事件的消息体（本服务生产，ticket-services 消费）。
 * <p>
 * 只带 orderSn —— 刻意【不带】座位坐标。
 * <p>
 * 为什么：票务服务需要的坐标（车次/区间/席别/车厢/座号）它自己从 t_ticket 就能查到，
 * 而且它现成的孤儿恢复任务 {@code TicketOrphanRecoveryJob} 就是这么做的。
 * 如果这里由订单服务拼坐标，就会出现"两条做同一件事的路径各自的坐标来源不同"的分歧风险 ——
 * 而孤儿恢复任务恰恰是通知丢失时兜底的那条路径。
 * <p>
 * 消息体小还有一个好处：它不承载任何一方的业务视图，只表达"这笔订单已经付过钱了"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaidMessage implements Serializable {

    private String orderSn;
}

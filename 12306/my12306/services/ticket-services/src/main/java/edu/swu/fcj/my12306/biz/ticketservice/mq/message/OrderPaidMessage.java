package edu.swu.fcj.my12306.biz.ticketservice.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ORDER_PAID 事件的消息体（消费端副本）。
 * <p>
 * 只有一个 orderSn，没有座位坐标 —— 坐标由本服务自己从 t_ticket 查
 * （见 {@code PayResultTicketConsumer}）。这样消息只表达"这笔订单已经付过钱了"这个事实，
 * 不承载任何一方的业务视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaidMessage implements Serializable {

    private String orderSn;
}

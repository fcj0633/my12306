package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 车票实体（购票事实）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket")
public class TicketDO extends BaseDO {

    private Long id;

    /**
     * Ticket-service generates the order id before reserving seats so an ambiguous remote timeout
     * can later be reconciled against the exact order instead of blindly releasing the seat.
     */
    private String orderSn;

    private String username;

    private Long trainId;

    private String carriageNumber;

    private String seatNumber;

    private String passengerId;

    /**
     * 席别（与座位、票价保持同一坐标）
     */
    private Integer seatType;

    /**
     * 出发站（中文站名，与座位行同一坐标）
     */
    private String startStation;

    /**
     * 到达站（中文站名，与座位行同一坐标）
     */
    private String endStation;

    /**
     * 0 未支付 10 已支付 30 已取消
     */
    private Integer ticketStatus;
}

package edu.swu.fcj.my12306.biz.ticketservice.dto.req;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * 车次列表查询入参
 */
@Data
public class TicketPageQueryReqDTO {

    /**
     * 出发地：车站/地区编码
     */
    private String fromStation;

    /**
     * 目的地：车站/地区编码
     */
    private String toStation;

    /**
     * 出发日期 yyyy-MM-dd
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date departureDate;

    /**
     * 前端筛选项（当前不参与服务端过滤）
     */
    private String departure;

    /**
     * 前端筛选项（当前不参与服务端过滤）
     */
    private String arrival;
}

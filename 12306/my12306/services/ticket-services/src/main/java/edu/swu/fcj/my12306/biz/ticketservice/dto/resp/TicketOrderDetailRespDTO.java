package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 购票结果中的乘车人明细（谁坐哪、多少钱）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderDetailRespDTO {

    private Integer amount;

    private String carriageNumber;

    private String seatNumber;

    private String realName;

    private Integer idType;

    private String idCard;

    private String phone;

    private Integer seatType;

    private Integer ticketType;
}

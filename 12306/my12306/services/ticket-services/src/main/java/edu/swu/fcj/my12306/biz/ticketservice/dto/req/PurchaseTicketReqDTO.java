package edu.swu.fcj.my12306.biz.ticketservice.dto.req;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import lombok.Data;

import java.util.List;

/**
 * 购票请求入参
 */
@Data
public class PurchaseTicketReqDTO {

    /**
     * 车次 ID
     */
    private String trainId;

    /**
     * 乘车人（乘车人 ID + 席别）
     */
    private List<PurchaseTicketPassengerDetailDTO> passengers;

    /**
     * 选座要求（软偏好，可空）
     */
    private List<String> chooseSeats;

    /**
     * 出发站点（中文站名）
     */
    private String departure;

    /**
     * 到达站点（中文站名）
     */
    private String arrival;
}

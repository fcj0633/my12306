package edu.swu.fcj.my12306.biz.ticketservice.remote.dto;

import lombok.Data;

/**
 * Minimal order state used by the ticket recovery job.
 */
@Data
public class OrderStatusRemoteRespDTO {

    private Boolean exists;

    private Integer status;
}

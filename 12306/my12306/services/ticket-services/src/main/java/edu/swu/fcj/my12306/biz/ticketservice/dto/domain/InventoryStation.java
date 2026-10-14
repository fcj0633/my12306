package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.Data;

/** Validated, immutable station projection created during inventory migration. */
@Data
public class InventoryStation {
    private Integer stationIndex;
    private String stationName;
}

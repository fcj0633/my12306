package edu.swu.fcj.my12306.biz.ticketservice.dto.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车次列表元素
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketListDTO {

    private String trainId;

    private String trainNumber;

    private String departureTime;

    private String arrivalTime;

    private String duration;

    private Integer daysArrived;

    private String departure;

    private String arrival;

    private Boolean departureFlag;

    private Boolean arrivalFlag;

    private Integer trainType;

    private String saleTime;

    private Integer saleStatus;

    private List<String> trainTags;

    private String trainBrand;

    private List<SeatClassDTO> seatClassList;
}

package edu.swu.fcj.my12306.biz.ticketservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 车次经停站出参
 */
@Data
public class TrainStationQueryRespDTO {

    private String sequence;

    private String departure;

    @JsonFormat(pattern = "HH:mm", timezone = "GMT+8")
    private Date arrivalTime;

    @JsonFormat(pattern = "HH:mm", timezone = "GMT+8")
    private Date departureTime;

    /**
     * 停留时间，单位分钟
     */
    private Integer stopoverTime;
}

package edu.swu.fcj.my12306.biz.ticketservice.dto.req;

import lombok.Data;

/**
 * 地区/车站查询入参
 */
@Data
public class RegionStationQueryReqDTO {

    /**
     * 查询类型：0 热门 1 A-E 2 F-J 3 K-O 4 P-T 5 U-Z
     */
    private Integer queryType;

    /**
     * 关键词：站名或拼音前缀
     */
    private String name;
}

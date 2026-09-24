package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.RouteDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 站点区间计算工具
 */
public final class StationCalculateUtil {

    /**
     * 计算 startStation 到 endStation 之间的所有子区间组合（包含两端，i < j）
     *
     * @param stations     车次全部经停站（按停靠顺序）
     * @param startStation 车次始发站
     * @param endStation   车次终到站
     * @return 区间组合；若站点不存在或顺序颠倒，返回空集合
     */
    public static List<RouteDTO> throughStation(List<String> stations, String startStation, String endStation) {
        List<RouteDTO> routes = new ArrayList<>();
        int startIndex = stations.indexOf(startStation);
        int endIndex = stations.indexOf(endStation);
        if (startIndex < 0 || endIndex < 0 || startIndex >= endIndex) {
            return routes;
        }
        for (int i = startIndex; i < endIndex; i++) {
            for (int j = i + 1; j <= endIndex; j++) {
                routes.add(new RouteDTO(stations.get(i), stations.get(j)));
            }
        }
        return routes;
    }

    private StationCalculateUtil() {
    }
}

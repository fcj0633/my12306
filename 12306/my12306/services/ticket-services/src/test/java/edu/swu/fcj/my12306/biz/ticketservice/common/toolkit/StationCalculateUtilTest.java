package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.RouteDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationCalculateUtilTest {

    private final List<String> stations = List.of("北京南", "济南西", "南京南", "杭州东", "宁波");

    @Test
    void throughStation_returnsAllSubRoutes() {
        List<RouteDTO> routes = StationCalculateUtil.throughStation(stations, "北京南", "南京南");
        assertEquals(3, routes.size());
        assertTrue(routes.stream().anyMatch(each -> "北京南".equals(each.getStartStation()) && "南京南".equals(each.getEndStation())));
        assertTrue(routes.stream().anyMatch(each -> "济南西".equals(each.getStartStation()) && "南京南".equals(each.getEndStation())));
    }

    @Test
    void throughStation_returnsEmptyWhenOrderInvalid() {
        assertTrue(StationCalculateUtil.throughStation(stations, "南京南", "济南西").isEmpty());
        assertTrue(StationCalculateUtil.throughStation(stations, "不存在的站", "南京南").isEmpty());
    }
}

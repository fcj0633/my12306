package edu.swu.fcj.my12306.biz.ticketservice.service.impl;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.InventoryStation;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.SegmentRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class InventoryRouteService {
    private final SeatMapper mapper;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public List<InventoryStation> stations(Long trainId) {
        return entries.compute(trainId, (id, old) -> {
            if (old != null && System.nanoTime() - old.loadedAt < TimeUnit.MINUTES.toNanos(5)) return old;
            List<InventoryStation> stations = List.copyOf(mapper.selectInventoryStations(id));
            if (stations.size() < 2) throw new ServiceException("区段库存站点数据缺失，请先完成迁移");
            var names = new java.util.HashSet<String>();
            for (int i = 0; i < stations.size(); i++) {
                InventoryStation s = stations.get(i);
                if (s.getStationIndex() == null || s.getStationIndex() != i || s.getStationName() == null
                        || s.getStationName().isBlank() || !names.add(s.getStationName())) {
                    throw new ServiceException("区段库存站点数据错误");
                }
            }
            return new Entry(stations, System.nanoTime());
        }).stations;
    }

    public SegmentRange resolve(Long trainId, String departure, String arrival) {
        int start = -1, end = -1;
        for (InventoryStation station : stations(trainId)) {
            if (station.getStationName().equals(departure)) start = station.getStationIndex();
            if (station.getStationName().equals(arrival)) end = station.getStationIndex();
        }
        if (start < 0 || end <= start) throw new ServiceException("列车车站数据错误");
        return new SegmentRange(start, end);
    }

    private record Entry(List<InventoryStation> stations, long loadedAt) { }
}

package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TrainStationDO;
import java.util.*;

public final class InventoryStationOrder {
    private InventoryStationOrder() { }
    public static List<TrainStationDO> validateAndSort(List<TrainStationDO> input) {
        var sorted=new ArrayList<>(input);
        try { sorted.sort(Comparator.comparingInt(s -> Integer.parseInt(s.getSequence()))); }
        catch (RuntimeException ex) { throw new ServiceException("站点序号必须为整数"); }
        if (sorted.size()<2) throw new ServiceException("列车站点缺失");
        int first=Integer.parseInt(sorted.getFirst().getSequence());
        var names=new HashSet<String>();
        for (int i=0;i<sorted.size();i++) {
            var s=sorted.get(i);
            if (first<0 || Integer.parseInt(s.getSequence())!=first+i || s.getDeparture()==null
                    || s.getDeparture().isBlank() || !names.add(s.getDeparture())) {
                throw new ServiceException("列车站点序号重复、缺失或站名错误");
            }
        }
        return List.copyOf(sorted);
    }
}

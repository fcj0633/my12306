package edu.swu.fcj.my12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.InventoryCacheEventDO;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface InventoryCacheEventMapper extends BaseMapper<InventoryCacheEventDO> {
    @Select("SELECT id,train_id,start_index,end_index,invalidate_bucket FROM t_inventory_cache_event ORDER BY id LIMIT 100")
    List<InventoryCacheEventDO> pending();
}

package edu.swu.fcj.my12306.biz.ticketservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_inventory_cache_event")
public class InventoryCacheEventDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trainId;
    private Integer startIndex;
    private Integer endIndex;
    private Boolean invalidateBucket;
}

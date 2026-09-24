package edu.swu.fcj.my12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.TicketDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

public interface TicketMapper extends BaseMapper<TicketDO> {

    /**
     * Select order ids first so a batch never cuts a multi-passenger reservation in half.
     */
    @Select("""
            SELECT order_sn
            FROM t_ticket
            WHERE del_flag = 0
              AND order_sn IS NOT NULL
              AND ticket_status = #{status}
              AND create_time < #{before}
            GROUP BY order_sn
            ORDER BY MIN(create_time)
            LIMIT #{limit}
            """)
    List<String> selectRecoverableOrderSns(@Param("status") Integer status,
                                           @Param("before") Date before,
                                           @Param("limit") Integer limit);
}

package edu.swu.fcj.my12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SeatMapper extends BaseMapper<SeatDO> {

    @Select("""
            <script>
            SELECT id, carriage_number, seat_number FROM t_seat
            WHERE train_id = #{trainId} AND seat_type = #{seatType}
              AND seat_status = 0 AND start_station = #{departure}
              AND end_station = #{arrival} AND del_flag = 0
            <if test="afterCarriage != null">
              AND carriage_number &gt; #{afterCarriage}
            </if>
            ORDER BY carriage_number, seat_number LIMIT #{count}
            </script>
            """)
    List<SeatDO> selectAvailableSeatCandidates(@Param("trainId") Long trainId,
            @Param("departure") String departure, @Param("arrival") String arrival,
            @Param("seatType") Integer seatType, @Param("afterCarriage") String afterCarriage,
            @Param("count") int count);

    @Select("""
            SELECT id, carriage_number, seat_number FROM t_seat
            WHERE train_id = #{trainId} AND seat_type = #{seatType}
              AND seat_status = 0 AND start_station = #{departure}
              AND end_station = #{arrival} AND del_flag = 0
              AND carriage_number = #{carriage}
            ORDER BY seat_number LIMIT #{count}
            """)
    List<SeatDO> selectAvailableSeatsInCarriage(@Param("trainId") Long trainId,
            @Param("departure") String departure, @Param("arrival") String arrival,
            @Param("seatType") Integer seatType, @Param("carriage") String carriage,
            @Param("count") int count);
}

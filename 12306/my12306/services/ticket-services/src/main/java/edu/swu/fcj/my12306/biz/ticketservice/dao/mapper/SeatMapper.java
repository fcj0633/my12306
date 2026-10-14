package edu.swu.fcj.my12306.biz.ticketservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.InventoryStation;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.SegmentAssignment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface SeatMapper extends BaseMapper<SeatDO> {
    @Select("<script>SELECT id AS seat_id, train_id FROM t_physical_seat WHERE del_flag=0 AND id IN " +
            "<foreach collection='seatIds' item='seatId' open='(' separator=',' close=')'>#{seatId}</foreach></script>")
    List<Map<String,Object>> selectPhysicalSeatTrains(@Param("seatIds") List<Long> seatIds);

    // SeatDO is retained as the small allocation projection. Its id now identifies a physical seat.
    String RANGE_JOIN = """
            JOIN t_inventory_station d ON d.train_id = p.train_id AND d.station_name = #{departure}
            JOIN t_inventory_station a ON a.train_id = p.train_id AND a.station_name = #{arrival}
            """;
    String FULLY_AVAILABLE = """
            AND a.station_index > d.station_index
            AND (SELECT COUNT(*) FROM t_seat_segment s WHERE s.seat_id = p.id
                 AND s.segment_index BETWEEN d.station_index AND a.station_index - 1
                 AND s.seat_status = 0 AND s.ticket_id IS NULL AND s.order_sn IS NULL)
                = a.station_index - d.station_index
            """;

    @Select("""
            SELECT DISTINCT carriage_number FROM t_physical_seat
            WHERE train_id = #{trainId} AND seat_type = #{seatType} AND del_flag = 0
            ORDER BY carriage_number
            """)
    List<String> selectCarriageNumbers(@Param("trainId") Long trainId, @Param("seatType") Integer seatType);

    @Select("""
            <script>
            SELECT p.id, p.carriage_number, p.seat_number FROM t_physical_seat p
            """ + RANGE_JOIN + """
            WHERE p.train_id = #{trainId} AND p.seat_type = #{seatType} AND p.del_flag = 0
            """ + FULLY_AVAILABLE + """
            <if test="afterCarriage != null">
              AND p.carriage_number &gt; #{afterCarriage}
            </if>
            ORDER BY p.carriage_number, p.seat_number LIMIT #{count}
            </script>
            """)
    List<SeatDO> selectAvailableSeatCandidates(@Param("trainId") Long trainId,
            @Param("departure") String departure, @Param("arrival") String arrival,
            @Param("seatType") Integer seatType, @Param("afterCarriage") String afterCarriage,
            @Param("count") int count);

    @Select("""
            SELECT p.id, p.carriage_number, p.seat_number FROM t_physical_seat p
            """ + RANGE_JOIN + """
            WHERE p.train_id = #{trainId} AND p.seat_type = #{seatType} AND p.del_flag = 0
              AND p.carriage_number = #{carriage}
            """ + FULLY_AVAILABLE + """
            ORDER BY p.seat_number LIMIT #{count}
            """)
    List<SeatDO> selectAvailableSeatsInCarriage(@Param("trainId") Long trainId,
            @Param("departure") String departure, @Param("arrival") String arrival,
            @Param("seatType") Integer seatType, @Param("carriage") String carriage,
            @Param("count") int count);

    @Select("SELECT station_index, station_name FROM t_inventory_station WHERE train_id=#{trainId} ORDER BY station_index")
    List<InventoryStation> selectInventoryStations(@Param("trainId") Long trainId);

    @Select("""
            SELECT p.seat_type, COUNT(*) AS cnt FROM t_physical_seat p
            """ + RANGE_JOIN + """
            WHERE p.train_id = #{trainId} AND p.del_flag = 0
            """ + FULLY_AVAILABLE + " GROUP BY p.seat_type")
    List<Map<String, Object>> countAvailableByType(@Param("trainId") Long trainId,
            @Param("departure") String departure, @Param("arrival") String arrival);

    @Select("""
            SELECT s.segment_index, p.seat_type, COUNT(*) AS cnt
            FROM t_seat_segment s JOIN t_physical_seat p ON p.id=s.seat_id
            WHERE p.train_id=#{trainId} AND p.del_flag=0 AND s.seat_status=0
              AND s.ticket_id IS NULL AND s.order_sn IS NULL
            GROUP BY s.segment_index,p.seat_type
            """)
    List<Map<String, Object>> countAvailableSegments(@Param("trainId") Long trainId);

    @Update("""
            <script>
            UPDATE t_seat_segment SET seat_status=1, order_sn=#{orderSn}, ticket_id=CASE seat_id
            <foreach collection="assignments" item="x"> WHEN #{x.seatId} THEN #{x.ticketId} </foreach>
            END, update_time=CURRENT_TIMESTAMP
            WHERE seat_id IN
            <foreach collection="assignments" item="x" open="(" separator="," close=")">#{x.seatId}</foreach>
            AND segment_index &gt;= #{start} AND segment_index &lt; #{end}
            AND seat_status=0 AND ticket_id IS NULL AND order_sn IS NULL
            ORDER BY seat_id,segment_index
            </script>
            """)
    int reserveSegments(@Param("assignments") List<SegmentAssignment> assignments,
            @Param("start") int start, @Param("end") int end, @Param("orderSn") String orderSn);

    @Update("""
            UPDATE t_seat_segment SET seat_status=#{targetStatus},update_time=CURRENT_TIMESTAMP
            WHERE ticket_id=#{ticketId} AND order_sn=#{orderSn} AND seat_status=1
              AND segment_index >= #{start} AND segment_index < #{end}
            ORDER BY seat_id,segment_index
            """)
    int sellOwnedSegments(@Param("ticketId") Long ticketId, @Param("orderSn") String orderSn,
            @Param("start") int start, @Param("end") int end, @Param("targetStatus") int targetStatus);

    @Update("""
            UPDATE t_seat_segment SET seat_status=0,ticket_id=NULL,order_sn=NULL,update_time=CURRENT_TIMESTAMP
            WHERE ticket_id=#{ticketId} AND order_sn=#{orderSn} AND seat_status=1
              AND segment_index >= #{start} AND segment_index < #{end}
            ORDER BY seat_id,segment_index
            """)
    int releaseOwnedSegments(@Param("ticketId") Long ticketId, @Param("orderSn") String orderSn,
            @Param("start") int start, @Param("end") int end);
}

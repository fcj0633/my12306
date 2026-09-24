package edu.swu.fcj.my12306.biz.ticketservice.service;

import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.ticketservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.dto.domain.TicketListDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.RegionStationQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.RegionStationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.StationQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TicketPageQueryRespDTO;
import edu.swu.fcj.my12306.biz.ticketservice.dto.resp.TrainStationQueryRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * P0 读链路测试：真实 MySQL + 内存缓存替身（不连接 Redis）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
class TicketReadServiceTest {

    @Autowired
    private RegionStationService regionStationService;

    @Autowired
    private TrainStationService trainStationService;

    @Autowired
    private TicketService ticketService;

    @MockBean
    private RedisCacheHelper redisCacheHelper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private RedissonClient redissonClient;

    private final Map<String, String> stringCache = new HashMap<>();

    private final Map<String, Map<Object, Object>> hashCache = new HashMap<>();

    @BeforeEach
    void stubRedisCacheHelper() {
        stringCache.clear();
        hashCache.clear();

        when(redisCacheHelper.get(anyString())).thenAnswer(invocation -> stringCache.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            stringCache.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(redisCacheHelper).put(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisCacheHelper.safeGet(anyString(), any(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = stringCache.get(key);
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
            Supplier<String> loader = invocation.getArgument(1);
            value = loader.get();
            if (StrUtil.isNotBlank(value)) {
                stringCache.put(key, value);
            }
            return value;
        });
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(redisCacheHelper).executeWithLock(anyString(), any(Runnable.class));

        when(redisCacheHelper.hGet(anyString(), anyString())).thenAnswer(invocation -> {
            Map<Object, Object> hash = hashCache.get(invocation.getArgument(0));
            return hash == null ? null : hash.get(invocation.getArgument(1));
        });
        when(redisCacheHelper.hMultiGet(anyString(), anyList())).thenAnswer(invocation -> {
            Map<Object, Object> hash = hashCache.getOrDefault(invocation.getArgument(0), Collections.emptyMap());
            List<Object> fields = invocation.getArgument(1);
            return fields.stream().map(hash::get).toList();
        });
        when(redisCacheHelper.hGetAll(anyString())).thenAnswer(invocation -> {
            Map<Object, Object> hash = hashCache.get(invocation.getArgument(0));
            return hash == null ? new HashMap<>() : new HashMap<>(hash);
        });
        doAnswer(invocation -> {
            hashCache.computeIfAbsent(invocation.getArgument(0), key -> new HashMap<>())
                    .putAll(invocation.getArgument(1));
            return null;
        }).when(redisCacheHelper).hPutAll(anyString(), anyMap());
        doAnswer(invocation -> {
            hashCache.computeIfAbsent(invocation.getArgument(0), key -> new HashMap<>())
                    .put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(redisCacheHelper).hPut(anyString(), anyString(), anyString());
        when(redisCacheHelper.hasKey(anyString())).thenAnswer(invocation ->
                stringCache.containsKey(invocation.getArgument(0)) || hashCache.containsKey(invocation.getArgument(0)));
    }

    @Test
    void trainStationQuery_returnsStopsInOrder() {
        List<TrainStationQueryRespDTO> stationList = trainStationService.listTrainStationQuery("1");
        assertEquals(5, stationList.size());
        assertEquals("北京南", stationList.get(0).getDeparture());
        assertEquals("济南西", stationList.get(1).getDeparture());
        assertEquals("宁波", stationList.get(4).getDeparture());
    }

    @Test
    void stationAll_returnsStationDictionary() {
        List<StationQueryRespDTO> stationList = regionStationService.listAllStation();
        assertFalse(stationList.isEmpty());
        assertTrue(stationList.stream().anyMatch(each -> "北京南".equals(each.getName()) && "北京".equals(each.getRegionName())));
    }

    @Test
    void regionStationQuery_byKeyword_returnsMatchedStations() {
        RegionStationQueryReqDTO requestParam = new RegionStationQueryReqDTO();
        requestParam.setName("北");
        List<RegionStationQueryRespDTO> stationList = regionStationService.listRegionStation(requestParam);
        assertFalse(stationList.isEmpty());
        assertTrue(stationList.stream().anyMatch(each -> "北京南".equals(each.getName())));
    }

    @Test
    void regionStationQuery_byQueryType_returnsRegions() {
        RegionStationQueryReqDTO requestParam = new RegionStationQueryReqDTO();
        requestParam.setQueryType(1);
        assertFalse(regionStationService.listRegionStation(requestParam).isEmpty());
    }

    @Test
    void regionStationQuery_invalidQueryType_throws() {
        RegionStationQueryReqDTO requestParam = new RegionStationQueryReqDTO();
        requestParam.setQueryType(99);
        ServiceException exception = assertThrows(ServiceException.class,
                () -> regionStationService.listRegionStation(requestParam));
        assertEquals("查询失败，请检查查询参数是否正确", exception.getMessage());
    }

    @Test
    void ticketQuery_returnsTrainsWithPriceAndRemainingTicket() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation("VNP");
        requestParam.setToStation("NKH");
        requestParam.setDepartureDate(tomorrow());

        TicketPageQueryRespDTO response = ticketService.pageListTicketQuery(requestParam);
        assertFalse(response.getTrainList().isEmpty());
        assertTrue(response.getTrainList().size() >= 3);

        TicketListDTO g35 = response.getTrainList().stream()
                .filter(each -> "G35".equals(each.getTrainNumber()))
                .findFirst()
                .orElseThrow();
        assertEquals("北京南", g35.getDeparture());
        assertEquals("南京南", g35.getArrival());
        assertFalse(g35.getSeatClassList().isEmpty());
        assertTrue(g35.getSeatClassList().stream()
                .allMatch(each -> each.getQuantity() >= 0 && each.getPrice().compareTo(BigDecimal.ZERO) > 0));
        assertTrue(response.getSeatClassTypeList().contains(2));
        assertTrue(response.getDepartureStationList().contains("北京南"));

        List<String> departureTimes = response.getTrainList().stream().map(TicketListDTO::getDepartureTime).toList();
        List<String> sortedTimes = new ArrayList<>(departureTimes);
        Collections.sort(sortedTimes);
        assertEquals(sortedTimes, departureTimes);
    }

    @Test
    void ticketQuery_noTrainForCityPair_returnsEmptyList() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation("NGH");
        requestParam.setToStation("VNP");
        requestParam.setDepartureDate(tomorrow());

        TicketPageQueryRespDTO response = ticketService.pageListTicketQuery(requestParam);
        assertTrue(response.getTrainList().isEmpty());
        assertTrue(response.getDepartureStationList().isEmpty());
    }

    @Test
    void ticketQuery_missingParam_throws() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setToStation("NKH");
        requestParam.setDepartureDate(tomorrow());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ticketService.pageListTicketQuery(requestParam));
        assertEquals("出发地不能为空", exception.getMessage());
    }

    @Test
    void ticketQuery_pastDate_throws() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation("VNP");
        requestParam.setToStation("NKH");
        requestParam.setDepartureDate(Date.from(LocalDate.now().minusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ticketService.pageListTicketQuery(requestParam));
        assertEquals("出发日期不能小于当前日期", exception.getMessage());
    }

    @Test
    void ticketQuery_sameStation_throws() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation("VNP");
        requestParam.setToStation("VNP");
        requestParam.setDepartureDate(tomorrow());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ticketService.pageListTicketQuery(requestParam));
        assertEquals("出发地和目的地不能相同", exception.getMessage());
    }

    @Test
    void ticketQuery_stationNotExist_throws() {
        TicketPageQueryReqDTO requestParam = new TicketPageQueryReqDTO();
        requestParam.setFromStation("ZZZ");
        requestParam.setToStation("NKH");
        requestParam.setDepartureDate(tomorrow());
        ServiceException exception = assertThrows(ServiceException.class,
                () -> ticketService.pageListTicketQuery(requestParam));
        assertEquals("出发地或目的地不存在", exception.getMessage());
    }

    private Date tomorrow() {
        return Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}

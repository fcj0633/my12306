package edu.swu.fcj.my12306.biz.ticketservice.common.constant;

/**
 * 票务服务 Redis Key 常量（my12306 命名空间）
 */
public final class RedisKeyConstant {

    /**
     * 车次基本信息，Key 前缀 + 车次ID
     */
    public static final String TRAIN_INFO = "my12306-ticket-service:train_info:";

    /**
     * 车站编码 → 城市名映射（用于把乘车编码翻译成城市）
     */
    public static final String REGION_TRAIN_STATION_MAPPING = "my12306-ticket-service:region_train_station_mapping";

    /**
     * 车站编码映射装载锁
     */
    public static final String LOCK_REGION_TRAIN_STATION_MAPPING = "my12306-ticket-service:lock:region_train_station_mapping";

    /**
     * 城市对车次列表，Key 模板 + 出发城市_到达城市
     */
    public static final String REGION_TRAIN_STATION = "my12306-ticket-service:region_train_station:%s_%s";

    /**
     * 城市对车次列表装载锁
     */
    public static final String LOCK_REGION_TRAIN_STATION = "my12306-ticket-service:lock:region_train_station";

    /**
     * 区间票价，Key 模板 + 车次ID_出发站名_到达站名
     */
    public static final String TRAIN_STATION_PRICE = "my12306-ticket-service:train_station_price:%s_%s_%s";

    /**
     * 地区/车站查询，Key 前缀 + 关键词或查询类型
     */
    public static final String REGION_STATION = "my12306-ticket-service:region-station:";

    /**
     * 区间余票，Key 前缀 + 车次ID_出发站名_到达站名（hash：席别 → 余票数）
     */
    public static final String TRAIN_STATION_REMAINING_TICKET = "my12306-ticket-service:train_station_remaining_ticket:";

    /**
     * 区间余票准入令牌桶，Key 前缀 + 车次ID_出发站名_到达站名（hash：席别 → 令牌数）
     */
    public static final String TRAIN_STATION_TOKEN_BUCKET = "my12306-ticket-service:train_station_token_bucket:";

    /**
     * 全量车站缓存
     */
    public static final String STATION_ALL = "my12306-ticket-service:all_station";

    /**
     * 地区 + 车站编码全集（hash：编码 → 名称），用于出发地/目的地存在性校验
     */
    public static final String QUERY_ALL_REGION_LIST = "my12306-ticket-service:query_all_region_list";

    /**
     * 编码全集装载锁
     */
    public static final String LOCK_QUERY_ALL_REGION_LIST = "my12306-ticket-service:lock:query_all_region_list";

    /**
     * 地区/车站列表装载锁，Key 模板 + 查询参数
     */
    public static final String LOCK_QUERY_REGION_STATION_LIST = "my12306-ticket-service:lock:query_region_station_list_%s";

    /**
     * 余票装载锁，Key 模板 + 车次ID_出发站名_到达站名
     */
    public static final String LOCK_SAFE_LOAD_SEAT_MARGIN_GET = "my12306-ticket-service:lock:safe_load_seat_margin_%s";

    /**
     * 令牌桶装载锁，Key 模板 + 车次ID_出发站名_到达站名
     */
    public static final String LOCK_TRAIN_STATION_TOKEN_BUCKET = "my12306-ticket-service:lock:token_bucket_load:%s";

    /**
     * 用户购票防重复提交锁，Key 模板 + 用户名_车次ID
     */
    public static final String LOCK_PURCHASE_TICKETS_USER = "my12306-ticket-service:lock:purchase_tickets_user_%s_%s";

    /**
     * 车次席别购票锁，Key 模板 + 车次ID_席别
     */
    public static final String LOCK_PURCHASE_TICKETS_SEAT_TYPE = "my12306-ticket-service:lock:purchase_tickets_%s_%s";

    /** Shared by fast and cross-carriage reservation paths; intentionally excludes OD and instance. */
    public static final String LOCK_PURCHASE_TICKETS_CARRIAGE = "my12306-ticket-service:lock:purchase_tickets_carriage_%s_%s_%s";

    /**
     * P2-4 定时任务锁：孤儿车票恢复。无占位符 —— 全集群同一把锁，
     * 保证同一轮扫描只有一个实例执行。命名沿用本项目 my12306-&lt;服务&gt;:lock: 的既有约定。
     */
    public static final String LOCK_JOB_TICKET_ORPHAN_RECOVERY = "my12306-ticket-service:lock:job:ticket-orphan-recovery";

    private RedisKeyConstant() {
    }
}

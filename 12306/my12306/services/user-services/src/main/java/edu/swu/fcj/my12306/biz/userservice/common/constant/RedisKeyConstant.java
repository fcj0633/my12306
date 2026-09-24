package edu.swu.fcj.my12306.biz.userservice.common.constant;

/**
 * Redis Key 常量（my12306 命名空间）
 */
public final class RedisKeyConstant {

    /** 注册分布式锁前缀：lock:user-register:{username} */
    public static final String LOCK_USER_REGISTER = "my12306-user-service:lock:user-register:";

    /** 注销分布式锁前缀：user-deletion:{username} */
    public static final String USER_DELETION = "my12306-user-service:user-deletion:";

    /** 已注销可复用用户名集合前缀：user-reuse:{hash(username)%1024} */
    public static final String USER_REGISTER_REUSE_SHARDING = "my12306-user-service:user-reuse:";

    /** 乘车人列表缓存前缀：user-passenger-list:{username} */
    public static final String USER_PASSENGER_LIST = "my12306-user-service:user-passenger-list:";

    /** 乘车人写操作（新增/修改/移除）分布式锁前缀：lock:user-passenger:{username} */
    public static final String LOCK_USER_PASSENGER_ALTER = "my12306-user-service:lock:user-passenger:";
}

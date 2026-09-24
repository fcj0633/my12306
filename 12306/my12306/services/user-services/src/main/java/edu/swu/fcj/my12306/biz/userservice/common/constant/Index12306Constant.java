package edu.swu.fcj.my12306.biz.userservice.common.constant;

/**
 * 通用常量
 */
public final class Index12306Constant {

    /** 用户名复用集合分片数：按 username 哈希取模分散，防止 Redis 大 Key */
    public static final int USER_REGISTER_REUSE_SHARDING_COUNT = 1024;
}

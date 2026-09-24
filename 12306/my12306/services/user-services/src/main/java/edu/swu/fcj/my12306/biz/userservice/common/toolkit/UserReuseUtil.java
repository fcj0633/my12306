package edu.swu.fcj.my12306.biz.userservice.common.toolkit;

import edu.swu.fcj.my12306.biz.userservice.common.constant.Index12306Constant;

/**
 * 用户名复用工具：计算复用集合分片位置
 */
public final class UserReuseUtil {

    /**
     * 按 username 哈希取模，得到 user-reuse 集合的分片下标（0~1023）
     */
    public static int hashShardingIdx(String username) {
        return Math.abs(username.hashCode() % Index12306Constant.USER_REGISTER_REUSE_SHARDING_COUNT);
    }
}

package edu.swu.fcj.my12306.biz.userservice.common.constant;

/**
 * 责任链 mark 常量
 */
public enum UserChainMarkEnum {

    /** 注册校验链：参数非空 → 用户名可用性 → 证件黑名单 */
    USER_REGISTER_FILTER
}

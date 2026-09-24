package edu.swu.fcj.my12306.biz.userservice.common.chain;

/**
 * 责任链处理器抽象：实现类声明 mark（链分组）与 order（执行顺序）
 */
public interface AbstractChainHandler<T> {

    /** 所属责任链标识（如 USER_REGISTER_FILTER） */
    String mark();

    /** 执行顺序（升序） */
    int order();

    /** 链上业务处理；抛异常即终止后续节点 */
    void handler(T requestParam);
}

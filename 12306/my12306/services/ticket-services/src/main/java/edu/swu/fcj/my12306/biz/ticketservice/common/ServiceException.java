package edu.swu.fcj.my12306.biz.ticketservice.common;

/**
 * 业务异常：统一由 GlobalExceptionHandler 转成 Result(code=500, message)
 */
public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }
}

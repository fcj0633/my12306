package edu.swu.fcj.my12306.biz.orderservice.common;

public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }
}

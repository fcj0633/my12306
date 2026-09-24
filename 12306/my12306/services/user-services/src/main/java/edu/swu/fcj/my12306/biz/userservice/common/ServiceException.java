package edu.swu.fcj.my12306.biz.userservice.common;

public class ServiceException extends RuntimeException{
    public ServiceException(String message) {
        super(message);
    }
}

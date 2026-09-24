package edu.swu.fcj.my12306.biz.payservice.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：所有异常在这里转成统一的 Result 结构
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public Result<Void> serviceException(ServiceException e) {
        return new Result<Void>().setCode("500").setMessage(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> messageNotReadableException(HttpMessageNotReadableException e) {
        return new Result<Void>().setCode("400").setMessage("请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> exception(Exception e) {
        log.error("系统异常", e);
        return new Result<Void>().setCode("500").setMessage("系统异常");
    }
}

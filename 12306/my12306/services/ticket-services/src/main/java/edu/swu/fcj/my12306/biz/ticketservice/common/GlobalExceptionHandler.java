package edu.swu.fcj.my12306.biz.ticketservice.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：所有异常在这里转成统一的 Result 结构
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：如“出发地不能为空”“出发地或目的地不存在”
     */
    @ExceptionHandler(ServiceException.class)
    public Result<Void> serviceException(ServiceException e) {
        return new Result<Void>().setCode("500").setMessage(e.getMessage());
    }

    /**
     * 参数校验失败（@Valid）：取第一个字段错误信息返回给前端
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> validException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse("参数校验失败");
        return new Result<Void>().setCode("400").setMessage(message);
    }

    /**
     * 请求体不是合法 JSON（或字段类型不匹配）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> messageNotReadableException(HttpMessageNotReadableException e) {
        return new Result<Void>().setCode("400").setMessage("请求体格式错误");
    }

    /**
     * 兜底：未知异常统一返回“系统异常”，堆栈只进日志不外泄
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> exception(Exception e) {
        log.error("系统异常", e);
        return new Result<Void>().setCode("500").setMessage("系统异常");
    }
}

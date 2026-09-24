package edu.swu.fcj.my12306.biz.payservice.common;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Result<T> {
    public static final String SUCCESS_CODE = "0";
    private String code;
    private String message;
    private T data;

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}

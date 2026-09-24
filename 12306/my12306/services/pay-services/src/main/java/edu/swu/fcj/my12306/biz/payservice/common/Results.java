package edu.swu.fcj.my12306.biz.payservice.common;

public final class Results {

    public static Result<Void> success() {
        return new Result<Void>().setCode(Result.SUCCESS_CODE);
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>().setCode(Result.SUCCESS_CODE).setData(data);
    }

    private Results() {
    }
}

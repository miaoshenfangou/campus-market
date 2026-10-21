package com.lanou.csh.util;
import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int SUCCESS_CODE = 200;
    private static final int FAILURE_CODE = 201;
    private int code;
    private String message;
    private T data;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // 成功重载
    public static <T> Result<T> success() {
        return success(null);
    }
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, "操作成功", data);
    }
    public static <T> Result<T> success(T data, String msg) {
        return new Result<>(SUCCESS_CODE, msg, data);
    }

    // 失败重载
    public static <T> Result<T> failure() {
        return failure("操作失败");
    }
    public static <T> Result<T> failure(String msg) {
        return new Result<>(FAILURE_CODE, msg, null);
    }
    public static <T> Result<T> failure(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}
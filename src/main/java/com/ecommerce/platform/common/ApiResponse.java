package com.ecommerce.platform.common;

/**
 * 统一 API 响应格式。
 *
 * @param code 状态码（200 表示成功）
 * @param message 提示信息
 * @param data 响应主体数据
 * @param timestamp 时间戳
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        long timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(200, message, data, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis());
    }
}

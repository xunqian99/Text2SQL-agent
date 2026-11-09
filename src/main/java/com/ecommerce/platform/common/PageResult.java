package com.ecommerce.platform.common;

import java.util.List;

/**
 * 通用分页返回结构。
 *
 * @param <T> 数据实体类型
 * @param list 当前页数据列表
 * @param total 总记录数
 * @param page 当前页码（从 1 开始）
 * @param pageSize 每页条数
 * @param totalPages 总页数
 */
public record PageResult<T>(
        List<T> list,
        long total,
        int page,
        int pageSize,
        int totalPages
) {
    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        int totalPages = pageSize <= 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResult<>(list, total, page, pageSize, totalPages);
    }
}

package com.text2sql.agent.execution;

import java.util.List;

/**
 * 一次查询的结果集。
 *
 * <p>刻意用 {@code List<List<String>>} 而不是实体类/Map：Text2SQL 的结果
 * 形状由用户问题决定，编译期无法知道列名。用「列名 + 行」这种最原始的形式，
 * 才能对任意 SQL 通用。
 *
 * <p>把值统一转成 String 也是刻意的：执行准确率的比对逻辑（阶段 1 的
 * 评估器）需要一套统一的规范化规则——浮点四舍五入、NULL 哨兵、时间格式。
 * 如果这里保留 Object，规范化就得在多个类型分支上重复实现，
 * 而「数字 1.0 和 1.0000001 算不算相同」这类判断最容易出现不一致。
 *
 * @param columns   列名，按 SELECT 顺序
 * @param rows      数据行
 * @param truncated 是否因为行数上限被截断
 * @param elapsedMs 数据库执行耗时
 */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated, long elapsedMs) {

    public int rowCount() {
        return rows.size();
    }
}

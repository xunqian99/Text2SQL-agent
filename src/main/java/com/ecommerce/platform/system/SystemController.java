package com.ecommerce.platform.system;

import com.ecommerce.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 传统管理系统必备的系统权限与操作日志接口。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        return ApiResponse.ok(List.of(
                Map.of("id", 1, "username", "admin", "nickname", "系统超级管理员", "role", "SUPER_ADMIN", "department", "研发技术部", "status", "NORMAL", "createdAt", "2026-01-01 09:00:00"),
                Map.of("id", 2, "username", "operator_zhang", "nickname", "张运营", "role", "OPERATOR", "department", "电商运营中台", "status", "NORMAL", "createdAt", "2026-02-15 14:20:00"),
                Map.of("id", 3, "username", "analyst_li", "nickname", "李分析师", "role", "DATA_ANALYST", "department", "商业智能分析部", "status", "NORMAL", "createdAt", "2026-03-10 11:15:00"),
                Map.of("id", 4, "username", "auditor_wang", "nickname", "王审计", "role", "AUDITOR", "department", "安全风控中心", "status", "DISABLED", "createdAt", "2026-04-05 16:30:00")
        ));
    }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> listLogs() {
        LocalDateTime now = LocalDateTime.now();
        return ApiResponse.ok(List.of(
                Map.of("id", 101, "operator", "admin", "action", "新增商品", "detail", "新增商品ID: 00066f42a...，类目: telefonia", "ip", "127.0.0.1", "status", "SUCCESS", "time", now.minusMinutes(3).format(FMT)),
                Map.of("id", 102, "operator", "operator_zhang", "action", "修改订单状态", "detail", "流转订单 ord-87140e... 状态为 delivered", "ip", "192.168.1.105", "status", "SUCCESS", "time", now.minusMinutes(12).format(FMT)),
                Map.of("id", 103, "operator", "analyst_li", "action", "AI 问数分析", "detail", "提问: '2018年销售额最高的前5个品类'，生成只读 SQL 并返回", "ip", "192.168.1.120", "status", "SUCCESS", "time", now.minusMinutes(25).format(FMT)),
                Map.of("id", 104, "operator", "admin", "action", "系统安全审计", "detail", "触发 AST 安全规则拦截恶意写操作 DROP TABLE orders", "ip", "127.0.0.1", "status", "INTERCEPTED", "time", now.minusHours(1).format(FMT)),
                Map.of("id", 105, "operator", "operator_zhang", "action", "导出订单报表", "detail", "导出 2018Q2 电商完成订单 CSV 明细", "ip", "192.168.1.105", "status", "SUCCESS", "time", now.minusHours(2).format(FMT))
        ));
    }
}

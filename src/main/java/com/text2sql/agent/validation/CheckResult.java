package com.text2sql.agent.validation;

import java.util.List;

/**
 * 启发式结果校验的判定产物。
 *
 * @param suspicious 是否被判定为可疑异常结果
 * @param reason 可读的原因说明（若可疑）
 * @param rule 命中的规则名称
 */
public record CheckResult(boolean suspicious, String reason, String rule) {

    public static final CheckResult NORMAL = new CheckResult(false, null, null);

    public static CheckResult suspicious(String rule, String reason) {
        return new CheckResult(true, reason, rule);
    }
}

package com.text2sql.agent.validation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 在模型调用前拦截明确的写操作意图。
 *
 * <p>SQL AST 校验仍然是最后一道硬护栏，但它只能看到模型生成的 SQL。
 * 如果用户输入“删除 orders 表中所有数据”，模型把它误改写成 SELECT，
 * AST 校验会认为这是一条合法查询，页面就会错误地显示“分析完成”。
 * 这里把自然语言本身也当成不可信输入，先拒绝明确的写操作命令，避免
 * 通过“把删除请求改写成查询”绕过只读产品契约。
 */
public final class ReadOnlyIntentGuard {

    private static final String MESSAGE =
            "[READ_ONLY_INTENT] 只读问数系统不执行删除、修改、写入或结构变更操作";

    private static final Pattern SQL_WRITE = Pattern.compile(
            "(?is)\\b(?:delete\\s+from|drop\\s+(?:table|schema|database)|"
                    + "truncate(?:\\s+table)?|update\\s+[^\\s]+\\s+set|insert\\s+into|"
                    + "alter\\s+(?:table|schema|database)|create\\s+(?:table|schema|database)|"
                    + "merge\\s+into)\\b");

    private static final Pattern CHINESE_COMMAND = Pattern.compile(
            "^\\s*(?:(?:请|帮我|麻烦|我要|执行)\\s*)?"
                    + "(?:删除|删掉|清空|修改|更新|写入|新增|插入|覆盖|重置|销毁|建表|删表)"
                    + "(?=\\s|[：:，,。.!！?？]|订单|商品|客户|表|数据|记录|全部|所有)");

    private static final Pattern CHINESE_OBJECT_COMMAND = Pattern.compile(
            "^\\s*(?:(?:请|帮我|麻烦|我要|执行)\\s*)?(?:把|将)\\s+[^。！？\\n]{0,40}"
                    + "(?:删除|删掉|清空|修改|更新|写入|新增|插入|覆盖|重置|销毁|建表|删表)"
                    + "(?=\\s|$|[。！？])");

    private ReadOnlyIntentGuard() {
    }

    /** 返回阻断原因；没有明确写操作意图时返回空。 */
    public static Optional<String> violation(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String normalized = question.trim();
        if (SQL_WRITE.matcher(normalized).find()
                || CHINESE_COMMAND.matcher(normalized).find()
                || CHINESE_OBJECT_COMMAND.matcher(normalized).find()) {
            return Optional.of(MESSAGE);
        }
        return Optional.empty();
    }
}

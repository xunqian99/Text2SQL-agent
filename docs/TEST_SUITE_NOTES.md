# 测试套件实测记录（2026-10-31）

> 目的：验证简历/README 中"248 项全绿"这一宣称是否可复现。
> 结论先行：**248 项全部通过，但需要一个环境参数；另有 1 个边界条件 flake。**

## 一、实测环境

- JDK：OpenJDK 21.0.11 (Corretto)
- Maven：3.9.4
- 命令：`mvn -o test`

## 二、结果

### 1. 不带参数（受限环境）

```
Tests run: 248, Failures: 1, Errors: 59, Skipped: 0
BUILD FAILURE
```

- **59 个 Error**：全部是
  `IllegalStateException: Could not initialize plugin: interface org.mockito.plugins.MockMaker`。
  集中在使用 `Mockito.mock()` 的测试（`LexicalSchemaRetrieverTest`、`ValueRetrieverTest`、
  `AgentLoopTest`、`ToolExecutorTest`、`ColumnPrunerTest` 等）。
  **根因**：Mockito 5 的内联 mockmaker 需要 byte-buddy-agent **自附加**到 JVM，
  受限环境/沙箱默认禁止。**不是项目缺陷**。
- **1 个 Failure**：`SessionStoreTest.testCleanExpired`（见第三节）。

### 2. 带自附加参数（推荐）

```
mvn -o test -DargLine="-Djdk.attach.allowAttachSelf=true"
```

```
Tests run: 248, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**248 项全绿成立。** 单独跑 `SessionStoreTest` 也是 5/5 通过。

### 3. 对抗攻击用例单独确认

`AdversarialSqlTest`：**Tests run: 44, Failures: 0, Errors: 0** —— 与"44 条对抗攻击 100% 拦截"一致。

## 三、唯一偶发用例：`SessionStoreTest.testCleanExpired`

### 现象

全量跑时偶发 `expected: 1 but was: 0`；单独跑或带参数跑则稳定通过。

### 根因（边界条件 flake）

- `ConversationSession` 构造：`lastActiveAt = Instant.now()`（`ConversationSession.java:32-33`）
- `SessionStore.cleanExpired(timeout)`：`threshold = Instant.now().minus(timeout)`（`SessionStore.java:112`）
- 判据：`session.lastActiveAt().isBefore(threshold)`（`SessionStore.java:116`）
- 测试用 `Duration.ofMillis(0)` 模拟"零超时"，于是 `threshold = Instant.now()`。

当**创建会话**与**调用 cleanExpired** 两次 `Instant.now()` 落在**同一时钟刻度**时，
`lastActiveAt == threshold`，而 `isBefore` 是**严格小于** → 判为"未超时" → 淘汰 0 条 → 断言失败。

这是典型的"边界取等"flaky test：测试语义上"零超时应立即淘汰"是对的，
但判据用了严格小于，恰好把"等于"这个合法边界排除掉了。

### 修法（二选一，尚未实施，待确认）

1. 生产逻辑改成非严格比较：`!session.lastActiveAt().isAfter(threshold)`
   （语义上"零超时=立即过期"，等于也应淘汰）。改动 1 行，风险低。
2. 只改测试：先 `Thread.sleep(1)` 再清理，或断言改为 `evicted >= 0`。治标不治本，不推荐。

## 四、面试答辩口径

- 被问"测试全绿吗"：**248 项通过**。若在受限环境跑，需加
  `-DargLine="-Djdk.attach.allowAttachSelf=true"`，否则 Mockito 内联 mockmaker
  无法自附加会报 59 个环境错误——这是环境限制，不是代码问题。
- 主动提 `testCleanExpired` 的边界 flake 并说明根因与修法，是**加分项**（体现你知道
  测试的边界，而不是只会喊"全绿"）。

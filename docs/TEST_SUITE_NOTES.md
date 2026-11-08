# 测试套件实测记录

> 目的：验证简历/README 里"测试全绿"这一宣称是否可复现。
> 结论：**`mvn test` 裸跑即可全绿**（250+ 项）。此前记录的两个问题都已修掉，见文末「已修复」。

## 2026-11-08 重测

`mvn clean test`：**Tests run: 270, Failures: 0, Errors: 0, Skipped: 0**。
本次数据库在线，9 条真库集成测试实际执行（不是跳过）；`AdversarialSqlTest` 47 条全绿。

dry-run 的 trusted gold SQL 修复后复跑 `mvn test`：**270/0/0/0**，无回归。

## 一、实测环境

- JDK：OpenJDK 21.0.11 (Corretto)
- Maven：3.9.4
- 命令：`mvn test`

## 二、当前结果

```
mvn test
Tests run: 270, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

分层确认：

- `AdversarialSqlTest`：**47 条对抗用例全部拦截**（含 schema 限定名、带引号函数名、
  CTE/子查询内隐藏危险函数等绕过手法）。
- `SqlValidatorTest`：19 项。
- `SessionStoreTest`：5 项，连续单独运行 10 次均通过（不再有偶发失败）。

## 三、曾经的两个问题（均已修复）

### 1. `mvn test` 裸跑会失败（Mockito 自附加）

**现象**：`Tests run: 248, Failures: 1, Errors: 59`，59 个 Error 全是
`Could not initialize plugin: interface org.mockito.plugins.MockMaker`。

**根因**：Mockito 5 默认用 inline mock maker，需要把 byte-buddy agent 自附加到当前 JVM；
JDK 9+ 默认禁止自附加（`jdk.attach.allowAttachSelf=false`）。

**修法**：在 `pom.xml` 的 surefire 配置里固化
`<argLine>-Djdk.attach.allowAttachSelf=true</argLine>`。
这样默认路径就是正确的路径——不需要每个人记住命令行参数，也不会有
"换台机器就跑不起来"的环境知识。

### 2. `SessionStoreTest.testCleanExpired` 偶发失败

**现象**：全量跑时偶发 `expected: 1 but was: 0`。

**根因**：`ConversationSession` 构造时 `lastActiveAt = Instant.now()`；
`cleanExpired(Duration.ZERO)` 算出的 `threshold = Instant.now()`；
判据是 `lastActiveAt().isBefore(threshold)`，**严格小于**。
当两次 `Instant.now()` 落在同一时钟刻度时，`lastActiveAt == threshold`，
被判为"未超时"，淘汰 0 条。

**修法**：判据改为 `!lastActiveAt().isAfter(threshold)`（即 `<=`）。
语义上"零超时 = 立即过期"本来就应该包含相等的情况，这是**生产逻辑的边界 bug**，
不是单纯把测试改绿。

## 四、面试答辩口径

- 被问"测试全绿吗"：**270 项全部通过，`mvn test` 一条命令即可复现**。
- 被问"测试里有没有坑"：可以主动讲上面两个——一个是**把环境知识固化进构建配置**，
  一个是**用严格不等号写超时判据导致的边界 flake**。后者比"全绿"更能体现
  你知道测试的边界在哪。

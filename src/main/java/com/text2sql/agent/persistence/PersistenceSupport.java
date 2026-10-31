package com.text2sql.agent.persistence;

import com.text2sql.agent.config.AgentProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持久化连接的统一构建入口。
 *
 * <p>缓存、调用日志、会话落库连的是同一个写账号，连接的建法只该有一份。
 * 两处各写一遍的代价不是重复十几行代码，而是**将来只改了一处**——
 * 比如换密码、加连接参数、改超时，漏改的那个会静默降级成「功能没了但不报错」。
 *
 * <p><b>为什么返回 null 而不是抛异常</b>：调用方（缓存、观测、会话）都是旁路组件，
 * 连不上时的正确行为是降级，不是让应用起不来。
 *
 * <p><b>为什么不用 Spring 的 DataSource Bean</b>：容器里出现第二个 DataSource
 * 会让自动配置的 {@code @ConditionalOnSingleCandidate} 失效，主业务数据源反而
 * 可能装配不上。这是多数据源最常见的翻车点，所以按需构造、不进容器。
 *
 * <p><b>为什么要池化，以及为什么只建一个池</b>
 *
 * <p>早先这里用的是 {@code DriverManagerDataSource}，它**不做池化**：
 * 每次 {@code update}/{@code query} 都会新建一条物理连接（TCP 握手 + 认证）。
 * 而持久化点在请求链路上（缓存 L2 读、调用日志落库、会话结束落库），
 * 意味着每次问数都要额外付一次建连成本；并发稍高还会顶到 PostgreSQL 的
 * {@code max_connections}——业务池那边特意只开了 5 个连接，结果被旁路连接反超，
 * 这个取舍就白做了。
 *
 * <p>改成 Hikari 小池，并且**全进程共用同一个池**：三个调用方各建一个池的话，
 * 每个池都要保活自己的最小空闲连接，等于又变相放大了连接数。
 * 池按 {@code url+username} 做键复用，换配置会重建。
 */
public final class PersistenceSupport {

    private static final Logger log = LoggerFactory.getLogger(PersistenceSupport.class);

    /** 持久化是旁路，2~4 个连接足够：写缓存、写日志、会话落库都是低频小事务。 */
    private static final int MAX_POOL_SIZE = 4;

    private static volatile JdbcTemplate sharedTemplate;
    private static volatile String sharedKey;

    private PersistenceSupport() {
    }

    /**
     * 按配置取得一个只用于持久化的 JdbcTemplate（全进程共享）。
     *
     * @param who 调用方名字，只用于日志，出问题时能立刻看出是谁连不上
     * @return 未启用或连不上时返回 null，调用方应降级而不是失败
     */
    public static JdbcTemplate jdbcTemplateOrNull(AgentProperties properties, String who) {
        var cfg = properties.getPersistence();
        if (!cfg.isEnabled()) {
            return null;
        }

        String key = cfg.getUrl() + "|" + cfg.getUsername();
        JdbcTemplate fast = sharedTemplate;
        if (fast != null && key.equals(sharedKey)) {
            return fast;
        }

        synchronized (PersistenceSupport.class) {
            if (sharedTemplate != null && key.equals(sharedKey)) {
                return sharedTemplate;
            }
            HikariDataSource dataSource = null;
            try {
                dataSource = new HikariDataSource();
                dataSource.setJdbcUrl(cfg.getUrl());
                dataSource.setUsername(cfg.getUsername());
                dataSource.setPassword(cfg.getPassword());
                dataSource.setMaximumPoolSize(MAX_POOL_SIZE);
                dataSource.setMinimumIdle(1);
                dataSource.setConnectionTimeout(5000);
                dataSource.setPoolName("text2sql-persistence");

                JdbcTemplate template = new JdbcTemplate(dataSource);
                // 立刻探一次连通性：不探的话第一个真实请求才会撞上失败，
                // 那时调用方已进入业务逻辑，降级路径更难验证。
                template.queryForObject("SELECT 1", Integer.class);

                sharedTemplate = template;
                sharedKey = key;
                log.info("{} 的持久化连接池已建立：{}（用户 {}，池上限 {}）",
                        who, cfg.getUrl(), cfg.getUsername(), MAX_POOL_SIZE);
                return template;
            } catch (Exception e) {
                // 建池失败要把已经创建的池关掉，否则它会占着后台线程与连接。
                if (dataSource != null) {
                    try {
                        dataSource.close();
                    } catch (Exception ignored) {
                        // 关闭失败无需再处理：进程仍会继续降级运行
                    }
                }
                log.warn("{} 的持久化连接不可用，降级运行：{}", who, e.getMessage());
                return null;
            }
        }
    }
}

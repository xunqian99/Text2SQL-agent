package com.text2sql.agent.persistence;

import com.text2sql.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 持久化连接的统一构建入口。
 *
 * <p>缓存和调用日志连的是同一个写账号，连接的建法只该有一份。
 * 两处各写一遍的代价不是重复十几行代码，而是**将来只改了一处**——
 * 比如换密码、加连接参数、改超时，漏改的那个会静默降级成「功能没了但不报错」。
 *
 * <p><b>为什么返回 null 而不是抛异常</b>：调用方（缓存、观测）都是旁路组件，
 * 连不上时的正确行为是降级，不是让应用起不来。
 *
 * <p><b>为什么不用 Spring 的 DataSource Bean</b>：容器里出现第二个 DataSource
 * 会让自动配置的 {@code @ConditionalOnSingleCandidate} 失效，主业务数据源反而
 * 可能装配不上。这是多数据源最常见的翻车点，所以按需构造。
 */
public final class PersistenceSupport {

    private static final Logger log = LoggerFactory.getLogger(PersistenceSupport.class);

    private PersistenceSupport() {
    }

    /**
     * 按配置建立一个只用于持久化的 JdbcTemplate。
     *
     * @param who 调用方名字，只用于日志，出问题时能立刻看出是谁连不上
     * @return 未启用或连不上时返回 null，调用方应降级而不是失败
     */
    public static JdbcTemplate jdbcTemplateOrNull(AgentProperties properties, String who) {
        var cfg = properties.getPersistence();
        if (!cfg.isEnabled()) {
            return null;
        }
        try {
            var dataSource = new DriverManagerDataSource(cfg.getUrl(), cfg.getUsername(), cfg.getPassword());
            JdbcTemplate template = new JdbcTemplate(dataSource);
            // 立刻探一次连通性：不探的话第一个真实请求才会撞上失败，
            // 那时调用方已进入业务逻辑，降级路径更难验证。
            template.queryForObject("SELECT 1", Integer.class);
            log.info("{} 的持久化连接已建立：{}（用户 {}）", who, cfg.getUrl(), cfg.getUsername());
            return template;
        } catch (Exception e) {
            log.warn("{} 的持久化连接不可用，降级运行：{}", who, e.getMessage());
            return null;
        }
    }
}

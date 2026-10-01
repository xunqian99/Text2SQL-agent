package com.text2sql.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * 这个类本身没有任何业务逻辑，只是 Spring Boot 的启动开关。
 * 业务逻辑按 ROADMAP 3.2 的分层职责放在各自的包下：
 * api / orchestrator / retrieval / generation / validation / execution / semantic / observability
 */
@SpringBootApplication
public class Text2SqlAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(Text2SqlAgentApplication.class, args);
    }
}

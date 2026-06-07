package com.taverntales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 奇幻酒馆 — Tavern Tales 游戏服务器入口。
 *
 * 使用 Spring Boot 3.4 + WebFlux (Netty) + JPA + H2。
 * 设计为可水平扩展的无状态服务（状态在数据库），后续接入 PostgreSQL + Redis 集群。
 */
@SpringBootApplication
public class TavernTalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TavernTalesApplication.class, args);
    }
}

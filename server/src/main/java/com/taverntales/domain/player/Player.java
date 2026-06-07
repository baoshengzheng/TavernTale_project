package com.taverntales.domain.player;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 玩家实体。
 *
 * 记录玩家在游戏世界中的状态（位置、在线状态、最后活跃时间）。
 *
 * 为什么把位置存在 DB 而非内存？
 *  便于后续多服务器部署时，任意节点都能获取玩家位置。
 *  短期内（单机）用缓存优化，但数据结构以分布式为前提设计。
 */
@Entity
@Table(name = "players")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Player {

    @Id
    private String id;

    /** 玩家显示名称 */
    private String name;

    /** 当前所在房间 ID */
    private String currentRoomId;

    /**
     * 玩家在房间内的 x 坐标。
     * 坐标原点在房间左上角，x 向右增加。
     */
    private int x;

    /**
     * 玩家在房间内的 y 坐标。
     * y 向下增加（2D 俯视图标准坐标系）。
     */
    private int y;

    /** 是否在线（WebSocket 已连接） */
    private boolean online;

    /** 最后活跃时间，用于超时清理 */
    private LocalDateTime lastActiveAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.lastActiveAt = LocalDateTime.now();
    }
}

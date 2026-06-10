package com.taverntales.domain.npc;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * NPC 运行时可变状态 — 持久化到 H2 npc_states 表。
 *
 * 为什么字符串字段用 TEXT 列？
 * 和 Room.objectsJson 保持一致。emotion、relationships、memories
 * 都是 JSON 格式，存为 TEXT 避免 JPA 的关联表开销。
 * 后续切 PostgreSQL 时直接映射到 JSONB 类型。
 *
 * 为什么先塞一张表？
 * Iteration 1 数据量小。后续好感度和记忆字段膨胀时拆出独立表。
 */
@Entity
@Table(name = "npc_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NpcState {

    @Id
    @Column(name = "npc_id")
    private String npcId;

    /** 当前 X 坐标（运行时可变） */
    private int currentX;

    /** 当前 Y 坐标（运行时可变） */
    private int currentY;

    /**
     * 当前情绪，JSON 格式存储。
     * 例：{"joy":60,"sadness":10,"anger":5,"fear":0,"surprise":15,"disgust":0}
     */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String currentEmotionJson = "{}";

    /** 对话状态：FREE 或 TALKING */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private NpcTalkState currentState = NpcTalkState.FREE;

    /** 当前对话目标（playerId），FREE 状态时为 null */
    @Column(nullable = true)
    private String currentTalkTarget;

    /**
     * 玩家好感度，JSON 格式存储。
     * 例：{"player_001":{"score":65,"lastUpdated":"2026-06-10T12:00:00"}}
     */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String playerRelationshipsJson = "{}";

    /**
     * 玩家记忆，JSON 格式存储。
     * 例：{"player_001":[{"event":"...","importance":5,"time":"..."}]}
     */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String playerMemoriesJson = "{}";

    /** 最后活跃时间，用于超时清理 */
    private LocalDateTime lastActiveAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.lastActiveAt = LocalDateTime.now();
    }
}

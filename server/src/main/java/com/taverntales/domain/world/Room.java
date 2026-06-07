package com.taverntales.domain.world;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏房间/场景实体。
 *
 * 每个 Room 代表一个独立场景（酒馆大厅、地下室、后院等）。
 * MVP 阶段只有一个酒馆大厅，后续可扩展为多房间。
 *
 * 为什么 width/height 和坐标体系？
 *  2D 俯视图需要房间尺寸和物体位置。后续 Canvas 渲染时直接使用这些数值。
 */
@Entity
@Table(name = "rooms")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    private String id;

    /** 房间显示名称 */
    private String name;

    /** 房间描述，用于 AI 上下文和前端展示 */
    @Column(length = 500)
    private String description;

    /** 房间宽度（像素），对应 Canvas 坐标系 */
    private int width;

    /** 房间高度（像素） */
    private int height;

    /**
     * 房间内的物体/家具列表。
     * 用 JSON 存储而非关联表，避免 Iteration 0 引入过多实体关系。
     * 后续如果需要物体交互（移动、破坏），再拆分为独立实体。
     */
    @Column(columnDefinition = "TEXT")
    @Builder.Default
    private String objectsJson = "[]";

    /**
     * NPC 出生/默认位置（可选，如果 room 有默认 NPC 刷出点则使用）
     */
    private int defaultSpawnX;
    private int defaultSpawnY;
}

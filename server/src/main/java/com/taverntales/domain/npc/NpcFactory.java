package com.taverntales.domain.npc;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC 工厂 — 加载定义、合并状态、管理运行时。
 *
 * 为什么用 ConcurrentHashMap 做缓存？
 * 读多写少。读来自 Netty 事件循环（每个玩家独立线程），
 * 写只在启动时和对话状态变更时发生。ConcurrentHashMap 提供读的高并发。
 *
 * 为什么用独立 ObjectMapper？
 * NPC 配置 JSON 需要支持 // 注释（Jackson 默认不支持）。
 * WebSocket 消息的 ObjectMapper 不需要这个功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NpcFactory {

    private final NpcStateRepository stateRepository;
    private final ResourceLoader resourceLoader;

    /** NPC 配置 JSON 专用 ObjectMapper，开启注释支持 */
    private final ObjectMapper configMapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    /** npcId → NpcInstance 缓存 */
    private final ConcurrentHashMap<String, NpcInstance> npcCache = new ConcurrentHashMap<>();

    /** playerId → 当前在范围内的 NPC 集合 */
    private final ConcurrentHashMap<String, Set<String>> playerInRangeNpcs = new ConcurrentHashMap<>();

    /**
     * 启动时加载所有 NPC。
     * 扫描 resources/npcs/*.json → 反序列化 → 合并 H2 状态 → 缓存。
     */
    @PostConstruct
    @Transactional
    public void init() {
        try {
            Resource[] resources = ResourcePatternUtils
                    .getResourcePatternResolver(resourceLoader)
                    .getResources("classpath:npcs/*.json");

            for (Resource resource : resources) {
                loadNpcFromResource(resource);
            }
            log.info("NpcFactory 加载完成，共 {} 个 NPC", npcCache.size());
        } catch (IOException e) {
            log.error("扫描 NPC 配置目录失败", e);
        }
    }

    /** 从单个 JSON 文件加载 NPC 定义并合并状态 */
    private void loadNpcFromResource(Resource resource) throws IOException {
        NpcDefinition def = configMapper.readValue(resource.getInputStream(), NpcDefinition.class);

        NpcState state = stateRepository.findById(def.getId()).orElseGet(() -> createDefaultState(def));
        NpcInstance instance = new NpcInstance(def, state);
        npcCache.put(def.getId(), instance);

        log.info("加载 NPC: {} ({}) — 位置: ({}, {}), 触发半径: {}px",
                def.getName(), def.getId(), state.getCurrentX(), state.getCurrentY(),
                def.getTriggerRadius());
    }

    /** 为没有 H2 记录的 NPC 创建默认状态 */
    private NpcState createDefaultState(NpcDefinition def) {
        NpcState state = NpcState.builder()
                .npcId(def.getId())
                .currentX(def.getPosition().getX())
                .currentY(def.getPosition().getY())
                .currentEmotionJson("{\"joy\":50,\"sadness\":10,\"anger\":5,\"fear\":0,\"surprise\":10,\"disgust\":5}")
                .currentState(NpcTalkState.FREE)
                .playerRelationshipsJson("{}")
                .playerMemoriesJson("{}")
                .build();
        return stateRepository.save(state);
    }

    // ==================== 查询 ====================

    /** 获取单个 NPC */
    public NpcInstance getNpc(String npcId) {
        return npcCache.get(npcId);
    }

    /** 获取所有 NPC */
    public Collection<NpcInstance> getAllNpcs() {
        return npcCache.values();
    }

    /** 获取指定房间内的所有 NPC */
    public List<NpcInstance> getAllNpcsInRoom(String roomId) {
        return npcCache.values().stream()
                .filter(npc -> roomId.equals(npc.getRoomId()))
                .toList();
    }

    // ==================== 感知检测 ====================

    /**
     * 检测玩家与所有 NPC 的距离，返回进出范围的 NPC。
     *
     * 维护 playerInRangeNpcs 映射表，每次移动时比较新旧范围差异。
     */
    public ProximityResult checkProximity(String playerId, int px, int py) {
        Set<String> previousRange = playerInRangeNpcs.getOrDefault(playerId, Set.of());
        Set<String> currentRange = new HashSet<>();

        for (NpcInstance npc : npcCache.values()) {
            double dx = px - npc.getCurrentX();
            double dy = py - npc.getCurrentY();
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance <= npc.getTriggerRadius()) {
                currentRange.add(npc.getId());
            }
        }

        // 计算差异
        List<NpcInstance> entered = new ArrayList<>();
        List<NpcInstance> exited = new ArrayList<>();

        for (String npcId : currentRange) {
            if (!previousRange.contains(npcId)) {
                entered.add(npcCache.get(npcId));
            }
        }
        for (String npcId : previousRange) {
            if (!currentRange.contains(npcId)) {
                NpcInstance npc = npcCache.get(npcId);
                if (npc != null) exited.add(npc);
            }
        }

        // 更新映射表
        if (currentRange.isEmpty()) {
            playerInRangeNpcs.remove(playerId);
        } else {
            playerInRangeNpcs.put(playerId, currentRange);
        }

        return new ProximityResult(entered, exited);
    }

    /** 判断玩家是否在指定 NPC 范围内 */
    public boolean isPlayerInRange(String playerId, String npcId) {
        Set<String> range = playerInRangeNpcs.get(playerId);
        return range != null && range.contains(npcId);
    }

    // ==================== 对话锁定 ====================

    /**
     * 尝试锁定 NPC 给指定玩家。
     * 只有 FREE 状态的 NPC 可以被锁定。
     *
     * 为什么用 synchronized？
     * 两个玩家可能同时向同一 NPC 发 PLAYER_TALK，Netty 在不同线程处理。
     * 不加锁时两线程可能同时读到 FREE 状态，都认为自己锁定成功。
     * 对话锁定是低频操作（每秒最多几次），synchronized 的阻塞开销可忽略。
     *
     * @return true=锁定成功，false=NPC 正忙
     */
    @Transactional
    public synchronized boolean tryLockNpc(String npcId, String playerId) {
        NpcInstance instance = npcCache.get(npcId);
        if (instance == null) return false;

        NpcState state = instance.getState();
        if (state.getCurrentState() != NpcTalkState.FREE) return false;

        state.setCurrentState(NpcTalkState.TALKING);
        state.setCurrentTalkTarget(playerId);
        NpcState saved = stateRepository.save(state);
        instance.setState(saved);

        log.info("NPC [{}] 已被玩家 [{}] 锁定对话", npcId, playerId);
        return true;
    }

    /**
     * 解锁 NPC。
     */
    @Transactional
    public void unlockNpc(String npcId) {
        NpcInstance instance = npcCache.get(npcId);
        if (instance == null) return;

        NpcState state = instance.getState();
        state.setCurrentState(NpcTalkState.FREE);
        state.setCurrentTalkTarget(null);
        NpcState saved = stateRepository.save(state);
        instance.setState(saved);

        log.info("NPC [{}] 已解锁", npcId);
    }

    /**
     * 释放指定玩家锁定的所有 NPC。
     * 用于玩家断连时批量清理。
     */
    @Transactional
    public void unlockAllForPlayer(String playerId) {
        for (NpcInstance instance : npcCache.values()) {
            if (instance.isLockedBy(playerId)) {
                NpcState state = instance.getState();
                state.setCurrentState(NpcTalkState.FREE);
                state.setCurrentTalkTarget(null);
                NpcState saved = stateRepository.save(state);
                instance.setState(saved);
                log.info("NPC [{}] 因玩家 [{}] 断连而解锁", instance.getId(), playerId);
            }
        }
        playerInRangeNpcs.remove(playerId);
    }

    /**
     * 持久化 NPC 状态变更（情绪、好感度、记忆）。
     */
    @Transactional
    public void saveNpcState(NpcState state) {
        NpcState saved = stateRepository.save(state);
        NpcInstance instance = npcCache.get(saved.getNpcId());
        if (instance != null) {
            instance.setState(saved);
        }
    }

    // ==================== 内部类型 ====================

    /**
     * 距离检测结果。
     */
    public record ProximityResult(List<NpcInstance> enteredNpcs, List<NpcInstance> exitedNpcs) {
    }

}

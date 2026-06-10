package com.taverntales.domain.npc;

/**
 * NPC 内存视图，合并不可变定义和可变状态。
 *
 * 为什么需要这个类？
 * NpcDefinition（JSON）和 NpcState（H2）是两个独立对象。
 * Handler 和其他 Service 需要同时访问两者，NpcInstance 提供一站式访问。
 *
 * 后续 NpcState 更新后，NpcFactory 会用新的 state 替换此实例的引用。
 */
public class NpcInstance {

    private final NpcDefinition definition;
    private volatile NpcState state;

    public NpcInstance(NpcDefinition definition, NpcState state) {
        this.definition = definition;
        this.state = state;
    }

    // ---- 来自 NpcDefinition 的不可变字段 ----

    public String getId() { return definition.getId(); }
    public String getName() { return definition.getName(); }
    public String getTitle() { return definition.getTitle(); }
    public String getDescription() { return definition.getDescription(); }
    public String getBackground() { return definition.getBackground(); }
    public String getSpeakingStyle() { return definition.getSpeakingStyle(); }
    public NpcDefinition.OCEAN getPersonality() { return definition.getPersonality(); }
    public int getTriggerRadius() { return definition.getTriggerRadius(); }

    // ---- 来自 NpcState 的可变字段 ----

    public int getCurrentX() { return state.getCurrentX(); }
    public int getCurrentY() { return state.getCurrentY(); }
    public String getCurrentEmotionJson() { return state.getCurrentEmotionJson(); }
    public NpcTalkState getCurrentState() { return state.getCurrentState(); }
    public String getCurrentTalkTarget() { return state.getCurrentTalkTarget(); }
    public String getPlayerRelationshipsJson() { return state.getPlayerRelationshipsJson(); }
    public String getPlayerMemoriesJson() { return state.getPlayerMemoriesJson(); }

    /** NPC 所在房间 ID */
    public String getRoomId() { return definition.getPosition().getRoomId(); }

    public boolean isFree() { return state.getCurrentState() == NpcTalkState.FREE; }
    public boolean isLockedBy(String playerId) {
        return state.getCurrentState() == NpcTalkState.TALKING
                && playerId.equals(state.getCurrentTalkTarget());
    }

    // ---- 状态更新（由 NpcFactory 调用） ----

    public NpcState getState() { return state; }

    void setState(NpcState newState) { this.state = newState; }
}

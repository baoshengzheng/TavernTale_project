package com.taverntales.domain.npc;

/**
 * NPC 对话状态机。
 *
 * 只有两个状态，模拟真实世界社交规则：一个人一次只能和一个人说话。
 * FREE → TALKING：玩家点击 NPC 且锁定成功
 * TALKING → FREE：对话结束、玩家走远、超时、断连
 */
public enum NpcTalkState {
    FREE,
    TALKING
}

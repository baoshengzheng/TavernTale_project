/**
 * 奇幻酒馆 — 游戏类型定义。
 *
 * 所有与后端 WebSocket 消息对应的 TypeScript 类型集中在以此，
 * 保证前后端数据结构的同步。
 */

/** WebSocket 消息统一外壳 */
export interface WebSocketMessage {
  version: string
  requestId: string
  type: string
  timestamp: number
  payload: Record<string, unknown>
}

/** 房间/场景定义 */
export interface Room {
  id: string
  name: string
  description: string
  width: number
  height: number
}

/** 玩家信息 */
export interface PlayerInfo {
  id: string
  name: string
  x: number
  y: number
  roomId: string
}

/** 世界状态（进入游戏时推送） */
export interface WorldState {
  rooms: Room[]
  players: PlayerInfo[]
  yourPlayerId: string
  yourPosition: {
    x: number
    y: number
    roomId: string
  }
}

/** 连接状态 */
export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

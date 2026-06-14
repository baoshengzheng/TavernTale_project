import { useEffect, useRef, useState, useCallback } from 'react'
import { useWebSocket } from './hooks/useWebSocket'
import type { WorldState, ConnectionStatus, PlayerInfo, Room, NpcInfo, DialogueMessage } from './types/game'
import { DialoguePanel } from './components/DialoguePanel'

/**
 * 酒馆物品定义（前端渲染用）。
 * 与 rooms.json 中的 objectsJson 对应。
 */
interface RoomObject {
  id: string
  name: string
  type: 'counter' | 'table' | 'decoration' | 'structure' | 'entrance'
  x: number
  y: number
  width: number
  height: number
}

/**
 * 奇幻酒馆 — 主游戏页面。
 *
 * Iteration 0 功能：
 *  - 连接到 WebSocket
 *  - 显示酒馆 CSS 俯视图（吧台、桌椅、壁炉等）
 *  - 显示玩家角色
 *  - 显示连接状态
 *
 * 后续迭代逐步替换为 Canvas 渲染。
 */
function App() {
  const [playerId] = useState(() => 'player_' + Math.random().toString(36).substring(2, 8))
  const [worldState, setWorldState] = useState<WorldState | null>(null)
  const [otherPlayers, setOtherPlayers] = useState<PlayerInfo[]>([])
  const [npcs, setNpcs] = useState<NpcInfo[]>([])
  const [npcsInRange, setNpcsInRange] = useState<Set<string>>(new Set())
  const [activeDialogue, setActiveDialogue] = useState<{ npcId: string; npcName: string } | null>(null)
  const [dialogueMessages, setDialogueMessages] = useState<Map<string, DialogueMessage[]>>(new Map())
  const [roomObjects, setRoomObjects] = useState<RoomObject[]>([])
  const [toast, setToast] = useState<{ message: string; level: string } | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  /**
   * 显示 toast 提示，3 秒后自动消失。
   * 连续调用时重置计时器。
   */
  const showToast = useCallback((message: string, level: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToast({ message, level })
    toastTimerRef.current = setTimeout(() => setToast(null), 3000)
  }, [])

  /**
   * 玩家本地位置状态 — 乐观更新。
   * 按键时立即更新此状态（不等服务器回复），保证 WASD 手感流畅。
   * 服务器确认位置后通过 PLAYER_MOVED 更新，但自己发的 PLAYER_MOVED 被忽略（见下方），
   * 所以本地位置完全由前端维护，不依赖服务器回包。
   */
  const [localPosition, setLocalPosition] = useState<{ x: number; y: number } | null>(null)

  const wsUrl = `ws://localhost:8080/ws/tavern`

  const { status, connect, send, addListener } = useWebSocket(wsUrl)

  // 监听 WORLD_STATE 消息
  useEffect(() => {
    const unsubscribe = addListener('WORLD_STATE', (msg) => {
      const state = msg.payload as unknown as WorldState
      setWorldState(state)
      setOtherPlayers(state.players.filter(p => p.id !== state.yourPlayerId))
      setNpcs(state.npcs || [])
      // 初始化本地位置
      if (state.yourPosition) {
        setLocalPosition({ x: state.yourPosition.x, y: state.yourPosition.y })
      }
    })
    return unsubscribe
  }, [addListener])

  // 监听 PLAYER_MOVED 消息
  useEffect(() => {
    const unsubscribe = addListener('PLAYER_MOVED', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const movedPlayer: PlayerInfo = {
        id: payload.playerId as string,
        name: '',
        x: payload.x as number,
        y: payload.y as number,
        roomId: '',
      }

      if (movedPlayer.id === worldState?.yourPlayerId) {
        // 自己的位置由本地更新
        return
      }
      // 更新其他玩家的位置
      setOtherPlayers(prev => {
        const exists = prev.find(p => p.id === movedPlayer.id)
        if (exists) {
          return prev.map(p => p.id === movedPlayer.id ? { ...p, x: movedPlayer.x, y: movedPlayer.y } : p)
        }
        return [...prev, movedPlayer]
      })
    })
    return unsubscribe
  }, [addListener, worldState?.yourPlayerId])

  // 监听 PLAYER_LEFT 消息
  useEffect(() => {
    const unsubscribe = addListener('PLAYER_LEFT', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      setOtherPlayers(prev => prev.filter(p => p.id !== payload.playerId))
    })
    return unsubscribe
  }, [addListener])

  // 监听 NPC_IN_RANGE 消息
  useEffect(() => {
    const unsubscribe = addListener('NPC_IN_RANGE', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const npcId = payload.npcId as string
      setNpcsInRange(prev => new Set(prev).add(npcId))
    })
    return unsubscribe
  }, [addListener])

  // 监听 NPC_OUT_OF_RANGE 消息
  useEffect(() => {
    const unsubscribe = addListener('NPC_OUT_OF_RANGE', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const npcId = payload.npcId as string
      setNpcsInRange(prev => {
        const next = new Set(prev)
        next.delete(npcId)
        return next
      })
    })
    return unsubscribe
  }, [addListener])

  // 监听 DIALOGUE 消息
  useEffect(() => {
    const unsubscribe = addListener('DIALOGUE', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const npcId = payload.npcId as string
      const npcName = npcs.find(n => n.id === npcId)?.name || npcId
      const newMsg: DialogueMessage = {
        id: crypto.randomUUID(),
        npcId,
        npcName,
        content: payload.message as string,
        isPlayer: false,
        type: 'DIALOGUE',
        timestamp: Date.now(),
      }
      setDialogueMessages(prev => {
        const next = new Map(prev)
        const list = next.get(npcId) || []
        next.set(npcId, [...list, newMsg])
        return next
      })
    })
    return unsubscribe
  }, [addListener, npcs])

  // 监听 DIALOGUE_PENDING 消息
  useEffect(() => {
    const unsubscribe = addListener('DIALOGUE_PENDING', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const npcId = payload.npcId as string
      const newMsg: DialogueMessage = {
        id: crypto.randomUUID(),
        npcId,
        content: '对方正在思考…',
        isPlayer: false,
        type: 'DIALOGUE_PENDING',
        timestamp: Date.now(),
      }
      setDialogueMessages(prev => {
        const next = new Map(prev)
        const list = next.get(npcId) || []
        next.set(npcId, [...list, newMsg])
        return next
      })
    })
    return unsubscribe
  }, [addListener])

  // 监听 DIALOGUE_BUSY 消息
  useEffect(() => {
    const unsubscribe = addListener('DIALOGUE_BUSY', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const npcName = payload.npcName as string
      showToast(`${npcName} 正在和别人交谈，请稍后再试`, 'warn')
    })
    return unsubscribe
  }, [addListener, showToast])

  // 监听 SYSTEM 消息 — 服务端通知（距离太远、NPC 不存在等）
  useEffect(() => {
    const unsubscribe = addListener('SYSTEM', (msg) => {
      const payload = msg.payload as Record<string, unknown>
      const level = (payload.level as string) || 'info'
      const message = (payload.message as string) || ''
      showToast(message, level)
    })
    return unsubscribe
  }, [addListener, showToast])

  // 自动连接
  useEffect(() => {
    connect()
  }, [connect])

  // 连接成功后发送 PLAYER_ENTER 完成身份注册
  useEffect(() => {
    if (status === 'connected') {
      send({ type: 'PLAYER_ENTER', payload: { playerId, playerName: '旅人' } })
    }
  }, [status, send, playerId])

  // 世界状态加载后解析房间物体
  useEffect(() => {
    if (worldState && worldState.rooms.length > 0) {
      const currentRoom = worldState.rooms[0] // 当前只有酒馆大厅
      // 从扩展字段 objectsJson 读取家具布局 JSON（由 Java 端 roomToMap 传入）
      const objectsRaw = (currentRoom as unknown as Record<string, unknown>).objectsJson
      if (typeof objectsRaw === 'string') {
        try {
          const objects = JSON.parse(objectsRaw) as RoomObject[]
          setRoomObjects(objects)
          return
        } catch (e) {
          console.warn('解析 objectsJson 失败:', e)
        }
      }
      // 兜底：用默认家具布局
      setRoomObjects(getDefaultObjects(currentRoom))
    }
  }, [worldState])

  /**
   * 玩家自己的位置（本地乐观更新）。
   * 初始化从 WORLD_STATE 读取，之后由键盘事件直接修改并同步到服务器。
   */
  const myPosition = localPosition

  /**
   * 连接状态指示器的文字和颜色。
   */
  const statusInfo: Record<ConnectionStatus, { label: string; className: string }> = {
    connecting: { label: '连接中…', className: 'status-connecting' },
    connected: { label: '已连接', className: 'status-connected' },
    disconnected: { label: '已断开', className: 'status-disconnected' },
    error: { label: '连接失败', className: 'status-error' },
  }

  return (
    <div className="game-container">
      {/* 顶栏：标题 + 连接状态 */}
      <header className="game-header">
        <h1 className="game-title">🍺 奇幻酒馆</h1>
        <div className={`connection-status ${statusInfo[status].className}`}>
          <span className="status-dot" />
          <span className="status-label">{statusInfo[status].label}</span>
        </div>
      </header>

      {/* 游戏世界 */}
      <main className="game-world">
        {/* 房间渲染 */}
        {worldState && worldState.rooms.length > 0 ? (
          <div className="room-view">
            {/* 房间信息 */}
            <div className="room-header">
              <h2>{worldState.rooms[0].name}</h2>
              <p className="room-desc">{worldState.rooms[0].description}</p>
            </div>

            {/* 酒馆地图 —— CSS 俯视图 */}
            {worldState.rooms[0] && (
              <TavernMap
                room={worldState.rooms[0]}
                objects={roomObjects}
                npcs={npcs}
                npcsInRange={npcsInRange}
                myPosition={myPosition ? { x: myPosition.x, y: myPosition.y } : null}
                otherPlayers={otherPlayers}
                onMove={(x, y) => {
                  // 乐观更新本地位置（不等服务器确认，保证手感流畅）
                  setLocalPosition({ x, y })
                  send({ type: 'PLAYER_MOVE', payload: { x, y } })
                }}
                onNpcClick={(npcId, npcName) => {
                  setActiveDialogue({ npcId, npcName })
                }}
              />
            )}
            {/* 对话面板 */}
            {activeDialogue && (
              <DialoguePanel
                npcName={activeDialogue.npcName}
                messages={dialogueMessages.get(activeDialogue.npcId) || []}
                onSend={(message) => {
                  // 乐观添加玩家消息到本地
                  const playerMsg: DialogueMessage = {
                    id: crypto.randomUUID(),
                    npcId: activeDialogue.npcId,
                    content: message,
                    isPlayer: true,
                    type: 'DIALOGUE',
                    timestamp: Date.now(),
                  }
                  setDialogueMessages(prev => {
                    const next = new Map(prev)
                    const list = next.get(activeDialogue.npcId) || []
                    next.set(activeDialogue.npcId, [...list, playerMsg])
                    return next
                  })
                  send({ type: 'PLAYER_TALK', payload: { npcId: activeDialogue.npcId, message } })
                }}
                onClose={() => {
                  send({ type: 'PLAYER_TALK_END', payload: { npcId: activeDialogue.npcId } })
                  setActiveDialogue(null)
                }}
              />
            )}
          </div>
        ) : (
          /* 未加载到世界状态：显示加载 */
          <div className="loading-state">
            <div className="loading-spinner" />
            <p>
              {status === 'connecting'
                ? '正在踏入酒馆…'
                : status === 'disconnected' || status === 'error'
                  ? '与酒馆的连接已断开，请刷新页面'
                  : '等待世界加载…'}
            </p>
            {(status === 'disconnected' || status === 'error') && (
              <button className="retry-button" onClick={connect}>
                重新连接
              </button>
            )}
          </div>
        )}
      </main>

      {/* Toast 通知 — 服务端 SYSTEM 消息、警告、错误提示 */}
      {toast && (
        <div className={`toast toast-${toast.level}`} onClick={() => setToast(null)}>
          <span className="toast-icon">
            {toast.level === 'error' ? '❌' : toast.level === 'warn' ? '⚠️' : 'ℹ️'}
          </span>
          <span className="toast-message">{toast.message}</span>
        </div>
      )}

      {/* 底栏：提示信息 */}
      <footer className="game-footer">
        <span>WASD / 方向键移动</span>
        {otherPlayers.length > 0 && (
          <span>| 其他旅人: {otherPlayers.length} 人在酒馆中</span>
        )}
      </footer>
    </div>
  )
}

/**
 * 酒馆地图组件 — CSS 俯视图。
 *
 * 完全用 CSS 绘制酒馆布局，每个家具是一个绝对定位的 div。
 * Iteration 2 替换为 Canvas 2D 后此组件退役。
 */
function TavernMap({
  room,
  objects,
  npcs,
  npcsInRange,
  myPosition,
  otherPlayers,
  onMove,
  onNpcClick,
}: {
  room: Room
  objects: RoomObject[]
  npcs: NpcInfo[]
  npcsInRange: Set<string>
  myPosition: { x: number; y: number } | null
  otherPlayers: PlayerInfo[]
  onMove: (x: number, y: number) => void
  onNpcClick: (npcId: string, npcName: string) => void
}) {
  const mapRef = useRef<HTMLDivElement>(null)

  /**
   * 处理键盘移动。
   * 步长 10px，拖尾效果在后续迭代优化。
   */
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!myPosition) return

      const step = 10
      let newX = myPosition.x
      let newY = myPosition.y

      switch (e.key) {
        case 'w':
        case 'ArrowUp':
          newY = Math.max(0, myPosition.y - step)
          break
        case 's':
        case 'ArrowDown':
          newY = Math.min(room.height, myPosition.y + step)
          break
        case 'a':
        case 'ArrowLeft':
          newX = Math.max(0, myPosition.x - step)
          break
        case 'd':
        case 'ArrowRight':
          newX = Math.min(room.width, myPosition.x + step)
          break
        default:
          return
      }
      e.preventDefault()
      onMove(newX, newY)
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [myPosition, room, onMove])

  /**
   * 处理点击地图移动。
   */
  const handleMapClick = (e: React.MouseEvent) => {
    if (!mapRef.current) return
    const rect = mapRef.current.getBoundingClientRect()
    const x = Math.round(e.clientX - rect.left)
    const y = Math.round(e.clientY - rect.top)
    onMove(x, y)
  }

  /**
   * 根据物体类型返回 CSS 类名和显示标签。
   */
  const getObjectStyle = (obj: RoomObject) => {
    const base: Record<string, React.CSSProperties> = {
      counter: { background: '#5c3a1e', borderRadius: '4px' },
      table: { background: '#7a5230', borderRadius: '6px', border: '2px solid #5c3a1e' },
      decoration: { background: '#3a2a1a', borderRadius: '4px' },
      structure: { background: '#4a3a2a', borderRadius: '4px' },
      entrance: { background: '#2a1a0a', borderRadius: '4px', border: '2px solid #8a6a4a' },
    }
    return base[obj.type] || base.structure
  }

  const getObjectLabel = (obj: RoomObject) => {
    const labels: Record<string, string> = {
      bar: '🍺 吧台',
      table1: '🪑', table2: '🪑', table3: '🪑', table4: '🪑',
      fireplace: '🔥',
      shelves: '🍾',
      staircase: '🚪',
      door: '🚪 大门',
    }
    return labels[obj.id] || obj.name
  }

  return (
    <div
      ref={mapRef}
      className="tavern-map"
      style={{ width: room.width, height: room.height }}
      onClick={handleMapClick}
    >
      {/* 地板纹理 */}
      <div className="floor" />

      {/* 家具 */}
      {objects.map((obj) => (
        <div
          key={obj.id}
          className="room-object"
          style={{
            left: obj.x,
            top: obj.y,
            width: obj.width,
            height: obj.height,
            ...getObjectStyle(obj),
          }}
          title={obj.name}
        >
          <span className="object-label">{getObjectLabel(obj)}</span>
        </div>
      ))}

      {/* NPC */}
      {npcs.map((npc) => {
        const inRange = npcsInRange.has(npc.id)
        const npcEmoji: Record<string, string> = { edith: '👩‍🍳', roderick: '🍺', luna: '🧝' }
        return (
          <div
            key={npc.id}
            className={`npc-entity ${inRange ? 'in-range' : ''}`}
            style={{
              left: npc.x - 14,
              top: npc.y - 14,
            }}
            onClick={(e) => {
              e.stopPropagation()
              if (inRange) onNpcClick(npc.id, npc.name)
            }}
            title={inRange ? `与 ${npc.name} 对话` : npc.name}
          >
            <span className="npc-emoji">{npcEmoji[npc.id] || '👤'}</span>
            <span className="npc-name-tag">{npc.name}</span>
            {inRange && <span className="npc-interact-hint">💬 对话</span>}
          </div>
        )
      })}

      {/* 其他玩家 */}
      {otherPlayers.map((player) => (
        <div
          key={player.id}
          className="other-player"
          style={{ left: player.x - 10, top: player.y - 10 }}
          title={player.name}
        >
          <span className="player-emoji">🧑</span>
          <span className="player-name-tag">{player.name}</span>
        </div>
      ))}

      {/* 自己 */}
      {myPosition && (
        <div
          className="my-player"
          style={{ left: myPosition.x - 10, top: myPosition.y - 10 }}
        >
          <span className="player-emoji">🧑‍🍳</span>
        </div>
      )}
    </div>
  )
}

/**
 * 默认家具布局。
 * 当 rooms.json 的 objectsJson 无法解析时使用。
 */
function getDefaultObjects(_room: Room): RoomObject[] {
  return [
    { id: 'bar', name: '吧台', type: 'counter', x: 180, y: 250, width: 200, height: 40 },
    { id: 'table1', name: '木桌', type: 'table', x: 350, y: 150, width: 60, height: 60 },
    { id: 'table2', name: '木桌', type: 'table', x: 500, y: 150, width: 60, height: 60 },
    { id: 'table3', name: '木桌', type: 'table', x: 350, y: 350, width: 60, height: 60 },
    { id: 'table4', name: '木桌', type: 'table', x: 500, y: 350, width: 60, height: 60 },
    { id: 'fireplace', name: '壁炉', type: 'decoration', x: 680, y: 80, width: 60, height: 80 },
    { id: 'shelves', name: '酒柜', type: 'decoration', x: 180, y: 120, width: 30, height: 150 },
    { id: 'staircase', name: '楼梯', type: 'structure', x: 50, y: 100, width: 60, height: 40 },
    { id: 'door', name: '大门', type: 'entrance', x: 50, y: 280, width: 40, height: 60 },
  ]
}

export default App

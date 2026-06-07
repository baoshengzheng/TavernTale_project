import { useEffect, useRef, useState, useCallback } from 'react'
import type { WebSocketMessage, ConnectionStatus } from '../types/game'

/**
 * WebSocket 连接 Hook。
 *
 * 管理连接生命周期：自动重连、心跳保活、消息收发。
 *
 * 为什么在 Hook 层管理连接而非全局？
 *  当前只有单个游戏页面，Hook 级管理足够。
 *  后续需要多页面共享连接时，可迁移到 Context 或状态管理库。
 *
 * 重连策略：
 *  断连后 3 秒自动重试，最多重试 5 次。
 *  避免在服务器重启时无限重连。
 */
export function useWebSocket(url: string) {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected')
  const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null)

  const wsRef = useRef<WebSocket | null>(null)
  const reconnectCountRef = useRef(0)
  const maxReconnect = 5
  const reconnectDelay = 3000
  const pingIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // 消息回调注册表：其他组件通过此回调监听特定消息
  const listenersRef = useRef<Map<string, (msg: WebSocketMessage) => void>>(new Map())

  /**
   * 注册消息监听器。
   * 返回取消注册函数。
   */
  const addListener = useCallback((type: string, handler: (msg: WebSocketMessage) => void) => {
    listenersRef.current.set(type, handler)
    return () => { listenersRef.current.delete(type) }
  }, [])

  /**
   * 发送消息到服务器。
   */
  const send = useCallback((message: Partial<WebSocketMessage>) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      console.warn('WebSocket 未连接，消息未发送:', message.type)
      return false
    }
    const msg: WebSocketMessage = {
      version: '1.0',
      requestId: crypto.randomUUID(),
      type: message.type || 'UNKNOWN',
      timestamp: Date.now(),
      payload: message.payload || {},
    }
    wsRef.current.send(JSON.stringify(msg))
    return true
  }, [])

  /**
   * 建立连接。
   */
  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN ||
        wsRef.current?.readyState === WebSocket.CONNECTING) {
      return
    }

    setStatus('connecting')
    console.log(`[WS] 正在连接: ${url}`)

    const ws = new WebSocket(url)

    ws.onopen = () => {
      console.log('[WS] 已连接')
      setStatus('connected')
      reconnectCountRef.current = 0

      // 启动心跳：每 30 秒发送 PING
      pingIntervalRef.current = setInterval(() => {
        send({ type: 'PING', payload: {} })
      }, 30000)
    }

    ws.onmessage = (event) => {
      try {
        const msg: WebSocketMessage = JSON.parse(event.data)
        setLastMessage(msg)

        // 触发对应类型的监听器
        const handler = listenersRef.current.get(msg.type)
        if (handler) {
          handler(msg)
        }
      } catch (e) {
        console.error('[WS] 消息解析失败:', e)
      }
    }

    ws.onclose = (event) => {
      console.log(`[WS] 连接关闭: code=${event.code}`)
      setStatus('disconnected')
      cleanup()

      // 自动重连
      if (reconnectCountRef.current < maxReconnect) {
        reconnectCountRef.current++
        console.log(`[WS] ${reconnectDelay}ms 后重试 (${reconnectCountRef.current}/${maxReconnect})`)
        setTimeout(connect, reconnectDelay)
      } else {
        console.error('[WS] 重连次数耗尽，请手动刷新')
        setStatus('error')
      }
    }

    ws.onerror = (error) => {
      console.error('[WS] 连接错误:', error)
    }

    wsRef.current = ws
  }, [url, send])

  /**
   * 断开连接。
   */
  const disconnect = useCallback(() => {
    cleanup()
    wsRef.current?.close()
    wsRef.current = null
    setStatus('disconnected')
  }, [])

  /**
   * 清理资源（心跳、引用）。
   */
  const cleanup = () => {
    if (pingIntervalRef.current) {
      clearInterval(pingIntervalRef.current)
      pingIntervalRef.current = null
    }
  }

  // 清理
  useEffect(() => {
    return () => {
      cleanup()
      wsRef.current?.close()
    }
  }, [])

  return {
    status,
    lastMessage,
    connect,
    disconnect,
    send,
    addListener,
  }
}

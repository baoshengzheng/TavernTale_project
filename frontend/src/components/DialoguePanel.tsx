import { useState, useRef, useEffect, useCallback } from 'react'
import type { DialogueMessage } from '../types/game'
import './DialoguePanel.css'

interface DialoguePanelProps {
  npcName: string
  messages: DialogueMessage[]
  onSend: (message: string) => void
  onClose: () => void
}

/**
 * NPC 对话面板 — 固定在页面右下角的聊天窗口。
 *
 * Iteration 1 功能：消息历史、输入框、发送按钮、关闭按钮。
 * 显示 NPC 正在输入的等待状态（DIALOGUE_PENDING）。
 */
export function DialoguePanel({ npcName, messages, onSend, onClose }: DialoguePanelProps) {
  const [input, setInput] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // 新消息时自动滚到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = useCallback(() => {
    const trimmed = input.trim()
    if (!trimmed) return
    onSend(trimmed)
    setInput('')
  }, [input, onSend])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const isPending = messages.length > 0 && messages[messages.length - 1].type === 'DIALOGUE_PENDING'

  return (
    <div className="dialogue-panel">
      <div className="dialogue-header" onClick={onClose}>
        <span className="dialogue-npc-name">💬 {npcName}</span>
        <button className="dialogue-close" title="关闭对话">✕</button>
      </div>

      <div className="dialogue-messages">
        {messages.length === 0 && (
          <div className="dialogue-hint">开始和 {npcName} 对话吧…</div>
        )}
        {messages.map((msg) => (
          <div key={msg.id} className={`dialogue-bubble ${msg.isPlayer ? 'player' : 'npc'} ${msg.type.toLowerCase()}`}>
            <div className="bubble-content">{msg.content}</div>
          </div>
        ))}
        {isPending && (
          <div className="dialogue-bubble npc pending">
            <div className="bubble-content">
              <span className="typing-indicator">
                <span>.</span><span>.</span><span>.</span>
              </span>
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      <div className="dialogue-input-area">
        <input
          type="text"
          className="dialogue-input"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={isPending ? '对方正在思考…' : '说点什么…'}
          disabled={isPending}
          autoFocus
        />
        <button
          className="dialogue-send-btn"
          onClick={handleSend}
          disabled={isPending || !input.trim()}
        >
          发送
        </button>
      </div>
    </div>
  )
}

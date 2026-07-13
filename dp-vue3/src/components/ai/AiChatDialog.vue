<script setup>
import { ref, watch, nextTick } from 'vue'
import { Close, ChatDotRound } from '@element-plus/icons-vue'
import { sendChatMessage } from '@/api/ai'
import { ElMessage } from 'element-plus'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible'])

const messages = ref([
  {
    role: 'assistant',
    content: '你好！我是 DP-Plus 智能助手 🤖\n\n我可以帮你：\n• 查询附近商铺信息\n• 查看优惠券详情\n• 查询订单状态\n• 了解个人信息\n\n请随时向我提问！',
    timestamp: Date.now()
  }
])
const inputText = ref('')
const loading = ref(false)
const messagesContainer = ref(null)

// 控制显示/隐藏
const show = ref(props.visible)
watch(() => props.visible, (val) => {
  show.value = val
  if (val) {
    nextTick(() => scrollToBottom())
  }
})

const close = () => {
  show.value = false
  emit('update:visible', false)
}

// 滚动到底部
const scrollToBottom = () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
}

// 发送消息
const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  // 添加用户消息
  messages.value.push({
    role: 'user',
    content: text,
    timestamp: Date.now()
  })
  inputText.value = ''
  scrollToBottom()

  // 调用 API
  loading.value = true
  try {
    const response = await sendChatMessage(text)
    if (response.data && response.data.reply) {
      messages.value.push({
        role: 'assistant',
        content: response.data.reply,
        timestamp: Date.now()
      })
    } else {
      messages.value.push({
        role: 'assistant',
        content: '抱歉，我暂时无法回复，请稍后再试。',
        timestamp: Date.now()
      })
    }
  } catch (error) {
    console.error('AI 聊天请求失败:', error)
    const errorMsg = error.response?.data?.errorMsg || '网络异常，请稍后重试'
    ElMessage.error(errorMsg)
    messages.value.push({
      role: 'assistant',
      content: '抱歉，服务暂时不可用 😢，请稍后再试。',
      timestamp: Date.now()
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

// Enter 发送，Shift+Enter 换行
const handleKeydown = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  const h = String(date.getHours()).padStart(2, '0')
  const m = String(date.getMinutes()).padStart(2, '0')
  return `${h}:${m}`
}
</script>

<template>
  <Transition name="dialog-slide">
    <div v-if="show" class="ai-chat-dialog">
      <!-- 头部 -->
      <div class="dialog-header">
        <div class="header-left">
          <el-icon :size="20"><ChatDotRound /></el-icon>
          <span class="header-title">AI 智能助手</span>
        </div>
        <el-button class="close-btn" :icon="Close" text @click="close" />
      </div>

      <!-- 消息列表 -->
      <div ref="messagesContainer" class="dialog-body">
        <div v-for="(msg, index) in messages" :key="index" class="message-item">
          <!-- AI 消息 -->
          <div v-if="msg.role === 'assistant'" class="msg-row msg-ai">
            <div class="msg-avatar ai-avatar">
              <el-icon :size="16"><ChatDotRound /></el-icon>
            </div>
            <div class="msg-bubble msg-bubble-ai">
              <div class="msg-content">{{ msg.content }}</div>
              <div class="msg-time">{{ formatTime(msg.timestamp) }}</div>
            </div>
          </div>
          <!-- 用户消息 -->
          <div v-else class="msg-row msg-user">
            <div class="msg-bubble msg-bubble-user">
              <div class="msg-content">{{ msg.content }}</div>
              <div class="msg-time">{{ formatTime(msg.timestamp) }}</div>
            </div>
          </div>
        </div>

        <!-- 加载状态 -->
        <div v-if="loading" class="msg-row msg-ai">
          <div class="msg-avatar ai-avatar">
            <el-icon :size="16"><ChatDotRound /></el-icon>
          </div>
          <div class="msg-bubble msg-bubble-ai typing-bubble">
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
          </div>
        </div>
      </div>

      <!-- 输入区域 -->
      <div class="dialog-footer">
        <div class="input-wrapper">
          <textarea
            v-model="inputText"
            class="msg-input"
            placeholder="输入你的问题..."
            :rows="1"
            :disabled="loading"
            @keydown="handleKeydown"
          />
          <el-button
            class="send-btn"
            type="primary"
            :disabled="!inputText.trim() || loading"
            @click="sendMessage"
          >
            发送
          </el-button>
        </div>
        <p class="input-hint">Enter 发送，Shift + Enter 换行</p>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.ai-chat-dialog {
  position: fixed;
  bottom: 100px;
  right: 20px;
  z-index: 998;
  width: 400px;
  height: 560px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 过渡动画 */
.dialog-slide-enter-active,
.dialog-slide-leave-active {
  transition: all 0.3s ease;
}
.dialog-slide-enter-from,
.dialog-slide-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}

/* 头部 */
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: linear-gradient(135deg, #f63, #e85d04);
  color: #fff;
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
}
.close-btn {
  color: #fff !important;
}
.close-btn:hover {
  background: rgba(255, 255, 255, 0.15) !important;
}

/* 消息区域 */
.dialog-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #f5f6fa;
}
.dialog-body::-webkit-scrollbar {
  width: 5px;
}
.dialog-body::-webkit-scrollbar-thumb {
  background: #ddd;
  border-radius: 3px;
}

/* 消息行 */
.msg-row {
  display: flex;
  margin-bottom: 16px;
}
.msg-user {
  justify-content: flex-end;
}
.msg-ai {
  align-items: flex-start;
}

/* 头像 */
.msg-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-right: 8px;
}
.ai-avatar {
  background: linear-gradient(135deg, #f63, #e85d04);
  color: #fff;
}

/* 消息气泡 */
.msg-bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 12px;
  position: relative;
}
.msg-bubble-ai {
  background: #fff;
  border-top-left-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.msg-bubble-user {
  background: linear-gradient(135deg, #f63, #e85d04);
  color: #fff;
  border-top-right-radius: 4px;
}
.msg-content {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-time {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  text-align: right;
}
.msg-bubble-user .msg-time {
  color: rgba(255, 255, 255, 0.7);
}

/* 加载动画 */
.typing-bubble {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 14px 18px;
}
.typing-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ccc;
  animation: typing 1.4s infinite ease-in-out both;
}
.typing-dot:nth-child(1) { animation-delay: 0s; }
.typing-dot:nth-child(2) { animation-delay: 0.2s; }
.typing-dot:nth-child(3) { animation-delay: 0.4s; }

@keyframes typing {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.5;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

/* 输入区域 */
.dialog-footer {
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid #eee;
  flex-shrink: 0;
}
.input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
.msg-input {
  flex: 1;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
  font-family: inherit;
  resize: none;
  outline: none;
  transition: border-color 0.2s;
  line-height: 1.5;
}
.msg-input:focus {
  border-color: #f63;
}
.msg-input:disabled {
  background: #f5f5f5;
  cursor: not-allowed;
}
.send-btn {
  height: 38px;
  flex-shrink: 0;
}
.input-hint {
  font-size: 11px;
  color: #bbb;
  margin: 4px 0 0 0;
  text-align: right;
}
</style>

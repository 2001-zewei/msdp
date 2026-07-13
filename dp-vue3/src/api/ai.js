import request from '@/utils/request'

/**
 * 发送聊天消息到 AI 助手
 * @param {string} message - 用户消息内容
 * @returns {Promise} { success, data: { reply, history } }
 */
export const sendChatMessage = (message) =>
  request.post('/ai/chat', { message })

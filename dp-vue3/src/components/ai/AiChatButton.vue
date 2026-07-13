<script setup>
import { ref } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/modules/user'
import AiChatDialog from './AiChatDialog.vue'

const userStore = useUserStore()
const dialogVisible = ref(false)

const toggleDialog = () => {
  dialogVisible.value = !dialogVisible.value
}
</script>

<template>
  <div v-if="userStore.token">
    <!-- 浮标按钮 -->
    <div class="ai-chat-button" @click="toggleDialog">
      <el-icon :size="24">
        <ChatDotRound />
      </el-icon>
    </div>

    <!-- 聊天对话框 -->
    <AiChatDialog v-model:visible="dialogVisible" />
  </div>
</template>

<style scoped>
.ai-chat-button {
  position: fixed;
  bottom: 100px;
  right: 20px;
  z-index: 999;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #f63, #e85d04);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 4px 16px rgba(255, 102, 51, 0.4);
  transition: all 0.3s ease;
  user-select: none;
}

.ai-chat-button:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 20px rgba(255, 102, 51, 0.55);
}

.ai-chat-button:active {
  transform: scale(0.95);
}
</style>

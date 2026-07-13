# 智能电商平台

> DP-Plus — 高并发秒杀电商平台 + 多 Agent AI 智能客服

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.0-blue.svg)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3.x-42b883.svg)](https://vuejs.org/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-c71a36.svg)](https://maven.apache.org/)

---

## 项目介绍

**智能电商平台** 是一款企业级高并发秒杀电商系统，围绕**优惠券秒杀**与**热点数据查询**两大核心场景，深度整合了**多 Agent 协作的 AI 智能客服**。项目在传统电商秒杀架构之上，补齐了高并发稳定性、动态令牌、限流、消息可靠性、数据一致性闭环，并加入了订阅通知、候补排队、AI 智能客服等亮点功能。

### 核心亮点

| 模块 | 能力 |
|---|---|
| **秒杀引擎** | Redis + Lua 原子化脚本，Kafka 异步削峰，分布式锁，四级缓存防击穿/穿透/雪崩 |
| **AI 智能客服** | 多 Agent 编排，意图识别，RAG 知识增强检索，三层记忆，动态 Skills 注入，LLM-as-Judge |
| **分库分表** | ShardingSphere-JDBC 5.3.2，8 张核心表水平拆分 |
| **分布式基础设施** | Redisson 分布式锁/延迟队列/布隆过滤器，雪花 ID 生成器，Kafka 消息可靠性 |
| **监控告警** | Prometheus + Micrometer，Agent 级性能采集，Webhook 告警 |

---

## AI 智能客服架构

```
用户 → Vue3 AiChatDialog → POST /ai/chat → ChatOrchestrator (六阶段管道)
                                                  │
        ┌──────────┬──────────┬──────────┬────────┼────────┬──────────┐
        ▼          ▼          ▼          ▼        ▼        ▼          ▼
   MemoryMgr  IntentRecog  Knowledge  AgentOrch  AnswerVerify  Monitor
   (三层记忆)  (三路投票)   (RAG引擎)  (多Agent)  (防幻觉)    (动态路由)
```

### 六阶段处理管道

1. **记忆上下文** — Redis 工作记忆 + 情景记忆向量检索 + 用户画像
2. **意图识别** — LLM Few-shot + Jaccard 语义 + Pattern 关键词 三路融合投票
3. **知识检索** — Query 重写 → 并行召回 → BM25+Vector 混合排序 → LLM 重排
4. **Agent 编排** — 意图路由 → General/Technical/Billing Agent → 复合问题并行
5. **答案校验** — LLM 校验 grounded/pass/escalation，拦截幻觉回答
6. **持久化 + 画像更新** — 写入 Redis 工作记忆，异步更新用户画像

### 动态 Skills 注入

每个 Agent 类型绑定额外的业务规范（SKILL.md），在请求时根据 **Agent 类型 + 用户意图关键词** 动态注入 System Prompt，支持热加载：

```
skills/
├── general_customer_service/SKILL.md   # 通用客服接待/分流/投诉规范
├── technical_support/SKILL.md          # 技术排障 SOP/错误码诊断
└── billing_support/SKILL.md            # 账单/退款/发票处理规范
```

---

## 技术栈

| 层级 | 技术 |
|---|---|
| **语言** | Java 21 |
| **框架** | Spring Boot 3.5.4, Spring AI 1.1.0 |
| **LLM** | DashScope (通义千问) / Anthropic Claude / DeepSeek 可切换 |
| **RAG** | BM25 + Hash Vector 混合检索, LangChain4j 文档分割, LLM Rerank |
| **ORM** | MyBatis-Plus 3.5.7, ShardingSphere-JDBC 5.3.2 |
| **缓存** | Redis 7 + Caffeine + Redisson 3.52 + Bloom Filter |
| **消息队列** | Apache Kafka |
| **前端** | Vue 3 + Vite + Element Plus + Pinia |
| **认证** | Sa-Token |
| **监控** | Micrometer + Prometheus + Actuator |
| **API 文档** | Knife4j (Swagger) |
| **部署** | Docker Compose + Nginx |

---

## 项目结构

```
msdp/
├── dp-core-service/          ← 核心服务 (Spring Boot 入口)
│   └── src/main/java/org/javaup/
│       ├── ai/               ← AI 智能客服模块
│       │   ├── agent/        ← 多 Agent 编排 + 答案校验
│       │   ├── intent/       ← 意图识别器
│       │   ├── knowledge/    ← RAG 混合检索知识库
│       │   ├── memory/       ← 三层记忆管理
│       │   ├── llm/          ← LLM 网关统一抽象
│       │   ├── tool/         ← 工具管理 + 熔断器 + 业务工具桥接
│       │   ├── evaluation/   ← LLM-as-Judge 评测
│       │   ├── monitor/      ← 性能监控 + 告警
│       │   ├── skill/        ← 动态 Skills 注入 + 热加载
│       │   └── config/       ← AI 配置
│       ├── controller/       ← REST 接口
│       ├── service/          ← 业务服务
│       ├── mapper/           ← MyBatis 数据访问
│       ├── entity/           ← 数据实体
│       ├── kafka/            ← Kafka 生产者/消费者
│       └── config/           ← Spring 配置
├── dp-common/                ← 公共模块 (枚举/异常/工具类)
├── dp-parameter/             ← DTO/VO 数据传输对象
├── dp-sharding/              ← ShardingSphere SPI 扩展
├── dp-id-generator-framework/ ← 雪花分布式 ID 生成器
├── dp-redis-tool-framework/  ← Redis 工具框架 (缓存/限流)
├── dp-redisson-framework/    ← Redisson 框架 (锁/布隆/延迟队列)
├── dp-mq-framework/          ← Kafka 消息队列框架
├── dp-vue3/                  ← Vue 3 前端
├── sql/                      ← 数据库初始化脚本
└── docs/                     ← 文档
```

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- Redis 7+
- MySQL 8.0+
- Kafka 3.x (或 Docker Compose 一键启动)
- Node.js 18+ (前端)

### 后端启动

```bash
# 1. 克隆项目
git clone https://github.com/2001-zewei/msdp.git
cd msdp

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env: 填入 AI_API_KEY, Redis/MySQL 连接信息

# 3. 启动基础设施 (Redis, Kafka, Zookeeper)
cd dp-core-service/src/main/resources
docker-compose up -d

# 4. 初始化数据库 (执行 sql/ 目录下的脚本)

# 5. 编译启动
cd ../../../../
mvn clean package -DskipTests
java -jar dp-core-service/target/dp-core-service-0.0.1-SNAPSHOT.jar
```

服务端口：
- 后端 API: `http://localhost:8085`
- API 文档 (Knife4j): `http://localhost:8085/doc.html`
- Skills 管理: `GET/POST /ai/skills`
- AI 聊天: `POST /ai/chat`

### 前端启动

```bash
cd dp-vue3
npm install
npm run dev
```

### Docker 一键部署

```bash
docker-compose up -d
```

---

## AI 智能客服 API

### 聊天

```bash
POST /ai/chat
Content-Type: application/json
Authorization: Bearer <token>

{
  "message": "为什么我的支付显示成功但订单没生成？"
}
```

响应：
```json
{
  "code": 0,
  "data": {
    "reply": "根据您的描述，这可能是支付回调未及时处理...",
    "conversationId": "uuid",
    "intent": "technical",
    "agentType": "technical",
    "escalated": false,
    "latencyMs": 1234,
    "knowledgeUsed": true,
    "verified": true,
    "grounded": true
  }
}
```

### Skills 管理

```bash
# 查看已加载的 Skills
GET /ai/skills

# 热加载 (修改 SKILL.md 后无需重启)
POST /ai/skills/reload
```

---

## AI 模块配置

```yaml
dp-plus:
  ai:
    llm:
      fallback-enabled: true
    memory:
      ttl-seconds: 86400
      working-max: 20
      compress-at: 15
    rag:
      top-k: 4
      bm25-weight: 0.45
      vector-weight: 0.55
    skill:
      dir: skills
      max-prompt-chars: 5000
```

---

## License

MIT

---

**Author**: [@2001-zewei](https://github.com/2001-zewei)

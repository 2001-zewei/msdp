# 🚀 DP-Plus（点评 Plus 企业级实战项目）

[![Java](https://img.shields.io/badge/Java-17-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.4-brightgreen)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4fc08d)](https://vuejs.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

> 🔥 基于"黑马点评"进行全面重构升级的企业级优惠券秒杀与本地生活点评项目。围绕**高并发秒杀**与**热点查询**，补齐高并发稳定性、动态令牌、限流、消息可靠性与数据一致性闭环，并新增订阅通知、候补排队等亮点功能。

**开源不易，如果对你有帮助，请点个 ⭐ Star 支持一下！**

---

## 📖 项目简介

DP-Plus 是一个"本地生活 + 商户点评 + 优惠券秒杀"场景的综合实战项目，采用 **Spring Boot 3 + Vue 3** 前后端分离架构，涵盖：

- 🏪 **商户浏览与查询** — 缓存穿透/击穿/雪崩的完整解决方案
- 🎫 **优惠券秒杀** — Redis + Lua 原子扣减、分布式锁、Kafka 消息队列
- 📍 **附近商户** — Redis GEO 地理位置检索
- ✍️ **达人探店** — 点赞列表与排行榜
- 👥 **好友关注** — 社交关系（共同关注、Feed 流）
- ✅ **用户签到** — BitMap 签到统计
- 📊 **UV 统计** — HyperLogLog 海量去重

### Plus 版新增亮点

| 功能 | 说明 |
|------|------|
| 🚦 **全链路流控** | 令牌前置授权 + 令牌桶限流，入口防护 |
| 🔐 **动态令牌** | 秒杀资格前置验证 |
| 📨 **可靠消息** | Kafka 消息不丢失 + 死信队列 + 消息重试 |
| 📊 **数据一致性** | Redis 与 DB 库存对账、补偿恢复 |
| 🔔 **订阅通知** | 秒杀结果实时通知 |
| ⏳ **候补排队** | 库存释放后的候补补购机制 |
| 🌸 **布隆过滤器** | 防止缓存穿透 |
| 🔁 **防重复下单** | 基于 Redisson 的幂等性保障 |
| 📈 **可观测性** | Prometheus + 健康监控 |
| 🔀 **分库分表** | ShardingSphere 5.x 水平拆分 |

---

## 🛠 技术栈

### 后端
- **核心框架**: Spring Boot 3.5.4
- **持久层**: MyBatis-Plus 3.5.7 + ShardingSphere 5.3.2（分库分表）
- **缓存**: Redis + Redisson 3.52.0 + 本地 Caffeine 缓存
- **消息队列**: Apache Kafka
- **认证鉴权**: Sa-Token 1.43.0
- **限流**: 自研令牌桶 + IP/用户维度限流
- **监控**: Prometheus + Micrometer
- **API 文档**: Knife4j 4.3.0 + OpenAPI 2.2.0
- **工具库**: Hutool 5.8.25, Fastjson 2.0.9, Aviator 5.4.3

### 前端
- **框架**: Vue 3.5 + Vite 6
- **UI 组件库**: Element Plus 2.9
- **状态管理**: Pinia 3.0 + persistedstate
- **路由**: Vue Router 4.5

### 中间件要求
- MySQL 8.0+
- Redis 7.0+
- Apache Kafka 3.0+

---

## 📁 项目结构

```
dp-plus/
├── dp-common/                          # 公共模块（常量、异常、工具类）
│   └── src/main/java/org/javaup/
│       ├── constant/                   # 常量定义
│       ├── enums/                      # 枚举类
│       ├── exception/                  # 业务异常（DpFrameException）
│       └── utils/                      # 工具类
├── dp-core-service/                    # 核心业务服务（Spring Boot 启动入口）
│   └── src/main/
│       ├── java/org/javaup/
│       │   ├── config/                 # 配置类
│       │   ├── controller/            # REST 控制器
│       │   ├── service/               # 业务逻辑层
│       │   ├── mapper/                # MyBatis 映射器
│       │   ├── entity/                # 实体类
│       │   └── kafka/                 # Kafka 消费者/生产者
│       └── resources/
│           ├── application.yml        # 主配置文件
│           └── shardingsphere.yaml    # 分库分表配置
├── dp-sharding/                        # 分库分表配置模块
├── dp-redisson-framework/             # Redisson 分布式服务框架
│   ├── dp-redisson-service-framework/
│   │   ├── dp-redisson-common-framework/       # Redisson 公共配置
│   │   ├── dp-service-lock-framework/          # 分布式锁
│   │   ├── dp-bloom-filter-framework/          # 布隆过滤器
│   │   └── dp-repeat-execute-limit-framework/  # 防重复执行
│   └── dp-service-delay-queue-framework/       # 延迟队列
├── dp-redis-tool-framework/           # Redis 工具框架
│   ├── dp-redis-common-framework/     # Redis 公共配置
│   ├── dp-redis-framework/            # Redis 基础操作
│   └── dp-redis-rate-limit-framework/ # 令牌桶限流
├── dp-mq-framework/                    # 消息队列框架
│   ├── dp-mq-common-framework/        # MQ 公共配置
│   ├── dp-mq-producer-framework/      # 消息生产者
│   └── dp-mq-consumer-framework/      # 消息消费者
├── dp-id-generator-framework/         # 分布式 ID 生成器
├── dp-parameter/                       # 参数模块
├── dp-vue3/                            # Vue 3 前端项目
├── sql/                                # 数据库初始化脚本
│   ├── 1_create_database.sql          # 建库脚本
│   ├── dp_0.sql                        # 分库 0 数据
│   └── dp_1.sql                        # 分库 1 数据
└── pom.xml                             # Maven 父 POM
```

---

## 🚀 快速开始

### 前置条件

| 软件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17+ | 编译与运行 |
| Maven | 3.8+ | 项目构建 |
| MySQL | 8.0+ | 数据库（需创建 dp_0, dp_1 两个库） |
| Redis | 7.0+ | 缓存与分布式锁 |
| Kafka | 3.0+ | 消息队列 |
| Node.js | 18+ | 前端构建 |

### 1. 克隆项目

```bash
git clone https://github.com/你的用户名/msdp.git
cd msdp
```

### 2. 初始化数据库

```bash
# 连接 MySQL，执行建库脚本
mysql -u root -p < sql/1_create_database.sql

# 导入分库数据
mysql -u root -p dp_0 < sql/dp_0.sql
mysql -u root -p dp_1 < sql/dp_1.sql
```

### 3. 修改配置

编辑 `dp-core-service/src/main/resources/application.yml`：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1      # 改为你的 Redis 地址
      port: 6379            # 改为你的 Redis 端口
  kafka:
    bootstrap-servers: localhost:9092  # 改为你的 Kafka 地址
```

编辑 `dp-core-service/src/main/resources/shardingsphere.yaml`：

```yaml
dataSources:
  ds_0:
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/dp_0?...  # 改为你的 MySQL 连接
    username: root                                    # 改为你的用户名
    password: 1234                                    # 改为你的密码
  ds_1:
    jdbcUrl: jdbc:mysql://127.0.0.1:3306/dp_1?...  # 同上
    username: root
    password: 1234
```

### 4. 启动后端

```bash
# 在项目根目录执行
mvn clean compile
cd dp-core-service
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8085`，Knife4j 文档：`http://localhost:8085/doc.html`

### 5. 启动前端

```bash
cd dp-vue3
npm install        # 或 pnpm install
npm run dev
```

前端默认运行在 `http://localhost:5173`

---

## 🔧 常见问题

### 1. Maven 编译报错找不到依赖
确保先执行 `mvn clean install -DskipTests` 在根目录安装所有模块到本地仓库。

### 2. ShardingSphere 连接失败
检查 `shardingsphere.yaml` 中的分库 `dp_0` 和 `dp_1` 是否已创建，且 MySQL 用户有访问权限。

### 3. Redis 连接失败
检查 Redis 是否启动，`application.yml` 中的 Redis 配置是否正确。

### 4. Kafka 连接失败
确保 Kafka 已启动并创建了相关 Topic。项目使用的主要 Topic 包括秒杀订单、缓存失效等。

### 5. 端口被占用
后端默认 `8085`，前端默认 `5173`。可在配置文件中修改。

---

## 🧪 核心功能演示场景

### 秒杀流程
1. 管理员后台发布秒杀优惠券（设置库存、秒杀时间）
2. 用户在前端查看优惠券列表
3. 秒杀时间到达 → 用户点击抢购 → 令牌校验 → 限流判断 → Redis 扣减库存 → Kafka 异步下单
4. 下单成功后实时通知用户

### 缓存一致性
- 查询时：布隆过滤器 → 本地缓存 → Redis → DB
- 更新时：先更新 DB → 删除 Redis 缓存 → 发布缓存失效事件 → 各节点同步清理本地缓存

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建你的功能分支：`git checkout -b feature/amazing-feature`
3. 提交你的改动：`git commit -m 'feat: add amazing feature'`
4. 推送到远程分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

---

## 📄 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

## 💡 参考资源

- [Spring Boot 官方文档](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/)
- [MyBatis-Plus 文档](https://baomidou.com/)
- [Redisson 文档](https://github.com/redisson/redisson/wiki)
- [ShardingSphere 文档](https://shardingsphere.apache.org/document/current/en/overview/)
- [Vue 3 官方文档](https://cn.vuejs.org/)
- [Element Plus 文档](https://element-plus.org/)

---

## ⭐ Star History

如果这个项目对你有帮助，请点亮 Star 让更多人看到！

---

> 📮 有问题欢迎提 Issue，看到后会尽快回复。

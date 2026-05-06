# 第一阶段：构建阶段（使用包含 Maven 和 JDK 17 的官方镜像）
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# 先复制 pom.xml，利用 Docker 层缓存：依赖不变时跳过重复下载
COPY pom.xml .
RUN mvn dependency:go-offline -q

# 再复制源码并打包（-DskipTests 加速 CI 构建，测试在独立 CI 步骤中运行）
COPY src/ src/
RUN mvn package -DskipTests -q

# 第二阶段：运行阶段（仅保留 JRE，去掉 JDK 和 Maven，镜像更小）
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 创建非 root 用户运行应用，遵循最小权限原则
RUN addgroup -S orderstream && adduser -S orderstream -G orderstream
USER orderstream

# 从构建阶段复制打包好的 jar
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# 启动时通过环境变量覆盖数据库/Kafka 地址（docker-compose 或 App Runner 注入）
ENTRYPOINT ["java", "-jar", "app.jar"]

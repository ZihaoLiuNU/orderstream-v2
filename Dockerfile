# ============================================================================
# 所有服务共用的一个 Dockerfile，靠 --build-arg MODULE=<模块名> 区分。
#
# 为什么不写 7 个 Dockerfile：
#   7 份几乎一样的文件必然会漂移 —— 改基础镜像补安全漏洞时，总会漏掉一两个。
#   一份参数化的 Dockerfile 保证所有服务的运行时环境完全一致。
# ============================================================================

# ---- 第 1 阶段：构建 ----
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# 关键优化：先只复制 pom 文件，把依赖下载单独做成一层。
# Docker 层缓存的规则是「输入没变就复用这一层」。源码天天改，依赖几周才动一次。
# 如果 COPY 整个项目再 mvn package，那么改一行 Java 代码就会让依赖重新下载一遍（几分钟）。
# 分开之后，只要 pom 没改，这一层直接命中缓存（几秒）。
COPY pom.xml .
COPY common/pom.xml               common/
COPY messaging/pom.xml            messaging/
COPY api-gateway/pom.xml          api-gateway/
COPY auth-service/pom.xml         auth-service/
COPY menu-service/pom.xml         menu-service/
COPY cart-service/pom.xml         cart-service/
COPY order-service/pom.xml        order-service/
COPY payment-service/pom.xml      payment-service/
COPY notification-service/pom.xml notification-service/

# go-offline 把所有依赖拉到本地仓库。-B = batch 模式，去掉进度条噪音
RUN mvn -B dependency:go-offline -DskipTests

# 现在才复制源码
COPY common/src               common/src
COPY messaging/src            messaging/src
COPY api-gateway/src          api-gateway/src
COPY auth-service/src         auth-service/src
COPY menu-service/src         menu-service/src
COPY cart-service/src         cart-service/src
COPY order-service/src        order-service/src
COPY payment-service/src      payment-service/src
COPY notification-service/src notification-service/src

ARG MODULE
# -pl 只构建指定模块，-am 同时构建它依赖的模块(common)。
# 跳过测试：测试在 CI 的独立步骤跑，构建镜像时再跑一遍是浪费，
# 而且会让「构建」和「验证」两个职责混在一起 —— 镜像构建失败时你分不清是编译问题还是测试问题
RUN mvn -B -pl ${MODULE} -am package -DskipTests

# jar 名字带版本号，用通配符复制后统一改名，让运行阶段的 ENTRYPOINT 可以写死
RUN cp ${MODULE}/target/*.jar /build/app.jar

# ---- 第 2 阶段：运行 ----
# 只要 JRE，不要 JDK 和 Maven。镜像体积减半，而且攻击面更小 ——
# 镜像里没有编译器和构建工具，攻击者拿到 shell 也没法就地编译 exploit。
#
# ============================================================================
# 【为什么是 jammy(Ubuntu) 而不是 alpine —— 这是实际踩坑后改的】
# ============================================================================
#
# 一开始用的是 eclipse-temurin:21-jre-alpine，镜像确实小（~180MB vs ~250MB）。
# 但跑起来之后 Kafka 生产者持续报错：
#
#     java.lang.NoClassDefFoundError: Could not initialize class org.xerial.snappy.Snappy
#
# 【根因】Alpine 用的是 musl libc，不是主流的 glibc。
#   任何带 JNI 原生组件的 Java 库（snappy-java、部分 netty 的原生传输、
#   某些加解密库）里打包的 .so 文件都是针对 glibc 编译的，
#   在 musl 上加载会直接失败。
#
# 【这个坑的恶劣之处】
#   1) 它在【运行时】才暴露，编译和启动都完全正常
#   2) 报的是 NoClassDefFoundError，看起来像"依赖没引入"，
#      很容易往错误方向排查半天
#   3) 只在用到那个特定功能时才触发 —— 我是在发第一条 Kafka 消息时才发现的
#
# 【教训】：Alpine 省下的一百多 MB，远不值得这类问题的排查成本。
#   除非镜像体积是硬约束（比如边缘设备、超大规模集群），
#   服务端 Java 应用应该默认用 glibc 基础镜像。
#
# 【另一种解法】是把 Kafka 压缩算法从 snappy 换成 gzip（纯 Java 实现）。
#   但那是绕过症状，不是解决根因 —— 下一个带原生库的依赖还会再炸一次。
FROM eclipse-temurin:21-jre-jammy

# 安装 curl 供健康检查使用（jammy 的 JRE 镜像默认不带 wget/curl）。
# --no-install-recommends + 清理 apt 缓存，是为了不把几十 MB 的无关包
# 和缓存文件留在镜像层里 —— 每多一个包就多一份潜在的 CVE
RUN apt-get update  && apt-get install -y --no-install-recommends curl  && rm -rf /var/lib/apt/lists/*

# 非 root 用户运行。容器逃逸漏洞的危害程度直接取决于容器内进程的权限：
# 以 root 跑的话，一旦逃逸就是宿主机 root。
# Debian/Ubuntu 的用户创建命令和 Alpine 不同（Alpine 用的是 busybox 版 adduser -S）
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=builder --chown=app:app /build/app.jar app.jar
USER app

EXPOSE 8080

# JVM 参数说明：
#   MaxRAMPercentage=75 —— 容器里【绝不能】用默认堆大小。JVM 会按宿主机内存算堆，
#     而 cgroup 限制的是容器内存，结果就是堆超过容器上限被 OOMKilled（退出码 137）。
#     这个参数让 JVM 按容器的内存限制来算。这是容器化 Java 最经典的坑。
#   UseG1GC —— 默认就是它，写出来是为了明确；G1 在几 GB 堆上的停顿时间比 Parallel GC 可控
#   ExitOnOutOfMemoryError —— OOM 后直接退出让编排器重启，
#     而不是留一个半死不活、一直报错却还在接流量的进程
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]

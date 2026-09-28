# 迭代 29 · 一条命令起服务。
#
# 多阶段构建：第一阶段用 Maven 镜像编译出 jar，第二阶段只带 JRE ——
# 最终镜像里没有 Maven、没有源码，也没有 .git。
#
# 【为什么运行期锁 17，而我本机是 JDK 25】
# pom 的 maven.compiler.target 是 17。镜像用 17 跑，保证「编译期和运行期是同一个版本」；
# 本机用 25 只是开发方便，不是运行环境。
#
# 【为什么必须显式设 UTF-8】
# 这个项目的日志、语料、提示词全是中文。容器默认 locale 不是 UTF-8 时，
# 控制台输出会变成乱码 —— 而它**不影响程序运行**，属于「不响的错」，
# 要靠人去看日志才发现。所以这里显式设了三处：LANG / LC_ALL / -Dfile.encoding。
#
# 【为什么没做「先拷 pom 拉依赖」这一层缓存】
# 那样确实能让改代码时不重下依赖，但 `mvn dependency:go-offline` 在
# 多仓库（本项目 pom 里配了 aliyun 仓库）的场景下**会偶发失败**，
# 而我没法在本地验证它到底成不成 —— 所以宁可不做这层优化，
# 换一个「肯定能跑通」的构建。（这条和项目里「不交付没验证过的东西」是同一条规矩。）

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src

# -DskipTests：本项目 test 目录是空的（无源码），加这个只是让意图明确
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

# curl 只给 healthcheck 用；没有它就得靠 bash 的 /dev/tcp，
# 而 CMD-SHELL 默认走的是 dash，不支持 /dev/tcp —— 装 curl 比赌 shell 更省事。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai

# repackage 之后 target 下只有【一个】 .jar（原始包被改名成 .jar.original）
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# -Dfile.encoding=UTF-8：上面那三个环境变量管的是 shell，这个是管 JVM 的
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]

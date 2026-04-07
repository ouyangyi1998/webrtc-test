# ===== 阶段1: 构建 =====
FROM maven:3.8-openjdk-8-slim AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ===== 阶段2: 运行 =====
FROM openjdk:8-jre-slim
WORKDIR /app
COPY --from=builder /app/target/webrtc-demo-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

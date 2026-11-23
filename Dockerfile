# 构建阶段：编译并打包
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY docker/maven-settings.xml settings.xml
RUN mvn -q -B -s settings.xml dependency:go-offline
COPY src ./src
RUN mvn -q -B -s settings.xml -DskipTests package

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/clearing-resilience-service.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

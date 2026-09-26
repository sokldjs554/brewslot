# 서비스 공용 이미지. 사용: docker build --build-arg SERVICE=order-service -t brewslot/order-service .
FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY libs libs
COPY services services
COPY e2e/build.gradle.kts e2e/build.gradle.kts
RUN ./gradlew :services:${SERVICE}:bootJar -x test --no-daemon -q \
 && cp services/${SERVICE}/build/libs/${SERVICE}-*[!n].jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

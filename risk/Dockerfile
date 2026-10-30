# syntax=docker/dockerfile:1
# risk 이미지. 멀티모듈이라 저장소 루트에서 빌드한다:
#   docker build -f risk/Dockerfile -t futures-risk-ops-risk:local .
# 키·DB 계정은 이미지에 넣지 않고 실행할 때 환경변수로 받는다(compose.yaml).

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# Windows 에서 복사하면 실행 권한이 빠질 수 있어 다시 준다. 테스트는 이미지 밖(./gradlew test)에서 돌린다
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew --no-daemon -q :risk:bootJar \
    && find risk/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# 컨테이너 안에서는 모든 주소에서 받아야 다른 컨테이너·호스트가 붙는다. 호스트 쪽 공개는 compose 가 127.0.0.1 로 묶는다
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

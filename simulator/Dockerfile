# syntax=docker/dockerfile:1
# simulator 이미지. 멀티모듈이라 저장소 루트에서 빌드한다:
#   docker build -f simulator/Dockerfile -t futures-risk-ops-simulator:local .
# 합성 시세만 내보낸다. 키가 필요 없다.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# Windows 에서 복사하면 실행 권한이 빠질 수 있어 다시 준다. 테스트는 이미지 밖(./gradlew test)에서 돌린다
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew --no-daemon -q :simulator:installDist

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home --uid 10001 app
WORKDIR /app
COPY --from=build /src/simulator/build/install/simulator/ /app/
USER app
# 컨테이너 안에서는 모든 주소에서 받는다(옵션 server.host). 다른 옵션도 SIM_* 환경변수나 명령 인자로 바꾼다(--help)
ENV SIM_SERVER_HOST=0.0.0.0
EXPOSE 18080
ENTRYPOINT ["/app/bin/simulator"]

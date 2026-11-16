package com.futuresriskops.collector.rest;

import java.util.Map;

/**
 * KIS REST 호출에 쓰는 앱키·시크릿. 환경변수에서만 만든다(이름은 프로필이 정한다).
 * {@link #toString()} 은 값을 내지 않는다. 로그나 예외 메시지에 실수로 들어가도 값이 새지 않게 한다.
 */
public record KisCredentials(String appKey, String appSecret) {

    /**
     * @param env          보통 {@code System.getenv()}
     * @param appKeyEnv    앱키를 담은 환경변수 이름. 없거나 비어 있으면 여기서 바로 실패한다(메시지에는 이름만 쓴다)
     * @param appSecretEnv 시크릿을 담은 환경변수 이름
     */
    public static KisCredentials fromEnv(Map<String, String> env, String appKeyEnv, String appSecretEnv) {
        return new KisCredentials(required(env, appKeyEnv), required(env, appSecretEnv));
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is required for KIS REST calls (set in the terminal that runs the collector)");
        }
        return v.trim();
    }

    @Override
    public String toString() {
        return "KisCredentials[appKey=***, appSecret=***]";
    }
}

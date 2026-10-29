package com.futuresriskops.collector.ws;

import java.time.Instant;
import java.util.Optional;

/** 웹소켓 접속키(approval_key)를 준다. 시뮬레이터는 아무 값이나 받고, KIS 는 /oauth2/Approval 로 받는다. */
@FunctionalInterface
public interface ApprovalKeyProvider {

    String approvalKey();

    /** 지금 들고 있는 접속키가 만료되는 시각. 아직 받지 않았거나 만료가 없는 키(시뮬레이터)면 비어 있다. */
    default Optional<Instant> expiresAt() {
        return Optional.empty();
    }

    /** 시뮬레이터용. 접속키를 검사하지 않으므로 실제 키를 쓰지 않는다. */
    static ApprovalKeyProvider simulator() {
        return () -> "simulator";
    }
}

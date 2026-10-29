package com.futuresriskops.collector.instrument;

import java.time.Instant;
import java.util.List;

/** 선물 종목을 저장하고 읽는 곳. 운영은 {@link InstrumentRepository}(PostgreSQL), 테스트는 메모리. */
public interface InstrumentStore {

    int upsertFutures(List<FuturesContract> contracts, Instant at);

    List<FuturesContract> loadFutures();

    /** DB 없이 돌릴 때(테스트, 시뮬레이터만 쓸 때). 저장하지 않고 늘 비어 있다. */
    static InstrumentStore none() {
        return new InstrumentStore() {
            @Override
            public int upsertFutures(List<FuturesContract> contracts, Instant at) {
                return 0;
            }

            @Override
            public List<FuturesContract> loadFutures() {
                return List.of();
            }
        };
    }
}

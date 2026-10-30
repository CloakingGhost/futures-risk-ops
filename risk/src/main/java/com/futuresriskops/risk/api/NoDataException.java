package com.futuresriskops.risk.api;

/** 요청한 출처·종목의 체결이 아직 없다(수집 전이거나 종목코드가 틀림). 404 로 돌려준다. */
class NoDataException extends RuntimeException {

    NoDataException(String message) {
        super(message);
    }
}

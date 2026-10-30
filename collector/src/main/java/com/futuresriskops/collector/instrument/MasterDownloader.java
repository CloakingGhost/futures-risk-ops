package com.futuresriskops.collector.instrument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * KIS 종목 마스터 zip 을 받아 안의 .mst 파일 내용을 돌려준다. 파일은 디스크에 남기지 않는다.
 * 주소는 공식 샘플(stocks_info/domestic_index_future_code.py)의 값을 설정으로 둔다. 인증은 필요 없다.
 */
public final class MasterDownloader implements MasterRefresher.Source {

    /** zip 이 이보다 크면 잘못 받은 것으로 본다(실제 파일은 1MB 안쪽). */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private final OkHttpClient http;
    private final String url;

    public MasterDownloader(OkHttpClient http, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("collector.instruments.master.url is required when master download is on");
        }
        // 수집기 공용 클라이언트는 읽기 제한이 없다(웹소켓용). 다운로드는 오래 걸리면 끊는다
        this.http = http.newBuilder().readTimeout(Duration.ofSeconds(30)).callTimeout(Duration.ofSeconds(60)).build();
        this.url = url;
    }

    @Override
    public byte[] download() throws IOException {
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) {
                throw new IOException("master download failed: HTTP " + r.code());
            }
            if (body.contentLength() > MAX_BYTES) {
                throw new IOException("master zip is too large: " + body.contentLength() + " bytes");
            }
            return unzipMst(body.bytes());
        }
    }

    /** zip 안의 첫 .mst 파일 내용. */
    static byte[] unzipMst(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (!e.isDirectory() && e.getName().endsWith(".mst")) {
                    return readLimited(in);
                }
            }
        }
        throw new IOException("no .mst file in the master zip");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] b = in.readNBytes((int) MAX_BYTES + 1);
        if (b.length > MAX_BYTES) {
            throw new IOException("master file is too large");
        }
        return b;
    }
}

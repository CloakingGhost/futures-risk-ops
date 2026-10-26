package com.futuresriskops.simulator.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 6455 서버 쪽 연결 하나. 핸드셰이크, 프레임 읽기·쓰기, ping/pong/close 만 구현한다
 * (압축·바이너리 처리·확장은 없음). 쓰기는 직렬화되어 여러 스레드에서 불러도 된다.
 */
public final class WebSocketConnection implements AutoCloseable {

    static final int OP_CONTINUATION = 0x0;
    static final int OP_TEXT = 0x1;
    static final int OP_BINARY = 0x2;
    static final int OP_CLOSE = 0x8;
    static final int OP_PING = 0x9;
    static final int OP_PONG = 0xA;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    /** 수신 프레임. */
    record Frame(boolean fin, int opcode, byte[] payload) {
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final String remote;
    private volatile boolean closed;

    private WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.remote = String.valueOf(socket.getRemoteSocketAddress());
    }

    /**
     * HTTP 업그레이드 요청을 읽고 101 로 응답한다. 웹소켓 요청이 아니면 400 을 보내고 null 을 돌려준다.
     */
    static WebSocketConnection accept(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());

        Map<String, String> headers = new HashMap<>();
        String requestLine = readHttpHead(in, headers);
        String key = headers.get("sec-websocket-key");
        boolean upgrade = "websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))
                && headers.getOrDefault("connection", "").toLowerCase(Locale.ROOT).contains("upgrade");
        if (requestLine == null || !requestLine.startsWith("GET ") || !upgrade || key == null
                || !"13".equals(headers.get("sec-websocket-version"))) {
            byte[] body = "This is a WebSocket endpoint (RFC 6455, version 13).\n".getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            return null;
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        socket.setSoTimeout(0);
        return new WebSocketConnection(socket, in, out);
    }

    static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readHttpHead(InputStream in, Map<String, String> headers) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0; // \r\n\r\n 매칭 단계
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (buf.size() >= MAX_HEADER_BYTES) {
                return null;
            }
            buf.write(b);
            if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) {
                state++;
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        String[] lines = buf.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }
        return lines.length == 0 ? null : lines[0];
    }

    public String remote() {
        return remote;
    }

    public boolean isOpen() {
        return !closed;
    }

    // ------------------------------------------------------------------ 읽기

    /** 프레임 하나를 읽는다. 연결이 끝나면 null. */
    Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = readByte();
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte();
            }
        }
        if (!masked) {
            throw new ProtocolException(1002, "client frames must be masked");
        }
        if (len < 0 || len > MAX_MESSAGE_BYTES) {
            throw new ProtocolException(1009, "frame too large");
        }
        byte[] mask = new byte[4];
        readFully(mask);
        byte[] payload = new byte[(int) len];
        readFully(payload);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, opcode, payload);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("connection closed in the middle of a frame");
        }
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("connection closed in the middle of a frame");
            }
            off += n;
        }
    }

    static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;
        final int closeCode;

        ProtocolException(int closeCode, String message) {
            super(message);
            this.closeCode = closeCode;
        }
    }

    static int maxMessageBytes() {
        return MAX_MESSAGE_BYTES;
    }

    // ------------------------------------------------------------------ 쓰기

    /** 텍스트 프레임을 보낸다. 연결이 이미 끝났거나 쓰기에 실패하면 false. */
    public boolean sendText(String text) {
        return send(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    boolean sendPong(byte[] payload) {
        return send(OP_PONG, payload);
    }

    private boolean send(int opcode, byte[] payload) {
        synchronized (writeLock) {
            if (closed) {
                return false;
            }
            try {
                out.write(0x80 | opcode);
                if (payload.length < 126) {
                    out.write(payload.length);
                } else if (payload.length <= 0xFFFF) {
                    out.write(126);
                    out.write(payload.length >> 8);
                    out.write(payload.length & 0xFF);
                } else {
                    out.write(127);
                    long n = payload.length;
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) (n >> shift) & 0xFF);
                    }
                }
                out.write(payload);
                out.flush();
                return true;
            } catch (IOException e) {
                markClosedAndCloseSocket();
                return false;
            }
        }
    }

    /** close 프레임을 보내고 소켓을 닫는다(정상 종료). */
    public void close(int code, String reason) {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            try {
                byte[] r = reason.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[2 + r.length];
                payload[0] = (byte) (code >> 8);
                payload[1] = (byte) code;
                System.arraycopy(r, 0, payload, 2, r.length);
                out.write(0x80 | OP_CLOSE);
                out.write(payload.length);
                out.write(payload);
                out.flush();
            } catch (IOException ignored) {
                // 상대가 이미 끊었으면 그대로 닫는다
            } finally {
                markClosedAndCloseSocket();
            }
        }
    }

    /** close 프레임 없이 TCP 를 리셋해 비정상 종료를 흉내 낸다. */
    public void abort() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            try {
                socket.setSoLinger(true, 0);
            } catch (IOException ignored) {
                // 옵션 설정에 실패해도 닫기는 진행
            }
            markClosedAndCloseSocket();
        }
    }

    @Override
    public void close() {
        close(1000, "");
    }

    private void markClosedAndCloseSocket() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }
}

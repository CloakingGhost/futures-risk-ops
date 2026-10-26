package com.futuresriskops.simulator.ws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 연결마다 스레드 하나를 쓰는 아주 작은 웹소켓 서버. 요청 경로는 검사하지 않는다. */
public final class WebSocketServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(WebSocketServer.class.getName());

    /** 콜백은 연결의 읽기 스레드에서 불린다. */
    public interface Listener {
        void onOpen(WebSocketConnection conn);

        void onText(WebSocketConnection conn, String text);

        void onPong(WebSocketConnection conn, byte[] payload);

        void onClose(WebSocketConnection conn);
    }

    private final String host;
    private final int requestedPort;
    private final Listener listener;
    private final Set<WebSocketConnection> open = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ws-conn-" + threadCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket serverSocket;

    public WebSocketServer(String host, int port, Listener listener) {
        this.host = host;
        this.requestedPort = port;
        this.listener = listener;
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName(host), requestedPort), 64);
        serverSocket = ss;
        Thread acceptor = new Thread(this::acceptLoop, "ws-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** 실제로 열린 포트(설정이 0 이었을 때 확인용). */
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        ServerSocket ss = serverSocket;
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    LOG.log(System.Logger.Level.WARNING, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        WebSocketConnection conn = null;
        try {
            conn = WebSocketConnection.accept(socket);
            if (conn == null) {
                socket.close();
                return;
            }
            open.add(conn);
            listener.onOpen(conn);
            readLoop(conn);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "connection ended: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "unexpected error in connection handler", e);
        } finally {
            if (conn != null) {
                open.remove(conn);
                conn.abort();
                try {
                    listener.onClose(conn);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "onClose failed", e);
                }
            } else {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 이미 닫힘
                }
            }
        }
    }

    private void readLoop(WebSocketConnection conn) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        boolean inText = false;
        while (conn.isOpen()) {
            WebSocketConnection.Frame f;
            try {
                f = conn.readFrame();
            } catch (WebSocketConnection.ProtocolException e) {
                conn.close(e.closeCode, e.getMessage());
                return;
            }
            if (f == null) {
                return;
            }
            switch (f.opcode()) {
                case WebSocketConnection.OP_PING -> conn.sendPong(f.payload());
                case WebSocketConnection.OP_PONG -> listener.onPong(conn, f.payload());
                case WebSocketConnection.OP_CLOSE -> {
                    conn.close(f.payload().length >= 2 ? ((f.payload()[0] & 0xFF) << 8) | (f.payload()[1] & 0xFF) : 1000, "");
                    return;
                }
                case WebSocketConnection.OP_TEXT, WebSocketConnection.OP_CONTINUATION -> {
                    if (f.opcode() == WebSocketConnection.OP_TEXT) {
                        message.reset();
                        inText = true;
                    } else if (!inText) {
                        conn.close(1002, "unexpected continuation frame");
                        return;
                    }
                    message.write(f.payload());
                    if (message.size() > WebSocketConnection.maxMessageBytes()) {
                        conn.close(1009, "message too large");
                        return;
                    }
                    if (f.fin()) {
                        inText = false;
                        listener.onText(conn, message.toString(StandardCharsets.UTF_8));
                    }
                }
                default -> {
                    // 바이너리 등 지원하지 않는 프레임은 무시
                }
            }
        }
    }

    @Override
    public void close() {
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
        for (WebSocketConnection c : open) {
            c.close(1001, "server shutting down");
        }
        pool.shutdownNow();
    }
}

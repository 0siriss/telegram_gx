package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseArray;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WEB proxy carrier: the MTProxy byte stream produced by tgnet is taken off a loopback
 * socket, split into protocol v1 frames and multiplexed through a private WebView that
 * talks HTTPS to the relay. See PROTOCOL.md of telegramdesktop/tproxy-server.
 *
 * tgnet is not modified: it still performs its own MTProxy transform and still believes
 * it is talking to an ordinary proxy, so DPI shaping and fake-TLS stay on the MTProto
 * path only and never apply to this carrier.
 */
public final class WebProxyTransport {

    public static final int FRAME_OPEN = 0x01;
    public static final int FRAME_DATA = 0x02;
    public static final int FRAME_CLOSE = 0x03;
    public static final int FRAME_WINDOW = 0x04;
    public static final int FRAME_PING = 0x05;
    public static final int FRAME_PONG = 0x06;
    public static final int FRAME_HELLO = 0x10;
    public static final int FRAME_WELCOME = 0x11;
    public static final int FRAME_BYE = 0x1f;

    /** Marks a link secret that carries a base path. Never 0xDD: old clients accept that one. */
    public static final int LINK_SECRET_MARKER = 0x70;

    private static final String BRIDGE_OBJECT = "TelegramWebProxy";
    private static final String CONTEXT_V1 = "tdesktop-web-proxy-bridge-v1\n";
    private static final String CONTEXT_V2 = "tdesktop-web-proxy-bridge-v2\n";

    private static final int FRAME_HEADER_SIZE = 8;
    private static final int MAX_PAYLOAD = 1024 * 1024;
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int INITIAL_WINDOW = 4 * 1024 * 1024;
    private static final int MAX_PENDING_UPLINK = 8 * 1024 * 1024;
    private static final int MAX_STREAM_ID = 0xffffff;

    private static final long RETRY_MIN_DELAY = 1000;
    private static final long RETRY_MAX_DELAY = 30000;

    private static volatile WebProxyTransport instance;

    public static WebProxyTransport getInstance() {
        WebProxyTransport local = instance;
        if (local == null) {
            synchronized (WebProxyTransport.class) {
                local = instance;
                if (local == null) {
                    instance = local = new WebProxyTransport();
                }
            }
        }
        return local;
    }

    private final Handler mux;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SparseArray<Stream> streams = new SparseArray<>();
    private final ArrayList<byte[]> pendingUplink = new ArrayList<>();

    private String host;
    private String basePath;
    private byte[] secret;

    private ServerSocket serverSocket;
    private int localPort;
    private Thread acceptThread;

    private WebView webView;
    private JavaScriptReplyProxy replyProxy;
    private String nonce;
    private boolean welcomed;
    private int nextStreamId = 1;
    private int pendingUplinkBytes;
    private long retryDelay = RETRY_MIN_DELAY;
    private int carrierGeneration;

    private WebProxyTransport() {
        HandlerThread thread = new HandlerThread("WebProxyTransport");
        thread.start();
        mux = new Handler(thread.getLooper());
    }

    // region public API

    /**
     * True when this device can carry a WEB proxy at all. A missing WebView feature fails
     * closed — tgnet is pointed at a dead loopback port rather than at Telegram directly.
     */
    public static boolean isSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
                    && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Binds the loopback listener and starts the carrier. Returns the port tgnet must be
     * pointed at, or 0 when the address or secret cannot be used.
     */
    public int start(String address, String secretText) {
        final String canonicalHost = hostOf(address);
        final String path = basePathOf(address);
        final byte[] secretBytes = decodeSecret(secretText);
        if (canonicalHost == null || path == null || secretBytes == null) {
            stop();
            return 0;
        }
        synchronized (this) {
            if (serverSocket != null
                    && canonicalHost.equals(host)
                    && TextUtils.equals(path, basePath)
                    && java.util.Arrays.equals(secretBytes, secret)) {
                return localPort;
            }
        }
        stop();
        synchronized (this) {
            host = canonicalHost;
            basePath = path;
            secret = secretBytes;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
                localPort = serverSocket.getLocalPort();
            } catch (IOException e) {
                FileLog.e(e);
                serverSocket = null;
                localPort = 0;
                return 0;
            }
            final ServerSocket bound = serverSocket;
            acceptThread = new Thread(() -> acceptLoop(bound), "WebProxyAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
        mux.post(this::startCarrier);
        return localPort;
    }

    public void stop() {
        final ServerSocket toClose;
        synchronized (this) {
            toClose = serverSocket;
            serverSocket = null;
            localPort = 0;
            acceptThread = null;
            host = null;
            basePath = null;
            secret = null;
        }
        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException ignore) {
            }
        }
        mux.post(() -> dropCarrier(false));
    }

    // endregion

    // region link helpers

    /** Lowercased host part of a {@code host} or {@code host/base/path} address, or null. */
    @Nullable
    public static String hostOf(String address) {
        if (TextUtils.isEmpty(address)) {
            return null;
        }
        int slash = address.indexOf('/');
        String value = (slash < 0 ? address : address.substring(0, slash)).toLowerCase();
        if (value.isEmpty() || value.length() > 253 || value.indexOf(':') >= 0
                || value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-')) {
                return null;
            }
        }
        // An IPv4 literal is not a DNS name and would defeat the TLS-name cover.
        return value.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") ? null : value;
    }

    /**
     * Base path of a {@code host/base/path} address: one or more {@code [A-Za-z0-9][A-Za-z0-9_-]*}
     * segments, case-sensitive, no leading or trailing slash. Empty string when there is none,
     * null when the value is present but malformed.
     */
    @Nullable
    public static String basePathOf(String address) {
        if (TextUtils.isEmpty(address)) {
            return null;
        }
        int slash = address.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String path = address.substring(slash + 1);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        if (path.length() > 128) {
            return null;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || !segment.matches("^[A-Za-z0-9][A-Za-z0-9_-]*$")) {
                return null;
            }
        }
        return path;
    }

    /** True when the address and secret together form a usable WEB proxy entry. */
    public static boolean isValidAddress(String address, String secretText) {
        return hostOf(address) != null
                && basePathOf(address) != null
                && decodeSecret(secretText) != null;
    }

    /**
     * MTProxy secret bytes of a stored WEB entry, or null. The leading {@code dd} byte of a
     * random-padding secret is part of the key and is kept.
     */
    @Nullable
    public static byte[] decodeSecret(String secretText) {
        if (TextUtils.isEmpty(secretText)) {
            return null;
        }
        byte[] bytes = null;
        if (secretText.length() % 2 == 0 && secretText.matches("^[0-9a-fA-F]+$")) {
            try {
                bytes = Utilities.hexToBytes(secretText);
            } catch (Exception ignore) {
            }
        }
        if (bytes == null) {
            try {
                bytes = Base64.decode(secretText, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            } catch (Exception ignore) {
                return null;
            }
        }
        if (bytes == null) {
            return null;
        }
        // WEB only carries a plain 16-byte secret or a 17-byte dd-prefixed one. A 0xee
        // fake-TLS secret belongs to the ordinary MTProto path, not to this carrier.
        if (bytes.length == 16) {
            return bytes;
        }
        if (bytes.length == 17 && (bytes[0] & 0xff) == 0xdd) {
            return bytes;
        }
        return null;
    }

    /**
     * Decodes the {@code secret} of a webproxy link. Returns the canonical hex secret, or null
     * when the link is unusable. A link that carries a base path must use the marked form.
     */
    @Nullable
    public static String decodeLinkSecret(String linkSecret, boolean hasBasePath) {
        if (TextUtils.isEmpty(linkSecret)) {
            return null;
        }
        byte[] marked = null;
        try {
            marked = Base64.decode(linkSecret, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception ignore) {
        }
        if (marked != null && marked.length >= 17 && (marked[0] & 0xff) == LINK_SECRET_MARKER) {
            byte[] plain = new byte[marked.length - 1];
            System.arraycopy(marked, 1, plain, 0, plain.length);
            return decodeSecret(Utilities.bytesToHex(plain)) != null ? Utilities.bytesToHex(plain) : null;
        }
        if (hasBasePath) {
            return null;
        }
        byte[] plain = decodeSecret(linkSecret);
        return plain != null ? Utilities.bytesToHex(plain) : null;
    }

    /** Encodes a stored secret for a share link: plain hex at the root, marked base64url under a path. */
    public static String encodeLinkSecret(String secretText, boolean hasBasePath) {
        byte[] plain = decodeSecret(secretText);
        if (plain == null) {
            return "";
        }
        if (!hasBasePath) {
            return Utilities.bytesToHex(plain);
        }
        byte[] marked = new byte[plain.length + 1];
        marked[0] = (byte) LINK_SECRET_MARKER;
        System.arraycopy(plain, 0, marked, 1, plain.length);
        return Base64.encodeToString(marked, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /**
     * Bridge capability: base64url(HMAC-SHA256(secret, context)). The root context is the frozen
     * v1 label; a base path binds host and path through v2.
     */
    @Nullable
    public static String bridgeCapability(String address, String secretText) {
        final String canonicalHost = hostOf(address);
        final String path = basePathOf(address);
        final byte[] key = decodeSecret(secretText);
        if (canonicalHost == null || path == null || key == null) {
            return null;
        }
        final String context = path.isEmpty()
                ? CONTEXT_V1 + canonicalHost
                : CONTEXT_V2 + canonicalHost + "\n" + path;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // endregion

    // region frames

    static byte[] frame(int type, int streamId, @Nullable byte[] payload, int offset, int length) {
        byte[] out = new byte[FRAME_HEADER_SIZE + length];
        out[0] = (byte) type;
        out[1] = (byte) (streamId >> 16);
        out[2] = (byte) (streamId >> 8);
        out[3] = (byte) streamId;
        out[4] = (byte) (length >> 24);
        out[5] = (byte) (length >> 16);
        out[6] = (byte) (length >> 8);
        out[7] = (byte) length;
        if (length > 0) {
            System.arraycopy(payload, offset, out, FRAME_HEADER_SIZE, length);
        }
        return out;
    }

    private static byte[] windowFrame(int streamId, int delta) {
        byte[] payload = new byte[]{
                (byte) (delta >> 24), (byte) (delta >> 16), (byte) (delta >> 8), (byte) delta
        };
        return frame(FRAME_WINDOW, streamId, payload, 0, payload.length);
    }

    // endregion

    // region loopback

    private void acceptLoop(ServerSocket bound) {
        while (true) {
            final Socket socket;
            try {
                socket = bound.accept();
            } catch (IOException e) {
                return;
            }
            try {
                socket.setTcpNoDelay(true);
            } catch (IOException ignore) {
            }
            mux.post(() -> registerStream(socket, bound));
        }
    }

    private void registerStream(Socket socket, ServerSocket owner) {
        synchronized (this) {
            if (serverSocket != owner) {
                closeQuietly(socket);
                return;
            }
        }
        if (!isSupported()) {
            // The listener stays bound so tgnet never falls back to a direct connection, but a
            // device without the required WebView features can never carry the stream.
            closeQuietly(socket);
            return;
        }
        if (nextStreamId > MAX_STREAM_ID) {
            // Stream ids are never reused inside a session, so exhausting them replaces the carrier.
            closeQuietly(socket);
            resetCarrier();
            return;
        }
        final Stream stream = new Stream(nextStreamId++, socket);
        streams.put(stream.id, stream);
        sendFrame(frame(FRAME_OPEN, stream.id, null, 0, 0));
        stream.startReader();
        stream.startWriter();
    }

    private static void closeQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }

    // endregion

    // region carrier

    private void startCarrier() {
        final String currentHost;
        final String path;
        final String capability;
        synchronized (this) {
            if (serverSocket == null || host == null) {
                return;
            }
            currentHost = host;
            path = basePath.isEmpty() ? "/" : "/" + basePath + "/";
            capability = bridgeCapability(basePath.isEmpty() ? host : host + "/" + basePath,
                    Utilities.bytesToHex(secret));
        }
        if (capability == null || !isSupported()) {
            return;
        }
        final byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        nonce = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        welcomed = false;
        final int generation = ++carrierGeneration;
        final String url = "https://" + currentHost + path + "?bridge=" + capability + "#android=" + nonce;
        final String origin = "https://" + currentHost;
        ui.post(() -> createWebView(generation, currentHost, origin, url));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(int generation, String currentHost, String origin, String url) {
        if (generation != carrierGeneration) {
            return;
        }
        destroyWebView();
        final WebView view;
        try {
            view = new WebView(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            FileLog.e(e);
            mux.post(this::scheduleRetry);
            return;
        }
        final WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkImage(true);
        settings.setLoadsImagesAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final Set<String> allowed = new HashSet<>(Collections.singletonList(origin));
        view.setWebViewClient(new CarrierClient(generation, currentHost, url));
        view.setDownloadListener((downloadUrl, agent, disposition, mime, size) -> {
        });
        try {
            WebViewCompat.addDocumentStartJavaScript(view, hardeningScript(currentHost), allowed);
            WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, allowed,
                    new CarrierListener(generation, origin));
        } catch (Throwable e) {
            FileLog.e(e);
            view.destroy();
            mux.post(this::scheduleRetry);
            return;
        }
        webView = view;
        view.loadUrl(url);
    }

    private static String hardeningScript(String host) {
        // A second CSP independent of the provider's response headers, plus inert shims for the
        // browser facilities the bridge never uses. The CSP and the native policy remain the
        // boundary; the shims only reduce surface.
        final String csp = "default-src 'none'; base-uri 'none'; child-src 'none'; "
                + "connect-src https://" + host + " wss://" + host + "; font-src 'none'; "
                + "form-action 'none'; frame-src 'none'; img-src 'none'; manifest-src 'none'; "
                + "media-src 'none'; object-src 'none'; script-src 'unsafe-inline'; "
                + "style-src 'none'; worker-src 'none'";
        return "(()=>{'use strict';"
                + "const root=document.documentElement;"
                + "const meta=(equiv,content)=>{const m=document.createElement('meta');"
                + "m.setAttribute('http-equiv',equiv);m.setAttribute('content',content);"
                + "root.insertBefore(m,root.firstChild)};"
                + "meta('Content-Security-Policy'," + jsString(csp) + ");"
                + "meta('x-dns-prefetch-control','off');"
                + "const dead=name=>{try{Object.defineProperty(window,name,"
                + "{configurable:false,get(){throw new Error('unavailable')}})}catch(e){}};"
                + "['localStorage','sessionStorage','indexedDB','caches','Worker','SharedWorker',"
                + "'BroadcastChannel','AudioContext','webkitAudioContext','open']"
                + ".forEach(dead);"
                + "const inert=()=>undefined;"
                + "['print','alert','confirm','prompt'].forEach(name=>{"
                + "try{Object.defineProperty(window,name,{configurable:false,value:inert})}catch(e){}});"
                + "try{Object.defineProperty(document,'cookie',"
                + "{configurable:false,get:()=>'',set:()=>{}})}catch(e){}"
                + "})();";
    }

    private static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private final class CarrierClient extends WebViewClientCompat {

        private final int generation;
        private final String host;
        private final String url;

        CarrierClient(int generation, String host, String url) {
            this.generation = generation;
            this.host = host;
            this.url = url;
        }

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
            // Only the one canonical bridge URL may ever be the main frame.
            return !(request.isForMainFrame() && url.equals(request.getUrl().toString()));
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(@NonNull WebView view, @NonNull WebResourceRequest request) {
            final Uri uri = request.getUrl();
            final String scheme = uri.getScheme();
            if (scheme == null) {
                return blocked();
            }
            if (!"https".equals(scheme) && !"wss".equals(scheme)) {
                return blocked();
            }
            final int port = uri.getPort();
            if (!host.equalsIgnoreCase(uri.getHost()) || (port != -1 && port != 443)) {
                return blocked();
            }
            return null;
        }

        @Override
        public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull androidx.webkit.WebResourceErrorCompat error) {
            if (request.isForMainFrame()) {
                mux.post(() -> onCarrierLost(generation));
            }
        }

        @Override
        public boolean onRenderProcessGone(@NonNull WebView view, @NonNull RenderProcessGoneDetail detail) {
            mux.post(() -> onCarrierLost(generation));
            return true;
        }

        private WebResourceResponse blocked() {
            final InputStream empty = new ByteArrayInputStream(new byte[0]);
            return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                    Collections.emptyMap(), empty);
        }
    }

    private final class CarrierListener implements WebViewCompat.WebMessageListener {

        private final int generation;
        private final String origin;

        CarrierListener(int generation, String origin) {
            this.generation = generation;
            this.origin = origin;
        }

        @Override
        public void onPostMessage(@NonNull WebView view, @NonNull WebMessageCompat message,
                                  @NonNull Uri sourceOrigin, boolean isMainFrame,
                                  @NonNull JavaScriptReplyProxy proxy) {
            if (!isMainFrame || view != webView || !origin.equals(sourceOrigin.toString())) {
                return;
            }
            if (message.getType() == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                final byte[] data = message.getArrayBuffer();
                mux.post(() -> onCarrierFrame(generation, data));
            } else {
                final String text = message.getData();
                mux.post(() -> onCarrierControl(generation, proxy, text));
            }
        }
    }

    private void onCarrierControl(int generation, JavaScriptReplyProxy proxy, @Nullable String text) {
        if (generation != carrierGeneration || text == null) {
            return;
        }
        final String type;
        final String messageNonce;
        try {
            JSONObject object = new JSONObject(text);
            type = object.optString("t");
            messageNonce = object.optString("nonce");
        } catch (Exception e) {
            return;
        }
        if ("tproxy-android-init".equals(type)) {
            if (nonce == null || !nonce.equals(messageNonce) || replyProxy != null) {
                return;
            }
            replyProxy = proxy;
            retryDelay = RETRY_MIN_DELAY;
            sendFrame(frame(FRAME_HELLO, 0, new byte[]{0x01}, 0, 1));
        } else if ("close".equals(type)) {
            onCarrierLost(generation);
        }
    }

    private void onCarrierFrame(int generation, @Nullable byte[] data) {
        if (generation != carrierGeneration || data == null || data.length < FRAME_HEADER_SIZE) {
            return;
        }
        final int type = data[0] & 0xff;
        final int streamId = ((data[1] & 0xff) << 16) | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
        final long length = ((long) (data[4] & 0xff) << 24) | ((data[5] & 0xff) << 16)
                | ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        if (length > MAX_PAYLOAD || data.length != FRAME_HEADER_SIZE + length) {
            resetCarrier();
            return;
        }
        switch (type) {
            case FRAME_WELCOME:
                welcomed = true;
                flushPendingUplink();
                break;
            case FRAME_PING: {
                byte[] echo = frame(FRAME_PONG, 0, data, FRAME_HEADER_SIZE, (int) length);
                sendFrame(echo);
                break;
            }
            case FRAME_DATA: {
                Stream stream = streams.get(streamId);
                if (stream != null) {
                    stream.enqueueDownlink(data, FRAME_HEADER_SIZE, (int) length);
                }
                break;
            }
            case FRAME_WINDOW: {
                Stream stream = streams.get(streamId);
                if (stream != null && length == 4) {
                    int delta = ((data[8] & 0xff) << 24) | ((data[9] & 0xff) << 16)
                            | ((data[10] & 0xff) << 8) | (data[11] & 0xff);
                    stream.grantSendWindow(delta);
                }
                break;
            }
            case FRAME_CLOSE:
                closeStream(streamId, false);
                break;
            case FRAME_BYE:
                resetCarrier();
                break;
            default:
                break;
        }
    }

    private void onCarrierLost(int generation) {
        if (generation != carrierGeneration) {
            return;
        }
        resetCarrier();
    }

    private void resetCarrier() {
        dropCarrier(true);
        scheduleRetry();
    }

    private void dropCarrier(boolean restarting) {
        carrierGeneration++;
        welcomed = false;
        replyProxy = null;
        nonce = null;
        pendingUplink.clear();
        pendingUplinkBytes = 0;
        for (int i = 0, count = streams.size(); i < count; i++) {
            streams.valueAt(i).shutdown();
        }
        streams.clear();
        ui.post(this::destroyWebView);
        if (!restarting) {
            retryDelay = RETRY_MIN_DELAY;
            mux.removeCallbacks(retryRunnable);
        }
    }

    private void destroyWebView() {
        final WebView view = webView;
        webView = null;
        if (view == null) {
            return;
        }
        try {
            view.stopLoading();
            view.loadUrl("about:blank");
            view.destroy();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private final Runnable retryRunnable = this::startCarrier;

    private void scheduleRetry() {
        synchronized (this) {
            if (serverSocket == null) {
                return;
            }
        }
        mux.removeCallbacks(retryRunnable);
        mux.postDelayed(retryRunnable, retryDelay);
        retryDelay = Math.min(retryDelay * 2, RETRY_MAX_DELAY);
    }

    private void sendFrame(byte[] data) {
        // HELLO opens the session; everything else waits for WELCOME.
        if (replyProxy == null || (!welcomed && (data[0] & 0xff) != FRAME_HELLO)) {
            if (pendingUplinkBytes + data.length > MAX_PENDING_UPLINK) {
                resetCarrier();
                return;
            }
            pendingUplink.add(data);
            pendingUplinkBytes += data.length;
            return;
        }
        postUplink(data);
    }

    private void flushPendingUplink() {
        final ArrayList<byte[]> queued = new ArrayList<>(pendingUplink);
        pendingUplink.clear();
        pendingUplinkBytes = 0;
        for (byte[] data : queued) {
            postUplink(data);
        }
    }

    private void postUplink(byte[] data) {
        final JavaScriptReplyProxy proxy = replyProxy;
        if (proxy == null) {
            return;
        }
        ui.post(() -> {
            try {
                proxy.postMessage(data);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private void closeStream(int streamId, boolean notifyRelay) {
        final Stream stream = streams.get(streamId);
        if (stream == null) {
            return;
        }
        streams.remove(streamId);
        stream.shutdown();
        if (notifyRelay) {
            sendFrame(frame(FRAME_CLOSE, streamId, null, 0, 0));
        }
    }

    // endregion

    // region stream

    /**
     * One MTProxy TCP connection from tgnet, carried as one logical relay stream. CLOSE is an
     * abort in both directions, matching the TCP path tgnet would otherwise use.
     */
    private final class Stream {

        final int id;
        private final Socket socket;
        private final ArrayList<byte[]> downlink = new ArrayList<>();
        private final Object sendLock = new Object();
        private int sendWindow = INITIAL_WINDOW;
        private volatile boolean closed;
        private Thread reader;
        private Thread writer;

        Stream(int id, Socket socket) {
            this.id = id;
            this.socket = socket;
        }

        void startReader() {
            reader = new Thread(this::readLoop, "WebProxyRead/" + id);
            reader.setDaemon(true);
            reader.start();
        }

        void startWriter() {
            writer = new Thread(this::writeLoop, "WebProxyWrite/" + id);
            writer.setDaemon(true);
            writer.start();
        }

        void grantSendWindow(int delta) {
            synchronized (sendLock) {
                sendWindow += delta;
                sendLock.notifyAll();
            }
        }

        void enqueueDownlink(byte[] source, int offset, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(source, offset, copy, 0, length);
            synchronized (downlink) {
                downlink.add(copy);
                downlink.notifyAll();
            }
        }

        void shutdown() {
            closed = true;
            closeQuietly(socket);
            synchronized (sendLock) {
                sendLock.notifyAll();
            }
            synchronized (downlink) {
                downlink.notifyAll();
            }
        }

        private void readLoop() {
            final byte[] buffer = new byte[CHUNK_SIZE];
            try {
                final InputStream input = socket.getInputStream();
                while (!closed) {
                    final int allowed;
                    synchronized (sendLock) {
                        while (sendWindow <= 0 && !closed) {
                            sendLock.wait();
                        }
                        if (closed) {
                            return;
                        }
                        allowed = Math.min(buffer.length, sendWindow);
                    }
                    final int read = input.read(buffer, 0, allowed);
                    if (read <= 0) {
                        break;
                    }
                    synchronized (sendLock) {
                        sendWindow -= read;
                    }
                    final byte[] data = frame(FRAME_DATA, id, buffer, 0, read);
                    mux.post(() -> sendFrame(data));
                }
            } catch (Exception ignore) {
            } finally {
                if (!closed) {
                    mux.post(() -> closeStream(id, true));
                }
            }
        }

        private void writeLoop() {
            try {
                final OutputStream output = socket.getOutputStream();
                while (!closed) {
                    final byte[] chunk;
                    synchronized (downlink) {
                        while (downlink.isEmpty() && !closed) {
                            downlink.wait();
                        }
                        if (closed) {
                            return;
                        }
                        chunk = downlink.remove(0);
                    }
                    output.write(chunk);
                    output.flush();
                    // Credit is returned only once the bytes have reached the local socket.
                    final byte[] window = windowFrame(id, chunk.length);
                    mux.post(() -> sendFrame(window));
                }
            } catch (Exception ignore) {
            } finally {
                if (!closed) {
                    mux.post(() -> closeStream(id, true));
                }
            }
        }
    }

    // endregion
}

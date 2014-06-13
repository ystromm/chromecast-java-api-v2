package su.litvak.chromecast.api.v2;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.net.Socket;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class Channel implements Closeable {
    private final Socket socket;
    private final String name;
    private Timer pingTimer;
    private ReadThread reader;
    private AtomicLong requestCounter = new AtomicLong(0);
    private Map<Long, ResultProcessor> requests = new ConcurrentHashMap<Long, ResultProcessor>();

    private class PingThread extends TimerTask {
        JSONObject msg;

        PingThread()
        {
            msg = new JSONObject();
            msg.put("type", "PING");
        }


        @Override
        public void run() {
            try {
                write("urn:x-cast:com.google.cast.tp.heartbeat", msg, "receiver-0");
            } catch (IOException ioex) {
                // TODO logging
            }
        }
    }

    private class ReadThread extends Thread {
        volatile boolean stop;

        @Override
        public void run() {
            while (!stop) {
                try {
                    CastChannel.CastMessage message = read();
                    if (message.getPayloadType() == CastChannel.CastMessage.PayloadType.STRING) {
                        JSONObject parsed = (JSONObject) JSONValue.parse(message.getPayloadUtf8());
                        Long requestId = (Long) parsed.get("requestId");
                        if (requestId != null) {
                            ResultProcessor rp = requests.remove(requestId);
                            if (rp != null) {
                                rp.put(parsed);
                            } else {
                                // TODO warn logging
                                System.out.println("Unable to process request ID = " + requestId + ", data: " + parsed.toJSONString());
                            }
                        }
                    } else {
                        System.out.println(message.getPayloadType());
                    }
                } catch (IOException ioex) {
                    // TODO logging
//                    ioex.printStackTrace();
                }
            }
        }
    }

    private static class ResultProcessor {
        AtomicReference<JSONObject> json = new AtomicReference<JSONObject>();

        private ResultProcessor() {
        }

        public void put(JSONObject json) {
            synchronized (this) {
                this.json.set(json);
                this.notify();
            }
        }

        public JSONObject get() {
            if (json.get() != null) {
                return json.get();
            }
            synchronized (this) {
                // TODO put timeout to constant
                try {
                    this.wait(5 * 1000);
                } catch (InterruptedException ie) {
                    // TODO either move to the 'throws' or put some logging here
                    // TODO remove this from requests (add timer or so)
                    ie.printStackTrace();
                }
                return json.get();
            }
        }
    }

    public Channel(String host) throws IOException, GeneralSecurityException {
        this(host, 8009);
    }

    public Channel(String host, int port) throws IOException, GeneralSecurityException {
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, new TrustManager[] { new X509TrustAllManager() }, new SecureRandom());
        this.socket = sc.getSocketFactory().createSocket(host, port);
        this.name = "sender-" + new RandomString(10).nextString();
        connect();
    }

    private void connect() throws IOException {
        CastChannel.DeviceAuthMessage authMessage = CastChannel.DeviceAuthMessage.newBuilder()
                .setChallenge(CastChannel.AuthChallenge.newBuilder().build())
                .build();

        CastChannel.CastMessage msg = CastChannel.CastMessage.newBuilder()
                .setDestinationId("receiver-0")
                .setNamespace("urn:x-cast:com.google.cast.tp.deviceauth")
                .setPayloadType(CastChannel.CastMessage.PayloadType.BINARY)
                .setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(name)
                .setPayloadBinary(authMessage.toByteString())
                .build();

        write(msg);
        CastChannel.CastMessage response = read();
        CastChannel.DeviceAuthMessage authResponse = CastChannel.DeviceAuthMessage.parseFrom(response.getPayloadBinary());
        if (authResponse.hasError()) {
            throw new IOException("Authentication failed: " + authResponse.getError().getErrorType().toString());
        }

        /**
         * Send 'PING' message
         */
        PingThread pingThread = new PingThread();
        pingThread.run();

        /**
         * Send 'CONNECT' message to start session
         */
        JSONObject jMSG = new JSONObject();
        jMSG.put("type", "CONNECT");
        jMSG.put("origin", new JSONObject());
        write("urn:x-cast:com.google.cast.tp.connection", jMSG, "receiver-0");

        /**
         * Start ping/pong and reader thread
         */
        pingTimer = new Timer(name + " PING");
        // TODO move PING interval to constants
        pingTimer.schedule(pingThread, 5 * 1000, 30 * 1000);

        reader = new ReadThread();
        reader.start();
    }

    private JSONObject send(String namespace, JSONObject message, String destinationId) throws IOException {
        long requestId = requestCounter.getAndIncrement();
        message.put("requestId", requestId);
        ResultProcessor rp = new ResultProcessor();
        requests.put(requestId, rp);
        write(namespace, message, destinationId);
        return rp.get();
    }

    private void write(String namespace, JSONObject message, String destinationId) throws IOException {
        write(namespace, message.toJSONString(), destinationId);
    }

    private void write(String namespace, String message, String destinationId) throws IOException {
        CastChannel.CastMessage msg = CastChannel.CastMessage.newBuilder()
                .setProtocolVersion(CastChannel.CastMessage.ProtocolVersion.CASTV2_1_0)
                .setSourceId(name)
                .setDestinationId(destinationId)
                .setNamespace(namespace)
                .setPayloadType(CastChannel.CastMessage.PayloadType.STRING)
                .setPayloadUtf8(message)
                .build();
        write(msg);
    }

    private void write(CastChannel.CastMessage message) throws IOException {
        socket.getOutputStream().write(toArray(message.getSerializedSize()));
        message.writeTo(socket.getOutputStream());
    }

    private CastChannel.CastMessage read() throws IOException {
        InputStream is = socket.getInputStream();
        byte[] buf = new byte[4];
        is.read(buf);
        int size = fromArray(buf);
        buf = new byte[size];
        is.read(buf);
        return CastChannel.CastMessage.parseFrom(buf);
    }

    public JSONObject deviceGetStatus() throws IOException {
        JSONObject msg = new JSONObject();
        msg.put("type", "GET_STATUS");

        return send("urn:x-cast:com.google.cast.receiver", msg, "receiver-0");
    }

    public JSONObject deviceGetAppAvailability(String appId) throws IOException {
        JSONObject msg = new JSONObject();
        msg.put("type", "GET_APP_AVAILABILITY");
        JSONArray apps = new JSONArray();
        apps.add(appId);
        msg.put("appId", apps);

        return send("urn:x-cast:com.google.cast.receiver", msg, "receiver-0");
    }

    public JSONObject appLaunch(String appId) throws IOException {
        JSONObject msg = new JSONObject();
        msg.put("type", "LAUNCH");
        msg.put("appId", appId);

        return send("urn:x-cast:com.google.cast.receiver", msg, "receiver-0");
    }

    public JSONObject play(String sessionId, String url, String destinationId) throws IOException {
        JSONObject jMSG = new JSONObject();
        jMSG.put("type", "CONNECT");
        jMSG.put("origin", new JSONObject());
        write("urn:x-cast:com.google.cast.tp.connection", jMSG, destinationId);

        JSONObject msg = new JSONObject();
        msg.put("type", "LOAD");
        msg.put("sessionId", sessionId);

        JSONObject media = new JSONObject();
        media.put("contentId", url);
        media.put("streamType", "buffered");
        media.put("contentType", "video/mp4");

        msg.put("media", media);
        msg.put("autoplay", true);
        msg.put("currentTime", 0);

        JSONObject customData = new JSONObject();
        JSONObject payload = new JSONObject();
        payload.put("title:", "Big Buck Bunny");
        payload.put("thumb", "images/BigBuckBunny.jpg");
        customData.put("payload", payload);

        msg.put("customData", customData);

        System.out.println(msg.toJSONString());

        return send("urn:x-cast:com.google.cast.media", msg, destinationId);
    }

    @Override
    public void close() throws IOException {
        if (pingTimer != null) {
            pingTimer.cancel();
        }
        if (reader != null) {
            reader.stop = true;
        }
        if (socket != null) {
            socket.close();
        }
    }

    private static int fromArray(byte[] payload){
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return buffer.getInt();
    }

    private static byte[] toArray(int value){
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(value);
        buffer.flip();
        return buffer.array();
    }
}

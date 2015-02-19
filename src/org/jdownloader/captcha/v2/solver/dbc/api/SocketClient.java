/*
 * Source: http://deathbycaptcha.eu/user/api
 * Slightly modified to work without json and base64 dependencies
 */
package org.jdownloader.captcha.v2.solver.dbc.api;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.Random;

import org.appwork.storage.SimpleMapper;
import org.appwork.utils.encoding.Base64;

/**
 * Death by Captcha socket API client.
 *
 */
public class SocketClient extends Client {
    final static public String HOST       = "api.dbcapi.me";
    final static public int    FIRST_PORT = 8123;
    final static public int    LAST_PORT  = 8130;

    final static public String TERMINATOR = "\r\n";

    protected SocketChannel    channel    = null;

    protected String sendAndReceive(byte[] payload) throws IOException {
        final ByteBuffer sbuf = ByteBuffer.wrap(payload);
        final ByteBuffer rbuf = ByteBuffer.allocate(256);
        final CharsetDecoder rbufDecoder = Charset.forName("UTF-8").newDecoder();
        final StringBuilder response = new StringBuilder();

        final int ops;
        if (this.channel.isConnectionPending()) {
            ops = SelectionKey.OP_CONNECT;
        } else {
            if (sbuf.hasRemaining()) {
                ops = SelectionKey.OP_WRITE;
            } else {
                ops = SelectionKey.OP_READ;
            }
        }
        final Selector selector = Selector.open();
        try {
            this.channel.register(selector, ops);
            while (true) {
                if (selector.select(Client.POLLS_INTERVAL * 1000) > 0) {
                    final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        try {
                            final SelectionKey key = keys.next();
                            if (key.isValid()) {
                                final SocketChannel ch = (SocketChannel) key.channel();
                                if (key.isConnectable()) {
                                    // Just connected
                                    ch.finishConnect();
                                    if (sbuf.hasRemaining()) {
                                        key.interestOps(SelectionKey.OP_WRITE);
                                    } else {
                                        key.interestOps(SelectionKey.OP_READ);
                                    }
                                } else {
                                    if (key.isWritable()) {
                                        // Sending the request
                                        while (0 < ch.write(sbuf) && sbuf.hasRemaining()) {
                                            //
                                        }
                                        if (!sbuf.hasRemaining()) {
                                            key.interestOps(SelectionKey.OP_READ);
                                        }
                                    }
                                    if (key.isReadable()) {
                                        // Receiving the response
                                        int read = 0;
                                        while ((read = ch.read(rbuf)) > 0) {
                                            rbuf.flip();
                                            response.append(rbufDecoder.decode(rbuf).toString());
                                        }
                                        if (2 <= response.length() && response.substring(response.length() - 2, response.length()).equals(SocketClient.TERMINATOR)) {
                                            response.setLength(response.length() - 2);
                                            return response.toString();
                                        } else if (0 == response.length() || read == -1) {
                                            //
                                            throw new IOException("Connection lost");
                                        }
                                    }
                                }
                            } else {
                                throw new IOException("Invalid key");
                            }
                        } finally {
                            keys.remove();
                        }
                    }
                }
            }
        } catch (java.lang.Exception e) {
            throw new IOException(e);
        } finally {
            selector.close();
        }
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#close
     */
    @Override
    public void close() {
        if (null != this.channel) {
            this.log("CLOSE");

            if (this.channel.isConnected() || this.channel.isConnectionPending()) {
                try {
                    this.channel.socket().shutdownOutput();
                    this.channel.socket().shutdownInput();
                } catch (java.lang.Exception e) {
                    //
                } finally {
                    try {
                        this.channel.close();
                    } catch (java.lang.Exception e) {
                        //
                    }
                }
            }
            try {
                this.channel.socket().close();
            } catch (java.lang.Exception e) {
                //
            }

            this.channel = null;
        }
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#connect
     */
    @Override
    public synchronized boolean connect() throws IOException {
        if (null == this.channel) {
            this.log("OPEN");
            final InetAddress host;
            try {
                host = InetAddress.getByName(SocketClient.HOST);
            } catch (UnknownHostException e) {
                throw new IOException("API host not found", e);
            }
            final SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            try {
                channel.connect(new InetSocketAddress(host, SocketClient.FIRST_PORT + new Random().nextInt(SocketClient.LAST_PORT - SocketClient.FIRST_PORT + 1)));
            } catch (IOException e) {
                throw new IOException("API connection failed", e);
            }
            this.channel = channel;
        }
        return null != this.channel;
    }

    protected synchronized DataObject call(String cmd, DataObject args) throws IOException, Exception {

        args.put("cmd", cmd).put("version", Client.API_VERSION);

        int attempts = 2;

        byte[] payload = (new SimpleMapper().objectToString(args) + SocketClient.TERMINATOR).getBytes();
        DataObject response = null;
        while (0 < attempts && null == response) {
            attempts--;
            if (null == this.channel && !cmd.equals("login")) {
                this.call("login", this.getCredentials());
            }
            if (this.connect()) {
                this.log("SEND", args.toString());
                try {
                    response = new DataObject(this.sendAndReceive(payload));
                } catch (java.lang.Exception e) {
                    this.logger.log(e);
                    this.close();
                }
            }
        }
        if (null == response) {
            throw new IOException("API connection lost or timed out");
        }

        this.log("RECV", response.toString());
        String error = response.optString("error", null);
        if (null != error) {
            this.close();
            if (error.equals("not-logged-in")) {
                throw new AccessDeniedException("Access denied, check your credentials");
            } else if (error.equals("banned")) {
                throw new AccessDeniedException("Access denied, account is suspended");
            } else if (error.equals("insufficient-funds")) {
                throw new AccessDeniedException("Access denied, balance is too low");
            } else if (error.equals("invalid-captcha")) {
                throw new InvalidCaptchaException("CAPTCHA was rejected by the service, check if it's a valid image");
            } else if (error.equals("service-overload")) {
                throw new ServiceOverloadException("CAPTCHA was rejected due to service overload, try again later");
            } else {
                throw new IOException("API server error occured: " + error);
            }
        } else {
            return response;
        }
    }

    {
    }

    protected DataObject call(String cmd) throws IOException, Exception {
        return this.call(cmd, new DataObject());
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#Client(String, String)
     */
    public SocketClient(String username, String password) {
        super(username, password);

    }

    @Override
    public void finalize() {
        this.close();
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#getUser
     */
    @Override
    public User getUser() throws IOException, Exception {
        return new User(this.call("user"));
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#upload
     */
    @Override
    public Captcha upload(byte[] img) throws IOException, Exception {
        DataObject args = new DataObject();

        String own = Base64.encodeToString(img, false);

        args.put("captcha", own).put("swid", Client.SOFTWARE_VENDOR_ID);

        Captcha c = new Captcha(this.call("upload", args));
        return c.isUploaded() ? c : null;
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#getCaptcha
     */
    @Override
    public Captcha getCaptcha(int id) throws IOException, Exception {
        DataObject args = new DataObject();

        args.put("captcha", id);

        return new Captcha(this.call("captcha", args));
    }

    /**
     * @see org.jdownloader.captcha.v2.solver.dbc.api.Client#report
     */
    @Override
    public boolean report(int id) throws IOException, Exception {
        DataObject args = new DataObject();

        args.put("captcha", id);

        return !new Captcha(this.call("report", args)).isCorrect();
    }
}

/*
 * Firebird Open Source JavaEE Connector - JDBC Driver
 *
 * Distributable under LGPL license.
 * You may obtain a copy of the License at http://www.gnu.org/copyleft/lgpl.html
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * LGPL License for more details.
 *
 * This file was created by members of the firebird development team.
 * All individual contributions remain the Copyright (C) of those
 * individuals.  Contributors to this file are either listed here or
 * can be obtained from a source control history command.
 *
 * All rights reserved.
 */
package org.firebirdsql.gds.ng.wire;

import org.firebirdsql.encodings.EncodingFactory;
import org.firebirdsql.encodings.IEncodingFactory;
import org.firebirdsql.gds.ISCConstants;
import org.firebirdsql.gds.impl.wire.WireProtocolConstants;
import org.firebirdsql.gds.impl.wire.XdrInputStream;
import org.firebirdsql.gds.impl.wire.XdrOutputStream;
import org.firebirdsql.gds.ng.AbstractConnection;
import org.firebirdsql.gds.ng.FbExceptionBuilder;
import org.firebirdsql.gds.ng.IAttachProperties;
import org.firebirdsql.gds.ng.IConnectionProperties;
import org.firebirdsql.logging.Logger;
import org.firebirdsql.logging.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeUnit;

import static org.firebirdsql.gds.impl.wire.WireProtocolConstants.*;

/**
 * Class managing the TCP/IP connection and initial handshaking with the
 * Firebird server.
 *
 * @param <T> Type of attach properties
 * @param <C> Type of connection handle
 * @author <a href="mailto:mrotteveel@users.sourceforge.net">Mark Rotteveel</a>
 * @since 3.0
 */
public abstract class WireConnection<T extends IAttachProperties<T>, C> extends AbstractConnection<T, C>
        implements Closeable {

    // TODO Check if methods currently throwing IOException should throw SQLException instead

    private static final Logger log = LoggerFactory.getLogger(WireConnection.class);

    private Socket socket;
    private ProtocolCollection protocols;
    private int protocolVersion;
    private int protocolArchitecture;
    private int protocolMinimumType;

    private SrpClient srpClient;
    private XdrOutputStream xdrOut;
    private XdrInputStream xdrIn;
    private final XdrStreamAccess streamAccess = new XdrStreamAccess() {
        @Override
        public XdrInputStream getXdrIn() throws SQLException {
            if (isConnected() && xdrIn != null) {
                return xdrIn;
            } else {
                throw new SQLException("Connection closed or no connection available");
            }
        }

        @Override
        public XdrOutputStream getXdrOut() throws SQLException {
            if (isConnected() && xdrOut != null) {
                return xdrOut;
            } else {
                throw new SQLException("Connection closed or no connection available");
            }
        }
    };

    /**
     * Creates a WireConnection (without establishing a connection to the
     * server) with the default protocol collection.
     *
     * @param attachProperties
     *         Attach properties
     */
    protected WireConnection(T attachProperties) throws SQLException {
        this(attachProperties, EncodingFactory.getDefaultInstance(), ProtocolCollection.getDefaultCollection());
    }

    /**
     * Creates a WireConnection (without establishing a connection to the
     * server).
     *
     * @param attachProperties
     *         Attach properties
     * @param encodingFactory
     *         Factory for encoding definitions
     * @param protocols
     *         The collection of protocols to use for this connection.
     */
    protected WireConnection(T attachProperties, IEncodingFactory encodingFactory,
            ProtocolCollection protocols) throws SQLException {
        super(attachProperties, encodingFactory);
        this.protocols = protocols;
    }

    public final boolean isConnected() {
        return !(socket == null || socket.isClosed());
    }

    public final int getProtocolVersion() {
        return protocolVersion;
    }

    public final int getProtocolArchitecture() {
        return protocolArchitecture;
    }

    public final int getProtocolMinimumType() {
        return protocolMinimumType;
    }

    /**
     * Sets the socket blocking timeout (SO_TIMEOUT) of the socket.
     * <p>
     * This method can also be called if a connection is established
     * </p>
     *
     * @param socketTimeout
     *         Value of the socket timeout (in milliseconds)
     * @throws SQLException
     *         If the timeout value cannot be changed
     */
    public final void setSoTimeout(int socketTimeout) throws SQLException {
        attachProperties.setSoTimeout(socketTimeout);
        resetSocketTimeout();
    }

    /**
     * Resets the socket timeout to the configured socketTimeout. Does nothing
     * if currently not connected.
     *
     * @throws SQLException
     *         If the timeout value cannot be changed
     */
    public final void resetSocketTimeout() throws SQLException {
        if (isConnected()) {
            try {
                final int soTimeout = attachProperties.getSoTimeout();
                final int desiredTimeout = soTimeout != -1 ? soTimeout : 0;
                if (socket.getSoTimeout() != desiredTimeout) {
                    socket.setSoTimeout(desiredTimeout);
                }
            } catch (SocketException e) {
                // TODO Add SQLState
                throw new SQLException("Unable to change socket timeout (SO_TIMEOUT)", e);
            }
        }
    }

    /**
     * Establishes the TCP/IP connection to serverName and portNumber of this
     * Connection
     *
     * @throws SQLTimeoutException
     *         If the connection cannot be established within the connect
     *         timeout (either explicitly set or implied by the OS timeout
     *         of the socket)
     * @throws SQLException
     *         If the connection cannot be established.
     */
    public final void socketConnect() throws SQLException {
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            final int connectTimeout = attachProperties.getConnectTimeout();
            final int socketConnectTimeout;
            if (connectTimeout != -1) {
                // connectTimeout is in seconds, need milliseconds
                socketConnectTimeout = (int) TimeUnit.SECONDS.toMillis(connectTimeout);
                // Blocking timeout initially identical to connect timeout
                socket.setSoTimeout(socketConnectTimeout);
            } else {
                // socket connect timeout is not set, so indefinite (0)
                socketConnectTimeout = 0;
                // Blocking timeout to normal socket timeout, 0 if not set
                socket.setSoTimeout(Math.max(attachProperties.getSoTimeout(), 0));
            }

            final int socketBufferSize = attachProperties.getSocketBufferSize();
            if (socketBufferSize != IConnectionProperties.DEFAULT_SOCKET_BUFFER_SIZE) {
                socket.setReceiveBufferSize(socketBufferSize);
                socket.setSendBufferSize(socketBufferSize);
            }

            socket.connect(new InetSocketAddress(getServerName(), getPortNumber()), socketConnectTimeout);
        } catch (SocketTimeoutException ste) {
            throw new FbExceptionBuilder().timeoutException(ISCConstants.isc_network_error).messageParameter(getServerName()).cause(ste).toSQLException();
        } catch (IOException ioex) {
            throw new FbExceptionBuilder().exception(ISCConstants.isc_network_error).messageParameter(getServerName()).cause(ioex).toSQLException();
        }
    }

    public final XdrStreamAccess getXdrStreamAccess() {
        return streamAccess;
    }

    /**
     * Performs the connection identification phase of the Wire protocol and
     * returns the FbWireDatabase implementation for the agreed protocol.
     *
     * @return FbWireDatabase
     * @throws SQLTimeoutException
     * @throws SQLException
     */
    @Override
    public final C identify() throws SQLException {
        try {
            xdrIn = new XdrInputStream(socket.getInputStream());
            xdrOut = new XdrOutputStream(socket.getOutputStream());

            // Here we identify the user to the engine. 
            // This may or may not be used as login info to a database.
            final byte[] userBytes = getSystemUserName().getBytes();
            final byte[] hostBytes = getSystemHostName().getBytes();

            ByteArrayOutputStream userId = new ByteArrayOutputStream();

            if (attachProperties.getUser() != null) {
                final byte[] loginBytes = attachProperties.getUser().getBytes(StandardCharsets.UTF_8);
                userId.write(CNCT_login);
                int loginLength = Math.min(loginBytes.length, 255);
                userId.write(loginLength);
                userId.write(loginBytes, 0, loginLength);
            }

            // TODO: switch Srp or Legacy_Auth by something property.
//            String pluginName = "Legacy_Auth";
            String pluginName = "Srp";

            userId.write(CNCT_plugin_name);
            final byte[] pluginNameBytes = pluginName.getBytes(StandardCharsets.UTF_8);
            userId.write(pluginNameBytes.length);
            userId.write(pluginNameBytes, 0, pluginNameBytes.length);

            userId.write(CNCT_plugin_list);
            final byte[] pluginListBytes = "Srp,Legacy_Auth".getBytes(StandardCharsets.UTF_8);
            userId.write(pluginListBytes.length);
            userId.write(pluginListBytes, 0, pluginListBytes.length);

            byte[] specificDataBytes = null;
            if (attachProperties.getPassword() != null) {
                switch (pluginName) {
                case "Legacy_Auth":
                    specificDataBytes = UnixCrypt.crypt(attachProperties.getPassword(), "9z").substring(2, 13).getBytes();

                    break;
                case "Srp":
                    srpClient = new SrpClient();
                    specificDataBytes = srpClient.getPublicKeyHex().getBytes();
                    break;
                }

                if (specificDataBytes != null) {
                    // write specific data
                    int remaining = specificDataBytes.length;
                    int position = 0;
                    int step = 0;
                    while (remaining > 0) {
                        userId.write(CNCT_specific_data);
                        int toWrite = Math.min(remaining, 254);
                        userId.write(toWrite + 1);
                        userId.write(step++);
                        userId.write(specificDataBytes, position, toWrite);
                        remaining -= toWrite;
                        position += toWrite;
                    }
                }
            }

            userId.write(CNCT_client_crypt);    // WireCrypt = Disabled
            userId.write(new byte[] { (byte) 4, (byte) 0, (byte) 0, (byte) 0, (byte) 0 });

            userId.write(CNCT_user);
            int userLength = Math.min(userBytes.length, 255);
            userId.write(userLength);
            userId.write(userBytes, 0, userLength);

            userId.write(CNCT_host);
            int hostLength = Math.min(hostBytes.length, 255);
            userId.write(hostLength);
            userId.write(hostBytes, 0, hostLength);
            userId.write(CNCT_user_verification);
            userId.write(0);

            xdrOut.writeInt(op_connect);
            xdrOut.writeInt(op_attach);
            xdrOut.writeInt(CONNECT_VERSION3);
            xdrOut.writeInt(arch_generic);

            xdrOut.writeString(getAttachObjectName(), getEncoding());
            xdrOut.writeInt(protocols.getProtocolCount()); // Count of protocols understood
            xdrOut.writeBuffer(userId.toByteArray());

            for (ProtocolDescriptor protocol : protocols) {
                xdrOut.writeInt(protocol.getVersion()); // Protocol version
                xdrOut.writeInt(protocol.getArchitecture()); // Architecture of client
                xdrOut.writeInt(protocol.getMinimumType()); // Minimum type
                xdrOut.writeInt(protocol.getMaximumType()); // Maximum type
                xdrOut.writeInt(protocol.getWeight()); // Preference weight
            }

            xdrOut.flush();
            final int op_code = readNextOperation();
            if (op_code == op_accept || op_code == op_cond_accept || op_code == op_accept_data) {
                protocolVersion = xdrIn.readInt(); // Protocol version
                protocolArchitecture = xdrIn.readInt(); // Architecture for protocol
                protocolMinimumType = xdrIn.readInt(); // Minimum type
                if (protocolVersion < 0) {
                    protocolVersion = (protocolVersion & FB_PROTOCOL_MASK) | FB_PROTOCOL_FLAG;
                }

                if (op_code == op_cond_accept || op_code == op_accept_data) {
                    byte[] data = xdrIn.readBuffer();
                    String acceptPluginName = xdrIn.readString(getEncoding());
                    final int is_authenticated = xdrIn.readInt();
                    String keys = xdrIn.readString(getEncoding());
                    if (is_authenticated == 0) {
                        byte[] authData;
                        if (acceptPluginName.equals("Srp")) {
                            authData = srpClient.clientProof(attachProperties.getUser(), attachProperties.getPassword(), data);
                        } else if (acceptPluginName.equals("Legacy_Auth")) {
                            authData = UnixCrypt.crypt(attachProperties.getPassword(), "9z").substring(2, 13).getBytes();
                        } else {
                            try {
                                close();
                            } catch (Exception ex) {
                                log.debug("Ignoring exception on disconnect in connect phase of protocol", ex);
                            }
                            // TODO Use different exception message + check what fbclient does here
                            throw new SQLException("Unauthorized");
                        }
                        attachProperties.setAuthData(authData);
                    }
                }


                ProtocolDescriptor descriptor = protocols.getProtocolDescriptor(protocolVersion);
                if (descriptor == null) {
                    throw new SQLException(
                            String.format(
                                    "Unsupported or unexpected protocol version %d connecting to database %s. Supported version(s): %s",
                                    protocolVersion, getServerName(), protocols.getProtocolVersions()));
                }
                return createConnectionHandle(descriptor);
            } else {
                try {
                    if (op_code == op_response) {
                        ProtocolDescriptor protocolDescriptor = protocols.getProtocolDescriptor(WireProtocolConstants.PROTOCOL_VERSION10);
                        AbstractWireOperations wireOperations = (AbstractWireOperations) protocolDescriptor.createWireOperations(this, null, this);
                        wireOperations.processResponse(wireOperations.processOperation(op_code));
                    }
                } finally {
                    try {
                        close();
                    } catch (Exception ex) {
                        log.debug("Ignoring exception on disconnect in connect phase of protocol", ex);
                    }
                }
                throw new FbExceptionBuilder().exception(ISCConstants.isc_connect_reject).toSQLException();
            }
        } catch (SocketTimeoutException ste) {
            throw new FbExceptionBuilder().timeoutException(ISCConstants.isc_network_error).messageParameter(getServerName()).cause(ste).toSQLException();
        } catch (IOException ioex) {
            throw new FbExceptionBuilder().exception(ISCConstants.isc_network_error).messageParameter(getServerName()).cause(ioex).toSQLException();
        }
    }

    /**
     * Creates the connection handle for this type of connection.
     *
     * @param protocolDescriptor The protocol descriptor selected by the identify phase
     * @return Connection handle
     */
    protected abstract C createConnectionHandle(ProtocolDescriptor protocolDescriptor);

    /**
     * Reads the next operation code. Skips all {@link org.firebirdsql.gds.impl.wire.WireProtocolConstants#op_dummy} codes received.
     *
     * @return Operation code
     * @throws IOException
     *         if an error occurs while reading from the underlying InputStream
     */
    public final int readNextOperation() throws IOException {
        int op;
        do {
            op = xdrIn.readInt();
        } while (op == op_dummy);
        return op;
    }

    /**
     * Closes the TCP/IP connection. This is not a normal detach operation.
     *
     * @throws IOException
     *         if closing fails
     */
    public final void close() throws IOException {
        IOException ioex = null;
        try {
            if (socket != null) {
                try {
                    if (xdrOut != null) xdrOut.close();
                } catch (IOException ex) {
                    ioex = ex;
                }

                try {
                    if (xdrIn != null) xdrIn.close();
                } catch (IOException ex) {
                    if (ioex == null) ioex = ex;
                }

                try {
                    socket.close();
                } catch (IOException ex) {
                    if (ioex == null) ioex = ex;
                }

                if (ioex != null) throw ioex;
            }
        } finally {
            xdrOut = null;
            xdrIn = null;
            socket = null;
            protocols = null;
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static String getSystemUserName() {
        try {
            return getSystemPropertyPrivileged("user.name");
        } catch (SecurityException ex) {
            log.debug("Unable to retrieve user.name property", ex);
            return "jaybird";
        }
    }

    private static String getSystemHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException ex1) {
                return "127.0.0.1";
            }
        }
    }

    private static String getSystemPropertyPrivileged(final String propertyName) {
        return AccessController.doPrivileged(new PrivilegedAction<String>() {
            public String run() {
                return System.getProperty(propertyName);
            }
        });
    }

    /**
     * Writes directly to the {@code OutputStream} of the underlying socket.
     *
     * @param data
     *         Data to write
     * @throws IOException
     *         If there is no socket, the socket is closed, or for errors writing to the socket.
     */
    public final void writeDirect(byte[] data) throws IOException {
        if (!isConnected()) throw new SocketException("Socket closed");
        final OutputStream outputStream = socket.getOutputStream();
        outputStream.write(data);
        outputStream.flush();
    }
}

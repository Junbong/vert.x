/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.eventbus.impl.clustered;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.eventbus.impl.*;
import io.vertx.core.impl.HAManager;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.impl.ServerID;
import io.vertx.core.parsetools.RecordParser;
import io.vertx.core.spi.cluster.AsyncMultiMap;
import io.vertx.core.spi.cluster.ChoosableIterable;
import io.vertx.core.spi.cluster.ClusterManager;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * An event bus implemmentation that clusters with other Vert.x nodes
 *
 * @author <a href="http://tfox.org">Tim Fox</a>   7                                                                                     T
 */
public class ClusteredEventBus extends EventBusImpl {

  private static final Logger log = LoggerFactory.getLogger(ClusteredEventBus.class);

  public static final String CLUSTER_PUBLIC_HOST_PROP_NAME = "vertx.cluster.public.host";
  public static final String CLUSTER_PUBLIC_PORT_PROP_NAME = "vertx.cluster.public.port";

  private static final Buffer PONG = Buffer.buffer(new byte[] { (byte)1 });
  private static final String SERVER_ID_HA_KEY = "server_id";
  private static final String SUBS_MAP_NAME = "__vertx.subs";

  private final ClusterManager clusterManager;
  private final HAManager haManager;
  private final ConcurrentMap<ServerID, ConnectionHolder> connections = new ConcurrentHashMap<>();
  private final Context sendNoContext;
  private VertxOptions options;
  private AsyncMultiMap<String, ServerID> subs;
  private ServerID serverID;
  private NetServer server;

  public ClusteredEventBus(VertxInternal vertx,
                           VertxOptions options,
                           ClusterManager clusterManager,
                           HAManager haManager) {
    super(vertx);
    this.options = options;
    this.clusterManager = clusterManager;
    this.haManager = haManager;
    this.sendNoContext = vertx.getOrCreateContext();
    setNodeCrashedHandler(haManager);
  }

  @Override
  public void start(Handler<AsyncResult<Void>> resultHandler) {
    clusterManager.<String, ServerID>getAsyncMultiMap(SUBS_MAP_NAME, ar2 -> {
      if (ar2.succeeded()) {
        subs = ar2.result();
        server = vertx.createNetServer(new NetServerOptions().setPort(options.getClusterPort()).setHost(options.getClusterHost()));
        server.connectHandler(getServerHandler());
        server.listen(asyncResult -> {
          if (asyncResult.succeeded()) {
            int serverPort = getClusterPublicPort(options, server.actualPort());
            String serverHost = getClusterPublicHost(options);
            serverID = new ServerID(serverPort, serverHost);
            haManager.addDataToAHAInfo(SERVER_ID_HA_KEY, new JsonObject().put("host", serverID.host).put("port", serverID.port));
            if (resultHandler != null) {
              started = true;
              resultHandler.handle(Future.succeededFuture());
            }
          } else {
            if (resultHandler != null) {
              resultHandler.handle(Future.failedFuture(asyncResult.cause()));
            } else {
              log.error(asyncResult.cause());
            }
          }
        });
      } else {
        if (resultHandler != null) {
          resultHandler.handle(Future.failedFuture(ar2.cause()));
        } else {
          log.error(ar2.cause());
        }
      }
    });
  }

  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    super.close(ar1 -> {
      if (server != null) {
        server.close(ar -> {
          if (ar.failed()) {
            log.error("Failed to close server", ar.cause());
          }
          // Close all outbound connections explicitly - don't rely on context hooks
          for (ConnectionHolder holder: connections.values()) {
            holder.close();
          }
          if (completionHandler != null) {
            completionHandler.handle(ar);
          }
        });
      } else {
        if (completionHandler != null) {
          completionHandler.handle(ar1);
        }
      }
    });

  }

  @Override
  protected MessageImpl createMessage(boolean send, String address, MultiMap headers, Object body, String codecName) {
    Objects.requireNonNull(address, "no null address accepted");
    MessageCodec codec = codecManager.lookupCodec(body, codecName);
    @SuppressWarnings("unchecked")
    ClusteredMessage msg = new ClusteredMessage(serverID, address, null, headers, body, codec, send, this);
    return msg;
  }

  @Override
  protected <T> void addRegistration(boolean newAddress, String address,
                                     boolean replyHandler, boolean localOnly,
                                     Handler<AsyncResult<Void>> completionHandler) {
    if (newAddress && subs != null && !replyHandler && !localOnly) {
      // Propagate the information
      subs.add(address, serverID, completionHandler);
    } else {
      completionHandler.handle(Future.succeededFuture());
    }
  }

  @Override
  protected <T> void removeRegistration(HandlerHolder lastHolder, String address,
                                        Handler<AsyncResult<Void>> completionHandler) {
    if (lastHolder != null && subs != null && !lastHolder.isLocalOnly()) {
      removeSub(address, serverID, completionHandler);
    } else {
      callCompletionHandlerAsync(completionHandler);
    }
  }

  @Override
  protected <T> void sendReply(SendContextImpl<T> sendContext, MessageImpl replierMessage) {
    clusteredSendReply(((ClusteredMessage) replierMessage).getSender(), sendContext);
  }

  @Override
  protected <T> void sendOrPub(SendContextImpl<T> sendContext) {
    String address = sendContext.message.address();
    Handler<AsyncResult<ChoosableIterable<ServerID>>> resultHandler = asyncResult -> {
      if (asyncResult.succeeded()) {
        ChoosableIterable<ServerID> serverIDs = asyncResult.result();
        if (serverIDs != null && !serverIDs.isEmpty()) {
          sendToSubs(serverIDs, sendContext);
        } else {
          metrics.messageSent(address, !sendContext.message.send(), true, false);
          deliverMessageLocally(sendContext);
        }
      } else {
        log.error("Failed to send message", asyncResult.cause());
      }
    };
    if (Vertx.currentContext() == null) {
      // Guarantees the order when there is no current context
      sendNoContext.runOnContext(v -> {
        subs.get(address, resultHandler);
      });
    } else {
      subs.get(address, resultHandler);
    }
  }

  @Override
  protected String generateReplyAddress() {
    // The address is a cryptographically secure id that can't be guessed
    return UUID.randomUUID().toString();
  }

  @Override
  protected boolean isMessageLocal(MessageImpl msg) {
    ClusteredMessage clusteredMessage = (ClusteredMessage)msg;
    return !clusteredMessage.isFromWire();
  }

  private void setNodeCrashedHandler(HAManager haManager) {
    haManager.setNodeCrashedHandler((failedNodeID, haInfo, failed) -> {
      JsonObject jsid = haInfo.getJsonObject(SERVER_ID_HA_KEY);
      if (jsid != null) {
        ServerID sid = new ServerID(jsid.getInteger("port"), jsid.getString("host"));
        if (subs != null) {
          subs.removeAllForValue(sid, res -> {
          });
        }
      }
    });
  }

  private int getClusterPublicPort(VertxOptions options, int actualPort) {
    // We retain the old system property for backwards compat
    int publicPort = Integer.getInteger(CLUSTER_PUBLIC_PORT_PROP_NAME, options.getClusterPublicPort());
    if (publicPort == -1) {
      // Get the actual port, wildcard port of zero might have been specified
      publicPort = actualPort;
    }
    return publicPort;
  }

  private String getClusterPublicHost(VertxOptions options) {
    // We retain the old system property for backwards compat
    String publicHost = System.getProperty(CLUSTER_PUBLIC_HOST_PROP_NAME, options.getClusterPublicHost());
    if (publicHost == null) {
      publicHost = options.getClusterHost();
    }
    return publicHost;
  }

  private Handler<NetSocket> getServerHandler() {
    return socket -> {
      RecordParser parser = RecordParser.newFixed(4, null);
      Handler<Buffer> handler = new Handler<Buffer>() {
        int size = -1;
        public void handle(Buffer buff) {
          if (size == -1) {
            size = buff.getInt(0);
            parser.fixedSizeMode(size);
          } else {
            ClusteredMessage received = new ClusteredMessage();
            received.readFromWire(buff, codecManager);
            metrics.messageRead(received.address(), buff.length());
            parser.fixedSizeMode(4);
            size = -1;
            if (received.codec() == CodecManager.PING_MESSAGE_CODEC) {
              // Just send back pong directly on connection
              socket.write(PONG);
            } else {
              deliverMessageLocally(received);
            }
          }
        }
      };
      parser.setOutput(handler);
      socket.handler(parser);
    };
  }

  private <T> void sendToSubs(ChoosableIterable<ServerID> subs, SendContextImpl<T> sendContext) {
    String address = sendContext.message.address();
    if (sendContext.message.send()) {
      // Choose one
      ServerID sid = subs.choose();
      if (!sid.equals(serverID)) {  //We don't send to this node
        metrics.messageSent(address, false, false, true);
        sendRemote(sid, sendContext.message);
      } else {
        metrics.messageSent(address, false, true, false);
        deliverMessageLocally(sendContext);
      }
    } else {
      // Publish
      boolean local = false;
      boolean remote = false;
      for (ServerID sid : subs) {
        if (!sid.equals(serverID)) {  //We don't send to this node
          remote = true;
          sendRemote(sid, sendContext.message);
        } else {
          local = true;
        }
      }
      metrics.messageSent(address, true, local, remote);
      if (local) {
        deliverMessageLocally(sendContext);
      }
    }
  }

  private <T> void clusteredSendReply(ServerID replyDest, SendContextImpl<T> sendContext) {
    MessageImpl message = sendContext.message;
    String address = message.address();
    if (!replyDest.equals(serverID)) {
      metrics.messageSent(address, false, false, true);
      sendRemote(replyDest, message);
    } else {
      metrics.messageSent(address, false, true, false);
      deliverMessageLocally(sendContext);
    }
  }

  private void sendRemote(ServerID theServerID, MessageImpl message) {
    // We need to deal with the fact that connecting can take some time and is async, and we cannot
    // block to wait for it. So we add any sends to a pending list if not connected yet.
    // Once we connect we send them.
    // This can also be invoked concurrently from different threads, so it gets a little
    // tricky
    ConnectionHolder holder = connections.get(theServerID);
    if (holder == null) {
      // When process is creating a lot of connections this can take some time
      // so increase the timeout
      holder = new ConnectionHolder(this, theServerID);
      ConnectionHolder prevHolder = connections.putIfAbsent(theServerID, holder);
      if (prevHolder != null) {
        // Another one sneaked in
        holder = prevHolder;
      } else {
        holder.connect();
      }
    }
    holder.writeMessage((ClusteredMessage)message);
  }

  private void removeSub(String subName, ServerID theServerID, Handler<AsyncResult<Void>> completionHandler) {
    subs.remove(subName, theServerID, ar -> {
      if (!ar.succeeded()) {
        log.error("Failed to remove sub", ar.cause());
      } else {
        if (ar.result()) {
          if (completionHandler != null) {
            completionHandler.handle(Future.succeededFuture());
          }
        } else {
          if (completionHandler != null) {
            completionHandler.handle(Future.failedFuture("sub not found"));
          }
        }

      }
    });
  }

  ConcurrentMap<ServerID, ConnectionHolder> connections() {
    return connections;
  }

  VertxInternal vertx() {
    return vertx;
  }

  VertxOptions options() {
    return options;
  }

}


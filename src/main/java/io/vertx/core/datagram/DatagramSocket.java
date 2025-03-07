/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.datagram;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.codegen.annotations.CacheReturn;
import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.metrics.Measured;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

/**
 * A datagram socket can be used to send {@link DatagramPacket}'s to remote datagram servers
 * and receive {@link DatagramPacket}s .
 * <p>
 * Usually you use a datagram socket to send UDP over the wire. UDP is connection-less which means you are not connected
 * to the remote peer in a persistent way. Because of this you have to supply the address and port of the remote peer
 * when sending data.
 * <p>
 * You can send data to ipv4 or ipv6 addresses, which also include multicast addresses.
 * <p>
 * Please consult the documentation for more information on datagram sockets.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@VertxGen
public interface DatagramSocket extends ReadStream<DatagramPacket>, Measured {

  /**
   * Write the given {@link io.vertx.core.buffer.Buffer} to the {@link io.vertx.core.net.SocketAddress}.
   *
   * @param packet  the {@link io.vertx.core.buffer.Buffer} to write
   * @param port  the host port of the remote peer
   * @param host  the host address of the remote peer
   * @return a future notified once the write completes.
   */
  Future<Void> send(Buffer packet, int port, String host);

  /**
   * Returns a {@code WriteStream<Buffer>} able to send {@link Buffer} to the
   * {@link io.vertx.core.net.SocketAddress}.
   *
   * @param port the port of the remote peer
   * @param host the host address of the remote peer
   * @return the write stream for sending packets
   */
  WriteStream<Buffer> sender(int port, String host);

  /**
   * Write the given {@link String} to the {@link io.vertx.core.net.SocketAddress} using UTF8 encoding.
   *
   * @param str   the {@link String} to write
   * @param port  the host port of the remote peer
   * @param host  the host address of the remote peer
   * @return a future notified once the write completes.
   */
  Future<Void> send(String str, int port, String host);

  /**
   * Write the given {@link String} to the {@link io.vertx.core.net.SocketAddress} using the given encoding.
   *
   * @param str  the {@link String} to write
   * @param enc  the charset used for encoding
   * @param port  the host port of the remote peer
   * @param host  the host address of the remote peer
   * @return a future notified once the write completes.
   */
  Future<Void> send(String str, String enc, int port, String host);

  /**
   * Closes the {@link io.vertx.core.datagram.DatagramSocket} implementation asynchronous
   * and notifies the handler once done.
   *
   * @return a future notified once the socket is closed
   */
  Future<Void> close();

  /**
   * Return the {@link io.vertx.core.net.SocketAddress} to which
   * this {@link io.vertx.core.datagram.DatagramSocket} is bound.
   *
   * @return the socket address
   */
  @CacheReturn
  SocketAddress localAddress();

  /**
   * Joins a multicast group and listens for packets send to it.
   *
   * @param multicastAddress  the address of the multicast group to join
   * @return a future notified once the operation completes
   */
  Future<Void> listenMulticastGroup(String multicastAddress);

  /**
   * Joins a multicast group and listens for packets send to it on the given network interface.
   *
   * @param  multicastAddress  the address of the multicast group to join
   * @param  networkInterface  the network interface on which to listen for packets.
   * @param  source  the address of the source for which we will listen for multicast packets
   * @return a future notified once the operation completes
   */
  Future<Void> listenMulticastGroup(String multicastAddress, String networkInterface, @Nullable String source);

  /**
   * Leaves a multicast group and stops listening for packets send to it.
   *
   * @param multicastAddress  the address of the multicast group to leave
   * @return a future notified once the operation completes
   */
  Future<Void> unlistenMulticastGroup(String multicastAddress);

  /**
   * Leaves a multicast group and stops listening for packets send to it on the given network interface.
   *
   * @param  multicastAddress  the address of the multicast group to join
   * @param  networkInterface  the network interface on which to listen for packets.
   * @param  source  the address of the source for which we will listen for multicast packets
   * @return a future notified once the operation completes
   */
  Future<Void> unlistenMulticastGroup(String multicastAddress, String networkInterface, @Nullable String source);

  /**
   * Block the given address for the given multicast address and notifies the {@link Handler} once
   * the operation completes.
   *
   * @param multicastAddress  the address for which you want to block the source address
   * @param sourceToBlock  the source address which should be blocked. You will not receive an multicast packets
   *                       for it anymore.
   * @return a future notified once the operation completes
   */
  Future<Void> blockMulticastGroup(String multicastAddress, String sourceToBlock);

  /**
   * Block the given address for the given multicast address on the given network interface and notifies
   * the {@link Handler} once the operation completes.
   *
   * @param  multicastAddress  the address for which you want to block the source address
   * @param  networkInterface  the network interface on which the blocking should occur.
   * @param  sourceToBlock  the source address which should be blocked. You will not receive an multicast packets
   *                        for it anymore.
   * @return a future notified once the operation completes
   */
  Future<Void> blockMulticastGroup(String multicastAddress, String networkInterface, String sourceToBlock);

  /**
   * Start listening on the given port and host. The handler will be called when the socket is listening.
   *
   * @param port  the port to listen on
   * @param host  the host to listen on
   * @return a future notified once the operation completes
   */
  Future<DatagramSocket> listen(int port, String host);

  @Override
  DatagramSocket pause();

  @Override
  DatagramSocket resume();

  @Override
  DatagramSocket fetch(long amount);

  @Override
  DatagramSocket endHandler(Handler<Void> endHandler);

  @Override
  DatagramSocket handler(Handler<DatagramPacket> handler);

  @Override
  DatagramSocket exceptionHandler(Handler<Throwable> handler);

}

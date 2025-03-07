/*
 * Copyright (c) 2011-2022 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.eventbus.impl;

import io.vertx.core.*;
import io.vertx.core.eventbus.*;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.utils.ConcurrentCyclicSequence;
import io.vertx.core.spi.metrics.EventBusMetrics;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.VertxMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

/**
 * A local event bus implementation
 *
 * @author <a href="http://tfox.org">Tim Fox</a>                                                                                        T
 */
public class EventBusImpl implements EventBusInternal, MetricsProvider {

  private static final AtomicReferenceFieldUpdater<EventBusImpl, Handler[]> OUTBOUND_INTERCEPTORS_UPDATER = AtomicReferenceFieldUpdater.newUpdater(EventBusImpl.class, Handler[].class, "outboundInterceptors");
  private static final AtomicReferenceFieldUpdater<EventBusImpl, Handler[]> INBOUND_INTERCEPTORS_UPDATER = AtomicReferenceFieldUpdater.newUpdater(EventBusImpl.class, Handler[].class, "inboundInterceptors");

  private volatile Handler<DeliveryContext>[] outboundInterceptors = new Handler[0];
  private volatile Handler<DeliveryContext>[] inboundInterceptors = new Handler[0];
  private final AtomicLong replySequence = new AtomicLong(0);
  protected final VertxInternal vertx;
  protected final EventBusMetrics metrics;
  protected final ConcurrentMap<String, ConcurrentCyclicSequence<HandlerHolder>> handlerMap = new ConcurrentHashMap<>();
  protected final CodecManager codecManager = new CodecManager();
  protected volatile boolean started;

  public EventBusImpl(VertxInternal vertx) {
    VertxMetrics metrics = vertx.metricsSPI();
    this.vertx = vertx;
    this.metrics = metrics != null ? metrics.createEventBusMetrics() : null;
  }

  @Override
  public <T> EventBus addOutboundInterceptor(Handler<DeliveryContext<T>> interceptor) {
    addInterceptor(OUTBOUND_INTERCEPTORS_UPDATER, Objects.requireNonNull(interceptor));
    return this;
  }

  @Override
  public <T> EventBus addInboundInterceptor(Handler<DeliveryContext<T>> interceptor) {
    addInterceptor(INBOUND_INTERCEPTORS_UPDATER, Objects.requireNonNull(interceptor));
    return this;
  }

  @Override
  public <T> EventBus removeOutboundInterceptor(Handler<DeliveryContext<T>> interceptor) {
    removeInterceptor(OUTBOUND_INTERCEPTORS_UPDATER, Objects.requireNonNull(interceptor));
    return this;
  }

  @Override
  public <T> EventBus removeInboundInterceptor(Handler<DeliveryContext<T>> interceptor) {
    removeInterceptor(INBOUND_INTERCEPTORS_UPDATER, Objects.requireNonNull(interceptor));
    return this;
  }

  Handler<DeliveryContext>[] inboundInterceptors() {
    return inboundInterceptors;
  }

  Handler<DeliveryContext>[] outboundInterceptors() {
    return outboundInterceptors;
  }

  @Override
  public EventBus clusterSerializableChecker(Function<String, Boolean> classNamePredicate) {
    codecManager.clusterSerializableCheck(classNamePredicate);
    return this;
  }

  @Override
  public EventBus serializableChecker(Function<String, Boolean> classNamePredicate) {
    codecManager.serializableCheck(classNamePredicate);
    return this;
  }

  @Override
  public synchronized void start(Promise<Void> promise) {
    if (started) {
      throw new IllegalStateException("Already started");
    }
    started = true;
    promise.complete();
  }

  @Override
  public EventBus send(String address, Object message) {
    return send(address, message, new DeliveryOptions());
  }

  @Override
  public EventBus send(String address, Object message, DeliveryOptions options) {
    MessageImpl msg = createMessage(true, address, options.getHeaders(), message, options.getCodecName());
    sendOrPubInternal(msg, options, null, null);
    return this;
  }

  @Override
  public <T> Future<Message<T>> request(String address, Object message, DeliveryOptions options) {
    MessageImpl msg = createMessage(true, address, options.getHeaders(), message, options.getCodecName());
    ReplyHandler<T> handler = createReplyHandler(msg, true, options);
    sendOrPubInternal(msg, options, handler, null);
    return handler.result();
  }

  @Override
  public <T> MessageProducer<T> sender(String address) {
    Objects.requireNonNull(address, "address");
    return new MessageProducerImpl<>(vertx, address, true, new DeliveryOptions());
  }

  @Override
  public <T> MessageProducer<T> sender(String address, DeliveryOptions options) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(options, "options");
    return new MessageProducerImpl<>(vertx, address, true, options);
  }

  @Override
  public <T> MessageProducer<T> publisher(String address) {
    Objects.requireNonNull(address, "address");
    return new MessageProducerImpl<>(vertx, address, false, new DeliveryOptions());
  }

  @Override
  public <T> MessageProducer<T> publisher(String address, DeliveryOptions options) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(options, "options");
    return new MessageProducerImpl<>(vertx, address, false, options);
  }

  @Override
  public EventBus publish(String address, Object message) {
    return publish(address, message, new DeliveryOptions());
  }

  @Override
  public EventBus publish(String address, Object message, DeliveryOptions options) {
    sendOrPubInternal(createMessage(false, address, options.getHeaders(), message, options.getCodecName()), options, null, null);
    return this;
  }

  @Override
  public <T> MessageConsumer<T> consumer(String address) {
    checkStarted();
    Objects.requireNonNull(address, "address");
    return new MessageConsumerImpl<>(vertx, vertx.getOrCreateContext(), this, address,  false);
  }

  @Override
  public <T> MessageConsumer<T> consumer(String address, Handler<Message<T>> handler) {
    Objects.requireNonNull(handler, "handler");
    MessageConsumer<T> consumer = consumer(address);
    consumer.handler(handler);
    return consumer;
  }

  @Override
  public <T> MessageConsumer<T> localConsumer(String address) {
    checkStarted();
    Objects.requireNonNull(address, "address");
    return new MessageConsumerImpl<>(vertx, vertx.getOrCreateContext(), this, address,  true);
  }

  @Override
  public <T> MessageConsumer<T> localConsumer(String address, Handler<Message<T>> handler) {
    Objects.requireNonNull(handler, "handler");
    MessageConsumer<T> consumer = localConsumer(address);
    consumer.handler(handler);
    return consumer;
  }

  @Override
  public EventBus registerCodec(MessageCodec codec) {
    codecManager.registerCodec(codec);
    return this;
  }

  @Override
  public EventBus unregisterCodec(String name) {
    codecManager.unregisterCodec(name);
    return this;
  }

  @Override
  public <T> EventBus registerDefaultCodec(Class<T> clazz, MessageCodec<T, ?> codec) {
    codecManager.registerDefaultCodec(clazz, codec);
    return this;
  }

  @Override
  public EventBus unregisterDefaultCodec(Class clazz) {
    codecManager.unregisterDefaultCodec(clazz);
    return this;
  }

  @Override
  public EventBus codecSelector(Function<Object, String> selector) {
    codecManager.codecSelector(selector);
    return this;
  }

  @Override
  public void close(Promise<Void> promise) {
    if (!started) {
      promise.complete();
      return;
    }
    unregisterAll().onComplete(ar -> {
      if (metrics != null) {
        metrics.close();
      }
      promise.handle(ar);
    });
  }

  @Override
  public boolean isMetricsEnabled() {
    return metrics != null;
  }

  @Override
  public EventBusMetrics<?> getMetrics() {
    return metrics;
  }

  public MessageImpl createMessage(boolean send, String address, MultiMap headers, Object body, String codecName) {
    Objects.requireNonNull(address, "no null address accepted");
    MessageCodec codec = codecManager.lookupCodec(body, codecName, true);
    @SuppressWarnings("unchecked")
    MessageImpl msg = new MessageImpl(address, headers, body, codec, send, this);
    return msg;
  }

  protected <T> HandlerHolder<T> addRegistration(String address, HandlerRegistration<T> registration, boolean replyHandler, boolean localOnly, Promise<Void> promise) {
    HandlerHolder<T> holder = addLocalRegistration(address, registration, replyHandler, localOnly);
    onLocalRegistration(holder, promise);
    return holder;
  }

  protected <T> void onLocalRegistration(HandlerHolder<T> handlerHolder, Promise<Void> promise) {
    if (promise != null) {
      promise.complete();
    }
  }

  private <T> HandlerHolder<T> addLocalRegistration(String address, HandlerRegistration<T> registration,
                                                    boolean replyHandler, boolean localOnly) {
    Objects.requireNonNull(address, "address");

    ContextInternal context = registration.context;

    HandlerHolder<T> holder = createHandlerHolder(registration, replyHandler, localOnly, context);

    ConcurrentCyclicSequence<HandlerHolder> handlers = new ConcurrentCyclicSequence<HandlerHolder>().add(holder);
    ConcurrentCyclicSequence<HandlerHolder> actualHandlers = handlerMap.merge(
      address,
      handlers,
      (old, prev) -> old.add(prev.first()));

    if (context.isDeployment()) {
      context.addCloseHook(registration);
    }

    return holder;
  }

  protected <T> HandlerHolder<T> createHandlerHolder(HandlerRegistration<T> registration, boolean replyHandler, boolean localOnly, ContextInternal context) {
    return new HandlerHolder<>(registration, replyHandler, localOnly, context);
  }

  protected <T> void removeRegistration(HandlerHolder<T> handlerHolder, Promise<Void> promise) {
    removeLocalRegistration(handlerHolder);
    onLocalUnregistration(handlerHolder, promise);
  }

  protected <T> void onLocalUnregistration(HandlerHolder<T> handlerHolder, Promise<Void> promise) {
    promise.complete();
  }

  private <T> void removeLocalRegistration(HandlerHolder<T> holder) {
    String address = holder.getHandler().address;
    handlerMap.compute(address, (key, val) -> {
      if (val == null) {
        return null;
      }
      ConcurrentCyclicSequence<HandlerHolder> next = val.remove(holder);
      return next.size() == 0 ? null : next;
    });
    if (holder.setRemoved() && holder.getContext().deploymentID() != null) {
      holder.getContext().removeCloseHook(holder.getHandler());
    }
  }

  protected <T> void sendReply(MessageImpl replyMessage, DeliveryOptions options, ReplyHandler<T> replyHandler) {
    if (replyMessage.address() == null) {
      throw new IllegalStateException("address not specified");
    } else {
      sendOrPubInternal(new OutboundDeliveryContext<>(vertx.getOrCreateContext(), replyMessage, options, replyHandler, null));
    }
  }

  protected <T> void sendOrPub(OutboundDeliveryContext<T> sendContext) {
    sendLocally(sendContext);
  }

  private <T> void sendLocally(OutboundDeliveryContext<T> sendContext) {
    ReplyException failure = deliverMessageLocally(sendContext.message);
    if (failure != null) {
      sendContext.written(failure);
    } else {
      sendContext.written(null);
    }
  }

  protected boolean isMessageLocal(MessageImpl msg) {
    return true;
  }

  protected ReplyException deliverMessageLocally(MessageImpl msg) {
    ConcurrentCyclicSequence<HandlerHolder> handlers = handlerMap.get(msg.address());
    boolean messageLocal = isMessageLocal(msg);
    if (handlers != null) {
      if (msg.isSend()) {
        //Choose one
        HandlerHolder holder = nextHandler(handlers, messageLocal);
        if (metrics != null) {
          metrics.messageReceived(msg.address(), !msg.isSend(), messageLocal, holder != null ? 1 : 0);
        }
        if (holder != null) {
          holder.handler.receive(msg.copyBeforeReceive());
        } else {
          // RACY issue !!!!!
        }
      } else {
        // Publish
        if (metrics != null) {
          metrics.messageReceived(msg.address(), !msg.isSend(), messageLocal, handlers.size());
        }
        for (HandlerHolder holder: handlers) {
          if (messageLocal || !holder.isLocalOnly()) {
            holder.handler.receive(msg.copyBeforeReceive());
          }
        }
      }
      return null;
    } else {
      if (metrics != null) {
        metrics.messageReceived(msg.address(), !msg.isSend(), messageLocal, 0);
      }
      return new ReplyException(ReplyFailure.NO_HANDLERS, "No handlers for address " + msg.address);
    }
  }

  protected HandlerHolder nextHandler(ConcurrentCyclicSequence<HandlerHolder> handlers, boolean messageLocal) {
    return handlers.next();
  }

  protected void checkStarted() {
    if (!started) {
      throw new IllegalStateException("Event Bus is not started");
    }
  }

  protected String generateReplyAddress() {
    return "__vertx.reply." + Long.toString(replySequence.incrementAndGet());
  }

  <T> ReplyHandler<T> createReplyHandler(MessageImpl message,
                                                 boolean src,
                                                 DeliveryOptions options) {
    long timeout = options.getSendTimeout();
    String replyAddress = generateReplyAddress();
    message.setReplyAddress(replyAddress);
    ReplyHandler<T> handler = new ReplyHandler<>(this, vertx.getOrCreateContext(), replyAddress, message.address, src, timeout);
    handler.register();
    return handler;
  }

  public <T> OutboundDeliveryContext<T> newSendContext(MessageImpl message, DeliveryOptions options,
                                               ReplyHandler<T> handler, Promise<Void> writePromise) {
    return new OutboundDeliveryContext<>(vertx.getOrCreateContext(), message, options, handler, writePromise);
  }

  public <T> void sendOrPubInternal(OutboundDeliveryContext<T> senderCtx) {
    checkStarted();
    senderCtx.bus = this;
    senderCtx.metrics = metrics;
    senderCtx.next();
  }

  public <T> void sendOrPubInternal(MessageImpl message, DeliveryOptions options,
                                    ReplyHandler<T> handler, Promise<Void> writePromise) {
    checkStarted();
    sendOrPubInternal(newSendContext(message, options, handler, writePromise));
  }

  private Future<Void> unregisterAll() {
    // Unregister all handlers explicitly - don't rely on context hooks
    List<Future> futures = new ArrayList<>();
    for (ConcurrentCyclicSequence<HandlerHolder> handlers : handlerMap.values()) {
      for (HandlerHolder holder : handlers) {
        futures.add(holder.getHandler().unregister());
      }
    }
    return CompositeFuture.join(futures).mapEmpty();
  }

  private void addInterceptor(AtomicReferenceFieldUpdater<EventBusImpl, Handler[]> updater, Handler interceptor) {
    while (true) {
      Handler[] interceptors = updater.get(this);
      Handler[] copy = Arrays.copyOf(interceptors, interceptors.length + 1);
      copy[interceptors.length] = interceptor;
      if (updater.compareAndSet(this, interceptors, copy)) {
        break;
      }
    }
  }

  private void removeInterceptor(AtomicReferenceFieldUpdater<EventBusImpl, Handler[]> updater, Handler interceptor) {
    while (true) {
      Handler[] interceptors = updater.get(this);
      int idx = -1;
      for (int i = 0;i < interceptors.length;i++) {
        if (interceptors[i].equals(interceptor)) {
          idx = i;
          break;
        }
      }
      if (idx == -1) {
        return;
      }
      Handler<DeliveryContext>[] copy = new Handler[interceptors.length - 1];
      System.arraycopy(interceptors, 0, copy, 0, idx);
      System.arraycopy(interceptors, idx + 1, copy, idx, copy.length - idx);
      if (updater.compareAndSet(this, interceptors, copy)) {
        break;
      }
    }
  }
}


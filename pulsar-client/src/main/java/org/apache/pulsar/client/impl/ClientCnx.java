/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.pulsar.client.impl.HttpClient.getPulsarClientVersion;

import com.google.common.collect.Queues;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.Errors.NativeIoException;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Promise;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.net.ssl.SSLSession;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.TimeoutException;
import org.apache.pulsar.client.impl.BinaryProtoLookupService.LookupDataResult;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.PulsarHandler;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandActiveConsumerChange;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandCloseConsumer;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandCloseProducer;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandConnected;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandError;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandGetLastMessageIdResponse;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandGetTopicsOfNamespaceResponse;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopicResponse;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandMessage;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandPartitionedTopicMetadataResponse;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandProducerSuccess;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandReachedEndOfTopic;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSendError;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSendReceipt;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSuccess;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageIdData;
import org.apache.pulsar.common.api.proto.PulsarApi.ServerError;
import org.apache.pulsar.common.util.collections.ConcurrentLongHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientCnx extends PulsarHandler {

    private final Authentication authentication;
    private State state;

    private final ConcurrentLongHashMap<CompletableFuture<ProducerResponse>> pendingRequests =
        new ConcurrentLongHashMap<>(16, 1);
    private final ConcurrentLongHashMap<CompletableFuture<LookupDataResult>> pendingLookupRequests =
        new ConcurrentLongHashMap<>(16, 1);
    // LookupRequests that waiting in client side.
    private final BlockingQueue<Pair<Long, Pair<ByteBuf, CompletableFuture<LookupDataResult>>>> waitingLookupRequests;
    private final ConcurrentLongHashMap<CompletableFuture<MessageIdData>> pendingGetLastMessageIdRequests =
        new ConcurrentLongHashMap<>(16, 1);
    private final ConcurrentLongHashMap<CompletableFuture<List<String>>> pendingGetTopicsRequests =
        new ConcurrentLongHashMap<>(16, 1);

    private final ConcurrentLongHashMap<ProducerImpl<?>> producers = new ConcurrentLongHashMap<>(16, 1);
    private final ConcurrentLongHashMap<ConsumerImpl<?>> consumers = new ConcurrentLongHashMap<>(16, 1);

    private final CompletableFuture<Void> connectionFuture = new CompletableFuture<Void>();
    private final Semaphore pendingLookupRequestSemaphore;
    private final EventLoopGroup eventLoopGroup;

    private static final AtomicIntegerFieldUpdater<ClientCnx> NUMBER_OF_REJECTED_REQUESTS_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(ClientCnx.class, "numberOfRejectRequests");
    @SuppressWarnings("unused")
    private volatile int numberOfRejectRequests = 0;
    private final int maxNumberOfRejectedRequestPerConnection;
    private final int rejectedRequestResetTimeSec = 60;
    private final long operationTimeoutMs;

    private String proxyToTargetBrokerAddress = null;
    // Remote hostName with which client is connected
    private String remoteHostName = null;
    private boolean isTlsHostnameVerificationEnable;
    private DefaultHostnameVerifier hostnameVerifier;

    enum State {
        None, SentConnectFrame, Ready, Failed
    }

    public ClientCnx(ClientConfigurationData conf, EventLoopGroup eventLoopGroup) {
        super(conf.getKeepAliveIntervalSeconds(), TimeUnit.SECONDS);
        checkArgument(conf.getMaxLookupRequest() > conf.getConcurrentLookupRequest());
        this.pendingLookupRequestSemaphore = new Semaphore(conf.getConcurrentLookupRequest(), true);
        this.waitingLookupRequests = Queues
            .newArrayBlockingQueue((conf.getMaxLookupRequest() - conf.getConcurrentLookupRequest()));
        this.authentication = conf.getAuthentication();
        this.eventLoopGroup = eventLoopGroup;
        this.maxNumberOfRejectedRequestPerConnection = conf.getMaxNumberOfRejectedRequestPerConnection();
        this.operationTimeoutMs = conf.getOperationTimeoutMs();
        this.state = State.None;
        this.isTlsHostnameVerificationEnable = conf.isTlsHostnameVerificationEnable();
        this.hostnameVerifier = new DefaultHostnameVerifier();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);

        if (proxyToTargetBrokerAddress == null) {
            if (log.isDebugEnabled()) {
                log.debug("{} Connected to broker", ctx.channel());
            }
        } else {
            log.info("{} Connected through proxy to target broker at {}", ctx.channel(), proxyToTargetBrokerAddress);
        }
        String authData = "";
        if (authentication.getAuthData().hasDataFromCommand()) {
            authData = authentication.getAuthData().getCommandData();
        }
        // Send CONNECT command
        ctx.writeAndFlush(Commands.newConnect(authentication.getAuthMethodName(), authData,
                getPulsarClientVersion(), proxyToTargetBrokerAddress))
                .addListener(future -> {
                    if (future.isSuccess()) {
                        if (log.isDebugEnabled()) {
                            log.debug("Complete: {}", future.isSuccess());
                        }
                        state = State.SentConnectFrame;
                    } else {
                        log.warn("Error during handshake", future.cause());
                        ctx.close();
                    }
                });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("{} Disconnected", ctx.channel());
        if (!connectionFuture.isDone()) {
            connectionFuture.completeExceptionally(new PulsarClientException("Connection already closed"));
        }

        PulsarClientException e = new PulsarClientException(
                "Disconnected from server at " + ctx.channel().remoteAddress());

        // Fail out all the pending ops
        pendingRequests.forEach((key, future) -> future.completeExceptionally(e));
        pendingLookupRequests.forEach((key, future) -> future.completeExceptionally(e));
        waitingLookupRequests.forEach(pair -> pair.getRight().getRight().completeExceptionally(e));
        pendingGetLastMessageIdRequests.forEach((key, future) -> future.completeExceptionally(e));
        pendingGetTopicsRequests.forEach((key, future) -> future.completeExceptionally(e));

        // Notify all attached producers/consumers so they have a chance to reconnect
        producers.forEach((id, producer) -> producer.connectionClosed(this));
        consumers.forEach((id, consumer) -> consumer.connectionClosed(this));

        pendingRequests.clear();
        pendingLookupRequests.clear();
        waitingLookupRequests.clear();
        pendingGetLastMessageIdRequests.clear();
        pendingGetTopicsRequests.clear();

        producers.clear();
        consumers.clear();
    }

    // Command Handlers

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (state != State.Failed) {
            // No need to report stack trace for known exceptions that happen in disconnections
            log.warn("[{}] Got exception {} : {}", remoteAddress, cause.getClass().getSimpleName(), cause.getMessage(),
                    isKnownException(cause) ? null : cause);
            state = State.Failed;
        } else {
            // At default info level, suppress all subsequent exceptions that are thrown when the connection has already
            // failed
            if (log.isDebugEnabled()) {
                log.debug("[{}] Got exception: {}", remoteAddress, cause.getMessage(), cause);
            }
        }

        ctx.close();
    }

    public static boolean isKnownException(Throwable t) {
        return t instanceof NativeIoException || t instanceof ClosedChannelException;
    }

    @Override
    protected void handleConnected(CommandConnected connected) {

        if (isTlsHostnameVerificationEnable && remoteHostName != null && !verifyTlsHostName(remoteHostName, ctx)) {
            // close the connection if host-verification failed with the broker
            log.warn("[{}] Failed to verify hostname of {}", ctx.channel(), remoteHostName);
            ctx.close();
            return;
        }

        checkArgument(state == State.SentConnectFrame);

        if (log.isDebugEnabled()) {
            log.debug("{} Connection is ready", ctx.channel());
        }
        connectionFuture.complete(null);
        remoteEndpointProtocolVersion = connected.getProtocolVersion();
        state = State.Ready;
    }

    @Override
    protected void handleSendReceipt(CommandSendReceipt sendReceipt) {
        checkArgument(state == State.Ready);

        long producerId = sendReceipt.getProducerId();
        long sequenceId = sendReceipt.getSequenceId();
        long ledgerId = -1;
        long entryId = -1;
        if (sendReceipt.hasMessageId()) {
            ledgerId = sendReceipt.getMessageId().getLedgerId();
            entryId = sendReceipt.getMessageId().getEntryId();
        }

        if (ledgerId == -1 && entryId == -1) {
            log.warn("[{}] Message has been dropped for non-persistent topic producer-id {}-{}", ctx.channel(),
                    producerId, sequenceId);
        }

        if (log.isDebugEnabled()) {
            log.debug("{} Got receipt for producer: {} -- msg: {} -- id: {}:{}", ctx.channel(), producerId, sequenceId,
                    ledgerId, entryId);
        }

        producers.get(producerId).ackReceived(this, sequenceId, ledgerId, entryId);
    }

    @Override
    protected void handleMessage(CommandMessage cmdMessage, ByteBuf headersAndPayload) {
        checkArgument(state == State.Ready);

        if (log.isDebugEnabled()) {
            log.debug("{} Received a message from the server: {}", ctx.channel(), cmdMessage);
        }
        ConsumerImpl<?> consumer = consumers.get(cmdMessage.getConsumerId());
        if (consumer != null) {
            consumer.messageReceived(cmdMessage.getMessageId(), headersAndPayload, this);
        }
    }

    @Override
    protected void handleActiveConsumerChange(CommandActiveConsumerChange change) {
        checkArgument(state == State.Ready);

        if (log.isDebugEnabled()) {
            log.debug("{} Received a consumer group change message from the server : {}", ctx.channel(), change);
        }
        ConsumerImpl<?> consumer = consumers.get(change.getConsumerId());
        if (consumer != null) {
            consumer.activeConsumerChanged(change.getIsActive());
        }
    }

    @Override
    protected void handleSuccess(CommandSuccess success) {
        checkArgument(state == State.Ready);

        if (log.isDebugEnabled()) {
            log.debug("{} Received success response from server: {}", ctx.channel(), success.getRequestId());
        }
        long requestId = success.getRequestId();
        CompletableFuture<ProducerResponse> requestFuture = pendingRequests.remove(requestId);
        if (requestFuture != null) {
            requestFuture.complete(null);
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), success.getRequestId());
        }
    }

    @Override
    protected void handleGetLastMessageIdSuccess(CommandGetLastMessageIdResponse success) {
        checkArgument(state == State.Ready);

        if (log.isDebugEnabled()) {
            log.debug("{} Received success GetLastMessageId response from server: {}", ctx.channel(), success.getRequestId());
        }
        long requestId = success.getRequestId();
        CompletableFuture<MessageIdData> requestFuture = pendingGetLastMessageIdRequests.remove(requestId);
        if (requestFuture != null) {
            requestFuture.complete(success.getLastMessageId());
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), success.getRequestId());
        }
    }

    @Override
    protected void handleProducerSuccess(CommandProducerSuccess success) {
        checkArgument(state == State.Ready);

        if (log.isDebugEnabled()) {
            log.debug("{} Received producer success response from server: {} - producer-name: {}", ctx.channel(),
                    success.getRequestId(), success.getProducerName());
        }
        long requestId = success.getRequestId();
        CompletableFuture<ProducerResponse> requestFuture = pendingRequests.remove(requestId);
        if (requestFuture != null) {
            requestFuture.complete(new ProducerResponse(success.getProducerName(), success.getLastSequenceId(), success.getSchemaVersion().toByteArray()));
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), success.getRequestId());
        }
    }

    @Override
    protected void handleLookupResponse(CommandLookupTopicResponse lookupResult) {
        if (log.isDebugEnabled()) {
            log.debug("Received Broker lookup response: {}", lookupResult.getResponse());
        }

        long requestId = lookupResult.getRequestId();
        CompletableFuture<LookupDataResult> requestFuture = getAndRemovePendingLookupRequest(requestId);

        if (requestFuture != null) {
            if (requestFuture.isCompletedExceptionally()) {
                if (log.isDebugEnabled()) {
                    log.debug("{} Request {} already timed-out", ctx.channel(), lookupResult.getRequestId());
                }
                return;
            }
            // Complete future with exception if : Result.response=fail/null
            if (!lookupResult.hasResponse()
                    || CommandLookupTopicResponse.LookupType.Failed.equals(lookupResult.getResponse())) {
                if (lookupResult.hasError()) {
                    checkServerError(lookupResult.getError(), lookupResult.getMessage());
                    requestFuture.completeExceptionally(
                            getPulsarClientException(lookupResult.getError(), lookupResult.getMessage()));
                } else {
                    requestFuture
                            .completeExceptionally(new PulsarClientException.LookupException("Empty lookup response"));
                }
            } else {
                requestFuture.complete(new LookupDataResult(lookupResult));
            }
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), lookupResult.getRequestId());
        }
    }

    @Override
    protected void handlePartitionResponse(CommandPartitionedTopicMetadataResponse lookupResult) {
        if (log.isDebugEnabled()) {
            log.debug("Received Broker Partition response: {}", lookupResult.getPartitions());
        }

        long requestId = lookupResult.getRequestId();
        CompletableFuture<LookupDataResult> requestFuture = getAndRemovePendingLookupRequest(requestId);

        if (requestFuture != null) {
            if (requestFuture.isCompletedExceptionally()) {
                if (log.isDebugEnabled()) {
                    log.debug("{} Request {} already timed-out", ctx.channel(), lookupResult.getRequestId());
                }
                return;
            }
            // Complete future with exception if : Result.response=fail/null
            if (!lookupResult.hasResponse()
                    || CommandPartitionedTopicMetadataResponse.LookupType.Failed.equals(lookupResult.getResponse())) {
                if (lookupResult.hasError()) {
                    checkServerError(lookupResult.getError(), lookupResult.getMessage());
                    requestFuture.completeExceptionally(
                            getPulsarClientException(lookupResult.getError(), lookupResult.getMessage()));
                } else {
                    requestFuture
                            .completeExceptionally(new PulsarClientException.LookupException("Empty lookup response"));
                }
            } else {
                // return LookupDataResult when Result.response = success/redirect
                requestFuture.complete(new LookupDataResult(lookupResult.getPartitions()));
            }
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), lookupResult.getRequestId());
        }
    }

    @Override
    protected void handleReachedEndOfTopic(CommandReachedEndOfTopic commandReachedEndOfTopic) {
        final long consumerId = commandReachedEndOfTopic.getConsumerId();

        log.info("[{}] Broker notification reached the end of topic: {}", remoteAddress, consumerId);

        ConsumerImpl<?> consumer = consumers.get(consumerId);
        if (consumer != null) {
            consumer.setTerminated();
        }
    }

    // caller of this method needs to be protected under pendingLookupRequestSemaphore
    private void addPendingLookupRequests(long requestId, CompletableFuture<LookupDataResult> future) {
        pendingLookupRequests.put(requestId, future);
        eventLoopGroup.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException(
                    requestId + " lookup request timedout after ms " + operationTimeoutMs));
            }
        }, operationTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<LookupDataResult> getAndRemovePendingLookupRequest(long requestId) {
        CompletableFuture<LookupDataResult> result = pendingLookupRequests.remove(requestId);
        if (result != null) {
            Pair<Long, Pair<ByteBuf, CompletableFuture<LookupDataResult>>> firstOneWaiting = waitingLookupRequests.poll();
            if (firstOneWaiting != null) {
                // schedule a new lookup in.
                eventLoopGroup.submit(() -> {
                    long newId = firstOneWaiting.getLeft();
                    CompletableFuture<LookupDataResult> newFuture = firstOneWaiting.getRight().getRight();
                    addPendingLookupRequests(newId, newFuture);
                    ctx.writeAndFlush(firstOneWaiting.getRight().getLeft()).addListener(writeFuture -> {
                        if (!writeFuture.isSuccess()) {
                            log.warn("{} Failed to send request {} to broker: {}", ctx.channel(), newId,
                                writeFuture.cause().getMessage());
                            getAndRemovePendingLookupRequest(newId);
                            newFuture.completeExceptionally(writeFuture.cause());
                        }
                    });
                });
            } else {
                pendingLookupRequestSemaphore.release();
            }
        }
        return result;
    }

    @Override
    protected void handleSendError(CommandSendError sendError) {
        log.warn("{} Received send error from server: {} : {}", ctx.channel(), sendError.getError(),
                sendError.getMessage());

        long producerId = sendError.getProducerId();
        long sequenceId = sendError.getSequenceId();

        switch (sendError.getError()) {
        case ChecksumError:
            producers.get(producerId).recoverChecksumError(this, sequenceId);
            break;

        case TopicTerminatedError:
            producers.get(producerId).terminated(this);
            break;

        default:
            // By default, for transient error, let the reconnection logic
            // to take place and re-establish the produce again
            ctx.close();
        }
    }

    @Override
    protected void handleError(CommandError error) {
        checkArgument(state == State.Ready);

        log.warn("{} Received error from server: {}", ctx.channel(), error.getMessage());
        long requestId = error.getRequestId();
        if (error.getError() == ServerError.ProducerBlockedQuotaExceededError) {
            log.warn("{} Producer creation has been blocked because backlog quota exceeded for producer topic",
                    ctx.channel());
        }
        CompletableFuture<ProducerResponse> requestFuture = pendingRequests.remove(requestId);
        if (requestFuture != null) {
            requestFuture.completeExceptionally(getPulsarClientException(error.getError(), error.getMessage()));
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), error.getRequestId());
        }
    }

    @Override
    protected void handleCloseProducer(CommandCloseProducer closeProducer) {
        log.info("[{}] Broker notification of Closed producer: {}", remoteAddress, closeProducer.getProducerId());
        final long producerId = closeProducer.getProducerId();
        ProducerImpl<?> producer = producers.get(producerId);
        if (producer != null) {
            producer.connectionClosed(this);
        } else {
            log.warn("Producer with id {} not found while closing producer ", producerId);
        }
    }

    @Override
    protected void handleCloseConsumer(CommandCloseConsumer closeConsumer) {
        log.info("[{}] Broker notification of Closed consumer: {}", remoteAddress, closeConsumer.getConsumerId());
        final long consumerId = closeConsumer.getConsumerId();
        ConsumerImpl<?> consumer = consumers.get(consumerId);
        if (consumer != null) {
            consumer.connectionClosed(this);
        } else {
            log.warn("Consumer with id {} not found while closing consumer ", consumerId);
        }
    }

    @Override
    protected boolean isHandshakeCompleted() {
        return state == State.Ready;
    }

    public CompletableFuture<LookupDataResult> newLookup(ByteBuf request, long requestId) {
        CompletableFuture<LookupDataResult> future = new CompletableFuture<>();

        if (pendingLookupRequestSemaphore.tryAcquire()) {
            addPendingLookupRequests(requestId, future);
            ctx.writeAndFlush(request).addListener(writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    log.warn("{} Failed to send request {} to broker: {}", ctx.channel(), requestId,
                        writeFuture.cause().getMessage());
                    getAndRemovePendingLookupRequest(requestId);
                    future.completeExceptionally(writeFuture.cause());
                }
            });
        } else {
            if (log.isDebugEnabled()) {
                log.debug("{} Failed to add lookup-request into pending queue", requestId);
            }
            if (!waitingLookupRequests.offer(Pair.of(requestId, Pair.of(request, future)))) {
                if (log.isDebugEnabled()) {
                    log.debug("{} Failed to add lookup-request into waiting queue", requestId);
                }
                future.completeExceptionally(new PulsarClientException.TooManyRequestsException(String.format(
                    "Requests number out of config: There are {%s} lookup requests outstanding and {%s} requests pending.",
                    pendingLookupRequests.size(),
                    waitingLookupRequests.size())));
            }
        }
        return future;
    }

    public CompletableFuture<List<String>> newGetTopicsOfNamespace(ByteBuf request, long requestId) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        pendingGetTopicsRequests.put(requestId, future);
        ctx.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("{} Failed to send request {} to broker: {}", ctx.channel(), requestId,
                    writeFuture.cause().getMessage());
                pendingGetTopicsRequests.remove(requestId);
                future.completeExceptionally(writeFuture.cause());
            }
        });

        return future;
    }

    @Override
    protected void handleGetTopicsOfNamespaceSuccess(CommandGetTopicsOfNamespaceResponse success) {
        checkArgument(state == State.Ready);

        long requestId = success.getRequestId();
        List<String> topics = success.getTopicsList();

        if (log.isDebugEnabled()) {
            log.debug("{} Received get topics of namespace success response from server: {} - topics.size: {}",
                ctx.channel(), success.getRequestId(), topics.size());
        }

        CompletableFuture<List<String>> requestFuture = pendingGetTopicsRequests.remove(requestId);
        if (requestFuture != null) {
            requestFuture.complete(topics);
        } else {
            log.warn("{} Received unknown request id from server: {}", ctx.channel(), success.getRequestId());
        }
    }

    Promise<Void> newPromise() {
        return ctx.newPromise();
    }

    ChannelHandlerContext ctx() {
        return ctx;
    }

    Channel channel() {
        return ctx.channel();
    }

    SocketAddress serverAddrees() {
        return remoteAddress;
    }

    CompletableFuture<Void> connectionFuture() {
        return connectionFuture;
    }

    CompletableFuture<ProducerResponse> sendRequestWithId(ByteBuf cmd, long requestId) {
        CompletableFuture<ProducerResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        ctx.writeAndFlush(cmd).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("{} Failed to send request to broker: {}", ctx.channel(), writeFuture.cause().getMessage());
                pendingRequests.remove(requestId);
                future.completeExceptionally(writeFuture.cause());
            }
        });
        return future;
    }

    public CompletableFuture<MessageIdData> sendGetLastMessageId(ByteBuf request, long requestId) {
        CompletableFuture<MessageIdData> future = new CompletableFuture<>();

        pendingGetLastMessageIdRequests.put(requestId, future);

        ctx.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("{} Failed to send GetLastMessageId request to broker: {}", ctx.channel(), writeFuture.cause().getMessage());
                pendingGetLastMessageIdRequests.remove(requestId);
                future.completeExceptionally(writeFuture.cause());
            }
        });

        return future;
    }

    /**
     * check serverError and take appropriate action
     * <ul>
     * <li>InternalServerError: close connection immediately</li>
     * <li>TooManyRequest: received error count is more than maxNumberOfRejectedRequestPerConnection in
     * #rejectedRequestResetTimeSec</li>
     * </ul>
     *
     * @param error
     * @param errMsg
     */
    private void checkServerError(ServerError error, String errMsg) {
        if (ServerError.ServiceNotReady.equals(error)) {
            log.error("{} Close connection becaues received internal-server error {}", ctx.channel(), errMsg);
            ctx.close();
        } else if (ServerError.TooManyRequests.equals(error)) {
            long rejectedRequests = NUMBER_OF_REJECTED_REQUESTS_UPDATER.getAndIncrement(this);
            if (rejectedRequests == 0) {
                // schedule timer
                eventLoopGroup.schedule(() -> NUMBER_OF_REJECTED_REQUESTS_UPDATER.set(ClientCnx.this, 0),
                        rejectedRequestResetTimeSec, TimeUnit.SECONDS);
            } else if (rejectedRequests >= maxNumberOfRejectedRequestPerConnection) {
                log.error("{} Close connection becaues received {} rejected request in {} seconds ", ctx.channel(),
                        NUMBER_OF_REJECTED_REQUESTS_UPDATER.get(ClientCnx.this), rejectedRequestResetTimeSec);
                ctx.close();
            }
        }
    }

    /**
     * verifies host name provided in x509 Certificate in tls session
     *
     * it matches hostname with below scenarios
     *
     * <pre>
     *  1. Supports IPV4 and IPV6 host matching
     *  2. Supports wild card matching for DNS-name
     *  eg:
     *     HostName                     CN           Result
     * 1.  localhost                    localhost    PASS
     * 2.  localhost                    local*       PASS
     * 3.  pulsar1-broker.com           pulsar*.com  PASS
     * </pre>
     *
     * @param ctx
     * @return true if hostname is verified else return false
     */
    private boolean verifyTlsHostName(String hostname, ChannelHandlerContext ctx) {
        ChannelHandler sslHandler = ctx.channel().pipeline().get("tls");

        SSLSession sslSession = null;
        if (sslHandler != null) {
            sslSession = ((SslHandler) sslHandler).engine().getSession();
            if (log.isDebugEnabled()) {
                log.debug("Verifying HostName for {}, Cipher {}, Protocols {}", hostname, sslSession.getCipherSuite(),
                        sslSession.getProtocol());
            }
            return hostnameVerifier.verify(hostname, sslSession);
        }
        return false;
    }

    void registerConsumer(final long consumerId, final ConsumerImpl<?> consumer) {
        consumers.put(consumerId, consumer);
    }

    void registerProducer(final long producerId, final ProducerImpl<?> producer) {
        producers.put(producerId, producer);
    }

    void removeProducer(final long producerId) {
        producers.remove(producerId);
    }

    void removeConsumer(final long consumerId) {
        consumers.remove(consumerId);
    }

    void setTargetBroker(InetSocketAddress targetBrokerAddress) {
        this.proxyToTargetBrokerAddress = String.format("%s:%d", targetBrokerAddress.getHostString(),
                targetBrokerAddress.getPort());
    }

     void setRemoteHostName(String remoteHostName) {
        this.remoteHostName = remoteHostName;
    }

    private PulsarClientException getPulsarClientException(ServerError error, String errorMsg) {
        switch (error) {
        case AuthenticationError:
            return new PulsarClientException.AuthenticationException(errorMsg);
        case AuthorizationError:
            return new PulsarClientException.AuthorizationException(errorMsg);
        case ProducerBusy:
            return new PulsarClientException.ProducerBusyException(errorMsg);
        case ConsumerBusy:
            return new PulsarClientException.ConsumerBusyException(errorMsg);
        case MetadataError:
            return new PulsarClientException.BrokerMetadataException(errorMsg);
        case PersistenceError:
            return new PulsarClientException.BrokerPersistenceException(errorMsg);
        case ServiceNotReady:
            return new PulsarClientException.LookupException(errorMsg);
        case TooManyRequests:
            return new PulsarClientException.TooManyRequestsException(errorMsg);
        case ProducerBlockedQuotaExceededError:
            return new PulsarClientException.ProducerBlockedQuotaExceededError(errorMsg);
        case ProducerBlockedQuotaExceededException:
            return new PulsarClientException.ProducerBlockedQuotaExceededException(errorMsg);
        case TopicTerminatedError:
            return new PulsarClientException.TopicTerminatedException(errorMsg);
        case UnknownError:
        default:
            return new PulsarClientException(errorMsg);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ClientCnx.class);
}

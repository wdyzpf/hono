/**
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 1.0 which is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-1.0
 */

package org.eclipse.hono.client.impl;

import java.util.Objects;
import java.util.UUID;

import org.eclipse.hono.client.CommandClient;
import org.eclipse.hono.client.StatusCodeMapper;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.util.CacheDirective;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.BufferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.proton.ProtonConnection;

/**
 * A Vertx-Proton based client for Hono's Command and Control API.
 *
 */
public class CommandClientImpl extends AbstractRequestResponseClient<BufferResult> implements CommandClient {

    private static final Logger LOG = LoggerFactory.getLogger(CommandClientImpl.class);

    CommandClientImpl(final Context context, final ClientConfigProperties config, final String tenantId, final String deviceId) {
        super(context, config, tenantId, deviceId);
    }

    @Override
    protected String getName() {
        return CommandConstants.COMMAND_ENDPOINT;
    }

    @Override
    protected String createMessageId() {
        return String.format("cmd-client-%s", UUID.randomUUID());
    }

    @Override
    protected BufferResult getResult(final int status, final Buffer payload, final CacheDirective cacheDirective) {
        return BufferResult.from(status, payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Buffer> sendCommand(final String command, final Buffer data) {

        Objects.requireNonNull(command);
        Objects.requireNonNull(data);

        final Future<BufferResult> responseTracker = Future.future();
        createAndSendRequest(command, null, data, null, responseTracker.completer(), null);

        return responseTracker.map(response -> {
            if(response.isOk()) {
                return response.getPayload();
            }
            else {
                throw StatusCodeMapper.from(response);
            }
        });
    }

    /**
     * Creates a new command client for a tenant and device.
     *
     * @param tenantId The tenant to create the client for.
     * @param deviceId The device to create the client for.
     * @param context The vert.x context to run all interactions with the server on.
     * @param clientConfig The configuration properties to use.
     * @param con The AMQP connection to the server.
     * @param senderCloseHook A handler to invoke if the peer closes the sender link unexpectedly.
     * @param receiverCloseHook A handler to invoke if the peer closes the receiver link unexpectedly.
     * @param creationHandler The handler to invoke with the outcome of the creation attempt.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public static final void create(
            final String tenantId,
            final String deviceId,
            final Context context,
            final ClientConfigProperties clientConfig,
            final ProtonConnection con,
            final Handler<String> senderCloseHook,
            final Handler<String> receiverCloseHook,
            final Handler<AsyncResult<CommandClient>> creationHandler) {

        final CommandClientImpl client = new CommandClientImpl(context, clientConfig, tenantId, deviceId);
        client.createLinks(con, senderCloseHook, receiverCloseHook).setHandler(s -> {
            if (s.succeeded()) {
                LOG.debug("successfully created command client for [{}]", tenantId);
                creationHandler.handle(Future.succeededFuture(client));
            } else {
                LOG.debug("failed to create command client for [{}]", tenantId, s.cause());
                creationHandler.handle(Future.failedFuture(s.cause()));
            }
        });
    }

}
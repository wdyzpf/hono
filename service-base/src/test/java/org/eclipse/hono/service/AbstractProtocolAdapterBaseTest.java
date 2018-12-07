/**
 * Copyright (c) 2017, 2018 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.service;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.HttpURLConnection;
import java.util.Optional;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.HonoClient;
import org.eclipse.hono.client.RegistrationClient;
import org.eclipse.hono.client.ServiceInvocationException;
import org.eclipse.hono.config.ProtocolAdapterProperties;
import org.eclipse.hono.service.auth.device.Device;
import org.eclipse.hono.service.command.CommandConnection;
import org.eclipse.hono.util.EventConstants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistrationConstants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.proton.ProtonHelper;


/**
 * Tests verifying behavior of {@link AbstractProtocolAdapterBase}.
 *
 */
@RunWith(VertxUnitRunner.class)
public class AbstractProtocolAdapterBaseTest {

    /**
     * Time out each test after 5 seconds.
     */
    @Rule
    public Timeout timeout = Timeout.seconds(5);

    private static final String ADAPTER_NAME = "abstract-adapter";

    private ProtocolAdapterProperties properties;
    private AbstractProtocolAdapterBase<ProtocolAdapterProperties> adapter;
    private RegistrationClient registrationClient;
    private HonoClient tenantService;
    private HonoClient registrationService;
    private HonoClient credentialsService;
    private HonoClient messagingService;
    private CommandConnection commandConnection;

    /**
     * Sets up the fixture.
     */
    @SuppressWarnings("unchecked")
    @Before
    public void setup() {

        tenantService = mock(HonoClient.class);
        when(tenantService.connect(any(Handler.class))).thenReturn(Future.succeededFuture(tenantService));

        registrationService = mock(HonoClient.class);
        when(registrationService.connect(any(Handler.class))).thenReturn(Future.succeededFuture(registrationService));

        registrationClient = mock(RegistrationClient.class);
        when(registrationService.getOrCreateRegistrationClient(anyString())).thenReturn(Future.succeededFuture(registrationClient));

        credentialsService = mock(HonoClient.class);
        when(credentialsService.connect(any(Handler.class))).thenReturn(Future.succeededFuture(credentialsService));

        messagingService = mock(HonoClient.class);
        when(messagingService.connect(any(Handler.class))).thenReturn(Future.succeededFuture(messagingService));

        commandConnection = mock(CommandConnection.class);
        when(commandConnection.connect(any(Handler.class))).thenReturn(Future.succeededFuture(commandConnection));

        properties = new ProtocolAdapterProperties();
        adapter = newProtocolAdapter(properties);
        adapter.setTenantServiceClient(tenantService);
        adapter.setRegistrationServiceClient(registrationService);
        adapter.setCredentialsServiceClient(credentialsService);
        adapter.setHonoMessagingClient(messagingService);
        adapter.setCommandConnection(commandConnection);
    }

    /**
     * Verifies that an adapter that does not define a type name
     * cannot be started.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testStartInternalFailsIfNoTypeNameIsDefined(final TestContext ctx) {

        // GIVEN an adapter that does not define a type name
        adapter = newProtocolAdapter(properties, null);
        adapter.setRegistrationServiceClient(mock(HonoClient.class));

        // WHEN starting the adapter
        // THEN startup fails
        adapter.startInternal().setHandler(ctx.asyncAssertFailure());
    }

    /**
     * Verifies that the adapter connects to required services during
     * startup and invokes the <em>doStart</em> method.
     * 
     * @param ctx The vert.x test context.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testStartInternalConnectsToServices(final TestContext ctx) {

        // GIVEN an adapter configured with service clients
        // that can connect to the corresponding services
        final Handler<Void> startupHandler = mock(Handler.class);
        adapter = newProtocolAdapter(properties, "test", startupHandler);
        adapter.setCredentialsServiceClient(credentialsService);
        adapter.setHonoMessagingClient(messagingService);
        adapter.setRegistrationServiceClient(registrationService);
        adapter.setTenantServiceClient(tenantService);
        adapter.setCommandConnection(commandConnection);

        // WHEN starting the adapter
        adapter.startInternal().setHandler(ctx.asyncAssertSuccess(ok -> {
            // THEN the service clients have connected
            verify(tenantService).connect(any(Handler.class));
            verify(registrationService).connect(any(Handler.class));
            verify(messagingService).connect(any(Handler.class));
            verify(credentialsService).connect(any(Handler.class));
            verify(commandConnection).connect(any(Handler.class));
            verify(startupHandler).handle(null);
        }));
    }

    /**
     * Verifies that the registration assertion is set on a downstream message.
     */
    @Test
    public void testAddPropertiesAddsRegistrationAssertion() {

        final Message message = ProtonHelper.message();
        adapter.addProperties(message, newRegistrationAssertionResult("token"));
        assertThat(MessageHelper.getRegistrationAssertion(message), is("token"));
    }

    /**
     * Verifies that the registration assertion is not set on a downstream message
     * if the downstream peer does not require it.
     */
    @Test
    public void testAddPropertiesOmitsRegistrationAssertion() {

        final Message message = ProtonHelper.message();
        adapter.addProperties(message, newRegistrationAssertionResult("token"), false);
        assertNull(MessageHelper.getRegistrationAssertion(message));
    }

    /**
     * Verifies that the adapter's name is set on a downstream message.
     */
    @Test
    public void testAddPropertiesAddsAdapterName() {

        final Message message = ProtonHelper.message();
        adapter.addProperties(message, newRegistrationAssertionResult("token"));
        assertThat(
                MessageHelper.getApplicationProperty(
                        message.getApplicationProperties(),
                        MessageHelper.APP_PROPERTY_ORIG_ADAPTER,
                        String.class),
                is(ADAPTER_NAME));
    }

    /**
     * Verifies that the registered default content type is set on a downstream message.
     */
    @Test
    public void testAddPropertiesAddsDefaultContentType() {

        final Message message = ProtonHelper.message();
        adapter.addProperties(message, newRegistrationAssertionResult("token", "application/hono"));
        assertThat(MessageHelper.getRegistrationAssertion(message), is("token"));
        assertThat(message.getContentType(), is("application/hono"));
    }

    /**
     * Verifies that the registered default content type is not set on a downstream message
     * that already contains a content type.
     */
    @Test
    public void testAddPropertiesDoesNotAddDefaultContentType() {

        final Message message = ProtonHelper.message();
        message.setContentType("application/existing");
        adapter.addProperties(message, newRegistrationAssertionResult("token", "application/hono"));
        assertThat(MessageHelper.getRegistrationAssertion(message), is("token"));
        assertThat(message.getContentType(), is("application/existing"));
    }

    /**
     * Verifies that the fall back content type is set on a downstream message
     * if no default has been configured for the device.
     */
    @Test
    public void testAddPropertiesAddsFallbackContentType() {

        final Message message = ProtonHelper.message();
        adapter.addProperties(message, newRegistrationAssertionResult("token"));
        assertThat(MessageHelper.getRegistrationAssertion(message), is("token"));
        assertThat(message.getContentType(), is(AbstractProtocolAdapterBase.CONTENT_TYPE_OCTET_STREAM));
    }

    /**
     * Verifies that the adapter successfully retrieves a registration assertion
     * for an existing device.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testGetRegistrationAssertionSucceedsForExistingDevice(final TestContext ctx) {

        // GIVEN an adapter connected to a registration service
        final JsonObject assertionResult = newRegistrationAssertionResult("token");
        when(registrationClient.assertRegistration(eq("device"), any())).thenReturn(Future.succeededFuture(assertionResult));

        // WHEN an assertion for the device is retrieved
        adapter.getRegistrationAssertion("tenant", "device", null).setHandler(ctx.asyncAssertSuccess(result -> {
            // THEN the result contains the registration assertion
            ctx.assertEquals(assertionResult, result);
        }));
    }

    /**
     * Verifies that the adapter fails a request to get a registration assertion for
     * a non-existing device.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testGetRegistrationAssertionFailsWith404ForNonExistingDevice(final TestContext ctx) {

        // GIVEN an adapter connected to a registration service
        when(registrationClient.assertRegistration(eq("non-existent"), any())).thenReturn(
                Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_NOT_FOUND)));

        // WHEN an assertion for a non-existing device is retrieved
        adapter.getRegistrationAssertion("tenant", "non-existent", null).setHandler(ctx.asyncAssertFailure(t -> {
            // THEN the request fails with a 404
            ctx.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, ((ServiceInvocationException) t).getErrorCode());
        }));
    }

    /**
     * Verifies that the adapter fails a request to retrieve a token for a gateway that does not
     * belong to the same tenant as the device it wants to act on behalf of.
     * 
     * @param ctx The vert.x test context.
     */
    @Test
    public void testGetRegistrationAssertionFailsWith403ForNonMatchingTenant(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a gateway tries to get an assertion for a device from another tenant
        adapter.getRegistrationAssertion(
                "tenant A",
                "device",
                new Device("tenant B", "gateway")).setHandler(ctx.asyncAssertFailure(t -> {
                    // THEN the request fails with a 403 Forbidden error
                    ctx.assertEquals(HttpURLConnection.HTTP_FORBIDDEN, ((ClientErrorException) t).getErrorCode());
                }));
    }

    private AbstractProtocolAdapterBase<ProtocolAdapterProperties> newProtocolAdapter(final ProtocolAdapterProperties props) {

        return newProtocolAdapter(props, ADAPTER_NAME);
    }

    private AbstractProtocolAdapterBase<ProtocolAdapterProperties> newProtocolAdapter(final ProtocolAdapterProperties props, final String typeName) {
        return newProtocolAdapter(props, typeName, start -> {});
    }

    private AbstractProtocolAdapterBase<ProtocolAdapterProperties> newProtocolAdapter(
            final ProtocolAdapterProperties props,
            final String typeName,
            final Handler<Void> startupHandler) {

        final AbstractProtocolAdapterBase<ProtocolAdapterProperties> result = new AbstractProtocolAdapterBase<ProtocolAdapterProperties>() {

            @Override
            public String getTypeName() {
                return typeName;
            }

            @Override
            public int getPortDefaultValue() {
                return 0;
            }

            @Override
            public int getInsecurePortDefaultValue() {
                return 0;
            }

            @Override
            protected int getActualPort() {
                return 0;
            }

            @Override
            protected int getActualInsecurePort() {
                return 0;
            }

            @Override
            protected void doStart(final Future<Void> startFuture) {
                startupHandler.handle(null);
                startFuture.complete();
            }
        };
        result.setConfig(props);
        return result;
    }

    private static JsonObject newRegistrationAssertionResult(final String token) {
        return newRegistrationAssertionResult(token, null);
    }

    private static JsonObject newRegistrationAssertionResult(final String token, final String defaultContentType) {

        final JsonObject result = new JsonObject()
                .put(RegistrationConstants.FIELD_ASSERTION, token);
        if (defaultContentType != null) {
            result.put(RegistrationConstants.FIELD_DEFAULTS, new JsonObject()
                    .put(MessageHelper.SYS_PROPERTY_CONTENT_TYPE, defaultContentType));
        }
        return result;
    }

    /**
     * Verifies that the helper approves empty notification without payload.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testEmptyNotificationWithoutPayload(final TestContext ctx) {
        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an empty event with an empty payload is approved, no error message must be returned
        final Buffer payload = null;
        final String contentType = EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION;

        ctx.assertTrue(adapter.isPayloadOfIndicatedType(payload, contentType));
    }

    /**
     * Verifies that any empty notification with a payload is an error.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testEmptyNotificationWithPayload(final TestContext ctx) {
        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an empty event with a non empty payload is approved, an error message must be returned
        final Buffer payload = Buffer.buffer("test");
        final String contentType = EventConstants.CONTENT_TYPE_EMPTY_NOTIFICATION;

        ctx.assertFalse(adapter.isPayloadOfIndicatedType(payload, contentType));
    }

    /**
     * Verifies that any general message with a payload is approved.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testNonEmptyGeneralMessage(final TestContext ctx) {
        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an non empty event with a non empty payload is approved, no error message must be returned
        final Buffer payload = Buffer.buffer("test");
        final String arbitraryContentType = "bum/lux";

        // arbitrary content-type needs non empty payload
        ctx.assertTrue(adapter.isPayloadOfIndicatedType(payload, arbitraryContentType));
    }

    /**
     * Verifies that any non empty message without a content type is approved.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testNonEmptyMessageWithoutContentType(final TestContext ctx) {
        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an event without content type and a non empty payload is approved, no error message must be returned
        final Buffer payload = Buffer.buffer("test");

        // arbitrary content-type needs non empty payload
        ctx.assertTrue(adapter.isPayloadOfIndicatedType(payload, null));
    }

    /**
     * Verifies that any empty general message is an error.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testEmptyGeneralMessage(final TestContext ctx) {
        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an event with content type and an empty payload is approved, an error message must be returned
        final Buffer payload = null;
        final String arbitraryContentType = "bum/lux";

        // arbitrary content-type needs non empty payload
        ctx.assertFalse(adapter.isPayloadOfIndicatedType(payload, arbitraryContentType));
    }

    /**
     * Verifies that from a correct command message the replyId can be constructed.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testReplyToCanBeExtractedFromCommandMessage(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a correct command message is used to construct a replyTo addresse for an adapter, then the address must be constructed without error
        final Message commandMessage = mock(Message.class);
        when(commandMessage.getReplyTo()).thenReturn("control/DEFAULT_TENANT/4711/replyId");

        final Optional<String> replyIdOpt = adapter.getReplyToIdFromCommand("DEFAULT_TENANT", "4711", commandMessage);
        ctx.assertNotNull(replyIdOpt);
        ctx.assertTrue(replyIdOpt.isPresent());
    }

    /**
     * Verifies that from an incorrect command message the replyId cannot be constructed (is not present in the returned
     * Optional).
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testReplyToIsNotPresentForIncorrectCommandMessage(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a command message without replyTo field is used to construct a replyTo addresse for an adapter,
        // then no replyId is returned
        final Message commandMessage = mock(Message.class);
        when(commandMessage.getReplyTo()).thenReturn("invalid");

        final Optional<String> replyIdOpt = adapter.getReplyToIdFromCommand("DEFAULT_TENANT", "4711", commandMessage);
        ctx.assertNotNull(replyIdOpt);
        ctx.assertFalse(replyIdOpt.isPresent());
    }

    /**
     * Verifies that from an incorrect command message the replyId cannot be constructed (is not present in the returned
     * Optional).
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCommandRequestIdIsGeneratedForCorrectCommandMessage(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a correct command message is used, a command request id is constructed and returned.
        final Optional<String> commandRequestIdOpt = generateCommandRequestIdForMessage("DEFAULT_TENANT", "4711",
                "replyId", "correlationId");

        ctx.assertNotNull(commandRequestIdOpt);
        ctx.assertTrue(commandRequestIdOpt.isPresent());
        ctx.assertNotNull(commandRequestIdOpt.get());
        ctx.assertTrue(commandRequestIdOpt.get().contains("correlationId"));
        ctx.assertTrue(commandRequestIdOpt.get().contains("replyId"));
    }

    private Optional<String> generateCommandRequestIdForMessage(final String tenant, final String deviceId,
                                                                final String replyId, final String correlationId) {
        final Message commandMessage = mock(Message.class);
        final ResourceIdentifier resource = ResourceIdentifier.from("control", tenant,
                new StringBuilder(deviceId).append("/").append(replyId).toString());
        when(commandMessage.getReplyTo()).thenReturn(resource.toString());
        when(commandMessage.getCorrelationId()).thenReturn(correlationId);

        return adapter.validateAndGenerateCommandRequestId(tenant, deviceId, commandMessage);
    }

    /**
     * Verifies that from a constructed command-request-id the correlationId and replyId can be reconstructed.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCorrelationIdAndReplyToCanBeRetrievedFromCommandRequestIdAgain(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a correct command request id is used, it can be split to the correlationId and replyId again.
        final Optional<String> commandRequestIdOpt = generateCommandRequestIdForMessage("DEFAULT_TENANT", "4711",
                "replyId", "correlationId");

        ctx.assertNotNull(commandRequestIdOpt);
        ctx.assertTrue(commandRequestIdOpt.isPresent());

        final Optional<String[]> corrIdAndReplyToArrayOpt = adapter.getCorrelationIdAndReplyToFromCommandRequestId(commandRequestIdOpt.get());

        ctx.assertNotNull(corrIdAndReplyToArrayOpt);
        ctx.assertTrue(corrIdAndReplyToArrayOpt.isPresent());
        ctx.assertEquals(corrIdAndReplyToArrayOpt.get().length, 2);
        ctx.assertEquals(corrIdAndReplyToArrayOpt.get()[0], "correlationId");
        ctx.assertEquals(corrIdAndReplyToArrayOpt.get()[1], "replyId");
    }

    /**
     * Verifies that from an incorrect command message the replyId and correlationId cannot be constructed (are not present in the returned
     * Optional).
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCorrelationIdAndReplyToCannotBeRetrievedFromInvalidCommandRequestId(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN an incorrect command request id is used, an empty Optional is returned.
        final Optional<String[]> corrIdAndReplyToArrayOpt = adapter.getCorrelationIdAndReplyToFromCommandRequestId("invalidCommandRequestId");

        ctx.assertNotNull(corrIdAndReplyToArrayOpt);
        ctx.assertFalse(corrIdAndReplyToArrayOpt.isPresent());
    }

    /**
     * Verifies that from an incorrect command message that has a longer run-length than the subsequent characters,
     * the replyId and correlationId cannot be constructed (are not present in the returned Optional).
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCorrelationIdAndReplyToCannotBeRetrievedFromTooShortCommandRequestId(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        final Optional<String> commandRequestIdOpt = generateCommandRequestIdForMessage("DEFAULT_TENANT", "4711",
                "replyId", "correlationId");

        ctx.assertNotNull(commandRequestIdOpt);
        ctx.assertTrue(commandRequestIdOpt.isPresent());

        // now shorten the run length by using the correlationId string length + 1 from the 2 chars of the run-length '0D'
        final String shortenedCommandRequestId = commandRequestIdOpt.get().substring(0,"correlationId".length() + 1);

        // WHEN a command message with too large run-length is used, an empty Optional is returned.
        final Optional<String[]> corrIdAndReplyToArrayOptShortened = adapter.getCorrelationIdAndReplyToFromCommandRequestId(shortenedCommandRequestId);

        ctx.assertNotNull(corrIdAndReplyToArrayOptShortened);
        ctx.assertFalse(corrIdAndReplyToArrayOptShortened.isPresent());
    }

    /**
     * Verifies that from an incorrect command message that has a non parseable run-length,
     * the replyId and correlationId cannot be reconstructed (are not present in the returned Optional).
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCorrelationIdAndReplyToCannotBeRetrievedFromInvalidRunLengthCommandRequestId(final TestContext ctx) {

        // GIVEN an adapter
        adapter = newProtocolAdapter(properties, null);

        // WHEN a command message with invalid run-length is used, an empty Optional is returned.
        final Optional<String[]> corrIdAndReplyToArrayOptShortened = adapter.getCorrelationIdAndReplyToFromCommandRequestId("ZZcorr");

        ctx.assertNotNull(corrIdAndReplyToArrayOptShortened);
        ctx.assertFalse(corrIdAndReplyToArrayOptShortened.isPresent());
    }

}
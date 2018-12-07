/**
 * Copyright (c) 2016, 2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 *
 */

package org.eclipse.hono.tests.mqtt;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.TenantObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttConnAckMessage;

/**
 * Base class for MQTT adapter integration tests.
 *
 */
public abstract class MqttTestBase {

    /**
     * The vert.xt instance to run on.
     */
    protected static final Vertx VERTX = Vertx.vertx();
    /**
     * The maximum number of milliseconds a test case may run before it
     * is considered to have failed.
     */
    protected static final int TEST_TIMEOUT = 2000; // milliseconds
    /**
     * The number of messages to send as part of the test cases.
     */
    protected static final int MESSAGES_TO_SEND = 200;

    /**
     * A client for publishing messages to the MQTT protocol adapter.
     */
    protected static MqttClient mqttClient;
    /**
     * A helper accessing the AMQP 1.0 Messaging Network and
     * for managing tenants/devices/credentials.
     */
    protected static IntegrationTestSupport helper;

    /**
     * Provide test name to unit tests.
     */
    @Rule
    public final TestName testName = new TestName();

    /**
     * A logger to be used by subclasses.
     */
    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    private final Set<Integer> pendingMessages = new HashSet<>();

    /**
     * Sets up the helper.
     * 
     * @param ctx The vert.x test context.
     */
    @BeforeClass
    public static void init(final TestContext ctx) {

        helper = new IntegrationTestSupport(VERTX);
        helper.init(ctx);

    }

    /**
     * Sets up the fixture.
     */
    @Before
    public void setUp() {
        LOGGER.info("running {}", testName.getMethodName());
    }

    /**
     * Deletes all temporary objects from the Device Registry which
     * have been created during the last test execution.
     * 
     * @param ctx The vert.x context.
     */
    @After
    public void postTest(final TestContext ctx) {

        final Future<Void> disconnectHandler = Future.future();
        if (mqttClient == null) {
            disconnectHandler.complete();
        } else {
            mqttClient.disconnect(disconnectHandler.completer());
        }
        disconnectHandler.setHandler(tidyUp -> {
            LOGGER.info("connection to MQTT adapter closed");
            pendingMessages.clear();
            helper.deleteObjects(ctx);
        });
    }

    /**
     * Closes the AMQP 1.0 Messaging Network client.
     * 
     * @param ctx The vert.x test context.
     */
    @AfterClass
    public static void disconnect(final TestContext ctx) {

        helper.disconnect(ctx);
    }

    /**
     * Sends a message on behalf of a device to the HTTP adapter.
     * 
     * @param tenantId The tenant that the device belongs to.
     * @param deviceId The identifier of the device.
     * @param payload The message to send.
     * @param useShortTopicName Whether to use short or standard topic names
     * @return A future indicating the outcome of the attempt to publish the
     *         message. The future will succeed if the message has been
     *         published successfully.
     */
    protected abstract Future<Void> send(
            String tenantId,
            String deviceId,
            Buffer payload,
            boolean useShortTopicName);

    /**
     * Asserts that the ration between messages that have been received and messages
     * being sent is acceptable for the particular QoS used for publishing messages.
     * <p>
     * This default implementation asserts that received = sent.
     * 
     * @param received The number of messages that have been received.
     * @param sent The number of messages that have been sent.
     * @param ctx The test context that will be failed if the ratio is not acceptable.
     */
    protected void assertMessageReceivedRatio(final long received, final long sent, final TestContext ctx) {
        if (received < sent) {
            ctx.fail(String.format("did not receive expected number of messages [expected: %d, received: %d]",
                    sent, received));
        }
    }

    /**
     * Gets the number of milliseconds that the message sending test cases
     * should wait for messages being received by the consumer.
     * 
     * @return The number of milliseconds.
     */
    protected long getTimeToWait() {
        return Math.max(TEST_TIMEOUT, MESSAGES_TO_SEND * 20);
    }

    /**
     * Creates a test specific message consumer.
     *
     * @param tenantId        The tenant to create the consumer for.
     * @param messageConsumer The handler to invoke for every message received.
     * @return A future succeeding with the created consumer.
     */
    protected abstract Future<MessageConsumer> createConsumer(String tenantId, Consumer<Message> messageConsumer);

    /**
     * Verifies that a number of messages published to Hono's MQTT adapter
     * using the standard topic names can be successfully consumed via the AMQP Messaging Network.
     * 
     * @param ctx The test context.
     * @throws InterruptedException if the test fails.
     */
    @Test
    public void testUploadMessages(final TestContext ctx) throws InterruptedException {
        doTestUploadMessages(ctx, false);
    }

    /**
     * Verifies that a number of messages published to Hono's MQTT adapter
     * using the short topic names can be successfully consumed via the AMQP Messaging Network.
     * 
     * @param ctx The test context.
     * @throws InterruptedException if the test fails.
     */
    @Test
    public void testUploadMessagesUsingShortTopicNames(final TestContext ctx) throws InterruptedException {
        doTestUploadMessages(ctx, true);
    }

    private void doTestUploadMessages(final TestContext ctx, final boolean useShortTopicName)
            throws InterruptedException {

        final CountDownLatch received = new CountDownLatch(MESSAGES_TO_SEND);
        final AtomicInteger messageCount = new AtomicInteger(0);
        final AtomicLong lastReceivedTimestamp = new AtomicLong();
        final Async setup = ctx.async();
        final String tenantId = helper.getRandomTenantId();
        final String deviceId = helper.getRandomDeviceId(tenantId);
        final String password = "secret";
        final TenantObject tenant = TenantObject.from(tenantId, true);

        helper.registry.addDeviceForTenant(tenant, deviceId, password)
            .compose(ok -> createConsumer(tenantId, msg -> {
                LOGGER.trace("received {}", msg);
                assertMessageProperties(ctx, msg);
                assertAdditionalMessageProperties(ctx, msg);
                received.countDown();
                lastReceivedTimestamp.set(System.currentTimeMillis());
                if (received.getCount() % 40 == 0) {
                    LOGGER.info("messages received: {}", MESSAGES_TO_SEND - received.getCount());
                }
            })).compose(ok -> {
                final Future<MqttConnAckMessage> result = Future.future();
                VERTX.runOnContext(connect -> {
                    final MqttClientOptions options = new MqttClientOptions()
                            .setUsername(IntegrationTestSupport.getUsername(deviceId, tenantId))
                            .setPassword(password);
                    mqttClient = MqttClient.create(VERTX, options);
                    mqttClient.connect(IntegrationTestSupport.MQTT_PORT, IntegrationTestSupport.MQTT_HOST, result.completer());
                });
                return result;
            }).setHandler(ctx.asyncAssertSuccess(ok -> setup.complete()));

        setup.await();

        final long start = System.currentTimeMillis();
        while (messageCount.get() < MESSAGES_TO_SEND) {

            final Async messageSent = ctx.async();
            VERTX.runOnContext(go -> {
                final Buffer msg = Buffer.buffer("hello " + messageCount.getAndIncrement());
                send(tenantId, deviceId, msg, useShortTopicName).setHandler(sendAttempt -> {
                    if (sendAttempt.failed()) {
                        LOGGER.debug("error sending message {}", messageCount.get(), sendAttempt.cause());
                    }
                    if (messageCount.get() % 40 == 0) {
                        LOGGER.info("messages sent: " + messageCount.get());
                    }
                    messageSent.complete();
                });
            });

            messageSent.await();
        }

        received.await(getTimeToWait(), TimeUnit.MILLISECONDS);
        final long messagesReceived = MESSAGES_TO_SEND - received.getCount();
        LOGGER.info("sent {} and received {} messages in {} milliseconds",
                messageCount.get(), messagesReceived, lastReceivedTimestamp.get() - start);
        assertMessageReceivedRatio(messagesReceived, messageCount.get(), ctx);
    }

    private void assertMessageProperties(final TestContext ctx, final Message msg) {
        ctx.assertNotNull(MessageHelper.getDeviceId(msg));
        ctx.assertNotNull(MessageHelper.getTenantIdAnnotation(msg));
        ctx.assertNotNull(MessageHelper.getDeviceIdAnnotation(msg));
        ctx.assertNull(MessageHelper.getRegistrationAssertion(msg));
    }

    /**
     * Perform additional checks on a received message.
     * <p>
     * This default implementation does nothing. Subclasses should override this method to implement
     * reasonable checks.
     * 
     * @param ctx The test context.
     * @param msg The message to perform checks on.
     */
    protected void assertAdditionalMessageProperties(final TestContext ctx, final Message msg) {
        // empty
    }
}

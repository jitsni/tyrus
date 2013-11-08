/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.tyrus.test.e2e;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.server.Server;
import org.glassfish.tyrus.test.tools.TestContainer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author Pavel Bucek (pavel.bucek at oracle.com)
 */
public class MaxMessageSizeTest extends TestContainer {

    private volatile CountDownLatch messageLatch;
    private volatile String receivedMessage;

    @ServerEndpoint(value = "/endpoint1")
    public static class Endpoint1 {

        public static volatile CloseReason closeReason = null;
        public static volatile Throwable throwable = null;


        @OnOpen
        public void onOpen(Session session) {
        }

        @OnMessage(maxMessageSize = 5)
        public String onMessage(String message) {
            return message;
        }

        @OnClose
        public void onClose(CloseReason c) {
            closeReason = c;
        }

        @OnError
        public void onError(Session s, Throwable t) {
            // onError needs to be called after session is closed.
            if (!s.isOpen()) {
                throwable = t;
            }
        }
    }

    @ServerEndpoint(value = "/servicemaxmessage")
    public static class ServiceEndpoint {

        @OnMessage
        public String onMessage(String message) {
            if (message.equals("THROWABLE") && Endpoint1.throwable != null) {
                return POSITIVE;
            } else if(message.equals("CLEANUP")){
                Endpoint1.closeReason = null;
                Endpoint1.throwable = null;
                return POSITIVE;
            } else if (Endpoint1.closeReason != null && Endpoint1.closeReason.getCloseCode().equals(CloseReason.CloseCodes.TOO_BIG)) {
                return POSITIVE;
            }

            return NEGATIVE;
        }
    }

    @ServerEndpoint(value = "/endpoint2")
    public static class Endpoint2 {

        @OnMessage(maxMessageSize = 5)
        public String doThat(Session s, String message, boolean last) {
            return message;
        }
    }

    @Test
    public void runTestBasic() throws DeploymentException {
        Server server = startServer(Endpoint1.class, ServiceEndpoint.class);

        try {
            messageLatch = new CountDownLatch(1);

            final ClientEndpointConfig clientConfiguration = ClientEndpointConfig.Builder.create().build();
            ClientManager client = ClientManager.createClient();

            client.connectToServer(new Endpoint() {

                @Override
                public void onOpen(Session session, EndpointConfig EndpointConfig) {
                    try {
                        session.addMessageHandler(new MessageHandler.Whole<String>() {
                            @Override
                            public void onMessage(String message) {
                                receivedMessage = message;
                                messageLatch.countDown();
                            }
                        });

                        session.getBasicRemote().sendText("TEST1");
                    } catch (IOException e) {
                        // do nothing.
                    }
                }
            }, clientConfiguration, getURI(Endpoint1.class));

            messageLatch.await(5, TimeUnit.SECONDS);
            assertEquals(0, messageLatch.getCount());
            assertEquals("TEST1", receivedMessage);

            final CountDownLatch closedLatch = new CountDownLatch(1);

            client.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig EndpointConfig) {
                    try {
                        session.getBasicRemote().sendText("LONG--");
                    } catch (IOException e) {
                        // do nothing.
                    }
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                    if (closeReason.getCloseCode().equals(CloseReason.CloseCodes.TOO_BIG)) {
                        closedLatch.countDown();
                    }
                }
            }, clientConfiguration, getURI(Endpoint1.class));
            closedLatch.await(5, TimeUnit.SECONDS);


            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "THROWABLE");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }

    @Test
    public void runTestAsync() throws DeploymentException {
        Server server = startServer(Endpoint2.class);

        try {
            messageLatch = new CountDownLatch(1);

            final ClientEndpointConfig clientConfiguration = ClientEndpointConfig.Builder.create().build();
            ClientManager client = ClientManager.createClient();

            client.connectToServer(new Endpoint() {

                @Override
                public void onOpen(Session session, EndpointConfig EndpointConfig) {
                    try {
                        session.addMessageHandler(new MessageHandler.Whole<String>() {
                            @Override
                            public void onMessage(String message) {
                                receivedMessage = message;
                                messageLatch.countDown();
                            }
                        });

                        session.getBasicRemote().sendText("TEST1", false);
                    } catch (IOException e) {
                        // do nothing.
                    }
                }
            }, clientConfiguration, getURI(Endpoint2.class));

            messageLatch.await(5, TimeUnit.SECONDS);
            assertEquals(0, messageLatch.getCount());
            assertEquals("TEST1", receivedMessage);

            messageLatch = new CountDownLatch(1);

            client.connectToServer(new Endpoint() {

                @Override
                public void onOpen(Session session, EndpointConfig EndpointConfig) {
                    try {
                        session.getBasicRemote().sendText("LONG--", false);
                    } catch (IOException e) {
                        // do nothing.
                    }
                }

                @Override
                public void onClose(Session session, CloseReason closeReason) {
                    if (closeReason.getCloseCode().equals(CloseReason.CloseCodes.TOO_BIG)) {
                        messageLatch.countDown();
                    }
                }
            }, clientConfiguration, getURI(Endpoint2.class));

            messageLatch.await(5, TimeUnit.SECONDS);
            assertEquals(0, messageLatch.getCount());

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }

    @ClientEndpoint
    public static class MyClientEndpoint {

        public static volatile CountDownLatch latch;
        public static volatile Throwable throwable = null;
        public static volatile CloseReason reason = null;

        @OnMessage(maxMessageSize = 3)
        public void onMessage(String message) {
            latch.countDown();
        }

        @OnError
        public void onError(Session s, Throwable t) {
            // onError needs to be called after session is closed.
            if (!s.isOpen()) {
                throwable = t;
            }

            if (latch.getCount() > 0) {
                latch.countDown();
            }
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
            MyClientEndpoint.reason = reason;
        }
    }

    @Test
    public void testClientOne() throws DeploymentException {
        Server server = startServer(Endpoint1.class, ServiceEndpoint.class);

        try {
            ClientManager client = ClientManager.createClient();

            final Session session = client.connectToServer(MyClientEndpoint.class, getURI(Endpoint1.class));
            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "CLEANUP");

            MyClientEndpoint.latch = new CountDownLatch(1);
            MyClientEndpoint.throwable = null;
            MyClientEndpoint.reason = null;
            session.getBasicRemote().sendText("t");
            MyClientEndpoint.latch.await(1, TimeUnit.SECONDS);
            assertEquals(0, MyClientEndpoint.latch.getCount());

            testViaServiceEndpoint(client, ServiceEndpoint.class, NEGATIVE, "TWO");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }

    @Test
    public void testClientTwo() throws DeploymentException {
        Server server = startServer(Endpoint1.class, ServiceEndpoint.class);

        try {
            ClientManager client = ClientManager.createClient();

            final Session session = client.connectToServer(MyClientEndpoint.class, getURI(Endpoint1.class));
            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "CLEANUP");

            MyClientEndpoint.latch = new CountDownLatch(1);
            MyClientEndpoint.throwable = null;
            MyClientEndpoint.reason = null;
            session.getBasicRemote().sendText("te");
            MyClientEndpoint.latch.await(1, TimeUnit.SECONDS);
            assertEquals(0, MyClientEndpoint.latch.getCount());

            testViaServiceEndpoint(client, ServiceEndpoint.class, NEGATIVE, "TWO");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }

    @Test
    public void testClientThree() throws DeploymentException {
        Server server = startServer(Endpoint1.class, ServiceEndpoint.class);

        try {
            ClientManager client = ClientManager.createClient();

            final Session session = client.connectToServer(MyClientEndpoint.class, getURI(Endpoint1.class));
            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "CLEANUP");

            MyClientEndpoint.latch = new CountDownLatch(1);
            MyClientEndpoint.throwable = null;
            MyClientEndpoint.reason = null;
            session.getBasicRemote().sendText("tes");
            MyClientEndpoint.latch.await(1, TimeUnit.SECONDS);
            assertEquals(0, MyClientEndpoint.latch.getCount());

            testViaServiceEndpoint(client, ServiceEndpoint.class, NEGATIVE, "TWO");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }

    @Test
    public void testClientFour() throws DeploymentException {
        Server server = startServer(Endpoint1.class, ServiceEndpoint.class);

        try {
            ClientManager client = ClientManager.createClient();

            final Session session = client.connectToServer(MyClientEndpoint.class, getURI(Endpoint1.class));
            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "CLEANUP");

            MyClientEndpoint.latch = new CountDownLatch(1);
            MyClientEndpoint.throwable = null;
            MyClientEndpoint.reason = null;
            session.getBasicRemote().sendText("test");
            MyClientEndpoint.latch.await(1, TimeUnit.SECONDS);
            assertEquals(0, MyClientEndpoint.latch.getCount());
            assertNotNull(MyClientEndpoint.throwable);

            testViaServiceEndpoint(client, ServiceEndpoint.class, POSITIVE, "TWO");
            assertEquals("CloseReason on client is not: "+CloseReason.CloseCodes.TOO_BIG.getCode(), CloseReason.CloseCodes.TOO_BIG.getCode(), MyClientEndpoint.reason.getCloseCode().getCode());
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            stopServer(server);
        }
    }
}

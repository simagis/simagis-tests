package com.simagis.pyramid.vertx.tests;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;

import java.io.IOException;

public class VertxProxyTest {

    public static void main(String[] args) {
            if (args.length < 3) {
                System.out.println("Usage: " + VertxProxyTest.class.getName() + " server-host server-port proxy-port");
                return;
            }
        final String serverHost = args[0];
        final int serverPort = Integer.parseInt(args[1]);
        final int proxyPort = Integer.parseInt(args[2]);
        Vertx vertx = Vertx.vertx();
        HttpClient client = vertx.createHttpClient(new HttpClientOptions());
        final HttpServer server = vertx.createHttpServer();
        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                System.out.println("Proxying request: " + request.uri());
                HttpClientRequest clientRequest = client.request(
                    request.method(), serverPort, serverHost, request.uri(), new Handler<HttpClientResponse>() {
                        @Override
                        public void handle(HttpClientResponse clientResponse) {
                            System.out.println("Proxying response: " + clientResponse.statusCode());
                            request.response().setChunked(true);
                            request.response().setStatusCode(clientResponse.statusCode());
                            request.response().headers().setAll(clientResponse.headers());
                            clientResponse.handler(new Handler<Buffer>() {
                                @Override
                                public void handle(Buffer data) {
                                    System.out.println("Proxying response body: " + data.length() + " bytes");
                                    request.response().write(data);
                                }
                            });
                            clientResponse.endHandler(new Handler<Void>() {
                                @Override
                                public void handle(Void v) {
                                    request.response().end();
                                }
                            });
                            clientResponse.exceptionHandler(new Handler<Throwable>() {
                                @Override
                                public void handle(Throwable throwable) {
                                    throwable.printStackTrace();
                                }
                            });
                        }
                    });
                System.out.println("Client request created: " + clientRequest.uri());
                clientRequest.setChunked(true);
                clientRequest.headers().setAll(request.headers());
                clientRequest.headers().remove("accept-encoding");
                request.handler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer data) {
                        System.out.println("Proxying request body " + data.toString("ISO-8859-1"));
                        clientRequest.write(data);
                    }
                });
                request.endHandler(new Handler<Void>() {
                    @Override
                    public void handle(Void v) {
                        clientRequest.end();
                    }
                });
                System.out.println("Clientrequests customized: <<<" + clientRequest.headers()
                    + ">>> ");
            }
        }).listen(proxyPort);
    }
}

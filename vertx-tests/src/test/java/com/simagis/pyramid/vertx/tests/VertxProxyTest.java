package com.simagis.pyramid.vertx.tests;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;

public class VertxProxyTest {
    final static int PROXY_PORT = 82;
    final static int SERVER_PORT = 8080;
    final static String SERVER_HOST = "localhost";

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpClient client = vertx.createHttpClient(new HttpClientOptions());
        final HttpServer server = vertx.createHttpServer();
        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                System.out.println("Proxying request: " + request.uri());
                HttpClientRequest clientRequest = client.request(
                    request.method(), SERVER_PORT, SERVER_HOST, request.uri(), new Handler<HttpClientResponse>() {
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
                System.out.println("Client and server requests customized: " + clientRequest + ", " + request);
            }
        }).listen(PROXY_PORT);
    }
}

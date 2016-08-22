package com.simagis.pyramid.vertx.tests;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;

/**
 * Created by Daniel on 19/07/2016.
 */
public class VertxServerTest {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer();

        Router router = Router.router(vertx);

        router.route().path("/my/path/*").handler(routingContext -> {

            // This handler will be called for every request
            final HttpServerRequest request = routingContext.request();
            System.out.println("Parameters: " + request.params());
            HttpServerResponse response = routingContext.response();
            response.putHeader("content-type", "text/plain");
            response.setChunked(true);
            response.write(request.path() + "\n");
            // Write to the response and end it
            response.end("Hello World from Vert.x-Web!");
        });

        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest httpServerRequest) {
                router.accept(httpServerRequest);
            }
        });
        server.listen(8080);    }
}

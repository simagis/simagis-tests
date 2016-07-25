package com.simagis.pyramid.vertx.tests;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;

/**
 * Created by Daniel on 19/07/2016.
 */
public class VertxClientTest {
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.createHttpClient(new HttpClientOptions().setIdleTimeout(3)).getNow(82, "localhost", "/time",
            new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse resp) {
                System.out.println("Got response " + resp.statusCode());
                resp.bodyHandler(body -> {
                    System.out.println("Got data " + body.toString("ISO-8859-1"));
                });
            }
        });
    }
}

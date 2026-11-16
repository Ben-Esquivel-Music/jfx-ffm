/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.com.sun.media.jfxmediaimpl;

import com.sun.media.jfxmedia.Media;
import com.sun.media.jfxmedia.MediaError;
import com.sun.media.jfxmedia.MediaException;
import com.sun.media.jfxmedia.locator.Locator;
import com.sun.media.jfxmediaimpl.JfxMediaNative;
import com.sun.media.jfxmediaimpl.NativeMedia;
import com.sun.media.jfxmediaimpl.platform.gstreamer.GSTPlatform;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * The child JVM of {@link SourceElementReleaseTest}: creates one media against the hooks build of {@code jfxmedia}
 * with one fault point of {@code CGstPipelineFactory::CreateSourceElement} armed, or none, and reports how the
 * creation ended and every counter of the elements and stream-callback adapters it made.
 * <p>
 * The three faults sit on the branch that wraps the {@code javasource} element in a bin with a progress buffer, and
 * that branch is taken only when the stream callbacks' {@code need_buffer} answers true. Of the connection holders
 * a {@link Locator} hands out, only the one over {@code http} and {@code https} does, so the media is served from a
 * loopback HTTP server in this JVM. Nothing else about it differs from the file media the other tests create: the
 * same {@link GSTPlatform#createMedia} and {@code jfxm_media_create}, and the pipeline is never started. The server
 * is the JDK's own, {@code com.sun.net.httpserver} of module {@code jdk.httpserver}, which this class-path child
 * reaches without {@code --add-modules}: for a main class on the class path, every system module that exports a
 * package unqualified is a root module.
 *
 * @see MediaTestHooks
 */
public final class SourceElementReleaseProbe {

    /**
     * How long a created media's elements get, after its dispose, to be finalized. Disposing a pipeline that never
     * left {@code GST_STATE_NULL} finalizes them on the calling thread, so this bounds a dispose that leaks rather
     * than waits for one that is slow; a failed creation is reported at once.
     */
    private static final long FINALIZE_TIMEOUT_MILLIS = 5_000L;

    private SourceElementReleaseProbe() {
    }

    /**
     * @param args the fault point to arm, one of {@code MediaTestHooks.FAULT_*}
     * @throws Exception if anything but the media creation fails, which ends the child with its stack trace
     */
    public static void main(String[] args) throws Exception {
        int fault = Integer.parseInt(args[0]);
        JfxmediaLibraryProbe.reportLibrary();
        JfxmTestHooksNative hooks = JfxmTestHooksNative.bind();

        HttpServer server = serve(TinyWav.bytes());
        try {
            InetSocketAddress address = server.getAddress();
            URI uri = new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/silence.wav",
                    null, null);
            Locator locator = new Locator(uri);
            locator.init();
            ChildJvm.report("content.type", locator.getContentType());
            ChildJvm.report("platform.init", MediaError.getFromCode(JfxMediaNative.platformInit()));

            int baselineRegistry = JfxMediaNative.registrySize();
            hooks.reset();
            hooks.armSourceFault(fault);
            try {
                Media media = GSTPlatform.getPlatformInstance().createMedia(locator);
                ChildJvm.report("create", "succeeded");
                ((NativeMedia) media).dispose();
                awaitFinalized(hooks);
            } catch (MediaException e) {
                ChildJvm.report("create", "failed");
                MediaError error = e.getMediaError();
                ChildJvm.report("error", error != null ? error.name() : e.toString());
            }
            ChildJvm.report("registry.baseline", baselineRegistry);
            ChildJvm.report("registry.after", JfxMediaNative.registrySize());
            for (int id = 0; id < MediaTestHooks.COUNTER_COUNT; id++) {
                ChildJvm.report("counter." + id, hooks.counter(id));
            }
        } finally {
            server.stop(0);
        }
        ChildJvm.report("done", true);
        System.exit(0);
    }

    /**
     * Serves {@code body} as {@code audio/x-wav} for every GET on the loopback interface. Anything else gets 405,
     * which sends {@code Locator.init}, which tries HEAD first, on to GET.
     */
    private static HttpServer serve(byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> respond(exchange, body));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", "audio/x-wav");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        } finally {
            exchange.close();
        }
    }

    /** Waits until every element and adapter the creation made is gone, or until the bound runs out. */
    private static void awaitFinalized(JfxmTestHooksNative hooks) throws InterruptedException {
        long deadline = System.nanoTime() + FINALIZE_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline && !(
                hooks.counter(MediaTestHooks.SOURCE_FINALIZED) >= hooks.counter(MediaTestHooks.SOURCE_WATCHED)
                && hooks.counter(MediaTestHooks.BIN_FINALIZED) >= hooks.counter(MediaTestHooks.BIN_WATCHED)
                && hooks.counter(MediaTestHooks.ADAPTERS_DESTROYED)
                        >= hooks.counter(MediaTestHooks.ADAPTERS_CONSTRUCTED))) {
            Thread.sleep(10L);
        }
    }
}

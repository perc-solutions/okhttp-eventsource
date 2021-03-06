package com.launchdarkly.eventsource;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import okhttp3.*;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.launchdarkly.eventsource.ReadyState.*;

/**
 * Client for <a href="https://www.w3.org/TR/2015/REC-eventsource-20150203/">Server-Sent Events</a>
 * aka EventSource
 */
public class EventSource implements ConnectionHandler, Closeable {
  private static final Logger logger = LoggerFactory.getLogger(EventSource.class);

  private static final long DEFAULT_RECONNECT_TIME_MS = 1000;
  static final long MAX_RECONNECT_TIME_MS = 30000;
  private volatile URI uri;
  private final Headers headers;
  private final ExecutorService executor;
  private volatile long reconnectTimeMs = 0;
  private volatile String lastEventId;
  private final EventHandler handler;
  private final AtomicReference<ReadyState> readyState;
  private final OkHttpClient client;
  private volatile Call call;
  private final Random jitter = new Random();
  private BufferedSource bufferedSource = null;

  EventSource(Builder builder) {
    this.uri = builder.uri;
    this.headers = addDefaultHeaders(builder.headers);
    this.reconnectTimeMs = builder.reconnectTimeMs;
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setNameFormat("okhttp-eventsource-%d")
        .build();
    this.executor = Executors.newSingleThreadExecutor(threadFactory);
    this.handler = new AsyncEventHandler(this.executor, builder.handler);
    this.readyState = new AtomicReference<>(RAW);

    OkHttpClient.Builder clientBuilder = builder.client.newBuilder()
        .connectionPool(new ConnectionPool(1, 1, TimeUnit.SECONDS))
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .proxy(builder.proxy);

    if (builder.proxyAuthenticator != null) {
      clientBuilder.proxyAuthenticator(builder.proxyAuthenticator);
    }

    this.client = clientBuilder.build();
  }

  public void start() {
    if (!readyState.compareAndSet(RAW, CONNECTING)) {
      logger.info("Start method called on this already-started EventSource object. Doing nothing");
      return;
    }
    logger.debug("readyState change: " + RAW + " -> " + CONNECTING);
    logger.info("Starting EventSource client using URI: " + uri);
    executor.execute(new Runnable() {
      public void run() {
        connect();
      }
    });
  }

  public ReadyState getState() {
    return readyState.get();
  }

  @Override
  public void close() throws IOException {
    ReadyState currentState = readyState.getAndSet(SHUTDOWN);
    logger.debug("readyState change: " + currentState + " -> " + SHUTDOWN);
    if (currentState == SHUTDOWN) {
      return;
    }
    if (currentState == ReadyState.OPEN) {
      try {
        handler.onClosed();
      } catch (Exception e) {
        handler.onError(e);
      }
    }
    executor.shutdownNow();
    if (bufferedSource != null) {
      bufferedSource.close();
    }

    if (client != null) {
      if (client.connectionPool() != null) {
        client.connectionPool().evictAll();
      }
      if (client.dispatcher() != null) {
        client.dispatcher().cancelAll();
        if (client.dispatcher().executorService() != null) {
          client.dispatcher().executorService().shutdownNow();
        }
      }
    }
  }

  private void connect() {
    Response response = null;
    int reconnectAttempts = 0;
    try {
      while (!Thread.currentThread().isInterrupted() && readyState.get() != SHUTDOWN) {
        maybeWaitWithBackoff(reconnectAttempts++);
        ReadyState currentState = readyState.getAndSet(CONNECTING);
        logger.debug("readyState change: " + currentState + " -> " + CONNECTING);
        try {
          Request.Builder builder = new Request.Builder()
              .headers(headers)
              .url(uri.toASCIIString())
              .get();

          if (lastEventId != null && !lastEventId.isEmpty()) {
            builder.addHeader("Last-Event-ID", lastEventId);
          }

          call = client.newCall(builder.build());
          response = call.execute();
          if (response.isSuccessful()) {
            reconnectAttempts = 0;
            currentState = readyState.getAndSet(OPEN);
            if (currentState != CONNECTING) {
              logger.warn("Unexpected readyState change: " + currentState + " -> " + OPEN);
            } else {
              logger.debug("readyState change: " + currentState + " -> " + OPEN);
            }
            logger.info("Connected to Event Source stream.");
            try {
              handler.onOpen();
            } catch (Exception e) {
              handler.onError(e);
            }
            if (bufferedSource != null) {
              bufferedSource.close();
            }
            bufferedSource = Okio.buffer(response.body().source());
            EventParser parser = new EventParser(uri, handler, EventSource.this);
            for (String line; !Thread.currentThread().isInterrupted() && (line = bufferedSource.readUtf8LineStrict()) != null; ) {
              parser.line(line);
            }
          } else {
            logger.debug("Unsuccessful Response: " + response);
            handler.onError(new UnsuccessfulResponseException(response.code()));
          }
        } catch (EOFException eofe) {
          logger.warn("Connection unexpectedly closed.");
        } catch (IOException ioe) {
          logger.debug("Connection problem.", ioe);
          handler.onError(ioe);
        } finally {
          currentState = readyState.getAndSet(CLOSED);
          logger.debug("readyState change: " + currentState + " -> " + CLOSED);
          if (response != null && response.body() != null) {
            response.body().close();
          }
          if (call != null) {
            call.cancel();
          }
          if (currentState == ReadyState.OPEN) {
            try {
              handler.onClosed();
            } catch (Exception e) {
              handler.onError(e);
            }
          }
        }
      }
    } catch (RejectedExecutionException ignored) {
      // During shutdown, we tried to send a message to the event handler
      // Do not reconnect; the executor has been shut down
    }
  }

  private void maybeWaitWithBackoff(int reconnectAttempts) {
    if (reconnectTimeMs > 0 && reconnectAttempts > 0) {
      try {
        long sleepTimeMs = backoffWithJitter(reconnectAttempts);
        logger.info("Waiting " + sleepTimeMs + " milliseconds before reconnecting...");
        Thread.sleep(sleepTimeMs);
      } catch (InterruptedException ignored) {
      }
    }
  }

  long backoffWithJitter(int reconnectAttempts) {
    long jitterVal = Math.min(MAX_RECONNECT_TIME_MS, reconnectTimeMs * pow2(reconnectAttempts));
    return jitterVal / 2 + nextLong(jitter, jitterVal) / 2;
  }

  // Returns 2**k, or Integer.MAX_VALUE if 2**k would overflow
  private int pow2(int k) {
    return (k < Integer.SIZE - 1) ? (1 << k) : Integer.MAX_VALUE;
  }

  // Adapted from http://stackoverflow.com/questions/2546078/java-random-long-number-in-0-x-n-range
  // Since ThreadLocalRandom.current().nextLong(n) requires Android 5
  private long nextLong(Random rand, long bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive");
    }

    long r = rand.nextLong() & Long.MAX_VALUE;
    long m = bound - 1L;
    if ((bound & m) == 0) { // i.e., bound is a power of 2
      r = (bound * r) >> (Long.SIZE - 1);
    } else {
      for (long u = r; u - (r = u % bound) + m < 0L; u = rand.nextLong() & Long.MAX_VALUE) ;
    }
    return r;
  }

  private static Headers addDefaultHeaders(Headers custom) {
    Headers.Builder builder = new Headers.Builder();

    builder.add("Accept", "text/event-stream").add("Cache-Control", "no-cache");

    for (Map.Entry<String, List<String>> header : custom.toMultimap().entrySet()) {
      for (String value : header.getValue()) {
        builder.add(header.getKey(), value);
      }
    }

    return builder.build();
  }

  public void setReconnectionTimeMs(long reconnectionTimeMs) {
    this.reconnectTimeMs = reconnectionTimeMs;
  }

  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public URI getUri() {
    return this.uri;
  }

  public void setUri(URI uri) {
    this.uri = uri;
  }

  public static final class Builder {
    private long reconnectTimeMs = DEFAULT_RECONNECT_TIME_MS;
    private final URI uri;
    private final EventHandler handler;
    private Headers headers = Headers.of();
    private Proxy proxy;
    private Authenticator proxyAuthenticator = null;
    private OkHttpClient client = new OkHttpClient();

    public Builder(EventHandler handler, URI uri) {
      this.uri = uri;
      this.handler = handler;
    }

    /**
     * Set the reconnect base time for the EventSource connection in milliseconds. Reconnect attempts are computed
     * from this base value with an exponential backoff and jitter.
     *
     * @param reconnectTimeMs the reconnect base time in milliseconds
     * @return the builder
     */
    public Builder reconnectTimeMs(long reconnectTimeMs) {
      this.reconnectTimeMs = reconnectTimeMs;
      return this;
    }

    /**
     * Set the headers to be sent when establishing the EventSource connection.
     *
     * @param headers headers to be sent with the EventSource request
     * @return the builder
     */
    public Builder headers(Headers headers) {
      this.headers = headers;
      return this;
    }

    /**
     * Set a custom HTTP client that will be used to make the EventSource connection
     *
     * @param client the HTTP client
     * @return the builder
     */
    public Builder client(OkHttpClient client) {
      this.client = client;
      return this;
    }

    /**
     * Set the HTTP proxy address to be used to make the EventSource connection
     *
     * @param proxyHost the proxy hostname
     * @param proxyPort the proxy port
     * @return the builder
     */
    public Builder proxy(String proxyHost, int proxyPort) {
      proxy = new Proxy(Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
      return this;
    }

    /**
     * Set the {@link Proxy} to be used to make the EventSource connection.
     *
     * @param proxy the proxy
     * @return the builder
     */
    public Builder proxy(Proxy proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Sets the Proxy Authentication mechanism if needed. Defaults to no auth.
     *
     * @param proxyAuthenticator
     * @return
     */
    public Builder proxyAuthenticator(Authenticator proxyAuthenticator) {
      this.proxyAuthenticator = proxyAuthenticator;
      return this;
    }

    public EventSource build() {
      return new EventSource(this);
    }
  }
}

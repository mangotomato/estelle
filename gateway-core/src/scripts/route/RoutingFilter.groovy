package com.greencloud.gateway.filters.route;

import com.dianping.cat.Cat;
import com.dianping.cat.message.Transaction
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.greencloud.gateway.GatewayFilter
import com.greencloud.gateway.common.CatContext
import com.greencloud.gateway.constants.Constants;
import com.greencloud.gateway.constants.GatewayConstants;
import com.greencloud.gateway.context.Debug;
import com.greencloud.gateway.context.RequestContext;
import com.greencloud.gateway.exception.GatewayException;
import com.greencloud.gateway.util.HTTPRequestUtil
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory
import com.netflix.config.DynamicStringProperty;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Route for http
 *
 * @author leejianhao
 */
public class RoutingFilter extends GatewayFilter {

    private static final Logger logger = LoggerFactory.getLogger(RoutingFilter.class);

    private static final Runnable CLIENTLOADER = new Runnable() {
        @Override
        public void run() {
            RoutingFilter.loadClient();
        }
    };

    private static final DynamicIntProperty MAX_CONNECTIONS = DynamicPropertyFactory.getInstance().getIntProperty(GatewayConstants.GATEWAY_CLIENT_MAX_CONNECTIONS, 500);
    private static final DynamicIntProperty MAX_CONNECTIONS_PER_ROUTE = DynamicPropertyFactory.getInstance().getIntProperty(GatewayConstants.GATEWAY_CLIENT_ROUTE_MAX_CONNECTIONS, 20);
    private static final DynamicIntProperty SOCKET_TIMEOUT_MILLIS = DynamicPropertyFactory.getInstance().getIntProperty(GatewayConstants.GATEWAY_CLIENT_SOCKET_TIMEOUT_MILLIS, 20000);
    private static final DynamicIntProperty CONNECTION_TIMEOUT_MILLIS = DynamicPropertyFactory.getInstance().getIntProperty(GatewayConstants.GATEWAY_CLIENT_CONNECT_TIMEOUT_MILLIS, 20000);

    private static final DynamicBooleanProperty EUREKA_ENABLE = DynamicPropertyFactory.getInstance().getBooleanProperty(GatewayConstants.EUREKA_ENABLE, false);
    private static final DynamicBooleanProperty NACOS_ENABLE = DynamicPropertyFactory.getInstance().getBooleanProperty(GatewayConstants.NACOS_ENABLE, false);
    private static final DynamicStringProperty ROUTE_REGISTRY_CENTER = DynamicPropertyFactory.getInstance().getStringProperty(GatewayConstants.ROUTE_REGISTER_CENTER, "");

    private static final AtomicReference<CloseableHttpClient> clientRef = new AtomicReference<>(newClient());

    private static final ScheduledExecutorService schedule = new ScheduledThreadPoolExecutor(1, new DefaultThreadFactory("RoutingFilter.connectionManagerTimer", true));

    // cleans expired connections at an interval
    static {
        MAX_CONNECTIONS.addCallback(CLIENTLOADER);
        MAX_CONNECTIONS_PER_ROUTE.addCallback(CLIENTLOADER);
        SOCKET_TIMEOUT_MILLIS.addCallback(CLIENTLOADER);
        CONNECTION_TIMEOUT_MILLIS.addCallback(CLIENTLOADER);
        schedule.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    final HttpClient hc = clientRef.get();
                    if (hc == null) {
                        return;
                    }
                    hc.getConnectionManager().closeExpiredConnections();
                } catch (Throwable t) {
                    logger.error("error closing expired connections", t);
                }
            }
        }, 30000, 5000, TimeUnit.MILLISECONDS);
    }

    public static final void loadClient() {
        final CloseableHttpClient oldClient = clientRef.get();
        clientRef.set(newClient());
        if (oldClient != null) {
            schedule.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        oldClient.close();
                    } catch (Throwable t) {
                        Cat.logError("error shutting down old connection manager", t);
                    }
                }
            }, 30000, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public String filterType() {
        return "route";
    }

    @Override
    public int filterOrder() {
        return 100;
    }

    @Override
    public boolean shouldFilter() {
        RequestContext ctx = RequestContext.getCurrentContext();
        // 如果走服务注册中心
        if (!Strings.isNullOrEmpty(ROUTE_REGISTRY_CENTER.get()) && (EUREKA_ENABLE.get() || NACOS_ENABLE.get())) {
            return false;
        }
        return ctx.getRouteUrl() != null && ctx.sendGatewayResponse();
    }

    @Override
    public Object run() throws GatewayException {
        RequestContext context = RequestContext.getCurrentContext();
        HttpServletRequest request = context.getRequest();

        InputStream requestEntity = getRequestBody(request);

        int contentLength = request.getContentLength();
        String url = context.getRouteUrl();
        Header[] headers = buildRequestHeaders(request);
        String method = request.getMethod().toUpperCase();

        Transaction tran = Cat.newTransaction("RouteFilter",
                StringUtils.substringBefore(request.getRequestURL().toString(), "?") + "=>" + StringUtils.substringBefore(url, "?"));

        try {
            RequestConfig requestConfig = buildRequestConfig();
            HttpResponse response = forward(clientRef.get(), requestConfig, method, url, headers, requestEntity, contentLength);
            Debug.addRequestDebug(String.format("GATEWAY :: %s", url));
            Debug.addRequestDebug(String.format("GATEWAY :: Response statusLine > %s", response.getStatusLine()));
            Debug.addRequestDebug(String.format("GATEWAY :: Response code > %s", response.getStatusLine().getStatusCode()));

            setResponse(response);
            tran.setStatus(Transaction.SUCCESS);
        } catch (Exception ex) {
            tran.setStatus(ex);
            String errorMsg = String.format("Origin URL: %s => Target URL: %s error, cause: %s", request.getRequestURL(), url, ex.getMessage());
            Cat.logError(errorMsg, ex);
            throw new GatewayException("Route Error", 500, errorMsg);
        } finally {
            tran.complete();
        }
        return null;
    }

    public HttpResponse forward(HttpClient client, RequestConfig requestConfig, String method, String url, Header[] headers, InputStream requestEntity, int contentLength)
            throws Exception {
        HttpUriRequest httpUriRequest;

        switch (method) {
            case "POST":
                HttpPost httpPost = new HttpPost(url);
                httpPost.setConfig(requestConfig);
                httpUriRequest = httpPost;
                InputStreamEntity entity = new InputStreamEntity(requestEntity, contentLength);
                httpPost.setEntity(entity);
                break;
            case "PUT":
                HttpPut httpPut = new HttpPut(url);
                httpPut.setConfig(requestConfig);
                httpUriRequest = httpPut;
                httpPut.setEntity(new InputStreamEntity(requestEntity, contentLength));
                break;
            case "PATCH":
                HttpPatch httpPatch = new HttpPatch(url);
                httpPatch.setConfig(requestConfig);
                httpUriRequest = httpPatch;
                httpPatch.setEntity(new InputStreamEntity(requestEntity, contentLength));
                break;
            default:
                httpUriRequest = RequestBuilder.create(method).setUri(url).setConfig(requestConfig).build();
        }

        try {
            httpUriRequest.setHeaders(headers);
            HttpResponse response = client.execute(httpUriRequest);
            return response;
        } finally {
            // httpclient.getConnectionManager().shutdown();
        }
    }

    public boolean isValidHeader(String name) {
        if (name.toLowerCase().contains("content-length")) {
            return false;
        }
        if (!RequestContext.getCurrentContext().getResponseGZipped()) {
            if (name.toLowerCase().contains("accept-encoding")) {
                return false;
            }
        }
        if (name.toLowerCase().contains("host")) {
            return false;
        }
        return true;
    }

    public boolean isValidHeader(Header header) {
        RequestContext ctx = RequestContext.getCurrentContext();
        if (ctx.containsKey(GatewayConstants.IGNORED_HEADERS)) {
            Object object = ctx.get(GatewayConstants.IGNORED_HEADERS);
            if (object instanceof Collection && ((Collection<?>) object).contains(header.getName())) {
                return false;
            }
        }
        switch (header.getName().toLowerCase()) {
            case "host":
            case "connection":
            case "content-length":
            case "content-encoding":
            case "server":
            case "transfer-encoding":
            case "x-application-context":
                return false;
            default:
                return true;
        }
    }

    public Header[] buildRequestHeaders(HttpServletRequest request) {
        Map<String, Header> headers = Maps.newHashMap();

        // Request's headers
        Enumeration headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = (String) headerNames.nextElement();

            final StringBuilder valBuilder = new StringBuilder();
            for (final Enumeration vals = request.getHeaders(name); vals.hasMoreElements(); ) {
                valBuilder.append(vals.nextElement());
                valBuilder.append(',');
            }
            valBuilder.setLength(valBuilder.length() - 1);

            if (isValidHeader(name)) {
                if (Constants.CAT_PARENT_MESSAGE_ID.equalsIgnoreCase(name)
                        || Constants.CAT_CHILD_MESSAGE_ID.equalsIgnoreCase(name)
                        || Constants.CAT_ROOT_MESSAGE_ID.equalsIgnoreCase(name)) {
                    continue;
                }
                headers.put(name.toLowerCase(), new BasicHeader(name, valBuilder.toString()));
                if ("X-Forwarded-For".equalsIgnoreCase(name)) {
                    headers.put("X-GW-IPADDRESS", new BasicHeader("X-GW-IPADDRESS", StringUtils.substringBefore(valBuilder.toString(), ",")));
                } else if ("User-Agent".equalsIgnoreCase(name)) {
                    headers.put("X-GW-USERAGENT", new BasicHeader("X-GW-USERAGENT", valBuilder.toString()));
                }
            }
        }

        // 将cat上下文通过header传递给下游
        Cat.Context ctx = new CatContext();
        Cat.logRemoteCallClient(ctx);
        headers.put(Constants.CAT_ROOT_MESSAGE_ID, new BasicHeader(Constants.CAT_ROOT_MESSAGE_ID, ctx.getProperty(Cat.Context.ROOT)));
        headers.put(Constants.CAT_PARENT_MESSAGE_ID, new BasicHeader(Constants.CAT_PARENT_MESSAGE_ID, ctx.getProperty(Cat.Context.PARENT)));
        headers.put(Constants.CAT_CHILD_MESSAGE_ID, new BasicHeader(Constants.CAT_CHILD_MESSAGE_ID, ctx.getProperty(Cat.Context.CHILD)));

        // Additional gateway's headers
        Map<String, String> gatewayRequestHeaders = RequestContext.getCurrentContext().getGatewayRequestHeaders();
        for (Map.Entry<String, String> gatewayRequestHeader : gatewayRequestHeaders.entrySet()) {
            String name = gatewayRequestHeader.getKey();
            String value = gatewayRequestHeader.getValue();
            if (isValidHeader(name)) {
                headers.put(name, new BasicHeader(name, value));
            }
        }

        if (RequestContext.getCurrentContext().getResponseGZipped()) {
            headers.put("accept-encoding", new BasicHeader("accept-encoding", "deflate, gzip"));
        }

        return headers.values().toArray(new Header[0]);
    }

    private InputStream getRequestBody(HttpServletRequest request) {
        InputStream requestEntity = null;
        try {
            requestEntity = request.getInputStream();
        } catch (IOException ex) {
            // ignored, isOK
        }
        return requestEntity;
    }

    private static final CloseableHttpClient newClient() {
        final RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS.get())
                .setConnectTimeout(CONNECTION_TIMEOUT_MILLIS.get())
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES).build();

        return HttpClients.custom().setConnectionManager(newConnectionManager())
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
                .setRedirectStrategy(new RedirectStrategy() {
                    @Override
                    public boolean isRedirected(HttpRequest request,
                                                HttpResponse response, HttpContext context)
                            throws ProtocolException {
                        return false;
                    }

                    @Override
                    public HttpUriRequest getRedirect(HttpRequest request,
                                                      HttpResponse response, HttpContext context)
                            throws ProtocolException {
                        return null;
                    }
                }).build();
    }

    private static final PoolingHttpClientConnectionManager newConnectionManager() {
        try {
            final SSLContext sslContext = SSLContext.getInstance("SSL");

            TrustManager[] trustManagers = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates,
                                               String s) throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            } as TrustManager[];

            sslContext.init(null, trustManagers , new SecureRandom());

            final Registry<ConnectionSocketFactory> registry = RegistryBuilder
                    .<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.INSTANCE)
                    .register("https", new SSLConnectionSocketFactory(sslContext))
                    .build();

            PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(registry);
            connectionManager.setMaxTotal(MAX_CONNECTIONS.get());
            connectionManager.setDefaultMaxPerRoute(MAX_CONNECTIONS_PER_ROUTE.get());
            return connectionManager;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void setResponse(HttpResponse response) throws IOException {
        RequestContext context = RequestContext.getCurrentContext();

        context.setResponseStatusCode(response.getStatusLine().getStatusCode());
        context.setResponseDataStream(response.getEntity().getContent());

        boolean isOriginResponseGzipped = false;
        for (Header header : response.getHeaders(HttpHeaders.CONTENT_ENCODING)) {
            if (HTTPRequestUtil.getInstance().isGzipped(header.getValue())) {
                isOriginResponseGzipped = true;
                break;
            }
        }
        context.setResponseGZipped(isOriginResponseGzipped);

        for (Header header : response.getAllHeaders()) {
            context.addOriginResponseHeader(header.getName(), header.getValue());
            if (header.getName().equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                context.setOriginContentLength(header.getValue());
            }
            if (isValidHeader(header)) {
                context.addGatewayResponseHeader(header.getName(), header.getValue());
            }
        }
    }
    // 开放平台每个API接口支持设置请求超时时间
    private RequestConfig buildRequestConfig() {
        RequestConfig.Builder builder = RequestConfig.custom();

        int timeout = RequestContext.getCurrentContext().getBackendServiceTimeout();
        builder.setSocketTimeout(timeout == 0 ? SOCKET_TIMEOUT_MILLIS.get() : timeout);
        return  builder.build();
    }
}

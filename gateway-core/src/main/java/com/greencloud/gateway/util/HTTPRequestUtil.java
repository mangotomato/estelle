package com.greencloud.gateway.util;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.greencloud.gateway.constants.GatewayConstants;
import com.greencloud.gateway.context.RequestContext;
import org.apache.http.HttpHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;

public class HTTPRequestUtil {

    private static final Logger logger = LoggerFactory.getLogger(HTTPRequestUtil.class);

    private final static HTTPRequestUtil INSTANCE = new HTTPRequestUtil();

    public static HTTPRequestUtil getInstance() {
        return INSTANCE;
    }

    public String getHeaderValue(String sHeaderName) {
        return RequestContext.getCurrentContext().getRequest().getHeader(sHeaderName);
    }

    public String getFormValue(String sHeaderName) {
        return RequestContext.getCurrentContext().getRequest().getParameter(sHeaderName);
    }

    public Map<String, String> getRequestHeaders() {
        HttpServletRequest request = RequestContext.getCurrentContext().getRequest();

        Map<String, String> headers = Maps.newHashMap();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();

                final StringBuilder valBuilder = new StringBuilder();
                for (final Enumeration vals = request.getHeaders(name); vals.hasMoreElements();) {
                    valBuilder.append(vals.nextElement());
                    valBuilder.append(',');
                }
                valBuilder.setLength(valBuilder.length() - 1);

                headers.put(name, valBuilder.toString());
            }
        }
        return Collections.unmodifiableMap(headers);

    }

    public Map<String, List<String>> getRequestHeaderMap() {
        HttpServletRequest request = RequestContext.getCurrentContext().getRequest();
        Map<String, List<String>> headers = Maps.newHashMap();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String value = request.getHeader(name);

                if (name != null && !name.isEmpty() && value != null) {
                    List<String> valueList = Lists.newArrayList();
                    if (headers.containsKey(name)) {
                        headers.get(name).add(value);
                    }
                    valueList.add(value);
                    headers.put(name, valueList);
                }
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    public Map<String, String> leaveFirstIfMultiQueryParamValue() {
        Map<String, List<String>> queryParams = getQueryParams();
        if (queryParams.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> qParams = new HashMap<>(queryParams.size());
        for (String key : queryParams.keySet()) {
            qParams.put(key, queryParams.get(key).get(0));
        }
        return qParams;
    }

    public Map<String, List<String>> getQueryParams() {

        Map<String, List<String>> qp = RequestContext.getCurrentContext().getRequestQueryParams();
        if (qp != null) {
            return qp;
        }

        HttpServletRequest request = RequestContext.getCurrentContext().getRequest();

        qp = Maps.newHashMap();

        if (request.getQueryString() == null) {
            return Collections.emptyMap();
        }
        qp = queryString2Map(request.getQueryString());

        RequestContext.getCurrentContext().setRequestQueryParams(qp);
        return qp;
    }

    public Map<String, String> leaveFirstIfMultiFormParamValue(String formQueryString) {
        if (Strings.isNullOrEmpty(formQueryString)) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> formParams = queryString2Map(formQueryString);
        if (formParams.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> fParams = Maps.newHashMapWithExpectedSize(formParams.size());
        for (String key : formParams.keySet()) {
            fParams.put(key, formParams.get(key).get(0));
        }
        return fParams;
    }

    private Map<String, List<String>> queryString2Map(String queryString) {
        Map<String, List<String>> qp = Maps.newHashMap();
        StringTokenizer st = new StringTokenizer(queryString, "&");
        int i;

        while (st.hasMoreTokens()) {
            String s = st.nextToken();
            i = s.indexOf("=");
            if (i > 0 && s.length() >= i + 1) {
                String name = s.substring(0, i);
                String value = s.substring(i + 1);

                try {
                    name = URLDecoder.decode(name, "UTF-8");
                } catch (Exception e) {
                }
                try {
                    value = URLDecoder.decode(value, "UTF-8");
                } catch (Exception e) {
                }

                List<String> valueList = qp.get(name);
                if (valueList == null) {
                    valueList = new LinkedList<String>();
                    qp.put(name, valueList);
                }

                valueList.add(value);
            } else if (i == -1) {
                String name = s;
                String value = "";
                try {
                    name = URLDecoder.decode(name, "UTF-8");
                } catch (Exception e) {
                }

                List<String> valueList = qp.get(name);
                if (valueList == null) {
                    valueList = new LinkedList<>();
                    qp.put(name, valueList);
                }

                valueList.add(value);

            }
        }
        return qp;
    }

    public String getValueFromRequestElements(String sName) {
        String sValue = null;
        if (getQueryParams() != null) {
            final List<String> v = getQueryParams().get(sName);
            if (v != null && !v.isEmpty()) {
                sValue = v.iterator().next();
            }
        }
        if (sValue != null) {
            return sValue;
        }
        sValue = getHeaderValue(sName);
        if (sValue != null) {
            return sValue;
        }
        sValue = getFormValue(sName);
        if (sValue != null) {
            return sValue;
        }
        return null;
    }

    public boolean isGzippedRequest(HttpServletRequest request) {
        String contentEncoding = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
        if (contentEncoding != null && isGzipped(contentEncoding)) {
            return true;
        }
        return false;
    }

    public boolean isGzipped(String contentEncoding) {
        return contentEncoding.contains("gzip");
    }

    public static String getClientIP(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    public String buildRequestURI(HttpServletRequest request) {
        RequestContext context = RequestContext.getCurrentContext();
        String uri = request.getRequestURI();
        String contextURI = (String) context.get("requestURI");
        if (contextURI != null) {
            try {
                uri = URLEncoder.encode(contextURI, GatewayConstants.DEFAULT_CHARACTER_ENCODING);
            } catch (Exception e) {
                logger.error("unable to encode uri path from context, falling back to uri from request", e);
            }
        }
        return uri;
    }

}

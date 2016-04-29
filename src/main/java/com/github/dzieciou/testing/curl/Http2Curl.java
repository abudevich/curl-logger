/*
 * Copyright (C) 2007, 2008 Apple Inc.  All rights reserved.
 * Copyright (C) 2008, 2009 Anthony Ricaud <rik@webkit.org>
 * Copyright (C) 2011 Google Inc. All rights reserved.
 * Copyright (C) 2016 Maciej Gawinecki <mgawinecki@gmail.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1.  Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 * 2.  Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 * 3.  Neither the name of Apple Computer, Inc. ("Apple") nor the names of
 *     its contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY APPLE AND ITS CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL APPLE OR ITS CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.github.dzieciou.testing.curl;

import com.google.common.collect.ImmutableSet;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import com.jayway.restassured.internal.multipart.*;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Generates CURL command for a given HTTP request.
 */
public class Http2Curl {

    private static final Logger log = LoggerFactory.getLogger(Http2Curl.class);

    private static final Set<String> nonBinaryContentTypes = ImmutableSet.<String>builder()
            .add("application/x-www-form-urlencoded")
            .add("application/json")
            .build();

    /**
     * Generates CURL command for a given HTTP request.
     *
     * @param request HTTP request
     * @return CURL command
     * @throws Exception if failed to generate CURL command
     */
    public static String generateCurl(HttpRequest request) throws Exception {

        List<String> command = new ArrayList<>();
        Set<String> ignoredHeaders = new HashSet<>();
        List<Header> headers = Arrays.asList(request.getAllHeaders());

        command.add("curl");

        String inferredUri = request.getRequestLine().getUri();
        if (!isValidUrl(inferredUri)) { // Missing schema and domain name
            String host = getHost(request);
            String inferredScheme = "http";
            if (host.endsWith(":443")) {
                inferredScheme = "https";
            } else if (request instanceof RequestWrapper) {
                if (getOriginalRequestUri(request).startsWith("https")) {
                    // This is for original URL, so if during redirects we go out of HTTPs, this might be a wrong guess
                    inferredScheme = "https";
                }
            }

            if ("CONNECT".equals(request.getRequestLine().getMethod())) {
                inferredUri = String.format("%s://%s", inferredScheme, host);
            } else {
                inferredUri =
                        String.format("%s://%s/%s", inferredScheme, host, inferredUri)
                                .replaceAll("(?<!http(s)?:)//", "/");
            }
        }
        command.add(escapeString(inferredUri).replaceAll("[[{}\\\\]]", "\\$&"));

        String inferredMethod = "GET";
        List<String> data = new ArrayList<>();

        Optional<String> requestContentType = tryGetHeaderValue(headers, "Content-Type");
        Optional<String> formData = Optional.empty();
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntityEnclosingRequest requestWithEntity = (HttpEntityEnclosingRequest) request;
            try {
                HttpEntity entity = requestWithEntity.getEntity();
                if (entity != null) {
                    if (requestContentType.get().startsWith(ContentType.MULTIPART_FORM_DATA.getMimeType())) {
                        HttpEntity wrappedEntity = (HttpEntity) getFieldValue(entity, "wrappedEntity");
                        RestAssuredMultiPartEntity multiPartEntity = (RestAssuredMultiPartEntity) wrappedEntity;
                        MultipartEntityBuilder multipartEntityBuilder = (MultipartEntityBuilder) getFieldValue(multiPartEntity, "builder");
                        List<FormBodyPart> bodyParts = (List<FormBodyPart>) getFieldValue(multipartEntityBuilder, "bodyParts");
                        StringBuffer sb = new StringBuffer();
                        for(FormBodyPart bodyPart : bodyParts) {
                            // TODO
                        }

                    } else {
                        formData = Optional.of(EntityUtils.toString(entity));
                    }
                }
            } catch (IOException e) {
                log.error("Failed to consume form data (entity) from HTTP request", e);
                throw e;
            }
        }

        if (requestContentType.isPresent()
                && nonBinaryContentTypes.contains(requestContentType.get())
                && formData.isPresent()) {
            data.add("--data");
            data.add(escapeString(formData.get()));
            ignoredHeaders.add("Content-Length");
            inferredMethod = "POST";
        } else if (formData.isPresent()) {
            data.add("--data-binary");
            data.add(escapeString(formData.get()));
            ignoredHeaders.add("Content-Length");
            inferredMethod = "POST";
        }

        if (!request.getRequestLine().getMethod().equals(inferredMethod)) {
            command.add("-X");
            command.add(request.getRequestLine().getMethod());
        }

        headers
                .stream()
                .filter(h -> !ignoredHeaders.contains(h.getName()))
                .forEach(h -> {
                    command.add("-H");
                    command.add(escapeString(h.getName() + ": " + h.getValue()));
                });

        command.addAll(data);
        command.add("--compressed");
        return command.stream().collect(Collectors.joining(" "));
    }



    private static String getOriginalRequestUri(HttpRequest request) {
        if (request instanceof HttpRequestWrapper) {
            return ((HttpRequestWrapper) request).getOriginal().getRequestLine().getUri();
        } else if (request instanceof RequestWrapper) {
            return ((RequestWrapper) request).getOriginal().getRequestLine().getUri();

        } else {
            throw new IllegalArgumentException("Unsupported request class type: " + request.getClass());
        }
    }

    private static String getHost(HttpRequest request) {
        return tryGetHeaderValue(Arrays.asList(request.getAllHeaders()), "Host")
                .orElseGet(() -> URI.create(getOriginalRequestUri(request)).getHost());
    }

    private static boolean isValidUrl(String url) {
        try {
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private static Optional<String> tryGetHeaderValue(List<Header> headers, String headerName) {
        return headers
                .stream()
                .filter(h -> h.getName().equals(headerName))
                .map(Header::getValue)
                .findFirst();
    }

    private static boolean isOsWindows() {
        return System.getProperty("os.name") != null && System.getProperty("os.name")
                .startsWith("Windows");
    }

    private static String escapeString(String s) {
        // cURL command is expected to run on the same platform that test run
        return isOsWindows() ? escapeStringWin(s) : escapeStringPosix(s);
    }

    /**
     * Replace quote by double quote (but not by \") because it is recognized by both cmd.exe and MS
     * Crt arguments parser.
     * <p>
     * Replace % by "%" because it could be expanded to an environment variable value. So %% becomes
     * "%""%". Even if an env variable "" (2 doublequotes) is declared, the cmd.exe will not
     * substitute it with its value.
     * <p>
     * Replace each backslash with double backslash to make sure MS Crt arguments parser won't
     * collapse them.
     * <p>
     * Replace new line outside of quotes since cmd.exe doesn't let to do it inside.
     */
    private static String escapeStringWin(String s) {
        return "\""
                + s
                .replaceAll("\"", "\"\"")
                .replaceAll("%", "\"%\"")
                .replaceAll("\\\\", "\\\\")
                .replaceAll("[\r\n]+", "\"^$&\"")
                + "\"";
    }

    private static String escapeStringPosix(String s) {

        if (s.matches("^.*([^\\x20-\\x7E]|\').*$")) {
            // Use ANSI-C quoting syntax.
            String escaped = s
                    .replaceAll("\\\\", "\\\\")
                    .replaceAll("'", "\\'")
                    .replaceAll("\n", "\\n")
                    .replaceAll("\r", "\\r");

            escaped = escaped.chars()
                    .mapToObj(c -> escapeCharacter((char) c))
                    .collect(Collectors.joining());

            return "$\'" + escaped + "'";
        } else {
            // Use single quote syntax.
            return "'" + s + "'";
        }

    }

    private static String escapeCharacter(char c) {
        int code = (int) c;
        String codeAsHex = Integer.toHexString(code);
        if (code < 256) {
            // Add leading zero when needed to not care about the next character.
            return code < 16 ? "\\x0" + codeAsHex : "\\x" + codeAsHex;
        }
        return "\\u" + ("" + codeAsHex).substring(codeAsHex.length(), 4);
    }



    private static <T> Object getFieldValue(T obj, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field f = getField(obj.getClass(), fieldName);
        f.setAccessible(true);
        return f.get(obj);
    }


    private static Field getField(Class clazz, String fieldName)
            throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class superClass = clazz.getSuperclass();
            if (superClass == null) {
                throw e;
            } else {
                return getField(superClass, fieldName);
            }
        }
    }

}
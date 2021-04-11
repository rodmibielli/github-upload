package com.rodmibielli.rest;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.http.HttpStatus.NOT_MODIFIED;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Arrays;

import javax.cache.Cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Wrapps a {@link ClientHttpRequestFactory} and applies caching behaviour to all created {@link ClientHttpRequest}
 * instances.
 * 
 * @author Oliver Gierke
 * @author Rodrigo Mibielli
 */
@SuppressWarnings("rawtypes")
public class CachingHttpRequestFactory implements ClientHttpRequestFactory {

    private static final String ETAG_HEADER = "ETag";
    private static final String IF_NONE_MATCH_HEADER = "If-None-Match";

    private ClientHttpRequestFactory delegate;

    private Cache<URI, AbstractMap.SimpleEntry> cache;

    private static final Log log = LogFactory.getLog(CachingHttpRequestFactory.class);

    public CachingHttpRequestFactory(Cache<URI, AbstractMap.SimpleEntry> cache) {
    	this(new SimpleClientHttpRequestFactory(),cache);
    }
    
    public CachingHttpRequestFactory(ClientHttpRequestFactory delegate, Cache<URI, AbstractMap.SimpleEntry> cache) {

        this.delegate = delegate;
        this.cache = cache;
    }

    public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {

        ClientHttpRequest request = delegate.createRequest(uri, method);

        if (isPurgingRequest(method)) {

            synchronized (cache) {

                cache.remove(uri);

                log.debug("Cache of " + uri + " response has been removed.");
            }

            return request;
        }

        synchronized (cache) {
            if (isCacheableRequest(method) && cache.containsKey(uri)) {
                log.debug("Setting header eTag:" + cache.get(uri).getKey().toString());
                request.getHeaders().add(IF_NONE_MATCH_HEADER, cache.get(uri).getKey().toString());
            }
        }

        return isCacheableRequest(method) ? new CachingHttpRequest(uri, request) : request;
    }

    private boolean isPurgingRequest(HttpMethod method) {

        return Arrays.asList(POST, PUT, DELETE).contains(method);
    }

    private boolean isCacheableRequest(HttpMethod method) {

        return GET.equals(method);
    }

    /**
     * Buffers the {@link InputStream} contained in the wrapped {@link ClientHttpResponse}. Delegates all other calls to
     * the original instance.
     * 
     * @author Oliver Gierke
     */
    private class CachingHttpResponse implements ClientHttpResponse {

        private ByteArrayOutputStream output = new ByteArrayOutputStream();
        private ClientHttpResponse delegate;

        /**
         * @throws IOException
         */
        public CachingHttpResponse(ClientHttpResponse response) throws IOException {

            byte[] buffer = new byte[1024];
            InputStream stream = response.getBody();
            int n = 0;

            while (-1 != (n = stream.read(buffer))) {
                output.write(buffer, 0, n);
            }

            this.delegate = response;
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.springframework.http.client.ClientHttpResponse#close()
         */
        public void close() {

            delegate.close();
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.springframework.http.client.ClientHttpResponse#getStatusCode()
         */
        public HttpStatus getStatusCode() throws IOException {

            return delegate.getStatusCode();
        }

        public String getStatusText() throws IOException {

            return delegate.getStatusText();
        }

        public InputStream getBody() throws IOException {

            return new ByteArrayInputStream(output.toByteArray());
        }

        public HttpHeaders getHeaders() {

            return delegate.getHeaders();
        }

        @Override
        public int getRawStatusCode() throws IOException {

            return delegate.getRawStatusCode();
        }
    }

    private class CachingHttpRequest extends AbstractClientHttpRequest {

        private URI uri;
        private ClientHttpRequest request;

        public CachingHttpRequest(URI uri, ClientHttpRequest request) {

            this.uri = uri;
            this.request = request;
        }

        @Override
        protected OutputStream getBodyInternal(HttpHeaders headers) throws IOException {
            return request.getBody();
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.springframework.http.client.AbstractClientHttpRequest#executeInternal
         * (org.springframework.http.HttpHeaders, byte[])
         */
        protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {

            ClientHttpResponse response = request.execute();

            HttpHeaders responseHeaders = response.getHeaders();

            log.debug("Response status: " + response.getStatusCode() + " with headers: " + responseHeaders);

            boolean isNotModified = NOT_MODIFIED.equals(response.getStatusCode());

            synchronized (cache) {

                // Return cached instance on 304
                if (isNotModified) {

                    if (cache.containsKey(uri)) {

                        ClientHttpResponse cachedResponse = (ClientHttpResponse) cache.get(uri).getValue();

                        log.debug("Returning cached response of " + uri);

                        return cachedResponse;

                    } else {

                        headers.remove(IF_NONE_MATCH_HEADER);

                        return executeInternal(headers);
                    }

                }

            }

            // Put into cache if ETag returned
            if (isCacheableRequest(request.getMethod()) && responseHeaders.containsKey(ETAG_HEADER)) {

                if (response.getBody().available() > 0L) {

                    ClientHttpResponse wrapper = new CachingHttpResponse(response);

                    synchronized (cache) {

                        cache.put(uri, new AbstractMap.SimpleEntry<String, ClientHttpResponse>(
                                responseHeaders.getFirst(ETAG_HEADER), wrapper));

                        log.debug("Response of " + uri + " has been cached.");
                    }

                    return wrapper;

                } else {

                    synchronized (cache) {

                        cache.remove(uri);

                        log.debug("Removed cache of " + uri + " because it has no content!");
                    }

                }

            }

            return response;
        }

        public HttpMethod getMethod() {

            return request.getMethod();
        }

        @Override
        public String getMethodValue() {
            return request.getMethodValue();
        }

        @Override
        public URI getURI() {
            return request.getURI();
        }

    }

}

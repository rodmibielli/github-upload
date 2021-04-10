package com.rodmibielli.rest;

import javax.cache.Cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.rodmibielli.resolver.MockCacheParameterResolver;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Tests for caching.
 * 
 * @author rodmi
 *
 */
@ExtendWith(MockCacheParameterResolver.class)
@DisplayName("A cache")
@SuppressWarnings({"rawtypes","unchecked"})
class CachingTests {

	private Cache cache;

    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    private Map<URI, String> responseContentMap = new HashMap<>();

	@BeforeEach
    void init(Cache cache) {

        // Given:
        this.cache = cache;

        this.restTemplate = new RestTemplate();

        this.mockServer = MockRestServiceServer.createServer(restTemplate);

        final ClientHttpRequestFactory requestFactory = this.restTemplate.getRequestFactory();

        this.restTemplate.setRequestFactory(new CachingHttpRequestFactory(requestFactory, this.cache));

        this.responseContentMap.clear();
    }

    @Nested
    @DisplayName("should not be used")
    class NoCaching {

        @Test
        @DisplayName("whenever a GET HTTP response comes with an ETag but no content.")
        void when_issue_GET_command_with_Etag_but_empty_body() throws URISyntaxException {

            // Given:
            URI uri = new URI("http://localhost/1234");

            String bodyContent = "";

            // When:
            ResponseEntity<String> response = exchange(uri, HttpMethod.GET, bodyContent);

            // Then:

            assertThat(response.getBody()).isNull();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(cache.containsKey(uri)).isFalse();

        }

        @ParameterizedTest
        @DisplayName("whenever purging HTTP method.")
        @EnumSource(value = HttpMethod.class, names = { "PUT", "POST", "DELETE" })
        void whenever_purging_request_command(HttpMethod httpMethod) throws URISyntaxException {

            // Given:
            URI uri = new URI("http://localhost/1234");

            String bodyContent = "Test";

            exchange(uri, HttpMethod.GET, bodyContent);

            // When:
            ResponseEntity<String> response = exchange(uri, httpMethod, bodyContent);

            // Then:

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(response.getBody()).isNull();

            assertThat(cache.containsKey(uri)).isFalse();
        }

        @Test
        @DisplayName("whenever last content from same URI returns blank.")
        void whenever_last_content_from_same_URI_returns_blank() throws URISyntaxException, IOException {

            // Given:
            URI uri = new URI("http://localhost/12345");

            String bodyContent1 = "Test1";

            exchange(uri, HttpMethod.GET, bodyContent1);

            String bodyContent2 = "";

            // When:

            ResponseEntity<String> response = exchange(uri, HttpMethod.GET, bodyContent2);

            // Then:

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            assertThat(cache.containsKey(uri)).isFalse();

        }

    }

    @Nested
    @DisplayName("should be used")
    class Caching {

        @Test
        @DisplayName("whenever a GET HTTP response comes with an ETag.")
        void when_issue_GET_command_with_Etag() throws URISyntaxException, IOException {

            // Given:
            URI uri = new URI("http://localhost/1234");

            String bodyContent = "Test";

            // When:
            ResponseEntity<String> response = exchange(uri, HttpMethod.GET, bodyContent);

            // Then:
            assertThat(cache.containsKey(uri)).isTrue();

            assertThat(getContentFromCache(uri)).isEqualTo(bodyContent);

            assertThat(response.getBody()).isEqualTo(bodyContent);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        }

        @Test
        @DisplayName("and return from cache whenever a GET HTTP response comes with status 304.")
        void should_return_response_from_cache_when_return_304_with_body() throws URISyntaxException, IOException {

            // Given:

            URI uri = new URI("http://localhost/1234");

            String bodyContent = "Test";

            exchange(uri, HttpMethod.GET, bodyContent);

            // When:

            ResponseEntity<String> response = exchange(uri, HttpMethod.GET, bodyContent);

            // Then:
            assertThat(cache.containsKey(uri)).isTrue();

            assertThat(getContentFromCache(uri)).isEqualTo(bodyContent);

            assertThat(response.getBody()).isEqualTo(bodyContent);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        }
    }

    @Test
    @DisplayName("to cache two contents from differents URIs.")
    void should_cache_two_contents_from_diff_URIs() throws URISyntaxException, IOException {

        // Given:

        String bodyContent1 = "Test1";

        URI uri1 = new URI("http://localhost/1234");

        String bodyContent2 = "Test2";

        URI uri2 = new URI("http://localhost/12345");

        // When:

        exchange(uri1, HttpMethod.GET, bodyContent1);

        exchange(uri2, HttpMethod.GET, bodyContent2);

        // Then:

        assertThat(cache.containsKey(uri1)).isTrue();

        assertThat(cache.containsKey(uri2)).isTrue();

        assertThat(getContentFromCache(uri1)).isEqualTo(bodyContent1);

        assertThat(getContentFromCache(uri2)).isEqualTo(bodyContent2);

    }

    @Test
    @DisplayName("to cache last content from same URI.")
    void should_cache_last_content_from_same_URI() throws URISyntaxException, IOException {

        // Given:
        URI uri = new URI("http://localhost/12345");

        String bodyContent1 = "Test1";

        exchange(uri, HttpMethod.GET, bodyContent1);

        String bodyContent2 = "Test2";

        // When:

        ResponseEntity<String> response = exchange(uri, HttpMethod.GET, bodyContent2);

        // Then:

        assertThat(response.getBody()).isEqualTo(bodyContent2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(cache.containsKey(uri)).isTrue();

        assertThat(getContentFromCache(uri)).isEqualTo(bodyContent2);

    }

    private String getContentFromCache(URI uri) throws IOException {
        return new String(((AbstractMap.SimpleEntry<String, ClientHttpResponse>) cache.get(uri)).getValue().getBody()
                .readAllBytes());
    }

    private ResponseEntity<String> exchange(URI uri, HttpMethod httpMethod, String bodyContent) {

        String eTagValue = "\"1234\"";

        HttpHeaders responseHeaders = new HttpHeaders();

        responseHeaders.setETag(eTagValue);

        boolean contentNotModified = responseContentMap.containsKey(uri)
                && responseContentMap.get(uri).equals(bodyContent);

        mockServer.expect(requestTo(uri)).andExpect(method(httpMethod)).andRespond(
                withStatus(httpMethod == HttpMethod.GET && contentNotModified ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                        .headers(responseHeaders).contentType(MediaType.APPLICATION_JSON)
                        .body(contentNotModified ? "" : bodyContent));

        ResponseEntity<String> response = restTemplate.exchange(uri, httpMethod, null, String.class);

        mockServer.verify();

        mockServer.reset();

        responseContentMap.put(uri, bodyContent);

        return response;
    }
}

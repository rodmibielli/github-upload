package com.rodmibielli.resolver;

import javax.cache.Cache;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Instancia um RestTemplate.
 * 
 * @author Rodrigo Mibielli
 *
 */
public class MockCacheParameterResolver implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {

        return parameterContext.getParameter().getType() == Cache.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {

        return createMockCache();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
	private Cache createMockCache() {

        final Map cacheMap = new HashMap<>();

        Cache mockedCache = mock(Cache.class);

        lenient().when(mockedCache.remove(any())).then(invocation -> {
            return cacheMap.remove(invocation.getArgument(0)) != null;
        });

        lenient().when(mockedCache.get(any(URI.class))).then(invocation -> {
            return cacheMap.get(invocation.getArgument(0));
        });

        lenient().when(mockedCache.containsKey(any(URI.class))).then(invocation -> {
            return cacheMap.containsKey(invocation.getArgument(0));
        });

        lenient().doAnswer(invocation -> {
            return cacheMap.put(invocation.getArgument(0), invocation.getArgument(1));

        }).when(mockedCache).put(any(URI.class), any());

        return mockedCache;
    }
}

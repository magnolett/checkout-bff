package io.github.magnolett.checkoutbff.integration;

import io.github.magnolett.checkoutbff.checkout.port.PaymentMethodCatalog;
import io.github.magnolett.checkoutbff.checkout.port.ProductCatalog;
import io.github.magnolett.checkoutbff.integration.cache.CachingPaymentMethodCatalog;
import io.github.magnolett.checkoutbff.integration.cache.CachingProductCatalog;
import io.github.magnolett.checkoutbff.integration.cache.RedisJsonCache;
import io.github.magnolett.checkoutbff.integration.http.PaymentMethodsServiceClient;
import io.github.magnolett.checkoutbff.integration.http.ProductsServiceClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the two catalogs the checkout depends on. Each port gets one bean, the HTTP client wrapped by the Redis
 * cache, so the application never learns that a cache exists.
 */
@Configuration(proxyBeanMethods = false)
class CatalogConfig {

    @Bean
    ProductCatalog productCatalog(WebClient.Builder webClientBuilder, IntegrationProperties properties,
                                  CircuitBreakerRegistry circuitBreakers, RetryRegistry retries, RedisJsonCache cache) {
        IntegrationProperties.Endpoint endpoint = properties.productsService();
        ProductsServiceClient client = new ProductsServiceClient(webClient(webClientBuilder, endpoint),
                endpoint.timeout(), circuitBreakers.circuitBreaker(ProductsServiceClient.SERVICE),
                retries.retry(ProductsServiceClient.SERVICE));
        return new CachingProductCatalog(client, cache, endpoint.cacheTtl());
    }

    @Bean
    PaymentMethodCatalog paymentMethodCatalog(WebClient.Builder webClientBuilder, IntegrationProperties properties,
                                              CircuitBreakerRegistry circuitBreakers, RetryRegistry retries,
                                              RedisJsonCache cache) {
        IntegrationProperties.Endpoint endpoint = properties.paymentMethodsService();
        PaymentMethodsServiceClient client = new PaymentMethodsServiceClient(webClient(webClientBuilder, endpoint),
                endpoint.timeout(), circuitBreakers.circuitBreaker(PaymentMethodsServiceClient.SERVICE),
                retries.retry(PaymentMethodsServiceClient.SERVICE));
        return new CachingPaymentMethodCatalog(client, cache, endpoint.cacheTtl());
    }

    private static WebClient webClient(WebClient.Builder builder, IntegrationProperties.Endpoint endpoint) {
        return builder.clone().baseUrl(endpoint.baseUrl().toString()).build();
    }
}

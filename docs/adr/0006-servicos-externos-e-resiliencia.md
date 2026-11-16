# 0006. Serviços externos atrás de portas, com timeout, retry e circuit breaker

Status: aceita em 14/11/2026.

## Contexto

"O BFF não é a fonte dos produtos nem dos meios de pagamento." Os serviços podem ser mocks no próprio
projeto, desde que o BFF os consuma por uma interface própria e não leia os dados direto no endpoint.

## Decisão

O núcleo define as portas `ProductCatalog` e `PaymentMethodCatalog`. Os adapters falam HTTP com
`WebClient`, e os dois serviços rodam em WireMock: como containers no compose e como servidor embutido nos
testes, com os mesmos mapeamentos (pasta `mocks/`). O payload de cada serviço tem modelo próprio (`title`,
`summary`, `pricing.amount`), diferente do contrato do BFF, e o adapter traduz. É essa tradução que
justifica um BFF.

Cada chamada passa por timeout de 1 segundo, circuit breaker e retry, nessa ordem de dentro para fora. O
retry fica por fora para que cada tentativa passe pelo circuit breaker e conte na janela dele.

- Repete só falha transitória: timeout, falha de conexão, 5xx e 429. Três tentativas, com backoff
  exponencial a partir de 100 ms.
- 4xx não repete nem conta como falha do serviço, porque o problema está no pedido.
- 404 de produto é resposta e não falha: vira `PRODUCT_NOT_FOUND`.
- Qualquer outra falha, inclusive payload inválido, vira `ServiceUnavailableException` e responde 503.
- Meio desabilitado é filtrado, e meio com código desconhecido é filtrado e logado, sem derrubar a lista.

Os operadores do Resilience4j entram explícitos na cadeia reativa, o que deixa a ordem visível no código e
testável.

## Alternativas consideradas

- **Implementação em memória da porta.** Mais simples, mas o cliente HTTP viraria uma classe trivial, o
  cache perderia o sentido e não haveria latência nem falha para simular.
- **Anotações `@CircuitBreaker` e `@Retry`.** Escondem a ordem dos operadores, que aqui importa.

## Consequências

- O pior caso de uma consulta com o serviço fora é de uns 3 segundos (três tentativas com timeout de 1
  segundo). O circuit breaker abre com 50% de falha numa janela de 20 chamadas (mínimo de 10), e a partir
  daí o 503 sai em milissegundos. Depois de 10 segundos ele passa a meio-aberto e fecha sozinho quando o
  serviço volta. Esse comportamento foi medido no compose.
- Os testes do cliente usam WireMock com cenários para provar cada regra: repetição até o sucesso, três
  tentativas e 503, uma tentativa só no 4xx, timeout, abertura do circuito e payload incompleto.

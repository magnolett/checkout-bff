# 0001. Spring WebFlux com MongoDB reativo

Status: aceita em 14/11/2026.

## Contexto

Um BFF é, antes de tudo, agregação de I/O. O pré-checkout consulta dois serviços e grava o carrinho; o
checkout lê e grava no banco e chama o gateway de pagamento. O enunciado deixa escolher entre PostgreSQL e
MongoDB, e a vaga pede programação reativa com destaque para MongoDB.

## Decisão

Spring WebFlux sobre Reactor Netty, `WebClient` para os serviços externos, Spring Data MongoDB reativo e o
cliente Lettuce reativo para o Redis. O carrinho é um documento só: o snapshot dos produtos e dos meios
ofertados, as tentativas de pagamento e os eventos que ainda não foram publicados.

A composição é declarativa. As duas consultas do pré-checkout correm juntas com `Mono.zip`, e timeout,
retry e circuit breaker entram como operadores na mesma cadeia. O carrinho cabe num documento, e o update
atômico e condicional de um documento resolve a concorrência do pagamento sem transação entre documentos
(ver o [ADR 0004](0004-estados-do-carrinho-e-concorrencia.md)). Um índice TTL apaga carrinho abandonado sem
precisar de job.

## Alternativas consideradas

- **Spring MVC com virtual threads e PostgreSQL.** O código imperativo é mais fácil de ler e de depurar, e
  escala igual para I/O. Virtual threads deixam barato o código bloqueante, mas não trazem composição de
  fluxos assíncronos nem backpressure: o paralelismo do pré-checkout teria de ser montado à mão, com
  `StructuredTaskScope` ou `CompletableFuture`. É uma escolha defensável e seria a minha para um serviço
  CRUD sem agregação.
- **PostgreSQL.** A concorrência sairia com coluna de versão ou `SELECT ... FOR UPDATE`. O modelo relacional
  pediria três ou quatro tabelas para o que aqui é um agregado lido e gravado inteiro.

## Consequências

- Nenhuma chamada bloqueante pode rodar no event loop. O envio ao Kafka, que pode bloquear até
  `max.block.ms` esperando metadados, roda no `boundedElastic`. O consumidor Kafka roda na thread dele e
  pode esperar a escrita no banco, que é o modelo esperado ali.
- Stack trace menos legível e contexto de log que não acompanha a thread. Os logs carregam `cartId` e
  `attemptId` explicitamente.
- Os testes usam `StepVerifier` e o tempo virtual do Reactor, que também prova o paralelismo sem depender
  de relógio de parede.

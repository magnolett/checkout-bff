# Registro de decisões de arquitetura

Cada ADR registra uma decisão com o contexto em que foi tomada, as alternativas consideradas e as
consequências aceitas. Todas foram tomadas em 14/11/2026, antes ou durante a implementação.

| ADR | Decisão |
|---|---|
| [0001](0001-stack-reativa.md) | Spring WebFlux com MongoDB reativo |
| [0002](0002-autorizacao-sincrona-e-eventos.md) | Autorização síncrona; ativação da assinatura por evento no Kafka |
| [0003](0003-falha-tecnica-em-5xx.md) | Falha técnica em 5xx com o mesmo objeto de erro |
| [0004](0004-estados-do-carrinho-e-concorrencia.md) | Máquina de estados, lock otimista e lease de processamento |
| [0005](0005-idempotencia-do-pagamento.md) | Retomada da tentativa pendente e `Idempotency-Key` opcional |
| [0006](0006-servicos-externos-e-resiliencia.md) | Serviços externos atrás de portas, com timeout, retry e circuit breaker |
| [0007](0007-cache-redis-fail-open.md) | Cache-aside no Redis que nunca derruba o checkout |
| [0008](0008-outbox-no-documento-do-carrinho.md) | Outbox embutido no documento do carrinho |
| [0009](0009-dado-de-cartao.md) | O que se valida e o que nunca se guarda do cartão |
| [0010](0010-java-25-e-spring-boot-4.md) | Java 25, Spring Boot 4.1 e Jackson 3 |

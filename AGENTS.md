# AGENTS.md

Instruções para agentes de código (Claude Code, Codex e afins) que trabalhem neste repositório. O contexto
do produto está no [README](README.md) e as decisões, com os motivos, em [`docs/adr`](docs/adr).

## Comandos

| Comando | Para quê |
|---|---|
| `./mvnw verify` | testes de unidade e de integração com Testcontainers (precisa do Docker); é a definição de pronto |
| `./mvnw test` | só os testes de unidade e de fatia web, sem Docker |
| `docker compose up -d --build --wait` | stack completa, API em http://localhost:8080 |
| `docker compose down -v` | derruba a stack e apaga os dados |

## Arquitetura

Pacotes sob `io.github.magnolett.checkoutbff`. As fronteiras são verificadas pelo `ArchitectureTest`: se ele
falhar, a mudança mexeu numa decisão de arquitetura, e quem decide é o dono do repositório, não o ajuste do
teste.

- `checkout.domain`: regras puras, só Java. Sem Spring, sem Reactor, sem anotação de framework.
- `checkout.port`: interfaces de que os casos de uso precisam. Só domínio e Reactor.
- `checkout.application`: casos de uso. Não conhece `bff`, `integration` nem `subscription`.
- `bff`: HTTP e contrato de erro. Não conhece os adapters.
- `integration`: adapters (HTTP, cache, gateway simulado, MongoDB, Kafka). Não conhece casos de uso nem web.
- `subscription`: consumidor no papel de outro serviço. Não importa nada do checkout; lê só o JSON do evento.

## Regras que não podem quebrar

- O número completo do cartão e o CVV nunca vão para banco, log, exceção, evento ou mensagem de validação.
  Tipo que carrega cartão tem `toString()` mascarado, e mensagem de erro cita o campo, nunca o valor.
- Erro de negócio é `CheckoutException` com um `CheckoutError`. O mapeamento para HTTP e para a tela fica no
  `bff` (`ErrorCode`), num `switch` exaustivo.
- Falha técnica de adapter vira `ServiceUnavailableException` (503). Falha ao autorizar pagamento também,
  porque o resultado ficou desconhecido e o retry é seguro.
- O objeto de erro tem sempre as mesmas chaves, inclusive as nulas.
- `checkout.processing-lease` precisa ser maior que `checkout.payment-timeout`; a aplicação não sobe se não
  for.
- Mudança de estado do carrinho e o evento dela saem no mesmo update, pelo outbox.
- Dinheiro em `BigDecimal` com escala 2. Horários em UTC, truncados no segundo.

## Convenções

- Código, identificadores, logs e comentários em inglês. README, ADRs e textos de tela em português.
- Toda regra nova ganha teste na camada certa: domínio em JUnit puro, caso de uso com o repositório em
  memória, contrato com `@WebFluxTest`, adapter com WireMock, fluxo com Testcontainers (classes `*IT`).
- Código reativo não bloqueia no event loop. Chamada que pode bloquear roda no `boundedElastic`.
- Decisão de arquitetura nova vira ADR em `docs/adr`.
- Mensagem de commit em inglês, no imperativo. Commit feito com agente leva o trailer `Co-Authored-By`.

## Armadilhas conhecidas

- Jackson 3 usa o pacote `tools.jackson`, mas as anotações continuam em `com.fasterxml.jackson.annotation`.
- No Boot 4.1 o `WebClient.Builder` só existe com o `spring-boot-starter-webclient`, e a URI do MongoDB fica
  em `spring.mongodb.uri`.
- O Spring Kafka 4 trocou o sufixo padrão da DLT para `-dlt`. O nome `.DLT` é explícito em
  `SubscriptionKafkaConfig`; não volte para o padrão da biblioteca.
- No Git Bash do Windows, mande corpo JSON com acento pelo stdin (`--data-binary @-`) e prefixe
  `docker compose exec` com `MSYS_NO_PATHCONV=1` quando o comando tiver caminho absoluto.

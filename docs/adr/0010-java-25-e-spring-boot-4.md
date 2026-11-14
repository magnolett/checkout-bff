# 0010. Java 25, Spring Boot 4.1 e Jackson 3

Status: aceita em 14/11/2026.

## Contexto

Escolha de versões para um projeto novo, em novembro de 2026.

## Decisão

Java 25, a LTS vigente, e Spring Boot 4.1.2, a versão estável mais recente (a 4.2 estava em release
candidate). Com o Boot 4 vêm Spring Framework 7, Jackson 3, Spring Kafka 4 e Spring Data MongoDB 5. Os
testes usam Testcontainers 2, WireMock 3 e ArchUnit.

Do Java moderno, o código usa records no domínio e nos DTOs, e `sealed interface` com `switch` exaustivo
na autorização, nos eventos e no mapeamento de erro para HTTP. Um erro de negócio novo sem tela definida não
compila.

## Alternativas consideradas

- **Java 21 com Spring Boot 3.5.** Há mais material publicado, mas começar um projeto novo na linha
  anterior é contratar a migração no primeiro dia.

## Consequências

Armadilhas encontradas no caminho, registradas para quem mexer depois:

- **Jackson 3** mudou de pacote para `tools.jackson`, mas as anotações continuam em
  `com.fasterxml.jackson.annotation`. A exceção base, `JacksonException`, deixou de ser checada.
- **O Boot 4.1 é modular.** O `WebClient.Builder` auto-configurado só existe com o
  `spring-boot-starter-webclient`, e a URI do MongoDB passou para `spring.mongodb.uri`.
- **O Spring Kafka 4 trocou o sufixo padrão da DLT de `.DLT` para `-dlt`.** O teste de integração pegou: o
  tópico criado era `checkout.payment-events.DLT` e o recoverer publicava em `checkout.payment-events-dlt`,
  que não existia. Em produção, com a criação automática de tópicos desligada, a mensagem envenenada não
  teria para onde ir. O nome agora é explícito no resolver do consumidor, que também declara o tópico.
- **O Testcontainers 2** organizou os containers por módulo (`org.testcontainers.mongodb`,
  `org.testcontainers.kafka`).

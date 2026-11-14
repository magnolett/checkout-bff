# 0002. Autorização síncrona; ativação da assinatura por evento

Status: aceita em 14/11/2026.

## Contexto

O contrato do checkout devolve sucesso ou erro na mesma chamada, e a tela de sucesso precisa de `paidAt`
e dos últimos dígitos. O enunciado lista como diferencial "eventos assíncronos (RabbitMQ ou Kafka) para
processar o pagamento".

## Decisão

A autorização do pagamento é síncrona, dentro da requisição. O que é assíncrono por natureza vai por
evento: o pagamento aprovado gera `PAYMENT_APPROVED` e a recusa gera `PAYMENT_DECLINED`, no tópico
`checkout.payment-events`, com chave `cartId` para manter a ordem dos eventos de um mesmo carrinho. O
evento carrega `schemaVersion` e não carrega dado de cartão.

Um consumidor separado, no pacote `subscription`, faz o papel de outro serviço e ativa a assinatura. Ele lê
o evento com um modelo próprio, tolerante a campo novo, e não importa nenhuma classe do produtor. O evento
sai do banco para o Kafka por outbox ([ADR 0008](0008-outbox-no-documento-do-carrinho.md)).

## Alternativas consideradas

- **Request-reply sobre Kafka.** Atende a letra do diferencial, mas é síncrono por cima de assíncrono: a
  latência da tela passa a depender do broker, e aparece um estado "em processamento" que o contrato não
  tem.
- **202 Accepted com consulta posterior.** É o modelo certo para pagamento assíncrono de verdade (o Pix real
  funciona assim, confirmado por webhook), mas troca o contrato de sucesso que o enunciado fixou.

Kafka e não RabbitMQ: o log persistente permite replay, a partição por chave garante a ordem por carrinho,
e consumidores independentes (assinatura, notificação, conciliação) podem ler o mesmo tópico sem que o
produtor saiba deles.

## Consequências

- A entrega é pelo menos uma vez, então o consumidor é idempotente: upsert com `$setOnInsert` e
  `_id = cartId`. Evento repetido não muda a assinatura nem é tratado como falha.
- Mensagem que não dá para processar é repetida duas vezes, com um segundo de intervalo, e depois vai para
  a DLT `checkout.payment-events.DLT` com o erro nos headers. JSON inválido vai direto, porque repetir não
  conserta. A DLT pertence ao consumidor e é declarada por ele, com o nome explícito (ver o
  [ADR 0010](0010-java-25-e-spring-boot-4.md)).
- Um campo novo no evento não quebra o consumidor; mudança incompatível sobe o `schemaVersion`.

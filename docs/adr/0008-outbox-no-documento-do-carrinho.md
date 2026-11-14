# 0008. Outbox embutido no documento do carrinho

Status: aceita em 14/11/2026.

## Contexto

Gravar o novo estado do carrinho e publicar o evento são duas operações. Sem cuidado, uma acontece e a
outra não: o pagamento fica aprovado e a assinatura nunca é ativada, ou o evento sai e o estado não foi
gravado.

## Decisão

O evento entra num array `outbox` do próprio documento do carrinho, no mesmo update atômico que muda o
estado. Um relay agendado, a cada segundo:

1. reivindica um carrinho com evento pendente por `findAndModify`, marcando `outboxClaimedUntil` por 30
   segundos;
2. publica os eventos em ordem, com chave `cartId`;
3. remove os publicados com `$pull` pelo `eventId` e limpa a reivindicação.

Se a publicação falha, a reivindicação fica até vencer e serve de backoff, e a rodada para. Um índice
parcial em `outboxClaimedUntil`, só para documentos com outbox não vazio, mantém a busca barata.

No MongoDB o update de um documento é atômico, então estado e evento nunca divergem, sem transação
multi-documento. O `$pull` usa operadores pontuais e não mexe na versão, então nunca conflita com o update
condicional do pagamento. Várias instâncias podem rodar o relay: a reivindicação evita publicação duplicada
no caminho normal.

## Alternativas consideradas

- **Coleção `outbox` separada, gravada em transação.** É o padrão clássico, mas pede transação
  multi-documento para cada pagamento.
- **CDC com Change Streams ou Debezium.** Mais infraestrutura para operar.
- **Publicar direto depois do update.** Perde o evento se a instância cair entre as duas operações.

## Consequências

- A entrega é pelo menos uma vez: se a instância cair depois de publicar e antes do `$pull`, o evento sai de
  novo. O consumidor é idempotente ([ADR 0002](0002-autorizacao-sincrona-e-eventos.md)).
- Entre o pagamento e o evento passa até cerca de um segundo.
- O teste de integração cobre a ordem dos eventos de um carrinho, a remoção do outbox e o respeito à
  reivindicação de outra instância.

# 0004. Máquina de estados, lock otimista e lease de processamento

Status: aceita em 14/11/2026.

## Contexto

Quatro exigências moldam o ciclo de vida do carrinho:

- a recusa não encerra o carrinho, porque a tela oferece "Tentar novamente" e "Trocar meio de pagamento";
- um carrinho aprovado não pode ser pago de novo;
- duas requisições simultâneas no mesmo carrinho (o duplo clique) não podem cobrar duas vezes;
- a instância pode cair no meio da cobrança, e o carrinho não pode ficar travado por isso.

## Decisão

Estados `OPEN`, `PROCESSING`, `PAID` e `DECLINED`. `DECLINED` aceita nova tentativa e `PAID` é terminal.

A passagem para `PROCESSING` é um update condicional pelo `_id` e pela `version` do documento, com
operadores pontuais (`$set`, `$inc`, `$push`). Só um pedido ganha. Quem perde recebe
`PAYMENT_IN_PROGRESS` (409, `INTERVENTION`, botão "Tentar novamente").

`PROCESSING` tem um lease de 30 segundos. Se a instância cair, o lease vence e o carrinho volta a aceitar
pagamento. O lease precisa ser maior que o prazo dado ao gateway (10 segundos), e a aplicação se recusa a
subir se a configuração disser o contrário: enquanto a primeira chamada ao gateway pode estar viva, ninguém
começa outra.

O snapshot vale 30 minutos (`CART_EXPIRED` depois disso). Um índice TTL apaga o carrinho não pago 24 horas
depois de vencido; o carrinho pago nunca é apagado.

As regras moram no domínio (`Cart`), em Java puro e testável sem banco. A garantia de exclusão mora no
update condicional do repositório.

## Alternativas consideradas

- **Lock distribuído no Redis.** Mais uma peça para falhar, e o Redis aqui é opcional por desenho
  ([ADR 0007](0007-cache-redis-fail-open.md)).
- **Transação com lock pessimista.** O MongoDB não tem `SELECT ... FOR UPDATE`, e uma transação
  multi-documento custaria latência para proteger o que um documento já protege sozinho.
- **Fila por carrinho.** Serializa os pagamentos, mas acrescenta infraestrutura sem ganho sobre o update
  condicional.

## Consequências

- O teste de integração dispara dois pagamentos ao mesmo tempo, com o gateway simulado lento, e confere um
  200, um 409 e uma única tentativa gravada.
- O evento de cada transição entra no mesmo update ([ADR 0008](0008-outbox-no-documento-do-carrinho.md)).

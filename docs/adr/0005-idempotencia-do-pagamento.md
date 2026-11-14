# 0005. Retomada da tentativa pendente e `Idempotency-Key` opcional

Status: aceita em 14/11/2026.

## Contexto

O botão "Tentar novamente" com `url: RETRY` cria dois casos difíceis:

- o pagamento aprova, a resposta se perde, e o front repete o pedido. Sem cuidado, ele recebe
  `CART_ALREADY_PAID` numa compra que deu certo;
- o gateway não responde a tempo. O resultado é desconhecido: o provedor pode ter cobrado. Uma nova
  tentativa ingênua cobraria de novo.

## Decisão

Três peças que se completam.

1. **O `attemptId` é a chave de idempotência do provedor.** Cada tentativa de pagamento tem um id, enviado
   ao gateway. O gateway simulado responde igual para o mesmo id, como os provedores reais fazem.
2. **Falha técnica deixa a tentativa pendente, e o retry a retoma.** Se o gateway falha ou passa do prazo, o
   lease é liberado, a tentativa continua `PENDING` e o cliente recebe 503. Um novo pedido com a mesma
   impressão digital (meio, últimos dígitos e validade, nunca o número completo) retoma a mesma tentativa,
   com o mesmo `attemptId`, inclusive depois de o carrinho expirar. Um cartão diferente abandona a tentativa
   pendente e abre outra.
3. **Header opcional `Idempotency-Key`.** Com a mesma chave no mesmo carrinho, a tentativa já gravada decide
   a resposta:

   | Estado da tentativa | Resposta |
   |---|---|
   | `APPROVED` | o recibo original, com o mesmo `paidAt` |
   | `DECLINED` | a mesma recusa |
   | `PENDING` com lease ativo | `PAYMENT_IN_PROGRESS` |
   | `PENDING` sem lease, ou `ABANDONED` | segue para a cobrança, que retoma a tentativa |
   | mesma chave com outra impressão digital | 422 `IDEMPOTENCY_KEY_REUSED` |

As peças 1 e 2 fecham a cobrança dupla do lado do provedor mesmo para quem não manda o header. A peça 3
devolve ao front a resposta original no lugar de `CART_ALREADY_PAID`. A regra para o front fica simples:
uma chave por intenção de pagamento. Depois de uma recusa, chave nova; depois de timeout ou 503, a mesma.

## Alternativas consideradas

- **Header obrigatório.** Quebraria o contrato do enunciado.
- **Idempotência só pelo header.** Não protege quem não manda o header, e o timeout do gateway continuaria
  aberto.
- **Respostas guardadas no Redis com TTL.** Separa a idempotência do estado do carrinho, e o Redis aqui é
  opcional.

## Consequências

- O prazo do gateway (10 segundos) menor que o lease (30 segundos) é parte da garantia, e é validado na
  partida ([ADR 0004](0004-estados-do-carrinho-e-concorrencia.md)).
- Risco residual, fora do escopo: a tentativa pendente que o usuário nunca repete fica sem desfecho. Em
  produção, um job de conciliação consultaria o provedor pelo `attemptId` antes de considerá-la abandonada,
  e a troca de cartão também conciliaria antes de abandonar a anterior.

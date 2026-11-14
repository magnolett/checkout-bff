# 0005. Retomada da tentativa pendente e `Idempotency-Key` opcional

Status: aceita em 14/11/2026 e revista no mesmo dia, depois da revisão independente, com a consulta de status
antes da troca de meio de pagamento.

## Contexto

O botão "Tentar novamente" com `url: RETRY` cria dois casos difíceis:

- o pagamento aprova, a resposta se perde, e o front repete o pedido. Sem cuidado, ele recebe
  `CART_ALREADY_PAID` numa compra que deu certo;
- o gateway não responde a tempo. O resultado é desconhecido: o provedor pode ter cobrado. Uma nova
  tentativa ingênua cobraria de novo, e o cliente pode voltar com o mesmo cartão ou com outro meio.

## Decisão

Quatro peças que se completam.

1. **O `attemptId` é a chave de idempotência do provedor.** Cada tentativa de pagamento tem um id, enviado
   ao gateway. O gateway simulado responde igual para o mesmo id, como os provedores reais fazem.
2. **Falha técnica deixa a tentativa pendente, e o retry com o mesmo instrumento a retoma.** Se o gateway
   falha ou passa do prazo, o lease é liberado, a tentativa continua `PENDING` e o cliente recebe 503. Um
   novo pedido com a mesma impressão digital (meio, últimos dígitos e validade, nunca o número completo)
   retoma a mesma tentativa, com o mesmo `attemptId`, inclusive depois de o carrinho expirar.
3. **Outro instrumento só entra depois de o provedor dizer o que houve com a tentativa pendente.** O
   checkout consulta o status dela pelo `attemptId`, a consulta que todo provedor oferece pela chave de
   idempotência, e grava o desfecho antes de qualquer outra coisa:

   | O provedor diz | O que acontece |
   |---|---|
   | aprovou | o carrinho fecha com aquela tentativa, com evento e assinatura; o pedido responde `CART_ALREADY_PAID` e o novo meio não é tocado |
   | recusou | a recusa é registrada e o novo meio segue |
   | nunca recebeu | a tentativa é abandonada e o novo meio segue |
   | não responde no prazo | 503, e nada muda |

   O domínio reforça a regra: ele se recusa a abrir uma tentativa nova enquanto houver uma pendente sem
   desfecho, então esquecer a consulta é erro de programação e não cobrança dupla.
4. **Header opcional `Idempotency-Key`.** Com a mesma chave no mesmo carrinho, a tentativa já gravada decide
   a resposta:

   | Estado da tentativa | Resposta |
   |---|---|
   | `APPROVED` | o recibo original, com o mesmo `paidAt` |
   | `DECLINED` | a mesma recusa |
   | `PENDING` com lease ativo | `PAYMENT_IN_PROGRESS` |
   | `PENDING` sem lease, ou `ABANDONED` | segue para a cobrança, que retoma a tentativa |
   | mesma chave com outra impressão digital | 422 `IDEMPOTENCY_KEY_REUSED` |

   A tentativa guarda todas as chaves que a alcançaram. Se o front gera chave nova depois de um 503 e
   retoma com o mesmo cartão, repetir qualquer uma das duas devolve o mesmo desfecho.

As peças 1 a 3 fecham a cobrança dupla do lado do provedor mesmo para quem não manda o header. A peça 4
devolve ao front a resposta original no lugar de `CART_ALREADY_PAID`. A regra para o front fica simples:
uma chave por intenção de pagamento. Depois de uma recusa, chave nova; depois de timeout ou 503, a mesma.

Quando o provedor responde e a gravação do desfecho falha, o carrinho relido decide a resposta. Desfecho
gravado, porque a escrita entrou e só a confirmação se perdeu ou porque outra requisição terminou a mesma
tentativa: recibo ou recusa. Tentativa ainda pendente com outra requisição no controle: 409
`PAYMENT_IN_PROGRESS`. Banco fora: o lease é liberado e o cliente recebe 503, para que o retry retome a
tentativa na hora em vez de esperar o lease acabar.

## Alternativas consideradas

- **Header obrigatório.** Quebraria o contrato do enunciado.
- **Idempotência só pelo header.** Não protege quem não manda o header, e o timeout do gateway continuaria
  aberto.
- **Respostas guardadas no Redis com TTL.** Separa a idempotência do estado do carrinho, e o Redis aqui é
  opcional.
- **Bloquear a troca de meio enquanto houver tentativa pendente.** Seguro, mas deixa preso o cliente que não
  tem mais o primeiro cartão à mão, e a consulta de status resolve o mesmo problema sem esse custo.

## Consequências

- O prazo do gateway (10 segundos) menor que o lease (30 segundos) é parte da garantia, e é validado na
  partida ([ADR 0004](0004-estados-do-carrinho-e-concorrencia.md)). A consulta de status usa o mesmo prazo.
- A troca de meio depois de uma resposta perdida custa uma chamada a mais ao provedor. É um caminho raro,
  e é exatamente onde o custo de errar é cobrar duas vezes.
- Risco residual, fora do escopo: a tentativa pendente que o usuário nunca repete fica sem desfecho até um
  job de conciliação consultar o provedor. E existe uma corrida que a consulta não fecha, a requisição
  original ainda a caminho do provedor quando ele responde que nunca a recebeu. Em produção isso se fecha
  na conciliação com o relatório de liquidação, com estorno automático da cobrança tardia.

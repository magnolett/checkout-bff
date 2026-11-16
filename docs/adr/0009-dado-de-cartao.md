# 0009. O que se valida e o que nunca se guarda do cartão

Status: aceita em 14/11/2026.

## Contexto

O enunciado manda não persistir o número completo do cartão nem o CVV, e devolver no sucesso só o que a
tela precisa. O cartão do próprio exemplo, `4111111111114242`, não passa no algoritmo de Luhn.

## Decisão

**Validação de formato, sem Luhn.** Titular presente, número com 13 a 19 dígitos, CVV com 3 ou 4 dígitos e
validade `MM/AA` não vencida, comparada com o mês corrente em UTC. Quem valida o número de verdade é o
emissor, pelo gateway. Com Luhn, quem testasse com o exemplo do enunciado receberia erro.

**A validação mora no domínio.** Exigir ou proibir cartão depende do meio escolhido (crédito e débito
exigem, Pix proíbe), então quem valida é o carrinho e não o Bean Validation. O erro sai como
`INVALID_PAYMENT_DATA`; o nome do campo vai para o log, nunca o valor, e a resposta mantém o objeto de
erro padrão.

**Nada sensível sai da requisição.** O número completo e o CVV não são persistidos, logados nem
publicados. O carrinho guarda os últimos quatro dígitos e uma impressão digital, o SHA-256 do meio, dos
últimos dígitos e da validade, usada só para reconhecer a retomada de uma tentativa
([ADR 0005](0005-idempotencia-do-pagamento.md)). O `toString()` do cartão e do DTO de entrada é mascarado.

**Simulação.** Cartão terminado em `0002` é recusado e qualquer outro é aprovado, com o id da transação
derivado do `attemptId`.

## Alternativas consideradas

- **Hash do número completo como impressão digital.** O espaço de números possíveis é pequeno o bastante
  para força bruta quando se conhece o BIN, e o hash puxaria o banco para o escopo PCI.
- **Validar Luhn.** Rejeitaria o exemplo do enunciado sem ganho real, pelo motivo acima.

## Consequências

- Dois cartões diferentes com os mesmos últimos dígitos e a mesma validade seriam tratados como o mesmo na
  retomada. O efeito é reaproveitar o `attemptId` pendente, e o provedor responderia pela tentativa
  original. Não há cobrança dupla, e o caso é raro: risco aceito. Um provedor real que compara o payload de
  cada chave de idempotência recusaria essa retomada como conflito. O adaptador dele trataria o conflito
  como troca de meio: consulta o status da tentativa e segue o que o [ADR 0005](0005-idempotencia-do-pagamento.md)
  decide para esse caso.
- Num checkout real o número nem chegaria ao BFF: o front tokeniza o cartão direto no provedor, e o BFF
  recebe só o token.
- Há teste para isso: o teste de integração procura o número completo no documento cru do MongoDB, e os
  testes do controller capturam o log e conferem a ausência do número e do CVV.

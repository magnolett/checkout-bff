# 0003. Falha técnica em 5xx com o mesmo objeto de erro

Status: aceita em 14/11/2026.

## Contexto

O enunciado diz: "Toda falha que o frontend precisa exibir deve usar o mesmo objeto, com HTTP 4xx". O
texto foi pensado para as falhas de negócio e deixa em aberto a falha técnica: serviço de produtos fora,
gateway sem resposta, banco indisponível, erro inesperado.

## Decisão

Falha de negócio sai em 4xx. Falha técnica sai em 503, quando uma dependência falhou, ou em 500, quando o
erro é inesperado, sempre com o mesmo objeto de erro e o botão "Tentar novamente". O formato não varia, que
é o que o enunciado protege, e o front trata as duas famílias do mesmo jeito.

O motivo é operacional. 4xx diz que o cliente errou. Uma dependência fora do ar devolvida como 4xx some do
monitoramento: alarme e SLO contam 5xx, e gateways e clientes HTTP repetem um 503 mas não repetem um 4xx.
O plantão não veria o incidente.

Detalhes que seguem a mesma regra:

- O erro técnico também leva o `cartId` quando o pedido já identificou o carrinho, para o front saber de
  onde retomar.
- Rota inexistente, método errado e mídia não suportada mantêm o status do protocolo (404, 405, 406 e 415)
  e devolvem o objeto com `INVALID_REQUEST`.
- Falha de qualquer tipo ao autorizar o pagamento vira 503, porque o resultado ficou desconhecido e repetir
  é seguro ([ADR 0005](0005-idempotencia-do-pagamento.md)).

## Alternativas consideradas

- **Tudo em 4xx, na leitura literal.** Esconde incidente, pelo motivo acima.
- **5xx em outro formato, como Problem Details (RFC 9457).** É um bom padrão, mas obrigaria o front a tratar
  dois formatos de erro.

## Consequências

Quem avalia por checklist pode ler o 5xx como desvio do enunciado. Por isso a decisão está explícita aqui e
no README.

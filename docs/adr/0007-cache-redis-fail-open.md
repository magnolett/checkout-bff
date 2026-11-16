# 0007. Cache-aside no Redis que nunca derruba o checkout

Status: aceita em 14/11/2026.

## Contexto

Catálogo e meios de pagamento mudam pouco e são lidos em todo pré-checkout. O enunciado sugere cache com
Redis nessas consultas.

## Decisão

Cache-aside explícito, em decorators das portas (`CachingProductCatalog` e `CachingPaymentMethodCatalog`).

- Chaves versionadas: `checkout-bff:v1:product:<slug>` e `checkout-bff:v1:payment-methods`. Mudar o formato
  do valor é mudar o prefixo, sem conflito com o que já está gravado.
- TTL de 10 minutos para produto e de 5 minutos para os meios, configuráveis.
- Só acerto entra no cache. Produto inexistente e lista vazia de meios não entram, para que um erro
  momentâneo do serviço não fique gravado como resposta.
- Falha aberta: qualquer problema com o Redis (fora do ar, sem resposta no timeout de comando do Lettuce,
  de 250 ms, valor que não desserializa) vira cache miss, e a consulta segue para o serviço. Escrita que
  falha é ignorada.
- A readiness não inclui o Redis.

O cache é otimização e não pode virar ponto único de falha do checkout. O checkout cobra o snapshot gravado
no carrinho, então o cliente paga o preço que viu; como o snapshot nasce do catálogo, esse preço pode ter até
10 minutos de defasagem.

## Alternativas consideradas

- **`@Cacheable`.** Menos explícito sobre o comportamento em falha e sobre o que não se guarda, e a
  integração com tipos reativos é menos direta.
- **Cache local (Caffeine).** Não é compartilhado entre instâncias. Poderia entrar como primeiro nível na
  frente do Redis.

## Consequências

- A tela pode mostrar um catálogo com até 10 minutos de defasagem, aceitável para planos de assinatura.
  Invalidação por evento do serviço de catálogo seria o próximo passo.
- Com o cache aquecido, o pré-checkout sobrevive a uma queda curta do serviço de produtos.

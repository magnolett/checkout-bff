package io.github.magnolett.checkoutbff.bff;

/** JSON examples shown in the OpenAPI documentation (Swagger UI). */
final class ApiExamples {

    private ApiExamples() {
    }

    static final String PRE_CHECKOUT_OK = """
            {"cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90","products":[{"slug":"premium","name":"Plano Premium",\
            "description":"Assinatura mensal com catálogo completo","price":39.90,"currency":"BRL"}],\
            "paymentMethods":[{"name":"Cartão de Crédito"},{"name":"Cartão de Débito"},{"name":"Pix"}]}""";

    static final String CHECKOUT_CARD_OK = """
            {"cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90","status":"APROVADO","paidAt":"2026-10-14T16:00:00Z",\
            "payment":{"method":"Cartão de Crédito","lastDigits":"4242","pixCode":null,"amount":39.90,"currency":"BRL"},\
            "products":[{"slug":"premium","name":"Plano Premium","price":39.90}]}""";

    static final String CHECKOUT_PIX_OK = """
            {"cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90","status":"APROVADO","paidAt":"2026-10-14T16:00:00Z",\
            "payment":{"method":"Pix","lastDigits":null,"pixCode":"E99999999202610141600K3J9QX2M7PA",\
            "amount":39.90,"currency":"BRL"},"products":[{"slug":"premium","name":"Plano Premium","price":39.90}]}""";

    static final String PRODUCT_NOT_FOUND = """
            {"displayType":"BLOCK","errorCode":"PRODUCT_NOT_FOUND","cartId":null,"frontendError":{"code":"C-1001",\
            "title":"Plano indisponível","description":"O plano escolhido não está disponível. Escolha outro plano \
            para continuar.","primaryButton":{"label":"Ver planos","url":"/planos"},"secondaryButton":null}}""";

    static final String PAYMENT_DECLINED = """
            {"displayType":"BLOCK","errorCode":"PAYMENT_DECLINED","cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90",\
            "frontendError":{"code":"P-1001","title":"Pagamento não aprovado","description":"Não foi possível \
            concluir o pagamento. Tente novamente ou escolha outro meio.","primaryButton":{"label":"Tentar \
            novamente","url":"RETRY"},"secondaryButton":{"label":"Trocar meio de pagamento",\
            "url":"/checkout/pagamento"}}}""";

    static final String CART_NOT_FOUND = """
            {"displayType":"BLOCK","errorCode":"CART_NOT_FOUND","cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90",\
            "frontendError":{"code":"C-1002","title":"Não encontramos seu pedido","description":"Este carrinho não \
            existe ou já foi encerrado. Escolha seu plano novamente.","primaryButton":{"label":"Escolher plano",\
            "url":"/planos"},"secondaryButton":null}}""";

    static final String CART_ALREADY_PAID = """
            {"displayType":"BLOCK","errorCode":"CART_ALREADY_PAID","cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90",\
            "frontendError":{"code":"C-1004","title":"Este pedido já foi pago","description":"O pagamento deste \
            carrinho já foi aprovado e a sua assinatura está ativa.","primaryButton":{"label":"Acessar minha conta",\
            "url":"/minha-conta"},"secondaryButton":null}}""";

    static final String INVALID_PAYMENT_DATA = """
            {"displayType":"INTERVENTION","errorCode":"INVALID_PAYMENT_DATA",\
            "cartId":"3f8a2c1e-9b4d-4e7a-8f1c-2d5b6a7c8e90","frontendError":{"code":"P-1003",\
            "title":"Confira os dados do pagamento","description":"Alguns dados estão incompletos ou inválidos. \
            Revise e tente novamente.","primaryButton":{"label":"Revisar dados","url":"/checkout/pagamento"},\
            "secondaryButton":null}}""";

    static final String SERVICE_UNAVAILABLE = """
            {"displayType":"BLOCK","errorCode":"SERVICE_UNAVAILABLE","cartId":null,"frontendError":{"code":"S-1001",\
            "title":"Serviço temporariamente indisponível","description":"Não conseguimos carregar as informações \
            agora. Tente novamente em instantes.","primaryButton":{"label":"Tentar novamente","url":"RETRY"},\
            "secondaryButton":null}}""";
}

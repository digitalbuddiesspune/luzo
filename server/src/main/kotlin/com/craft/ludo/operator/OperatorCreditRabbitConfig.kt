package com.craft.ludo.operator

import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.CustomExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * RabbitMQ topology + template used to **publish** winner/refund credits.
 *
 * Per the operator handoff, Ludo publishes to the delayed exchange / `games_cashout`
 * routing key. The operator platform owns the consumer that credits the real wallet.
 *
 * Topology declaration is optional (`app.operator.credit-declare-topology`) so
 * production can rely on the platform-owned exchange/queue.
 */
@Configuration
class OperatorCreditRabbitConfig(
    private val appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(OperatorCreditRabbitConfig::class.java)
    private val operator = appProperties.operator

    @Bean
    fun operatorCreditMessageConverter(objectMapper: ObjectMapper): Jackson2JsonMessageConverter {
        return Jackson2JsonMessageConverter(objectMapper)
    }

    @Bean
    @Primary
    @Qualifier("operatorCreditRabbitTemplate")
    fun operatorCreditRabbitTemplate(
        connectionFactory: ConnectionFactory,
        operatorCreditMessageConverter: Jackson2JsonMessageConverter,
    ): RabbitTemplate {
        val template = RabbitTemplate(connectionFactory)
        template.messageConverter = operatorCreditMessageConverter
        template.setMandatory(true)
        log.info(
            "Operator credit RabbitTemplate ready exchange={} routingKey={} queue={}",
            operator.creditExchange,
            operator.creditRoutingKey,
            operator.creditQueueName,
        )
        return template
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.operator",
        name = ["credit-declare-topology"],
        havingValue = "true",
    )
    fun operatorCreditDelayedExchange(): CustomExchange {
        return CustomExchange(
            operator.creditExchange,
            "x-delayed-message",
            true,
            false,
            mapOf("x-delayed-type" to "direct"),
        )
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.operator",
        name = ["credit-declare-topology"],
        havingValue = "true",
    )
    fun operatorCreditQueue(): Queue {
        return QueueBuilder.durable(operator.creditQueueName)
            .withArgument("x-dead-letter-exchange", operator.creditDeadLetterExchange)
            .withArgument("x-dead-letter-routing-key", operator.creditDeadLetterRoutingKey)
            .build()
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.operator",
        name = ["credit-declare-topology"],
        havingValue = "true",
    )
    fun operatorCreditDeadLetterQueue(): Queue {
        return QueueBuilder.durable(operator.creditDeadLetterQueue).build()
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "app.operator",
        name = ["credit-declare-topology"],
        havingValue = "true",
    )
    fun operatorCreditBinding(
        operatorCreditQueue: Queue,
        operatorCreditDelayedExchange: CustomExchange,
    ): Binding {
        return BindingBuilder
            .bind(operatorCreditQueue)
            .to(operatorCreditDelayedExchange)
            .with(operator.creditRoutingKey)
            .noargs()
    }
}

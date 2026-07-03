package com.craft.ludo.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory
import org.springframework.data.mongodb.ReactiveMongoTransactionManager
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import java.time.Clock

@Configuration
class PersistenceConfig {
    @Bean
    fun reactiveTransactionManager(
        databaseFactory: ReactiveMongoDatabaseFactory,
    ): ReactiveTransactionManager = ReactiveMongoTransactionManager(databaseFactory)

    @Bean
    fun transactionalOperator(
        transactionManager: ReactiveTransactionManager,
    ): TransactionalOperator = TransactionalOperator.create(transactionManager)

    @Bean
    fun appClock(): Clock = Clock.systemUTC()
}

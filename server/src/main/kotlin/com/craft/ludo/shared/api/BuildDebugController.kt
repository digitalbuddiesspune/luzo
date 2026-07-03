package com.craft.ludo.shared.api

import com.craft.ludo.shared.support.newId
import com.craft.ludo.wallet.WalletService
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class BuildDebugResponse(
    val marker: String = "ludo-roomfee-newid-starting-state",
    val roomFeeIdMode: String = "newId(roomfee)",
    val builtFrom: String = "C:\\Users\\User\\Desktop\\Craft Projects\\Ludo\\server",
    val sampleRoomFeeId: String = newId("roomfee"),
    val walletServiceClassName: String,
    val walletServiceCodeSource: String?,
    val walletServiceClassLoader: String,
    val walletServiceBeanNames: List<String>,
    val checkedAt: Instant = Instant.now(),
)

@RestController
@RequestMapping("/api/v1/debug", produces = [MediaType.APPLICATION_JSON_VALUE])
class BuildDebugController(
    private val applicationContext: ApplicationContext,
    private val walletService: WalletService,
) {
    @GetMapping("/build")
    fun build(): BuildDebugResponse {
        val walletServiceClass = walletService::class.java
        return BuildDebugResponse(
            walletServiceClassName = walletServiceClass.name,
            walletServiceCodeSource = walletServiceClass.protectionDomain.codeSource?.location?.toString(),
            walletServiceClassLoader = walletServiceClass.classLoader.toString(),
            walletServiceBeanNames = applicationContext.getBeanNamesForType(WalletService::class.java).toList(),
        )
    }
}

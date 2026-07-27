package com.craft.ludo.gameplay.bot

import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.config.BotProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SuperiorBotConfiguration(
    private val appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun applyBotSettings() {
        val bot = appProperties.gameplay.bot
        SuperiorBotEngine.difficulty = parseDifficulty(bot.difficulty)
        SuperiorBotEngine.weights = bot.toRewardWeights()
        log.info(
            "Superior bot configured difficulty={} expectimaxDepth={} maxDecisionMillis={}",
            SuperiorBotEngine.difficulty,
            SuperiorBotEngine.weights.expectimaxDepth,
            SuperiorBotEngine.weights.maxDecisionMillis,
        )
    }
}

fun BotProperties.toRewardWeights(): BotRewardWeights = BotRewardWeights(
    winGame = winGame,
    tokenHome = tokenHome,
    captureBase = captureBase,
    escapeThreat = escapeThreat,
    createBlockade = createBlockade,
    breakOpponentBlockade = breakOpponentBlockade,
    enterHomePath = enterHomePath,
    landSafe = landSafe,
    saveThreatened = saveThreatened,
    createCaptureThreat = createCaptureThreat,
    maintainBlockade = maintainBlockade,
    leaveBase = leaveBase,
    progressPerStep = progressPerStep,
    exposeToCapture = exposeToCapture,
    multiOpponentDanger = multiOpponentDanger,
    breakOwnBlockade = breakOwnBlockade,
    ignoreGuaranteedCapture = ignoreGuaranteedCapture,
    leaveSafetyIntoDanger = leaveSafetyIntoDanger,
    twoPlayerAttackMultiplier = twoPlayerAttackMultiplier,
    expectimaxDepth = expectimaxDepth,
    maxDecisionMillis = maxDecisionMillis,
)

fun parseDifficulty(raw: String): BotDifficulty =
    runCatching { BotDifficulty.valueOf(raw.trim().uppercase()) }
        .getOrDefault(BotDifficulty.SUPER)

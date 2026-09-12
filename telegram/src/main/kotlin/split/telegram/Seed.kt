package split.telegram

import split.core.MemberRepository
import split.storage.ExposedExpenseRepository
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory
import split.storage.connectDatabaseFromEnv

/**
 * Applies a named [Scenario] to the database `SPLIT_DB_PATH` points at, so the multi-member flows
 * can be exercised without recruiting real Telegram accounts.
 *
 * Usage: `./gradlew :telegram:seed --args="<chatId> <scenario>"`, or `--args="list"` to see what's
 * available. The chat id is the one the bot logs on every command. Re-running is safe: members are
 * resolved by identity, so anyone already present is left alone.
 */
suspend fun main(args: Array<String>) {
    if (args.firstOrNull() == "list" || args.isEmpty()) {
        printUsage()
        return
    }
    val externalChatId = args[0]
    val scenarioName = args.getOrNull(1) ?: SCENARIOS.first().name
    val scenario = SCENARIOS.firstOrNull { it.name == scenarioName }
    if (scenario == null) {
        System.err.println("Unknown scenario \"$scenarioName\".")
        printUsage()
        return
    }

    val db = connectDatabaseFromEnv()
    val memberRepository = ExposedMemberRepository(db)
    val resolver =
        IdentityResolver(ExposedPlatformDirectory(db), memberRepository, ExposedGroupRepository(db))
    val groupId = resolver.resolveGroup(externalChatId)
    val context = ScenarioContext(resolver, ExposedExpenseRepository(db), groupId)

    scenario.apply(context)

    println("Applied \"${scenario.name}\" to chat $externalChatId (group ${groupId.value})")
    printResult(context, memberRepository)
}

private suspend fun printResult(
    context: ScenarioContext,
    memberRepository: MemberRepository,
) {
    val members = memberRepository.findByGroup(context.groupId)
    println("  members  ${members.joinToString(", ") { it.displayName }}")

    val expenses = context.expenseRepository.listActive(context.groupId)
    if (expenses.isEmpty()) {
        println("  expenses none")
        return
    }
    expenses.forEach { println("  expense  ${it.description} ${it.amount} ${it.currency} (${it.splitType})") }

    val nameOf = members.associateBy { it.id }
    println("  balances")
    context
        .balancesAfterSeeding()
        .entries
        .sortedBy { nameOf[it.key]?.displayName }
        .forEach { (memberId, balance) ->
            println("    ${nameOf[memberId]?.displayName ?: memberId.value} $balance")
        }
}

private const val SCENARIO_NAME_WIDTH = 12

private fun printUsage() {
    println("Usage: seed <chatId> [scenario]   (default: ${SCENARIOS.first().name})")
    println("Scenarios:")
    SCENARIOS.forEach { println("  ${it.name.padEnd(SCENARIO_NAME_WIDTH)} ${it.description}") }
}

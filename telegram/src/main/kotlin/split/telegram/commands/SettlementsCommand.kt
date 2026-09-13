package split.telegram.commands

import split.core.GroupRepository
import split.core.MemberRepository
import split.core.PlatformDirectory
import split.core.SettlementRepository
import split.telegram.CommandContext
import split.telegram.IdentityResolver
import split.telegram.api.TelegramApi
import split.telegram.buildSettlementListMessage

class SettlementsCommand(
    private val groupRepository: GroupRepository,
    private val memberRepository: MemberRepository,
    private val settlementRepository: SettlementRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        groupRepository.find(context.groupId) ?: error("Group ${context.groupId} not found")
        val settlements =
            settlementRepository
                .listActive(context.groupId)
                .sortedBy { it.createdAt }
                .takeLast(10)
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })

        telegramApi.sendRichMessage(context.chatId, buildSettlementListMessage(settlements, members, usernames))
    }
}

package split.telegram

import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import split.core.PlatformDirectory

class MembersCommand(
    private val memberRepository: MemberRepository,
    private val platformDirectory: PlatformDirectory,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val members = memberRepository.findByGroup(context.groupId)
        val usernames = platformDirectory.findUsernames(IdentityResolver.PLATFORM, members.map { it.id })
        telegramApi.sendMessage(context.chatId, formatMembers(members, usernames))
    }
}

internal fun formatMembers(members: List<Member>, usernames: Map<MemberId, String> = emptyMap()): String {
    if (members.isEmpty()) return "No members yet."
    return members.joinToString("\n") { "• ${mentionName(it, usernames)}" }
}

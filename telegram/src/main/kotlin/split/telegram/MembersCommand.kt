package split.telegram

import split.core.Member
import split.core.MemberRepository

class MembersCommand(
    private val memberRepository: MemberRepository,
    private val telegramApi: TelegramApi,
) {
    suspend fun handle(context: CommandContext) {
        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatMembers(members))
    }
}

internal fun formatMembers(members: List<Member>): String {
    if (members.isEmpty()) return "No members yet."
    return members.joinToString("\n") { "• ${it.displayName}" }
}

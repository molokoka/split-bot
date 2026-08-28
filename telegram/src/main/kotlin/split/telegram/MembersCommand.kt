package split.telegram

import split.core.GroupRepository
import split.core.Member
import split.core.MemberId
import split.core.MemberRepository
import java.util.UUID

class MembersCommand(
    private val memberRepository: MemberRepository,
    private val groupRepository: GroupRepository,
    private val telegramApi: TelegramApi,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun handle(context: CommandContext) {
        val trimmed = context.args.trim()
        if (trimmed == "add" || trimmed.startsWith("add ")) {
            val name = trimmed.removePrefix("add").trim()
            if (name.isEmpty()) {
                telegramApi.sendMessage(context.chatId, "Usage: /members add <name>")
                return
            }
            val member = Member(MemberId(idGenerator()), name)
            memberRepository.create(member)
            groupRepository.addMember(context.groupId, member.id)
            telegramApi.sendMessage(context.chatId, "Added $name to this group.")
            return
        }

        val members = memberRepository.findByGroup(context.groupId)
        telegramApi.sendMessage(context.chatId, formatMembers(members))
    }
}

internal fun formatMembers(members: List<Member>): String {
    if (members.isEmpty()) return "No members yet."
    return members.joinToString("\n") { "• ${it.displayName}" }
}

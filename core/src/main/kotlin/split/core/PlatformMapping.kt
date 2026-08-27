package split.core

data class PlatformIdentity(
    val platform: String,
    val externalUserId: String,
    val memberId: MemberId,
)

data class PlatformGroupLink(
    val platform: String,
    val externalChatId: String,
    val groupId: GroupId,
)

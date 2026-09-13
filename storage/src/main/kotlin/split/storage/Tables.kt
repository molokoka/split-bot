package split.storage

import org.jetbrains.exposed.v1.core.Table

object GroupTable : Table("group_") {
    val id = text("id")
    val defaultCurrency = text("default_currency")
    val createdAt = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

object MemberTable : Table("member") {
    val id = text("id")
    val displayName = text("display_name")
    override val primaryKey = PrimaryKey(id)
}

object GroupMemberTable : Table("group_member") {
    val groupId = text("group_id").references(GroupTable.id)
    val memberId = text("member_id").references(MemberTable.id)
    override val primaryKey = PrimaryKey(groupId, memberId)
}

object ExpenseTable : Table("expense") {
    val id = text("id")
    val groupId = text("group_id").references(GroupTable.id)
    val currency = text("currency")
    val description = text("description")
    val amountCents = long("amount_cents")
    val payerId = text("payer_id").references(MemberTable.id)
    val splitType = text("split_type")
    val createdBy = text("created_by").references(MemberTable.id)
    val createdAt = text("created_at")
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ExpenseShareTable : Table("expense_share") {
    val expenseId = text("expense_id").references(ExpenseTable.id)
    val memberId = text("member_id").references(MemberTable.id)
    val shareAmountCents = long("share_amount_cents")
    override val primaryKey = PrimaryKey(expenseId, memberId)
}

object SettlementTable : Table("settlement") {
    val id = text("id")
    val groupId = text("group_id").references(GroupTable.id)
    val currency = text("currency")
    val fromMemberId = text("from_member_id").references(MemberTable.id)
    val toMemberId = text("to_member_id").references(MemberTable.id)
    val amountCents = long("amount_cents")
    val createdBy = text("created_by").references(MemberTable.id)
    val createdAt = text("created_at")
    val deletedAt = text("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PlatformIdentityTable : Table("platform_identity") {
    val platform = text("platform")
    val externalUserId = text("external_user_id")
    val memberId = text("member_id").references(MemberTable.id)
    val username = text("username").nullable()
    override val primaryKey = PrimaryKey(platform, externalUserId)
}

object PlatformGroupLinkTable : Table("platform_group_link") {
    val platform = text("platform")
    val externalChatId = text("external_chat_id")
    val groupId = text("group_id").references(GroupTable.id)
    override val primaryKey = PrimaryKey(platform, externalChatId)
}

object SplitFlowStateTable : Table("split_flow_state") {
    val chatId = long("chat_id")
    val promptMessageId = long("prompt_message_id")
    val groupId = text("group_id").references(GroupTable.id)
    val stateType = text("state_type")
    val invokerId = text("invoker_id").references(MemberTable.id)
    val description = text("description").nullable()
    val amountCents = long("amount_cents").nullable()
    val currency = text("currency").nullable()
    val awaiting = text("awaiting").nullable()
    val splitTypeHint = text("split_type_hint").nullable()
    val mentionUsernames = text("mention_usernames").nullable()
    val exactAmounts = text("exact_amounts").nullable()
    val stage = text("stage").nullable()
    val participantIds = text("participant_ids").nullable()
    val amountsEntered = text("amounts_entered").nullable()
    val actionsMessageId = long("actions_message_id").nullable()
    val pendingParticipantId = text("pending_participant_id").nullable()
    val pendingPromptMessageId = long("pending_prompt_message_id").nullable()
    val pendingIsAutoAdvance = bool("pending_is_auto_advance").nullable()
    override val primaryKey = PrimaryKey(chatId, promptMessageId)
}

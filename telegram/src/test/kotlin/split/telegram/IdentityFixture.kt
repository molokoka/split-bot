package split.telegram

import org.jetbrains.exposed.v1.jdbc.Database
import split.core.GroupId
import split.core.MemberId
import split.storage.ExposedGroupRepository
import split.storage.ExposedMemberRepository
import split.storage.ExposedPlatformDirectory

/**
 * The identity/storage core every flow fixture needs: a resolver wired to real repositories, plus
 * a fake Telegram API to record what got sent. Domain-specific fixtures (callback handling, replies,
 * commands, ...) extend this and add only what their handler-under-test needs.
 */
internal open class IdentityFixture(
    db: Database,
) {
    val platformDirectory = ExposedPlatformDirectory(db)
    val groupRepository = ExposedGroupRepository(db)
    val memberRepository = ExposedMemberRepository(db)
    val resolver = IdentityResolver(platformDirectory, memberRepository, groupRepository)
    val telegramApi = FakeTelegramApi()

    private var resolvedGroupId: GroupId? = null

    /**
     * The group the current scenario is about — set once a [Personas] chain resolves it, so call sites never
     * repeat it.
     */
    var groupId: GroupId
        get() =
            resolvedGroupId
                ?: error("No group resolved yet — start the scenario with e.g. personas().alice().inGroup().")
        internal set(value) {
            resolvedGroupId = value
        }

    private val names = mutableMapOf<MemberId, String>()

    internal fun remember(
        memberId: MemberId,
        name: String,
    ) {
        names[memberId] = name
    }

    /** The display name a resolved member was given — labels who did what in [chat]'s transcript. */
    fun nameOf(memberId: MemberId): String = names[memberId] ?: memberId.value
}

/** Resolves (or creates) a member's identity — the foundational action every scenario builds on. */
internal suspend fun IdentityFixture.member(
    externalId: String,
    username: String,
    displayName: String,
): MemberId {
    val memberId = resolver.resolveMember(externalId, username, displayName)
    remember(memberId, username)
    return memberId
}

/** Resolves (or creates) a group's identity for the given chat. */
internal suspend fun IdentityFixture.group(externalChatId: String): GroupId = resolver.resolveGroup(externalChatId)

/** Adds each member to the group, the way a real command/reply/tap does via ensureGroupMembership. */
internal suspend fun IdentityFixture.joined(
    groupId: GroupId,
    memberIds: List<MemberId>,
) {
    memberIds.forEach { resolver.ensureGroupMembership(groupId, it) }
}

/** The recurring test persona: Alice, member "1". */
internal suspend fun IdentityFixture.alice(): MemberId = member("1", "alice", "Alice")

/**
 * The recurring test persona: Bob, member "2", username "bobby" — a real Telegram username must be
 * at least 5 characters, so "bob" alone can never be @mentioned; use "bobby" everywhere, not just in
 * scenarios that parse mentions from text.
 */
internal suspend fun IdentityFixture.bobby(): MemberId = member("2", "bobby", "Bob")

/** The recurring test persona: Carol, member "3" — typically used as a non-participant. */
internal suspend fun IdentityFixture.carol(): MemberId = member("3", "carol", "Carol")

/**
 * Builds up a scenario's cast one persona at a time — `alice().bobby()` — without a combinatorial
 * method for every subset of personas x membership state a test might need. Terminate the chain with
 * [Personas.inGroup] (everyone resolved becomes an actual group member) or [Personas.known] (everyone's
 * identity is resolved, but nobody is a group member) to get a [GroupOf] back.
 */
internal class Personas internal constructor(
    private val fixture: IdentityFixture,
) {
    private val resolved = linkedMapOf<String, MemberId>()

    suspend fun alice(): Personas = persona("alice") { it.alice() }

    suspend fun bobby(): Personas = persona("bobby") { it.bobby() }

    suspend fun carol(): Personas = persona("carol") { it.carol() }

    private suspend fun persona(
        name: String,
        resolve: suspend (IdentityFixture) -> MemberId,
    ): Personas {
        resolved[name] = resolve(fixture)
        return this
    }

    suspend fun inGroup(externalChatId: String = "-100"): GroupOf {
        val groupId = fixture.group(externalChatId)
        fixture.joined(groupId, resolved.values.toList())
        fixture.groupId = groupId
        return GroupOf(groupId, resolved)
    }

    suspend fun known(externalChatId: String = "-100"): GroupOf {
        val groupId = fixture.group(externalChatId)
        fixture.groupId = groupId
        return GroupOf(groupId, resolved)
    }
}

/** The cast and group resolved by a [Personas] chain, read back by name rather than by position. */
internal class GroupOf(
    val groupId: GroupId,
    private val members: Map<String, MemberId>,
) {
    val alice: MemberId get() = members.getValue("alice")
    val bobby: MemberId get() = members.getValue("bobby")
    val carol: MemberId get() = members.getValue("carol")
}

/** Starts a persona chain, e.g. `personas().alice().bobby().inGroup()`. */
internal fun IdentityFixture.personas(): Personas = Personas(this)

/** What the chat looks like now — assert on this instead of raw fields where there's something to say. */
internal fun IdentityFixture.chat(tail: Int? = null): String = renderChat(telegramApi.events, tail)

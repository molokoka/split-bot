package split.storage

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * [RoundingMode.UNNECESSARY] is deliberate: a [BigDecimal] with more than 2 decimal
 * places reaching storage means a bug upstream (e.g. in split resolution) - this
 * fails loudly at the boundary instead of silently truncating money.
 */
internal fun BigDecimal.toCents(): Long = setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()

internal fun Long.centsToAmount(): BigDecimal = BigDecimal(this).movePointLeft(2)

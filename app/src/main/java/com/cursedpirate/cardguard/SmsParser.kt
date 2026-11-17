package com.cursedpirate.cardguard

enum class TxType { DEBIT, CREDIT }

data class ParsedSms(
    val type: TxType,
    val amount: Double,
    val last4: String,
    val merchant: String?,
    val bank: String?,
)

/** Pure parser for Indian bank credit-card SMS. Returns null if not a card transaction. */
object SmsParser {
    private val amountRe = Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val cardRe = Regex("""card[^\d]{0,25}?(?:ending|xx+|x+|no\.?)?\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val merchantRe = Regex("""(?:\bat|\btowards|\bto)\s+([A-Za-z0-9][A-Za-z0-9 .&*'-]{1,40}?)(?=\s+(?:on|via|using|ref)\b|[.,](?:\s|$)|$)""", RegexOption.IGNORE_CASE)
    private val debitWords = Regex("""\b(spent|purchase|debited|used for|txn of|transaction of)\b""", RegexOption.IGNORE_CASE)
    private val creditWords = Regex("""\b(payment|credited|refund|reversal|reversed|received)\b""", RegexOption.IGNORE_CASE)
    private val skipWords = Regex("""\b(otp|one time password|offer|apply now|statement is generated)\b""", RegexOption.IGNORE_CASE)
    private val banks = listOf("HDFC", "ICICI", "SBI", "Axis", "Amex", "American Express", "Kotak", "IDFC", "Yes Bank", "IndusInd", "RBL", "Federal", "Standard Chartered")

    fun parse(body: String): ParsedSms? {
        if (skipWords.containsMatchIn(body)) return null
        if (!body.contains("card", ignoreCase = true)) return null
        val amount = amountRe.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        val last4 = cardRe.find(body)?.groupValues?.get(1) ?: return null
        // Credit wins over debit words only when no debit verb ("payment received" vs "spent")
        val type = when {
            debitWords.containsMatchIn(body) -> TxType.DEBIT
            creditWords.containsMatchIn(body) -> TxType.CREDIT
            else -> return null
        }
        val merchant = if (type == TxType.DEBIT) merchantRe.find(body)?.groupValues?.get(1)?.trim() else null
        val bank = banks.firstOrNull { body.contains(it, ignoreCase = true) }
        return ParsedSms(type, amount, last4, merchant, bank)
    }
}

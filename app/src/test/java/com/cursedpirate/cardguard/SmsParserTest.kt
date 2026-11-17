package com.cursedpirate.cardguard

import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {
    @Test fun debit() {
        val p = SmsParser.parse("Spent Rs.1,500.00 on your HDFC Bank Card ending 4291 at Amazon.in on 12-06-25.")!!
        assertEquals(TxType.DEBIT, p.type); assertEquals(1500.0, p.amount, 0.0)
        assertEquals("4291", p.last4); assertEquals("Amazon.in", p.merchant); assertEquals("HDFC", p.bank)
    }
    @Test fun payment() {
        val p = SmsParser.parse("Thank you for payment of Rs. 12,000.00 towards your ICICI Card ending 8823.")!!
        assertEquals(TxType.CREDIT, p.type); assertEquals(12000.0, p.amount, 0.0); assertEquals("8823", p.last4)
    }
    @Test fun rupeeSymbolXX() {
        val p = SmsParser.parse("INR 2,450.50 spent on SBI Card XX7788 at Starbucks.")!!
        assertEquals(2450.5, p.amount, 0.0); assertEquals("7788", p.last4)
    }
    @Test fun ignoresOtpAndNonCard() {
        assertNull(SmsParser.parse("123456 is your OTP for Rs.500 txn on card ending 4291"))
        assertNull(SmsParser.parse("Hello, lunch at 1?"))
    }
}

package com.cardify

import org.junit.Assert.assertEquals
import org.junit.Test

class CardParserTest {
    @Test
    fun parsesTypicalCards() {
        val a = parseCardText(
            """
            NORTHWIND
            Ayesha Karim
            Senior Software Engineer
            Northwind Limited
            Mobile: +880 1711-000000
            Tel: +880 2 5550100
            ayesha@example.com
            www.example.com
            House 12, Road 5, Gulshan 1, Dhaka 1212
            """.trimIndent()
        )
        assertEquals(
            Card(
                name = "Ayesha Karim",
                title = "Senior Software Engineer",
                company = "Northwind Limited",
                phone = "+880 1711-000000\n+880 2 5550100",
                email = "ayesha@example.com",
                website = "www.example.com",
                address = "House 12, Road 5, Gulshan 1, Dhaka 1212",
            ),
            a,
        )

        val b = parseCardText(
            """
            Jane Doe
            Marketing Director
            jane.doe@acme.io | M: +1 (555) 010-9999
            acme.io
            """.trimIndent()
        )
        assertEquals(
            Card(
                name = "Jane Doe",
                title = "Marketing Director",
                phone = "+1 (555) 010-9999",
                email = "jane.doe@acme.io",
                website = "acme.io",
            ),
            b,
        )
    }
}

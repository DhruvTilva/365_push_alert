package com.dhruvtilva.pushalert.domain

import com.dhruvtilva.pushalert.domain.MessageStatus.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStatusTest {
    private val allowed = mapOf(
        PENDING to setOf(EDITED, SENDING),
        EDITED to setOf(PENDING, SENDING),
        SENDING to setOf(SENT, FAILED, DELIVERY_UNCERTAIN, PENDING, EDITED),
        FAILED to setOf(SENDING),
        DELIVERY_UNCERTAIN to setOf(SENDING, SENT, FAILED),
        SENT to emptySet(),
    )

    @Test fun transitionMatrixIsExactlyAsDesigned() {
        for (from in MessageStatus.values()) for (to in MessageStatus.values()) {
            assertEquals("$from -> $to", to in allowed.getValue(from), from.canTransitionTo(to))
        }
    }

    @Test fun sentIsTerminal() {
        assertTrue(MessageStatus.values().none { SENT.canTransitionTo(it) })
    }

    @Test fun uncertainCannotBecomePendingAgainWithoutUserDecision() {
        assertFalse(DELIVERY_UNCERTAIN.canTransitionTo(PENDING))
        assertFalse(FAILED.canTransitionTo(SENT))
    }

    @Test fun onlyInboxStatesAreSendableAndEditable() {
        assertEquals(setOf(PENDING, EDITED, FAILED, DELIVERY_UNCERTAIN), MessageStatus.values().filter { it.isSendable }.toSet())
        assertEquals(MessageStatus.values().filter { it.isSendable }, MessageStatus.values().filter { it.isEditable })
    }

    @Test fun parseFallsBackSafely() {
        assertEquals(PENDING, MessageStatus.parse("garbage"))
        assertEquals(FAILED, MessageStatus.parse("FAILED"))
    }
}

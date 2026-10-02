package dev.vanssa.claudeacp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DropdownItemsTest {

    @Test
    fun `blank comes first, then the agent's models`() {
        assertEquals(listOf("", "opus", "haiku"), dropdownItems(listOf("opus", "haiku"), "opus"))
    }

    @Test
    fun `nothing is offered before the agent has answered`() {
        assertEquals(listOf(""), dropdownItems(emptyList(), ""))
    }

    /** No hard-coded fallback: an empty agent list must not bring back opus/sonnet/haiku. */
    @Test
    fun `a saved model the agent does not offer is kept, so Apply cannot clear it`() {
        assertEquals(listOf("", "haiku", "sonnet[1m]"), dropdownItems(listOf("haiku"), "sonnet[1m]"))
        assertEquals(listOf("", "opus"), dropdownItems(emptyList(), "opus"))
    }

    @Test
    fun `duplicate ids from the agent appear once`() {
        assertEquals(listOf("", "opus"), dropdownItems(listOf("opus", "opus"), ""))
    }
}

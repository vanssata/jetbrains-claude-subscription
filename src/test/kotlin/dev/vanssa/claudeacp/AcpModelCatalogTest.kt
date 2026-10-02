package dev.vanssa.claudeacp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AcpModelCatalogTest {

    private fun parse(json: String) = AcpModelCatalog.modelsOf(JsonParser.parseString(json).asJsonObject)

    /** Shaped after a real `session/new` answer from claude-agent-acp 0.85.1 on a Max plan. */
    @Test
    fun `reads the model option and drops the agent's default marker`() {
        val models = parse(
            """
            {"sessionId":"s","configOptions":[
              {"id":"mode","options":[{"value":"plan","name":"Plan"}]},
              {"id":"model","currentValue":"default","options":[
                {"value":"default","name":"Default (recommended)"},
                {"value":"claude-opus-5-5[1m]","name":"Opus 5.5"},
                {"value":"opus","name":"Opus 5.5"},
                {"value":"fable[1m]","name":"Fable 5.1"},
                {"value":"haiku","name":"Haiku 4.5"}
              ]}
            ]}
            """,
        )
        assertEquals(
            listOf(
                ModelChoice("claude-opus-5-5[1m]", "Opus 5.5"),
                ModelChoice("opus", "Opus 5.5"),
                ModelChoice("fable[1m]", "Fable 5.1"),
                ModelChoice("haiku", "Haiku 4.5"),
            ),
            models,
        )
    }

    @Test
    fun `a model without a name is shown by its id`() {
        val models = parse("""{"configOptions":[{"id":"model","options":[{"value":"sonnet"}]}]}""")
        assertEquals(listOf(ModelChoice("sonnet", "sonnet")), models)
    }

    /** Null, not empty: an empty list would overwrite the remembered models with nothing. */
    @Test
    fun `answers without models give null`() {
        assertNull(parse("""{"sessionId":"s"}"""))
        assertNull(parse("""{"configOptions":[{"id":"mode","options":[]}]}"""))
        assertNull(parse("""{"configOptions":[{"id":"model","options":[{"value":"default"}]}]}"""))
        assertNull(parse("""{"configOptions":[{"id":"model"}]}"""))
    }

    @Test
    fun `remembered models survive encoding, brackets included`() {
        val model = ModelChoice("claude-opus-5-5[1m]", "Opus 5.5 (1M context)")
        assertEquals(model, ModelChoice.decode(model.encode()))
    }

    @Test
    fun `malformed remembered entries are skipped`() {
        assertNull(ModelChoice.decode("opus"))
        assertNull(ModelChoice.decode("\tOpus"))
    }
}

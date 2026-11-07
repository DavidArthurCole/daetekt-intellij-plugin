package io.gitlab.arturbosch.detekt.idea.intention

import io.gitlab.arturbosch.detekt.idea.engine.api.FixTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FixTemplatesTest {

    @Test
    fun templateOnlyEditsInsideTheFindingRange() {
        val template = FixTemplate(find = "foo", replace = "bar", ignoreCase = true)
        val text = "val foo = 1\nval x = Foo"

        val edit = template.editWithin(text, text.indexOf("x"), text.length)

        assertThat(edit?.start).isEqualTo(text.lastIndexOf("Foo"))
        assertThat(edit?.replacement).isEqualTo("Bar")
    }
}

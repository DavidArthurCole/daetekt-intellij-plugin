package io.gitlab.arturbosch.detekt.idea

import com.intellij.openapi.util.TextRange
import io.gitlab.arturbosch.detekt.api.TextLocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextRangeWithinTest {

    private val file = TextRange(0, 1740)

    @Test
    fun findingInsideFileKeepsExactRange() {
        assertThat(TextLocation(28, 30).toTextRangeWithin(file)).isEqualTo(TextRange(28, 30))
    }

    @Test
    fun findingPastEndOfFileMovesOntoLastCharacter() {
        assertThat(TextLocation(1740, 1741).toTextRangeWithin(file)).isEqualTo(TextRange(1739, 1740))
    }

    @Test
    fun findingFromStaleLongerDocumentIsCutAtEndOfFile() {
        assertThat(TextLocation(1730, 1745).toTextRangeWithin(file)).isEqualTo(TextRange(1730, 1740))
    }

    @Test
    fun findingOnEmptyFileIsEmptyRangeAtStart() {
        assertThat(TextLocation(0, 1).toTextRangeWithin(TextRange(0, 0))).isEqualTo(TextRange(0, 0))
    }

    @Test
    fun wholeFileFindingStillMatchesFileRange() {
        assertThat(TextLocation(0, 1740).toTextRangeWithin(file)).isEqualTo(file)
    }
}

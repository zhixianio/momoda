package app.momoda

import app.momoda.PaletteLayout.Entry.Header
import app.momoda.PaletteLayout.Entry.Item
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteLayoutTest {
    private val groups = listOf("文档" to listOf("new", "open", "trash"), "写作" to listOf("zen", "vim"))

    @Test fun noUsesMeansNoFrequentSection() = assertEquals(
        listOf(Header("文档"), Item("new"), Item("open"), Item("trash"), Header("写作"), Item("zen"), Item("vim")),
        PaletteLayout.layout(groups, emptyMap()),
    )

    @Test fun frequentTakesTopThreeByCountThenFixedOrder() = assertEquals(
        listOf(Header("常用"), Item("zen", false), Item("open", false), Item("trash", false)),
        PaletteLayout.layout(groups, mapOf("zen" to 5, "trash" to 2, "open" to 2, "vim" to 1)).take(4),
    )

    @Test fun groupsStayInFixedOrderRegardlessOfUse() = assertEquals(
        listOf(Header("文档"), Item("new"), Item("open"), Item("trash"), Header("写作"), Item("zen"), Item("vim")),
        PaletteLayout.layout(groups, mapOf("vim" to 9)).drop(2),
    )

    @Test fun unknownIdsIgnored() = assertEquals(
        listOf(Header("文档"), Item("new"), Item("open"), Item("trash"), Header("写作"), Item("zen"), Item("vim")),
        PaletteLayout.layout(groups, mapOf("gone" to 9)),
    )

    @Test fun destructiveCommandsNeverFrequent() = assertEquals(
        listOf(Header("常用"), Item("zen", false), Header("文档")),
        PaletteLayout.layout(groups, mapOf("trash" to 9, "zen" to 1), neverFrequent = setOf("trash")).take(3),
    )

    @Test fun usesRoundTrip() = assertEquals(mapOf("a" to 3, "b" to 1), PaletteLayout.parseUses(PaletteLayout.formatUses(mapOf("a" to 3, "b" to 1))))

    @Test fun badUsesIgnored() = assertEquals(mapOf("a" to 3), PaletteLayout.parseUses("a:3,,b,c:x"))
}

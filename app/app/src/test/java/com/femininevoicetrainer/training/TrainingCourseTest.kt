package com.femininevoicetrainer.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 训练课程数据完整性测试(GH#3 / COD-54 验收 4):
 * - 主题/步骤数量与编号(7 主题 × 3–5 步)
 * - 文案资源非零且无重复接线(防复制粘贴错位)
 * - strings.xml 全部条目非空(文案非空;文件缺失时跳过该项)
 * - P0 七张图解资源存在(经 R 常量编译期绑定 + 引用一致性)
 * - 固定朗读语料 3 句、互不相同
 */
class TrainingCourseTest {

    private val expectedTopicIds = listOf("T1", "T2", "T3", "T4", "T5", "T6", "T7")

    @Test
    fun testTopicCountAndOrder() {
        assertEquals(7, TrainingCourse.topics.size)
        assertEquals(expectedTopicIds, TrainingCourse.topics.map { it.id })
    }

    @Test
    fun testStepCountsInRange() {
        TrainingCourse.topics.forEach { topic ->
            val size = topic.steps.size
            assertTrue("主题 ${topic.id} 步骤数应在 3–5,实际 $size", size in 3..5)
        }
    }

    @Test
    fun testStepIdsUniqueAndPrefixed() {
        val allIds = TrainingCourse.topics.flatMap { it.steps }.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
        TrainingCourse.topics.forEach { topic ->
            topic.steps.forEach { step ->
                assertTrue("步骤 ${step.id} 应以主题 ${topic.id} 为前缀", step.id.startsWith(topic.id))
            }
        }
    }

    @Test
    fun testAllCopyResourcesNonZero() {
        TrainingCourse.topics.forEach { topic ->
            assertTrue(topic.titleRes != 0)
            assertTrue(topic.goalRes != 0)
            topic.steps.forEach { step ->
                val label = step.id
                assertTrue("$label titleRes 为 0", step.titleRes != 0)
                assertTrue("$label focusRes 为 0", step.focusRes != 0)
                assertTrue("$label mistakesRes 为 0", step.mistakesRes != 0)
                assertTrue("$label checkRes 为 0", step.checkRes != 0)
            }
        }
    }

    @Test
    fun testNoDuplicateCopyWiring() {
        // 四段文案 + 主题标题/目标全部两两不同:任何重复都意味着接线复制错位
        val used = TrainingCourse.topics.flatMap { topic ->
            listOf(topic.titleRes, topic.goalRes) + topic.steps.flatMap { step ->
                listOf(step.titleRes, step.focusRes, step.mistakesRes, step.checkRes)
            }
        }
        assertEquals("文案资源接线存在重复", used.size, used.toSet().size)
    }

    @Test
    fun testP0Illustrations() {
        // P0 七张:数量、非零、互不相同(R 常量经编译期绑定,引用即存在)
        val p0 = TrainingCourse.p0IllustrationResIds
        assertEquals(7, p0.size)
        p0.forEach { assertTrue(it != 0) }

        // 每张 P0 图都挂在某个步骤上
        val attached = TrainingCourse.topics
            .flatMap { it.steps }
            .mapNotNull { it.illustrationRes }
            .toSet()
        assertEquals(p0, attached)

        // 每张挂载的图解都有无障碍描述
        attached.forEach { resId ->
            assertTrue("图解 $resId 缺少 contentDescription", TrainingCourse.illustrationDescriptions.containsKey(resId))
        }
        assertEquals(attached, TrainingCourse.illustrationDescriptions.keys)
    }

    @Test
    fun testFixedReadingSentences() {
        val sentences = TrainingCourse.fixedReadingSentences
        assertEquals(3, sentences.size)
        assertEquals(sentences.size, sentences.toSet().size)
        sentences.forEach { assertTrue(it != 0) }
    }

    @Test
    fun testStringsXmlEntriesNonEmpty() {
        // 逐条校验 strings.xml 文案非空(测试工作目录 = 模块根;找不到文件则跳过)
        val stringsFile = sequenceOf(File("."), File(".."), File("../.."))
            .map { File(it, "src/main/res/values/strings.xml") }
            .firstOrNull { it.isFile }
        assumeTrue("strings.xml not found from test working dir", stringsFile != null)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val nodes = doc.getElementsByTagName("string")
        assertTrue(nodes.length > 0)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            val text = node.textContent?.trim().orEmpty()
            assertTrue("string \"$name\" 文案为空", text.isNotEmpty())
        }
    }
}

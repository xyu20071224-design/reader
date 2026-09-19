package com.linguareader.shared.sync

/** 同步集合名（与 阶段2 提案 §3 一致）。 */
object SyncCollections {
    const val PROGRESS = "progress"
    const val VOCABULARY = "vocabulary"
    const val GLOSSARY = "glossary"
    const val PREFERENCE = "preference"

    val ALL = setOf(PROGRESS, VOCABULARY, GLOSSARY, PREFERENCE)
}

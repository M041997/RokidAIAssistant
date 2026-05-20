package com.example.rokidphone.data

data class VisualTranslationLanguageOption(
    val code: String,
    val displayName: String
)

object VisualTranslationLanguages {
    const val AUTO = "auto"

    val options = listOf(
        VisualTranslationLanguageOption(AUTO, "Auto-detect"),
        VisualTranslationLanguageOption("ja", "Japanese"),
        VisualTranslationLanguageOption("es", "Spanish"),
        VisualTranslationLanguageOption("de", "German"),
        VisualTranslationLanguageOption("fr", "French"),
        VisualTranslationLanguageOption("zh", "Chinese"),
        VisualTranslationLanguageOption("ko", "Korean"),
        VisualTranslationLanguageOption("it", "Italian"),
        VisualTranslationLanguageOption("pt", "Portuguese")
    )

    fun fromCode(code: String): VisualTranslationLanguageOption {
        return options.firstOrNull { it.code == code } ?: options.first()
    }

    fun displayName(code: String): String = fromCode(code).displayName

    fun promptSource(code: String): String {
        return when (val option = fromCode(code)) {
            options.first() -> "any visible non-English text"
            else -> "any visible ${option.displayName} text"
        }
    }
}

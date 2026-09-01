package com.mewmix.nabu.data

object ModelTestTags {
    const val Screen = "models_screen"
    const val List = "models_list"
    const val ImportLocalModelButton = "models_import_local_model_button"
    const val ProgressDialog = "models_progress_dialog"

    fun row(modelId: String): String = "models_row_$modelId"
    fun downloadButton(modelId: String): String = "models_download_$modelId"
    fun deleteButton(modelId: String): String = "models_delete_$modelId"
    fun deletePartialButton(modelId: String): String = "models_delete_partial_$modelId"
    fun progress(modelId: String): String = "models_progress_$modelId"
}

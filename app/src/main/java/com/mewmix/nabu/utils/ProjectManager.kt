package com.mewmix.nabu.utils

import android.content.Context

object ProjectManager {
    fun save(context: Context, project: Project) {
        DatabaseManager.setProject(context, project)
    }

    fun load(context: Context, uri: String): Project? {
        return DatabaseManager.getProject(context, uri)
    }

    fun list(context: Context): List<Project> {
        return DatabaseManager.getProjects(context)
    }

    fun delete(context: Context, uri: String) {
        DatabaseManager.deleteProject(context, uri)
    }
}

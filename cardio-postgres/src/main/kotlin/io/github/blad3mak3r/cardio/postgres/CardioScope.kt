package io.github.blad3mak3r.cardio.postgres

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

object CardioScope : CoroutineScope {

    private val supervisor = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisor + Dispatchers.IO
}
package com.example.toyotaobd.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Au plus une valeur toutes les [periodMs] : la première tout de suite, puis la plus récente à la
 * fin de chaque pause (les valeurs intermédiaires sont ignorées).
 */
fun <T> Flow<T>.atMostEvery(periodMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}

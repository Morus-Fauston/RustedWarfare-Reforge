package com.rustedwarfare.reforge.core.content

sealed interface ContentResult<out T> {
    data class Success<T>(val value: T) : ContentResult<T>
    data class Failure(val error: ContentError) : ContentResult<Nothing>
}

package io.github.blad3mak3r.cardio.postgres

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata

interface ClassMapper<T> {

    fun transform(row: Row, metadata: RowMetadata): T

}
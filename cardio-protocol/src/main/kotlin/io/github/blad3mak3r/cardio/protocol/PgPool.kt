package io.github.blad3mak3r.cardio.protocol

import kotlinx.coroutines.channels.Channel

class PgPool(
    private val pool: Channel<PgConnection>,
    private val opts: Options
) {
    class Options {
        var conn = PgConnection.Options()
        var poolSize: Int = 10
    }

    companion object {
        suspend fun new(opts: Options): PgPool {
            val pool = Channel<PgConnection>(opts.poolSize)
            repeat(opts.poolSize) {
                pool.send(PgConnection.new(opts.conn))
            }
            return PgPool(pool, opts)
        }
    }
}
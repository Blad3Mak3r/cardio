package io.github.blad3mak3r.cardio.protocol

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers

class PgConnection private constructor(
    private val socket: Socket,
    private val opts: Options
){
    class Options {
        var host: String = "localhost"
        var port: Int = 5432
        var database: String = "postgres"
    }



    companion object {
        private val selector = SelectorManager(Dispatchers.IO)

        suspend fun new(opts: Options): PgConnection {
            val socket = aSocket(selector).tcp().connect(opts.host, opts.port)

            return PgConnection(socket, opts)
        }
    }

}
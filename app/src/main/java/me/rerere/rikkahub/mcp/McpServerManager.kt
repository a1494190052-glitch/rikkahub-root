package me.rerere.rikkahub.mcp

import android.content.Context
import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.web.NsdServiceRegistrar
import java.net.ServerSocket

private const val TAG = "McpServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"
private const val MCP_SESSION_ID_HEADER = "mcp-session-id"

/** mDNS service name advertised on the LAN (resolves to rikkahub-mcp.local). */
const val MCP_SERVER_MDNS_NAME = "rikkahub-mcp"

data class McpServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8081,
    val localhostOnly: Boolean = false,
    val authEnabled: Boolean = true,
    val hostname: String? = null,
    val address: String? = null,
    val token: String? = null,
    val error: String? = null,
)

/**
 * Manages the lifecycle of the embedded MCP Streamable-HTTP server that exposes all local tools
 * to external MCP clients. Mirrors [me.rerere.rikkahub.web.WebServerManager].
 *
 * Auth: when [start] is given a non-blank token and authEnabled=true, every /mcp request must carry
 * `Authorization: Bearer <token>` (validated by a Ktor bearer auth provider). Without a valid token
 * Ktor short-circuits with 401 before any MCP handling runs. The token is the authorization boundary:
 * MCP calls bypass the in-app per-call approval UI (see McpToolBridge / SettingMcpServerPage).
 */
class McpServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val localTools: LocalTools,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8081,
        localhostOnly: Boolean = false,
        authEnabled: Boolean = true,
        token: String? = null,
    ) {
        if (server != null) {
            Log.w(TAG, "MCP server already running")
            return
        }

        appScope.launch {
            val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
            val baseState = McpServerState(
                port = port,
                localhostOnly = localhostOnly,
                authEnabled = authEnabled,
                token = token,
            )
            try {
                _state.value = _state.value.copy(isLoading = true)
                Log.i(TAG, "Starting MCP server on $host:$port (auth=$authEnabled)")
                if (!isPortAvailable(port)) {
                    Log.w(TAG, "Port $port is already in use")
                    _state.value = baseState.copy(error = "Port $port is already in use")
                    return@launch
                }
                server = embeddedServer(CIO, port = port, host = host, module = {
                    installMcpServer(
                        authEnabled = authEnabled,
                        token = token,
                        serverFactory = { createMcpServer(localTools) },
                    )
                }).start(wait = false)

                _state.value = baseState.copy(isRunning = true)
                // LAN mode: advertise via mDNS for discovery
                if (!localhostOnly) {
                    runCatching {
                        nsdRegistrar.register(
                            port = port,
                            serviceName = MCP_SERVER_MDNS_NAME,
                            onRegistered = { info ->
                                _state.value = _state.value.copy(
                                    hostname = info.hostname,
                                    address = info.address.hostAddress,
                                )
                            },
                        )
                    }.onFailure {
                        Log.w(TAG, "NSD register failed", it)
                    }
                }
                Log.i(TAG, "MCP server started successfully on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MCP server", e)
                _state.value = baseState.copy(error = e.message)
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            try {
                Log.i(TAG, "Stopping MCP server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "MCP server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop MCP server", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        localhostOnly: Boolean = _state.value.localhostOnly,
        authEnabled: Boolean = _state.value.authEnabled,
        token: String? = _state.value.token,
    ) {
        stop()
        start(port, localhostOnly, authEnabled, token)
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Configures this Ktor [Application] to serve MCP over Streamable HTTP at `/mcp`.
 *
 * - No auth (or blank token): delegates to the SDK's [mcpStreamableHttp] helper which manages
 *   GET/POST/DELETE + sessions automatically.
 * - With auth: installs a bearer auth provider and wires the transports manually (pattern taken
 *   from the official kotlin-sdk 0.14.0 `simple-streamable-server --auth` sample), so that Ktor
 *   rejects missing/invalid tokens with 401 before reaching any MCP handler.
 */
private fun Application.installMcpServer(
    authEnabled: Boolean,
    token: String?,
    serverFactory: () -> Server,
) {
    // MCP requires json(McpJson) (explicitNulls=false, encodeDefaults=true) for correct JSON-RPC.
    install(ContentNegotiation) {
        json(McpJson)
    }

    if (!authEnabled || token.isNullOrBlank()) {
        mcpStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false) {
            serverFactory()
        }
        return
    }

    install(SSE)
    install(Authentication) {
        bearer("mcp-bearer") {
            authenticate { credential ->
                if (credential.token == token) UserIdPrincipal("mcp-client") else null
            }
        }
    }

    val transports = ConcurrentMap<String, StreamableHttpServerTransport>()

    routing {
        authenticate("mcp-bearer") {
            route("/mcp") {
                sse {
                    val transport = findMcpTransport(call, transports) ?: return@sse
                    transport.handleRequest(this, call)
                }
                post {
                    val transport = getOrCreateMcpTransport(call, transports, serverFactory) ?: return@post
                    transport.handleRequest(null, call)
                }
                delete {
                    val transport = findMcpTransport(call, transports) ?: return@delete
                    transport.handleRequest(null, call)
                }
            }
        }
    }
}

private suspend fun findMcpTransport(
    call: ApplicationCall,
    transports: ConcurrentMap<String, StreamableHttpServerTransport>,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId.isNullOrEmpty()) {
        call.respond(HttpStatusCode.BadRequest, "Bad Request: No valid session ID provided")
        return null
    }
    val transport = transports[sessionId]
    if (transport == null) {
        call.respond(HttpStatusCode.NotFound, "Session not found")
        return null
    }
    return transport
}

private suspend fun getOrCreateMcpTransport(
    call: ApplicationCall,
    transports: ConcurrentMap<String, StreamableHttpServerTransport>,
    serverFactory: () -> Server,
): StreamableHttpServerTransport? {
    val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (sessionId != null) {
        val transport = transports[sessionId]
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
        }
        return transport
    }

    val configuration = StreamableHttpServerTransport.Configuration(enableJsonResponse = true)
    val transport = StreamableHttpServerTransport(configuration)

    transport.setOnSessionInitialized { initializedSessionId ->
        transports[initializedSessionId] = transport
    }
    transport.setOnSessionClosed { closedSessionId ->
        transports.remove(closedSessionId)
    }

    val server = serverFactory()
    server.onClose {
        transport.sessionId?.let { transports.remove(it) }
    }
    server.createSession(transport)

    return transport
}
